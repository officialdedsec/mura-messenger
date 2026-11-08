package com.mura.mesh.core

/*
 * Forward secrecy for 1:1 chats.
 *
 * Session start (X3DH style, without a server):
 *   Each phone keeps a medium-term "signed prekey" (SPK), rotated every week and signed with its
 *   Ed25519 identity key. Contacts learn it from the end-to-end encrypted profile.
 *   The first sender picks a fresh ephemeral key EK and computes
 *       SK = HKDF( DH(IK_a, SPK_b) || DH(EK_a, IK_b) || DH(EK_a, SPK_b) )
 *   If no prekey is known yet (a brand-new contact), IK_b stands in for SPK_b (prekey id 0);
 *   the ratchet below then makes the session forward-secret after the first reply.
 *
 * Double Ratchet (Signal specification):
 *   Every message is encrypted with its own message key from a symmetric hash chain, and every
 *   time the conversation turns around both sides mix in a fresh X25519 key pair. Used keys are
 *   deleted immediately, so a stolen phone cannot decrypt messages that relays copied earlier.
 *   Messages may arrive late, twice or in any order (they travel phone to phone for days):
 *   keys for skipped messages are kept for at most 9 days, then erased.
 *
 * The ratchet message is then wrapped in the sealed-sender [Envelope], so relays still see
 * only the destination address.
 */

class SignedPreKey(val id: Int, val pub: ByteArray, val sig: ByteArray) {
    fun verify(edPub: ByteArray): Boolean =
        id != 0 && pub.size == 32 && sig.size == 64 && Crypto.ed.verify(edPub, message(id, pub), sig)

    companion object {
        private val CONTEXT = "mura-spk-v1".toByteArray()
        fun message(id: Int, pub: ByteArray) = ByteWriter(48).raw(CONTEXT).i32(id).fixed(pub, 32).toByteArray()

        /** Returns (privateKey, signed public part). */
        fun create(id: Int, edSeed: ByteArray): Pair<ByteArray, SignedPreKey> {
            val priv = Crypto.curve.generatePrivate()
            val pub = Crypto.curve.publicFrom(priv)
            return priv to SignedPreKey(id, pub, Crypto.ed.sign(edSeed, message(id, pub)))
        }
    }
}

class RatchetHeader(
    val sid: ByteArray,          // 8 bytes: which session
    val spkId: Int,              // prekey messages only
    val ek: ByteArray?,          // prekey messages only: sender's ephemeral key
    val dh: ByteArray,           // sender's current ratchet public key
    val pn: Int,                 // length of the sender's previous sending chain
    val n: Int,                  // message number in the current chain
) {
    val isPrekey: Boolean get() = ek != null

    fun encode(): ByteArray {
        val w = ByteWriter(96).u8(VERSION).u8(if (ek != null) 1 else 0).fixed(sid, 8)
        if (ek != null) w.i32(spkId).fixed(ek, 32)
        return w.fixed(dh, 32).i32(pn).i32(n).toByteArray()
    }

    companion object { const val VERSION = 1 }
}

class RatchetMessage(val header: RatchetHeader, val headerBytes: ByteArray, val cipher: ByteArray) {
    fun encode(): ByteArray = headerBytes + cipher

    companion object {
        fun parse(wire: ByteArray): RatchetMessage {
            val r = ByteReader(wire)
            if (r.u8() != RatchetHeader.VERSION) throw ProtocolException("ratchet version")
            val flags = r.u8()
            val sid = r.fixed(8)
            var spkId = 0
            var ek: ByteArray? = null
            if (flags and 1 != 0) { spkId = r.i32(); ek = r.fixed(32) }
            val h = RatchetHeader(sid, spkId, ek, r.fixed(32), r.i32(), r.i32())
            val headerLen = wire.size - r.remaining
            if (h.pn < 0 || h.n < 0) throw ProtocolException("ratchet counters")
            return RatchetMessage(h, wire.copyOfRange(0, headerLen), r.rest())
        }
    }
}

