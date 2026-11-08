package com.mura.mesh.mesh

import com.mura.mesh.core.Crypto
import com.mura.mesh.core.Payload
import com.mura.mesh.core.ProtocolException
import com.mura.mesh.core.Ratchet
import com.mura.mesh.core.RatchetMessage
import com.mura.mesh.core.RatchetSession
import com.mura.mesh.core.SenderChain
import com.mura.mesh.core.SenderKeys
import com.mura.mesh.core.SignedPreKey
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import com.mura.mesh.data.Db
import com.mura.mesh.identity.Identity
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the forward-secrecy state: my signed prekeys, a Double Ratchet session per contact
 * and a sender-key chain per group. All of it is stored encrypted in the database and
 * updated only after an operation succeeded, so a bad or replayed message can't damage a session.
 */
class SessionManager(private val db: Db, private val identity: Identity, private val now: () -> Long) {

    companion object {
        const val PREKEY_ROTATE_MS = 7L * 24 * 3600 * 1000
        /** Old prekeys stay for messages still travelling (7-day TTL) plus a margin, then are erased. */
        const val PREKEY_KEEP_MS = 16L * 24 * 3600 * 1000
        const val CHAIN_ROTATE_MS = 7L * 24 * 3600 * 1000
        const val CHAIN_MAX_MESSAGES = 5000
        const val MAX_STORED_SENDER_KEYS = 5000
        const val MAX_UNKNOWN_GROUP_KEYS = 300
    }

    private val locks = ConcurrentHashMap<String, Any>()
    private fun lock(key: String): Any = locks.getOrPut(key) { Any() }

    // ---------------------------------------------------------------- my prekeys

    @Synchronized
    fun currentPrekey(): SignedPreKey {
        db.prekeys().firstOrNull()?.let { return SignedPreKey(it.id, it.pub, it.sig) }
        maintainPrekeys()
        val p = db.prekeys().first()
        return SignedPreKey(p.id, p.pub, p.sig)
    }

    /** Rotates the prekey weekly and erases old ones. Returns true if contacts should get the new one. */
    @Synchronized
    fun maintainPrekeys(): Boolean {
        val t = now()
        val list = db.prekeys()
        var rotated = false
        val newest = list.firstOrNull()
        if (newest == null || t - newest.created > PREKEY_ROTATE_MS) {
            var id = (newest?.id ?: 0) + 1
            if (id <= 0) id = 1
            val (priv, spk) = SignedPreKey.create(id, identity.edSeed)
            db.putPrekey(id, priv, spk.pub, spk.sig, t)
            priv.fill(0)
            rotated = true
        }
        // Each superseded prekey is kept PREKEY_KEEP_MS after its successor was made.
        val all = db.prekeys()
        for (i in 1 until all.size) if (t - all[i - 1].created > PREKEY_KEEP_MS) db.deletePrekey(all[i].id)
        return rotated
    }

    private fun prekeyPair(id: Int): Pair<ByteArray, ByteArray>? =
        if (id == 0) identity.privateKey to identity.publicKey
        else db.prekeys().firstOrNull { it.id == id }?.let { it.priv to it.pub }

    // ---------------------------------------------------------------- 1:1 sessions

    /**
     * Encrypts for [peerHex] with the active session, or starts a new one.
     * [fresh] forces a new session (used to recover when the peer's session was lost, e.g. after a restore).
     */
    fun encrypt(peerHex: String, plain: ByteArray, ad: ByteArray, fresh: Boolean = false): ByteArray = synchronized(lock(peerHex)) {
        val active = if (fresh) null else db.sessions(peerHex).firstOrNull()?.let { runCatching { RatchetSession.decode(it) }.getOrNull() }
        val s = active?.takeIf { it.cks != null } ?: run {
            val peer = peerHex.hexToBytes()
            val spk = db.contactPrekey(peerHex)
            if (spk != null) Ratchet.initiate(identity.privateKey, identity.publicKey, peer, spk.first, spk.second, now())
            else Ratchet.initiate(identity.privateKey, identity.publicKey, peer, 0, peer, now())
        }
        val out = Ratchet.encrypt(s, plain, ad)
        db.putSession(peerHex, s.sidHex, s.encode(), now())
        out
    }

