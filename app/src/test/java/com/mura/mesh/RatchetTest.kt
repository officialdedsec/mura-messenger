package com.mura.mesh

import com.mura.mesh.core.*
import com.mura.mesh.identity.TinkCurve
import com.mura.mesh.identity.TinkEd
import org.junit.BeforeClass
import org.junit.Test

/** Minimal in-memory version of the app's SessionManager logic, to test the protocol end to end. */
class RatchetParty(val name: String) {
    val ikPriv = Crypto.curve.generatePrivate(); val ikPub = Crypto.curve.publicFrom(ikPriv)
    val edSeed = Crypto.randomBytes(32); val edPub = Crypto.ed.publicFromSeed(edSeed)
    val spks = HashMap<Int, Pair<ByteArray, SignedPreKey>>()
    var currentSpk = 0
    val sessions = HashMap<String, MutableList<RatchetSession>>()   // peer -> sessions (index 0 = active)
    val peerSpk = HashMap<String, SignedPreKey>()
    var now = 1_000L

    fun rotateSpk(): SignedPreKey { currentSpk++; val (p, s) = SignedPreKey.create(currentSpk, edSeed); spks[currentSpk] = p to s; return s }

    fun send(to: RatchetParty, text: String, ad: ByteArray = ByteArray(0)): ByteArray {
        val list = sessions.getOrPut(to.ikPub.toHex()) { mutableListOf() }
        if (list.isEmpty()) {
            val spk = peerSpk[to.ikPub.toHex()]
            list.add(0, if (spk != null) Ratchet.initiate(ikPriv, ikPub, to.ikPub, spk.id, spk.pub, now)
                        else Ratchet.initiate(ikPriv, ikPub, to.ikPub, 0, to.ikPub, now))
        }
        return Ratchet.encrypt(list[0], text.toByteArray(), ad)
    }

    fun receive(from: RatchetParty, wire: ByteArray, ad: ByteArray = ByteArray(0)): String {
        val m = RatchetMessage.parse(wire)
        val list = sessions.getOrPut(from.ikPub.toHex()) { mutableListOf() }
        val existing = list.firstOrNull { it.sid.contentEquals(m.header.sid) }
        val work = existing?.copy() ?: run {
            val ek = m.header.ek ?: throw ProtocolException("no session")
            val (spkPriv, spkPub) = if (m.header.spkId == 0) ikPriv to ikPub else spks[m.header.spkId]?.let { it.first to it.second.pub } ?: throw ProtocolException("prekey gone")
            Ratchet.accept(ikPriv, ikPub, spkPriv, spkPub, from.ikPub, ek, now)
        }
        val plain = Ratchet.decrypt(work, m, ad, now)
        list.removeAll { it.sid.contentEquals(work.sid) }
        list.add(0, work)
        return String(plain)
    }
}

class RatchetTest {
    companion object {
        @JvmStatic @BeforeClass fun setup() { Crypto.curve = TinkCurve; Crypto.ed = TinkEd; Compression.padding = false }
    }

    @Test fun forwardSecrecy() = ratchetTests()
}