/** Mutable session state. Persist it only after an operation succeeded (work on [copy]). */
class RatchetSession(
    val sid: ByteArray,
    /** IK_initiator || IK_responder, bound into every message. */
    val ad0: ByteArray,
    var rk: ByteArray,
    var dhsPriv: ByteArray,
    var dhsPub: ByteArray,
    var dhr: ByteArray?,
    var cks: ByteArray?,
    var ns: Int,
    var ckr: ByteArray?,
    var nr: Int,
    var pn: Int,
    /** Set while we started the session and have not heard back yet: every message repeats the prekey header. */
    var pendingSpkId: Int,
    var pendingEk: ByteArray?,
    val created: Long,
    var lastRecv: Long,
    val skipped: LinkedHashMap<String, Pair<ByteArray, Long>> = LinkedHashMap(),
) {
    val sidHex: String get() = sid.toHex()

    fun encode(): ByteArray {
        val w = ByteWriter(512).u8(1).fixed(sid, 8).bytes(ad0).fixed(rk, 32).fixed(dhsPriv, 32).fixed(dhsPub, 32)
        fun opt(b: ByteArray?) { if (b == null) w.u8(0) else w.u8(1).fixed(b, 32) }
        opt(dhr); opt(cks); w.i32(ns); opt(ckr); w.i32(nr); w.i32(pn)
        w.i32(pendingSpkId); opt(pendingEk)
        w.u64(created).u64(lastRecv)
        w.u16(skipped.size)
        for ((k, v) in skipped) w.str(k).fixed(v.first, 32).u64(v.second)
        return w.toByteArray()
    }

    fun copy(): RatchetSession = decode(encode())

    companion object {
        fun decode(b: ByteArray): RatchetSession {
            val r = ByteReader(b)
            if (r.u8() != 1) throw ProtocolException("session version")
            fun opt(): ByteArray? = if (r.u8() == 1) r.fixed(32) else null
            val sid = r.fixed(8); val ad0 = r.bytes(128); val rk = r.fixed(32)
            val dp = r.fixed(32); val du = r.fixed(32)
            val dhr = opt(); val cks = opt(); val ns = r.i32(); val ckr = opt(); val nr = r.i32(); val pn = r.i32()
            val spk = r.i32(); val ek = opt()
            val created = r.u64(); val last = r.u64()
            val n = r.u16()
            val skipped = LinkedHashMap<String, Pair<ByteArray, Long>>()
            repeat(n) { skipped[r.str()] = r.fixed(32) to r.u64() }
            return RatchetSession(sid, ad0, rk, dp, du, dhr, cks, ns, ckr, nr, pn, spk, ek, created, last, skipped)
        }
    }
}

object Ratchet {
    /** Most messages one chain may jump ahead (lost or still travelling). */
    const val MAX_SKIP = 2000
    /** Skipped keys kept per session. */
    const val MAX_STORED = 2000
    /** Messages live 7 days in the mesh; skipped keys are erased after 9. */
    const val SKIPPED_TTL_MS = 9L * 24 * 3600 * 1000

    private val INFO_X3DH = "mura-x3dh-v1".toByteArray()
    private val INFO_RK = "mura-ratchet-rk-v1".toByteArray()
    private val INFO_MK = "mura-ratchet-mk-v1".toByteArray()
    private val SID = "mura-sid".toByteArray()

    fun sessionId(ek: ByteArray): ByteArray = Crypto.sha256(SID, ek).copyOf(8)

    private fun x3dh(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, ikInitiator: ByteArray, ikResponder: ByteArray): ByteArray =
        Crypto.hkdf(dh1 + dh2 + dh3, ByteArray(32), INFO_X3DH + ikInitiator + ikResponder)