    /** Throws if the message can't be opened (unknown session, expired prekey, replay, tampering). */
    fun decrypt(peerHex: String, wire: ByteArray, ad: ByteArray): ByteArray = synchronized(lock(peerHex)) {
        val m = RatchetMessage.parse(wire)
        val sid = m.header.sid
        val existing = db.sessions(peerHex).asSequence()
            .mapNotNull { runCatching { RatchetSession.decode(it) }.getOrNull() }
            .firstOrNull { it.sid.contentEquals(sid) }
        val work = existing ?: run {
            val ek = m.header.ek ?: throw ProtocolException("no session for this message")
            if (!Ratchet.sessionId(ek).contentEquals(sid)) throw ProtocolException("session id mismatch")
            val (priv, pub) = prekeyPair(m.header.spkId) ?: throw ProtocolException("prekey expired")
            Ratchet.accept(identity.privateKey, identity.publicKey, priv, pub, peerHex.hexToBytes(), ek, now())
        }
        val plain = Ratchet.decrypt(work, m, ad, now())
        db.putSession(peerHex, work.sidHex, work.encode(), now())
        plain
    }

    // ---------------------------------------------------------------- groups (sender keys)

    /**
     * Encrypts a group message with my sender chain. First hands the current chain state to
     * members who don't have it yet ([sendKey] queues a private 1:1 message).
     */
    fun groupEncrypt(
        gid: String, epoch: Int, members: List<String>, plain: ByteArray, aad: ByteArray,
        sendKey: (to: String, key: Payload.SenderKey) -> Unit,
    ): ByteArray = synchronized(lock("g$gid")) {
        val t = now()
        val stored = db.myChain(gid)
        var ch = if (stored != null && stored.first == epoch) runCatching { SenderChain.decode(stored.second) }.getOrNull() else null
        if (ch == null || t - ch.created > CHAIN_ROTATE_MS || ch.iteration > CHAIN_MAX_MESSAGES) ch = SenderKeys.create(t)
        val sent = db.senderKeySentTo(gid, ch.keyId)
        val missing = members.filter { it != identity.publicHex && it !in sent }
        for (m in missing) sendKey(m, Payload.SenderKey(Crypto.randomBytes(16), t, gid.hexToBytes(), epoch, ch.keyId, ch.iteration, ch.chainKey.copyOf()))
        db.markSenderKeySent(gid, ch.keyId, sent + missing)
        val body = SenderKeys.encrypt(ch, plain, aad)
        db.putMyChain(gid, epoch, ch.encode())
        body
    }

    /** Stores a member's chain. A repeated copy never rewinds a chain we already advanced. */
    fun onSenderKey(fromHex: String, p: Payload.SenderKey) {
        val gid = p.groupId.toHex()
        // Groups I'm in are never refused (each member keeps at most 3 chains). Keys for groups I haven't joined yet
        // have their own small quota, so nobody can fill the table and block my real groups.
        if (db.group(gid) == null && (db.unknownGroupSenderKeyCount() >= MAX_UNKNOWN_GROUP_KEYS || db.senderKeyCount() > MAX_STORED_SENDER_KEYS)) return
        synchronized(lock("g$gid")) {
            db.putSenderChain(gid, fromHex, p.keyId, SenderChain(p.keyId, p.iteration, p.chainKey, now()).encode(), now(), onlyIfNew = true)
        }
    }

    /** Null if the sender's chain hasn't arrived yet (keep the message for later). Throws if it's bad or a replay. */
    fun groupDecrypt(gid: String, senderHex: String, body: ByteArray, aad: ByteArray): ByteArray? = synchronized(lock("g$gid")) {
        val keyId = SenderKeys.keyIdOf(body)
        val st = db.senderChain(gid, senderHex, keyId) ?: return null
        val ch = SenderChain.decode(st)
        val plain = SenderKeys.decrypt(ch, body, aad)
        db.putSenderChain(gid, senderHex, keyId, ch.encode(), now())
        plain
    }
}