fun ratchetTests() {
    println("\n-- forward secrecy --")
    val a = RatchetParty("A"); val b = RatchetParty("B")

    // SPK signature
    val bSpk = b.rotateSpk()
    check(bSpk.verify(b.edPub) && !bSpk.verify(a.edPub), "signed prekey verifies only with its owner's key")
    val prof = Payload.unpack(Payload.Profile(Crypto.randomBytes(16), 1, "B", b.edPub, ByteArray(0), Crypto.randomBytes(32), 1, bSpk).pack()) as Payload.Profile
    check(prof.prekey?.id == bSpk.id && prof.prekey!!.verify(b.edPub), "prekey travels in the profile")
    a.peerSpk[b.ikPub.toHex()] = prof.prekey!!

    // Basic conversation, with the prekey header until the first reply
    val m1 = a.send(b, "hi"); val m2 = a.send(b, "are you there")
    check(RatchetMessage.parse(m1).header.isPrekey && RatchetMessage.parse(m2).header.isPrekey, "first messages carry the prekey header")
    check(b.receive(a, m2) == "are you there" && b.receive(a, m1) == "hi", "out-of-order first messages decrypt")
    val r1 = b.send(a, "yes!")
    check(!RatchetMessage.parse(r1).header.isPrekey && a.receive(b, r1) == "yes!", "reply decrypts")
    val m3 = a.send(b, "great")
    check(!RatchetMessage.parse(m3).header.isPrekey, "prekey header dropped once the peer answered")
    check(b.receive(a, m3) == "great", "ratchet continues")

    // Replay / duplicate
    check(runCatching { b.receive(a, m3) }.isFailure, "a replayed message is rejected (its key is gone)")
    check(b.receive(a, a.send(b, "still fine")) == "still fine", "session survives a rejected duplicate")

    // Long out-of-order delivery across several ratchet turns
    val late = (1..5).map { a.send(b, "late $it") }
    val r2 = b.send(a, "turn"); a.receive(b, r2)
    val newer = a.send(b, "after turn")
    check(b.receive(a, newer) == "after turn", "newer chain first")
    check(late.reversed().map { b.receive(a, it) } == (5 downTo 1).map { "late $it" }, "5 delayed messages from an older chain still decrypt")

    // Forward secrecy: state captured later cannot open earlier messages
    val secret = a.send(b, "the cave is at 46.55N")
    check(b.receive(a, secret) == "the cave is at 46.55N", "secret delivered")
    val stolen = b.sessions[a.ikPub.toHex()]!![0].copy()
    check(runCatching { Ratchet.decrypt(stolen, RatchetMessage.parse(secret), ByteArray(0), b.now) }.isFailure, "a stolen session can't decrypt a past message")
    val enc = b.sessions[a.ikPub.toHex()]!![0]
    check(RatchetSession.decode(enc.encode()).encode().contentEquals(enc.encode()), "session state serialises losslessly")

    // Post-compromise: after a round trip, new keys differ completely
    val rkBefore = b.sessions[a.ikPub.toHex()]!![0].rk.copyOf()
    a.receive(b, b.send(a, "ping")); b.receive(a, a.send(b, "pong"))
    check(!b.sessions[a.ikPub.toHex()]!![0].rk.contentEquals(rkBefore), "root key moved on (self-healing)")

    // Tampering and header binding
    val t = a.send(b, "tamper me", "aad1".toByteArray())
    val bad = t.copyOf(); bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
    check(runCatching { b.receive(a, bad, "aad1".toByteArray()) }.isFailure, "tampered ciphertext rejected")
    check(runCatching { b.receive(a, t, "aad2".toByteArray()) }.isFailure, "message bound to its bundle header")
    check(b.receive(a, t, "aad1".toByteArray()) == "tamper me", "original still decrypts after failed attempts")

    // New contact with no prekey (id 0) + simultaneous start
    val c = RatchetParty("C"); val d = RatchetParty("D")
    val cm = c.send(d, "hello D"); val dm = d.send(c, "hello C")
    check(RatchetMessage.parse(cm).header.spkId == 0, "unknown prekey falls back to the identity key")
    check(d.receive(c, cm) == "hello D" && c.receive(d, dm) == "hello C", "both sides started at once: both messages read")
    repeat(3) { i ->
        check(d.receive(c, c.send(d, "c$i")) == "c$i" && c.receive(d, d.send(c, "d$i")) == "d$i", "simultaneous-start sessions converge ($i)")
    }

    // Too far ahead is refused (DoS guard)
    val e = RatchetParty("E"); val f = RatchetParty("F")
    f.receive(e, e.send(f, "x")); e.receive(f, f.send(e, "y"))
    repeat(Ratchet.MAX_SKIP + 5) { e.send(f, "lost") }
    check(runCatching { f.receive(e, e.send(f, "far")) }.isFailure, "jump of more than ${Ratchet.MAX_SKIP} messages refused")

    // Skipped keys expire
    val g = RatchetParty("G"); val h = RatchetParty("H")
    val g1 = g.send(h, "1"); val g2 = g.send(h, "2")
    h.receive(g, g2)
    h.now += Ratchet.SKIPPED_TTL_MS + 1
    check(runCatching { h.receive(g, g1) }.isFailure, "skipped keys are erased after ${Ratchet.SKIPPED_TTL_MS / 86_400_000} days")

    // Sender keys (groups)
    val chain = SenderKeys.create(0)
    val snapshot = chain.copy()
    val aad = "group".toByteArray()
    val bodies = (0 until 6).map { SenderKeys.encrypt(chain, "g$it".toByteArray(), aad) }
    val recv = snapshot.copy()
    check(String(SenderKeys.decrypt(recv, bodies[3], aad)) == "g3", "group message 3 first")
    check(String(SenderKeys.decrypt(recv, bodies[0], aad)) == "g0" && String(SenderKeys.decrypt(recv, bodies[5], aad)) == "g5", "group messages in any order")
    check(runCatching { SenderKeys.decrypt(recv, bodies[0], aad) }.isFailure, "group replay rejected")
    val later = recv.copy()
    check(runCatching { SenderKeys.decrypt(later.copy(), bodies[1], aad) }.isSuccess, "pending skipped key still usable")
    val joined = chain.copy()   // a member who joins now gets the current chain state
    check(runCatching { SenderKeys.decrypt(joined, bodies[2], aad) }.isFailure, "a new member can't read earlier group messages")
    check(runCatching { SenderKeys.decrypt(snapshot.copy(), bodies[1], "other".toByteArray()) }.isFailure, "group message bound to its header")

    // Payload extras
    val rp = Payload.unpack(Payload.Text(Crypto.randomBytes(16), 5, "yes", false, null,
        Reply(Crypto.randomBytes(16), a.ikPub, 1, "are you there"), 86_400).pack()) as Payload.Text
    check(rp.reply?.snippet == "are you there" && rp.ttl == 86_400, "reply + disappearing timer codec")
    val sk = Payload.unpack(Payload.SenderKey(Crypto.randomBytes(16), 5, Crypto.randomBytes(16), 9, 42, 7, Crypto.randomBytes(32)).pack()) as Payload.SenderKey
    check(sk.keyId == 42 && sk.iteration == 7, "sender key codec")
}