    private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val o = Crypto.hkdf(dhOut, rk, INFO_RK, 64)
        return o.copyOfRange(0, 32) to o.copyOfRange(32, 64)
    }

    /** (next chain key, message key) */
    fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> =
        Crypto.hmac(ck, byteArrayOf(2)) to Crypto.hmac(ck, byteArrayOf(1))

    /** A message key is used once, so a nonce derived from it is safe. */
    fun aeadKey(mk: ByteArray): Pair<ByteArray, ByteArray> {
        val o = Crypto.hkdf(mk, ByteArray(32), INFO_MK, 44)
        return o.copyOfRange(0, 32) to o.copyOfRange(32, 44)
    }

    fun initiate(myIkPriv: ByteArray, myIkPub: ByteArray, theirIk: ByteArray, spkId: Int, theirSpk: ByteArray, now: Long): RatchetSession {
        val c = Crypto.curve
        val ekPriv = c.generatePrivate()
        val ek = c.publicFrom(ekPriv)
        val sk = x3dh(c.shared(myIkPriv, theirSpk), c.shared(ekPriv, theirIk), c.shared(ekPriv, theirSpk), myIkPub, theirIk)
        ekPriv.fill(0)
        val dhsPriv = c.generatePrivate()
        val (rk, cks) = kdfRk(sk, c.shared(dhsPriv, theirSpk))
        return RatchetSession(sessionId(ek), myIkPub + theirIk, rk, dhsPriv, c.publicFrom(dhsPriv), theirSpk.copyOf(), cks, 0,
            null, 0, 0, spkId, ek, now, 0)
    }

    fun accept(myIkPriv: ByteArray, myIkPub: ByteArray, mySpkPriv: ByteArray, mySpkPub: ByteArray, theirIk: ByteArray, ek: ByteArray, now: Long): RatchetSession {
        val c = Crypto.curve
        val sk = x3dh(c.shared(mySpkPriv, theirIk), c.shared(myIkPriv, ek), c.shared(mySpkPriv, ek), theirIk, myIkPub)
        return RatchetSession(sessionId(ek), theirIk + myIkPub, sk, mySpkPriv.copyOf(), mySpkPub.copyOf(), null, null, 0,
            null, 0, 0, 0, null, now, 0)
    }

    /** Encrypts and advances the sending chain (the old chain key is overwritten). */
    fun encrypt(s: RatchetSession, plain: ByteArray, ad: ByteArray): ByteArray {
        val ck = s.cks ?: throw ProtocolException("session cannot send yet")
        val (next, mk) = kdfCk(ck)
        s.cks = next
        val h = RatchetHeader(s.sid, s.pendingSpkId, s.pendingEk, s.dhsPub, s.pn, s.ns)
        s.ns++
        val hb = h.encode()
        val (key, nonce) = aeadKey(mk)
        val ct = Crypto.gcmEncrypt(key, nonce, plain, s.ad0 + ad + hb)
        mk.fill(0)
        return hb + ct
    }

    /** Decrypts [m]. Mutates [s]; on any exception the caller must throw the mutated copy away. */
    fun decrypt(s: RatchetSession, m: RatchetMessage, ad: ByteArray, now: Long): ByteArray {
        val h = m.header
        prune(s, now)
        val skippedKey = h.dh.toHex() + ":" + h.n
        val stored = s.skipped.remove(skippedKey)
        val mk: ByteArray
        if (stored != null) {
            mk = stored.first
        } else {
            if (s.dhr == null || !s.dhr!!.contentEquals(h.dh)) {
                skip(s, h.pn, now)
                dhRatchet(s, h.dh)
            }
            skip(s, h.n, now)
            val (next, key) = kdfCk(s.ckr!!)
            s.ckr = next
            s.nr++
            mk = key
        }
        val (key, nonce) = aeadKey(mk)
        val plain = Crypto.gcmDecrypt(key, nonce, m.cipher, s.ad0 + ad + m.headerBytes)
        mk.fill(0)
        // The other side has our session now: stop repeating the prekey header.
        s.pendingEk = null
        s.pendingSpkId = 0
        s.lastRecv = now
        prune(s, now)
        return plain
    }

    private fun skip(s: RatchetSession, until: Int, now: Long) {
        val ck0 = s.ckr ?: return
        if (until - s.nr > MAX_SKIP) throw ProtocolException("too many skipped messages")
        var ck = ck0
        val dhr = s.dhr!!.toHex()
        while (s.nr < until) {
            val (next, mk) = kdfCk(ck)
            s.skipped["$dhr:${s.nr}"] = mk to now
            ck = next
            s.nr++
        }
        s.ckr = ck
    }

    private fun dhRatchet(s: RatchetSession, theirDh: ByteArray) {
        val c = Crypto.curve
        s.pn = s.ns
        s.ns = 0
        s.nr = 0
        s.dhr = theirDh.copyOf()
        val (rk1, ckr) = kdfRk(s.rk, c.shared(s.dhsPriv, theirDh))
        s.ckr = ckr
        s.dhsPriv.fill(0)
        s.dhsPriv = c.generatePrivate()
        s.dhsPub = c.publicFrom(s.dhsPriv)
        val (rk2, cks) = kdfRk(rk1, c.shared(s.dhsPriv, theirDh))
        s.rk = rk2
        s.cks = cks
    }

    /** Erase skipped keys that are too old or too many. */
    fun prune(s: RatchetSession, now: Long) {
        val it = s.skipped.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (now - e.value.second > SKIPPED_TTL_MS) { e.value.first.fill(0); it.remove() } }
        while (s.skipped.size > MAX_STORED) { val k = s.skipped.keys.first(); s.skipped.remove(k)?.first?.fill(0) }
    }
}

/*
 * Forward secrecy for groups ("sender keys", as in Signal):
 * every member has their own hash chain per group. Each message uses the next key of the chain and
 * the chain key is then overwritten, so earlier messages can't be recovered from a stolen phone.
 * The current chain state is handed to each member privately over the 1:1 ratchet. Chains are
 * replaced when the membership changes (new epoch) and at least weekly.
 */
class SenderChain(
    val keyId: Int,
    var iteration: Int,
    var chainKey: ByteArray,
    val created: Long,
    val skipped: LinkedHashMap<Int, ByteArray> = LinkedHashMap(),
) {
    fun encode(): ByteArray {
        val w = ByteWriter(128).u8(1).i32(keyId).i32(iteration).fixed(chainKey, 32).u64(created).u16(skipped.size)
        for ((i, k) in skipped) w.i32(i).fixed(k, 32)
        return w.toByteArray()
    }

    fun copy(): SenderChain = decode(encode())

    companion object {
        fun decode(b: ByteArray): SenderChain {
            val r = ByteReader(b)
            if (r.u8() != 1) throw ProtocolException("chain version")
            val id = r.i32(); val it = r.i32(); val ck = r.fixed(32); val created = r.u64()
            val n = r.u16()
            val sk = LinkedHashMap<Int, ByteArray>()
            repeat(n) { sk[r.i32()] = r.fixed(32) }
            return SenderChain(id, it, ck, created, sk)
        }
    }
}

object SenderKeys {
    const val MAX_AHEAD = 2000
    const val MAX_SKIPPED = 500

    fun create(now: Long): SenderChain {
        var id = Crypto.random.nextInt()
        if (id == 0) id = 1
        return SenderChain(id, 0, Crypto.randomBytes(32), now)
    }

    fun keyIdOf(body: ByteArray): Int = ByteReader(body).i32()

    private fun aad(base: ByteArray, keyId: Int, iteration: Int) = ByteWriter(base.size + 8).raw(base).i32(keyId).i32(iteration).toByteArray()

    /** body = keyId(u32) || iteration(u32) || AES-GCM(mk, plain). Advances the chain. */
    fun encrypt(ch: SenderChain, plain: ByteArray, aad: ByteArray): ByteArray {
        val (next, mk) = Ratchet.kdfCk(ch.chainKey)
        val (key, nonce) = Ratchet.aeadKey(mk)
        val ct = Crypto.gcmEncrypt(key, nonce, plain, aad(aad, ch.keyId, ch.iteration))
        val out = ByteWriter(ct.size + 8).i32(ch.keyId).i32(ch.iteration).raw(ct).toByteArray()
        ch.chainKey.fill(0)
        ch.chainKey = next
        ch.iteration++
        return out
    }

    /** Mutates [ch]; throw the copy away on failure. */
    fun decrypt(ch: SenderChain, body: ByteArray, aad: ByteArray): ByteArray {
        val r = ByteReader(body)
        val keyId = r.i32()
        val iteration = r.i32()
        if (keyId != ch.keyId) throw ProtocolException("wrong sender key")
        if (iteration < 0) throw ProtocolException("bad iteration")
        val ct = r.rest()
        val mk: ByteArray
        if (iteration < ch.iteration) {
            mk = ch.skipped.remove(iteration) ?: throw ProtocolException("old or repeated group message")
        } else {
            if (iteration - ch.iteration > MAX_AHEAD) throw ProtocolException("too far ahead")
            var ck = ch.chainKey
            while (ch.iteration < iteration) {
                val (next, k) = Ratchet.kdfCk(ck)
                ch.skipped[ch.iteration] = k
                ck = next
                ch.iteration++
            }
            val (next, k) = Ratchet.kdfCk(ck)
            ch.chainKey = next
            ch.iteration++
            mk = k
            while (ch.skipped.size > MAX_SKIPPED) { val first = ch.skipped.keys.first(); ch.skipped.remove(first)?.fill(0) }
        }
        val (key, nonce) = Ratchet.aeadKey(mk)
        return Crypto.gcmDecrypt(key, nonce, ct, aad(aad, keyId, iteration)).also { mk.fill(0) }
    }
}
