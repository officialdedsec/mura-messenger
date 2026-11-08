package com.mura.mesh.mesh

import android.content.Context
import android.util.Log
import com.mura.mesh.call.CallManager
import com.mura.mesh.core.Bundle
import com.mura.mesh.core.BundleMeta
import com.mura.mesh.core.Chunks
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.Envelope
import com.mura.mesh.core.Frame
import com.mura.mesh.core.GroupCrypto
import com.mura.mesh.core.GroupMember
import com.mura.mesh.core.IncomingFile
import com.mura.mesh.core.LinkPreview
import com.mura.mesh.core.Link
import com.mura.mesh.core.MeshHost
import com.mura.mesh.core.MeshRouter
import com.mura.mesh.core.Onion
import com.mura.mesh.media.Translate
import com.mura.mesh.core.OutgoingFile
import com.mura.mesh.core.Payload
import com.mura.mesh.core.Reply
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import com.mura.mesh.data.ChatId
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.ContactState
import com.mura.mesh.data.Db
import com.mura.mesh.data.FileRow
import com.mura.mesh.data.FileState
import com.mura.mesh.data.GroupRow
import com.mura.mesh.data.MemberRow
import com.mura.mesh.data.MessageRow
import com.mura.mesh.data.MsgKind
import com.mura.mesh.data.MsgStatus
import com.mura.mesh.data.StatusRow
import com.mura.mesh.identity.Identity
import com.mura.mesh.media.Prepared
import com.mura.mesh.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.mura.mesh.core.Mention
import com.mura.mesh.core.ListItem
import com.mura.mesh.core.DoodleCodec
import java.io.File
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

data class NearbyPeer(val nodeHex: String, val rssi: Int, val lastSeen: Long)
/** A contact's live status, learned only from their phone over a direct, verified link. Never stored. */
data class PeerPresence(val online: Boolean = false, val activity: Int = Presence.NONE, val activityAt: Long = 0, val seenAt: Long = 0) {
    fun typing(now: Long) = activity == Presence.TYPING && now - activityAt < Presence.ACTIVITY_MS
    fun recording(now: Long) = activity == Presence.RECORDING && now - activityAt < Presence.ACTIVITY_MS
}

object Presence {
    const val NONE = 0; const val ONLINE = 1; const val TYPING = 2; const val RECORDING = 3; const val OFFLINE = 4
    const val ACTIVITY_MS = 6_000L      // "typing…" disappears this long after the last keystroke signal
    const val ONLINE_MS = 75_000L       // "online" needs a heartbeat at least this often
    const val BEAT_MS = 25_000L
}

data class ConnectedPeer(val linkId: String, val pubHex: String, val name: String) {
    /** Over the internet (Tor) rather than Bluetooth. */
    val remote: Boolean get() = linkId.startsWith("tor:")
}

object Settings {
    /** Share and see "online", "typing…" and "recording audio…" with contacts (both ways; off = neither). */
    const val SHARE_PRESENCE = "share_presence"
    /** Let contacts screenshot / screen-record my status updates (off by default). */
    const val STATUS_CAPTURE = "status_allow_capture"
    /** Friends who also know the recipient can hold my undelivered internet mail (and I hold theirs). */
    const val FRIEND_MAILBOX = "friend_mailbox"
    /** Carry anyone's sealed mail across internet (Tor) links too, not only over Bluetooth. */
    const val INTERNET_RELAY = "internet_relay"
    /** Remove location, date and phone model from videos and files: ask | always | never. */
    const val STRIP_META = "strip_meta"
    /** Pad message sizes and send cover traffic so lengths and timing reveal less. */
    const val PAD_TRAFFIC = "pad_traffic"
    const val READ_RECEIPTS = "read_receipts"
    const val BLOCK_UNKNOWN_MESSAGES = "block_unknown_messages"
    const val BLOCK_UNKNOWN_CALLS = "block_unknown_calls"
    const val BLOCK_UNKNOWN_FILES = "block_unknown_files"
    const val BLOCK_UNKNOWN_GROUPS = "block_unknown_groups"
    const val RELAY = "relay"
    const val RELAY_MB = "relay_mb"
    const val VISIBLE_NAME = "visible_name"
    const val SCREEN_SECURITY = "screen_security"
    const val AUTO_DOWNLOAD_MB = "auto_download_mb"
    const val LINK_PREVIEWS = "link_previews"
    const val NOTIF_PREVIEW = "notif_preview"          // full | name | none
    const val APP_LOCK = "app_lock"
    const val PHOTO_QUALITY = "photo_quality"          // STANDARD | HD
    const val VIDEO_QUALITY = "video_quality"          // DATA_SAVER | STANDARD | HD
    const val AVATAR = "my_avatar"                     // secret
    const val STATUS_KEY = "status_key"                // secret
    const val STATUS_EPOCH = "status_epoch"
    const val STATUS_KEY_AT = "status_key_at"
    const val CLIPBOARD_CLEAR = "clipboard_clear"       // wipe copied messages from the clipboard after 60 s
    const val LOCK_AFTER = "lock_after"                 // seconds away before App lock locks again
    const val DEFAULT_TIMER = "default_timer"           // disappearing timer for new chats (seconds)
    const val HIDE_LOCKED = "hide_locked"               // hide locked chats from the chat list
}

/** "24 hours", "7 days"… for disappearing-message timers. */
fun timerLabel(seconds: Int): String = when {
    seconds <= 0 -> "Off"
    seconds % 86_400 == 0 -> (seconds / 86_400).let { if (it == 1) "24 hours" else "$it days" }
    seconds % 3600 == 0 -> (seconds / 3600).let { if (it == 1) "1 hour" else "$it hours" }
    else -> "${seconds / 60} min"
}

/**
 * The messenger: turns user actions into sealed bundles and received bundles into chats,
 * groups, statuses, files and notifications. One instance for the whole app.
 */
class MeshEngine(private val ctx: Context, val identity: Identity, val db: Db) : MeshHost, BleTransport.Listener {

    companion object {
        /**
         * View-once messages opened in the last few seconds (id → when), kept only in memory, so the chat can play the
         * Cipher seal when you come back from the viewer (or when the other person opens yours). Never stored.
         */
        val recentlyOpened = ConcurrentHashMap<String, Long>()
        private const val TAG = "MuraEngine"
        private const val MAX_PENDING_REQUESTS = 100
        const val MESSAGE_TTL_MS = 7L * 24 * 3600 * 1000
        const val RECEIPT_TTL_MS = 3L * 24 * 3600 * 1000
        const val STATUS_TTL_MS = 24L * 3600 * 1000
        /** Own text messages can be edited for an hour after sending. */
        const val EDIT_WINDOW_MS = 60 * 60_000L
        private val CALL_AAD = "mura-call-v2".toByteArray()
        /** Live status rides the same sealed link channel as calls, under its own label (older Mura versions ignore it). */
        private val PRESENCE_AAD = "mura-presence-v1".toByteArray()
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val router = MeshRouter(this, db)
    val transport = BleTransport(ctx, identity.nodeId, this)
    val notifier = Notifier(ctx)
    val calls = CallManager(ctx, this)
    /** Internet links through Tor onion services — no server anywhere. Bluetooth keeps working exactly as before. */
    val online = OnlineTransport(ctx, this)
    /** Forward secrecy: prekeys, 1:1 ratchet sessions and group sender keys. */
    val sessions = SessionManager(db, identity) { now() }
    private val lastReset = ConcurrentHashMap<String, Long>()

    private val _dbTick = MutableStateFlow(0L)
    val dbTick: StateFlow<Long> = _dbTick.asStateFlow()
    private val _nearby = MutableStateFlow<Map<String, NearbyPeer>>(emptyMap())
    val nearby: StateFlow<Map<String, NearbyPeer>> = _nearby.asStateFlow()
    private val _connected = MutableStateFlow<List<ConnectedPeer>>(emptyList())
    val connected: StateFlow<List<ConnectedPeer>> = _connected.asStateFlow()
    private val _state = MutableStateFlow(BleTransport.State.OFF)
    val state: StateFlow<BleTransport.State> = _state.asStateFlow()
    private val _sendProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val sendProgress: StateFlow<Map<String, Float>> = _sendProgress.asStateFlow()
    private val handedPieces = ConcurrentHashMap<String, BitSet>()

    @Volatile var openChat: String? = null

    private val outgoing = ConcurrentHashMap<String, OutgoingFile>()
    private val incoming = ConcurrentHashMap<String, IncomingFile>()
    /** Group mesh addresses (hex) -> group id, and contacts' status addresses -> contact key. */
    @Volatile private var groupNodes: Map<String, String> = emptyMap()
    @Volatile private var statusNodes: Map<String, String> = emptyMap()
    private val myStatusNode = GroupCrypto.statusNode(identity.publicKey).toHex()
    private var started = false

    init {
        db.onChange = { _dbTick.value = _dbTick.value + 1 }
    }

    // ================================================================ lifecycle

    /** Traffic padding (message sizes and internet-link cover traffic). */
    fun setPadding(on: Boolean) {
        db.setBool(Settings.PAD_TRAFFIC, on)
        com.mura.mesh.core.Compression.padding = on
        com.mura.mesh.core.StreamLink.padding = on
    }

    fun start() {
        val pad = db.getBool(Settings.PAD_TRAFFIC, true)
        com.mura.mesh.core.Compression.padding = pad
        com.mura.mesh.core.StreamLink.padding = pad
        if (started) { transport.start(); online.start(); return }
        started = true
        refreshSubscriptions()
        loadOutgoingFiles()
        transport.start()
        online.start()
        scope.launch {
            try { if (sessions.maintainPrekeys()) broadcastProfile(light = true) } catch (e: Exception) { Log.w(TAG, "prekeys", e) }
            while (isActive) {
                delay(120_000)
                try { housekeeping() } catch (e: Exception) { Log.w(TAG, "housekeeping", e) }
            }
        }
        // Disappearing messages are erased within a few seconds of their time.
        scope.launch {
            while (isActive) {
                try { expireMessages() } catch (e: Exception) { Log.w(TAG, "expiry", e) }
                try { sendDueScheduled() } catch (e: Exception) { Log.w(TAG, "scheduled", e) }
                delay(5_000)
            }
        }
    }

    fun stop() { transport.stop(); online.stop() }

    private fun housekeeping() {
        val now = now()
        for (id in db.purge(now)) db.raiseStatus(id, MsgStatus.EXPIRED)
        for ((k, f) in outgoing) if (f.expiresAt <= now) { outgoing.remove(k); db.setFileState(k, FileState.GONE) }
        val cutoff = now - 3 * 60_000   // ids rotate every 15 min, so keep the radar fresh
        _nearby.value = _nearby.value.filterValues { it.lastSeen > cutoff }
        if (sessions.maintainPrekeys()) broadcastProfile(light = true)
        router.kick(reofferAll = true)
    }

    private fun expireMessages() {
        val t = now()
        val next = db.nextExpiry()
        if (next == 0L || next > t) return
        for ((id, fid) in db.expiredMessages(t)) {
            val m = db.message(id)
            fid?.let { f ->
                db.file(f)?.let { File(it.path).delete() }
                outgoing.remove(f); incoming.remove(f); handedPieces.remove(f)
                db.deleteFileRow(f)
            }
            if (m?.outgoing == true) db.delete(id.hexToBytes())   // not delivered yet: it disappears from the mesh too
            db.deleteMessage(id)
        }
    }

    private fun loadOutgoingFiles() {
        for (f in db.filesWhere("outgoing=1 AND state=${FileState.ACTIVE}")) {
            val file = File(f.path)
            if (!file.exists() || f.expires <= now()) { db.setFileState(f.fileId, FileState.GONE); continue }
            outgoing[f.fileId] = OutgoingFile(f.fileId.hexToBytes(), f.dest, f.key, file, f.size, f.chunkSize, f.expires)
        }
    }

    fun refreshSubscriptions() {
        val g = HashMap<String, String>()
        for (grp in db.groups()) if (!grp.removed && !grp.dissolved) g[GroupCrypto.groupNode(grp.id.hexToBytes()).toHex()] = grp.id
        groupNodes = g
        val s = HashMap<String, String>()
        for (c in db.contacts()) if (c.state == ContactState.CONTACT && c.statusKey != null) s[GroupCrypto.statusNode(c.pub.hexToBytes()).toHex()] = c.pub
        statusNodes = s
        contactNodes = db.contacts().filter { it.state == ContactState.CONTACT }.map { Crypto.nodeId(it.pub.hexToBytes()).toHex() }.toSet()
    }

    // ================================================================ settings

    val readReceipts get() = db.getBool(Settings.READ_RECEIPTS, true)
    override val myPublicKey: ByteArray get() = identity.publicKey
    override val myNodeId: ByteArray get() = identity.nodeId
    override fun sharedSecret(peerPublic: ByteArray): ByteArray = Crypto.curve.shared(identity.privateKey, peerPublic)
    override val helloName: String get() = if (db.getBool(Settings.VISIBLE_NAME, true)) identity.name else ""
    override val relayEnabled: Boolean get() = db.getBool(Settings.RELAY, true)
    override fun now(): Long = System.currentTimeMillis()
    override fun log(msg: String) { Log.i(TAG, msg) }
    private fun autoDownloadLimit(): Long = (db.getSetting(Settings.AUTO_DOWNLOAD_MB, "50").toLongOrNull() ?: 50L) * 1024 * 1024

    private fun changedMesh() { transport.clearCooldowns(); router.kick(); online.poke() }

    // ================================================================ internet (Tor)

    /** My onion address was created or changed: tell my contacts (end-to-end encrypted, contacts only). */
    fun onOnionChanged() = scope.launch { broadcastProfile(light = true) }

    @Volatile private var pendingCache: Pair<Long, Map<String, Long>> = 0L to emptyMap()
    @Volatile private var mailboxCache: Pair<Long, Map<String, Long>> = 0L to emptyMap()
    /** Node ids of my contacts: I hold sealed mail for them when friends hand it to me over the internet. */
    @Volatile private var contactNodes: Set<String> = emptySet()

    /** Friend mailboxes (Settings › Internet): on unless turned off. */
    val mailboxOn: Boolean get() = db.getBool(Settings.FRIEND_MAILBOX, true)

    /** My messages that couldn't be delivered directly for 90 s: friends who also know the recipient may carry them. */
    private fun mailboxCandidates(): Map<String, Long> {
        val t = now()
        return mailboxCache.let { (at, m) -> if (t - at < 5_000) m else db.undeliveredOwn(t, t - 90_000).also { mailboxCache = t to it } }
    }

    override fun isMailboxFor(dest: ByteArray): Boolean = mailboxOn && dest.toHex() in contactNodes

    override val internetRelay: Boolean get() = db.getBool(Settings.INTERNET_RELAY, true)
    @Volatile private var carriedCache: Pair<Long, Long> = 0L to 0L

    override fun offerToMailbox(meta: BundleMeta): Boolean = mailboxOn && meta.id.toHex() in mailboxCandidates()

    /**
     * Is there anything waiting on this phone that [pub] should get — a 1:1 message, a file,
     * a message in a group they're in, or a new status of mine — newer than our last sync?
     * Decides whether to open a Tor connection to them.
     */
    fun hasPendingFor(pub: String, since: Long): Boolean {
        val t = now()
        val pending = pendingCache.let { (at, m) -> if (t - at < 2_000) m else db.pendingByDest(t).also { pendingCache = t to it } }
        val node = Crypto.nodeId(pub.hexToBytes())
        val nodeHex = node.toHex()
        if ((pending[nodeHex] ?: 0L) > since) return true
        if (outgoing.values.any { it.dest.contentEquals(node) }) return since == 0L || t - since > 10 * 60_000
        for (gid in db.groupsOf(pub)) {
            if ((pending[GroupCrypto.groupNode(gid.hexToBytes()).toHex()] ?: 0L) > since) return true
        }
        if ((pending[myStatusNode] ?: 0L) > since) return true
        // Carrying mail for others: pass it on to every contact once (they carry it further, or deliver it).
        if (relayEnabled && internetRelay) {
            val newest = carriedCache.let { (at, v) -> if (t - at < 5_000) v else db.newestCarried(t).also { carriedCache = t to it } }
            if (newest > since) return true
        }
        // Friend mailbox: a message for someone else has been stuck for a while — maybe this friend can carry it.
        if (mailboxOn) {
            val newest = mailboxCandidates().values.maxOrNull() ?: 0L
            if (newest + 90_000 > since) return true
        }
        return false
    }


    // ================================================================ sending primitives

    /** 1:1: Double Ratchet (forward secrecy) inside the sealed-sender envelope. */
    private fun queueDirect(peerHex: String, p: Payload, ttl: Long = MESSAGE_TTL_MS, freshSession: Boolean = false) {
        val peer = peerHex.hexToBytes()
        val dest = Crypto.nodeId(peer)
        val aad = Bundle.aad(p.msgId, dest)
        val inner = sessions.encrypt(peerHex, p.pack(), aad, freshSession)
        val sealed = Envelope.seal(identity.privateKey, identity.publicKey, peer, aad, inner)
        db.put(Bundle(p.msgId, Bundle.TYPE_MSG, dest, Bundle.ZERO16, 0, now() + ttl, MeshRouter.MAX_HOPS, sealed), mine = true)
        changedMesh()
    }

    /** Encrypt once with the group key and address it to the group. Returns false if we have no key. */
    private fun queueGroup(gid: String, p: Payload, ttl: Long = MESSAGE_TTL_MS): Boolean {
        val g = db.group(gid) ?: return false
        if (g.removed || g.dissolved && p !is Payload.GroupState) return false
        val key = db.groupKey(gid, g.epoch) ?: return false
        val node = GroupCrypto.groupNode(gid.hexToBytes())
        // Inner layer: my sender-key chain (forward secrecy). Outer layer: the group epoch key + my signature.
        val members = db.members(gid).map { it.pub }
        val body = sessions.groupEncrypt(gid, g.epoch, members, p.pack(), p.msgId + node) { to, sk -> queueDirect(to, sk) }
        val wire = GroupCrypto.seal(key, g.epoch, p.msgId, node, identity.publicKey, identity.edSeed, body)
        db.markSeen(p.msgId)
        db.put(Bundle(p.msgId, Bundle.TYPE_MSG, node, Bundle.ZERO16, 0, now() + ttl, MeshRouter.MAX_HOPS, wire), mine = true)
        changedMesh()
        return true
    }

    private fun queueToChat(chat: String, p: Payload): Boolean =
        if (ChatId.isGroup(chat)) queueGroup(ChatId.groupIdOf(chat), p) else { queueDirect(chat, p); true }

    private fun destFor(chat: String): ByteArray =
        if (ChatId.isGroup(chat)) GroupCrypto.groupNode(ChatId.groupIdOf(chat).hexToBytes()) else Crypto.nodeId(chat.hexToBytes())

    // ================================================================ chat actions

    // ---------------- replies and disappearing timers

    /**
     * Reply to someone's status, privately, in your 1:1 chat with them (like WhatsApp). It's an ordinary
     * sealed, ratcheted message with a quote of the status — only the two of you ever see it.
     */
    fun replyToStatus(s: StatusRow, text: String) {
        if (s.owner == identity.publicHex || text.isBlank()) return
        val what = when {
            s.kind == Payload.STATUS_IMAGE -> "📷 " + s.text.ifBlank { "Photo" }
            else -> s.text
        }
        val ref = Reply(s.id.hexToBytes(), s.owner.hexToBytes(), MsgKind.STATUS, ("Status · " + what).take(160))
        sendText(s.owner, text, viewOnce = false, preview = null, replyRef = ref)
    }

    /** The quote attached to a reply. View-once content is never quoted. */
    fun replyFor(m: MessageRow): Reply {
        val sender = (if (m.outgoing) identity.publicHex else m.sender).let { h -> runCatching { h.hexToBytes() }.getOrNull()?.takeIf { it.size == 32 } }
        val snippet = when {
            m.viewOnce -> "View-once message"
            m.kind == MsgKind.LOCATION -> "Location"
            m.kind == MsgKind.POLL -> "Poll: ${m.body}"
            m.kind == MsgKind.LIST -> "List: ${m.body}"
            else -> m.body
        }.take(160)
        return Reply(m.id.hexToBytes(), sender ?: Reply.NOBODY, m.kind, snippet)
    }

    private fun putReply(meta: JSONObject, r: Reply?) {
        if (r == null) return
        meta.put("r_id", r.msgId.toHex()).put("r_sender", r.sender.toHex()).put("r_kind", r.kind).put("r_text", r.snippet)
    }

    private fun expiryFor(chat: String, created: Long): Pair<Int, Long> {
        val ttl = db.chatTimer(chat)
        return ttl to (if (ttl > 0) created + ttl * 1000L else 0L)
    }

    /** Turn disappearing messages on/off for a chat (tells the other side too). */
    fun setTimer(chat: String, seconds: Int) = scope.launch {
        if (db.chatTimer(chat) == seconds) return@launch
        db.setChatTimer(chat, seconds)
        system(chat, if (seconds > 0) "You turned on disappearing messages · ${timerLabel(seconds)}" else "You turned off disappearing messages")
        queueToChat(chat, Payload.Timer(Crypto.randomBytes(16), now(), seconds))
    }

    private fun defaultTimer(): Int? = db.getSetting(Settings.DEFAULT_TIMER, "0").toIntOrNull()?.takeIf { it > 0 && it in Payload.TIMERS }

    private fun applyTimer(chat: String, byName: String, seconds: Int) {
        if (seconds !in Payload.TIMERS || db.chatTimer(chat) == seconds) return
        db.setChatTimer(chat, seconds)
        system(chat, if (seconds > 0) "$byName turned on disappearing messages · ${timerLabel(seconds)}" else "$byName turned off disappearing messages")
    }

    // ---------------- sending

    fun sendText(chat: String, text: String, viewOnce: Boolean, preview: LinkPreview?, replyTo: MessageRow? = null,
                 forwarded: Boolean = false, mentions: List<Mention> = emptyList(), replyRef: Reply? = null) = scope.launch {
        val t = text.trim()
        if (t.isEmpty()) return@launch
        val id = Crypto.randomBytes(16)
        val at = now()
        val reply = replyRef ?: replyTo?.let { replyFor(it) }
        val (ttl, expireAt) = expiryFor(chat, at)
        val meta = JSONObject()
        if (preview != null) meta.put("p_url", preview.url).put("p_title", preview.title).put("p_desc", preview.description)
            .put("p_img", android.util.Base64.encodeToString(preview.image, android.util.Base64.NO_WRAP))
        putReply(meta, reply)
        val ms = mentions.filter { t.contains("@" + it.name) }
        extrasMeta(meta, forwarded, ms)
        db.insertMessage(MessageRow(id.toHex(), chat, identity.publicHex, true, MsgKind.TEXT, t, null, 0, MsgStatus.WAITING, viewOnce, false, at, meta,
            expireAt = expireAt))
        if (!queueToChat(chat, Payload.Text(id, at, t, viewOnce, preview, reply, ttl, forwarded, ms))) failSend(id.toHex())
    }

    fun sendLocation(chat: String, lat: Double, lon: Double, accuracy: Float, viewOnce: Boolean, replyTo: MessageRow? = null,
                     forwarded: Boolean = false) = scope.launch {
        val id = Crypto.randomBytes(16)
        val at = now()
        val reply = replyTo?.let { replyFor(it) }
        val (ttl, expireAt) = expiryFor(chat, at)
        val meta = JSONObject().put("lat", lat).put("lon", lon).put("acc", accuracy.toDouble())
        putReply(meta, reply)
        extrasMeta(meta, forwarded, emptyList())
        db.insertMessage(MessageRow(id.toHex(), chat, identity.publicHex, true, MsgKind.LOCATION, "Location", null, 0, MsgStatus.WAITING, viewOnce, false, at, meta,
            expireAt = expireAt))
        if (!queueToChat(chat, Payload.Location(id, at, lat, lon, accuracy, viewOnce, reply, ttl, forwarded))) failSend(id.toHex())
    }

    /** Send a file that SecureMedia already compressed and sealed. */
    fun sendPrepared(chat: String, p: Prepared, caption: String, viewOnce: Boolean, replyTo: MessageRow? = null,
                     forwarded: Boolean = false) = scope.launch {
        val msgId = Crypto.randomBytes(16)
        val expires = now() + MESSAGE_TTL_MS
        val dest = destFor(chat)
        val count = Chunks.count(p.size)
        db.insertFile(FileRow(p.fileId.toHex(), msgId.toHex(), chat, true, p.name, p.mime, p.media, p.size, Chunks.SIZE, count, p.sha, p.key,
            p.thumb, null, count, p.sealed.absolutePath, FileState.ACTIVE, p.durationMs, p.width, p.height, expires, p.compressed, p.originalSize, dest))
        val at = now()
        val reply = replyTo?.let { replyFor(it) }
        val (ttl, expireAt) = expiryFor(chat, at)
        val meta = JSONObject().put("caption", caption).put("name", p.name).put("size", p.originalSize).put("mime", p.mime)
            .put("duration", p.durationMs).put("w", p.width).put("h", p.height).put("sent_size", p.size)
        putReply(meta, reply)
        extrasMeta(meta, forwarded, emptyList())
        db.insertMessage(MessageRow(msgId.toHex(), chat, identity.publicHex, true, MsgKind.FILE, previewLabel(p.media, p.name, p.durationMs),
            p.fileId.toHex(), p.media, MsgStatus.WAITING, viewOnce, false, at, meta, expireAt = expireAt))
        outgoing[p.fileId.toHex()] = OutgoingFile(p.fileId, dest, p.key, p.sealed, p.size, Chunks.SIZE, expires)
        val payload = Payload.File(msgId, at, p.fileId, p.media, p.name, p.mime, p.size, Chunks.SIZE, count, p.sha, p.key,
            p.durationMs, p.width, p.height, p.thumb, caption, viewOnce, p.compressed, p.originalSize, reply, ttl, forwarded)
        if (!queueToChat(chat, payload)) failSend(msgId.toHex())
    }

    /** A call recording or other local-only file: shown in the chat, never sent. */
    fun saveLocalFile(chat: String, p: Prepared, label: String) = scope.launch {
        val msgId = Crypto.randomBytes(16)
        db.insertFile(FileRow(p.fileId.toHex(), msgId.toHex(), chat, true, p.name, p.mime, p.media, p.size, Chunks.SIZE, Chunks.count(p.size),
            p.sha, p.key, null, null, Chunks.count(p.size), p.sealed.absolutePath, FileState.COMPLETE, p.durationMs, 0, 0, Long.MAX_VALUE,
            false, p.originalSize, ByteArray(8)))
        db.insertMessage(MessageRow(msgId.toHex(), chat, identity.publicHex, true, MsgKind.FILE, label, p.fileId.toHex(), p.media,
            MsgStatus.READ, false, false, now(), JSONObject().put("local", true).put("duration", p.durationMs)))
    }

    /**
     * A call recording goes only to call history: sealed in the app's private storage, never shown in or sent to
     * any chat. Only this phone (with Mura unlocked) can play it.
     */
    fun saveCallRecording(peer: String, p: Prepared) = scope.launch {
        val id = Crypto.randomBytes(16).toHex()
        db.insertFile(FileRow(p.fileId.toHex(), id, "rec:$peer", true, p.name, p.mime, p.media, p.size, Chunks.SIZE, Chunks.count(p.size),
            p.sha, p.key, null, null, Chunks.count(p.size), p.sealed.absolutePath, FileState.COMPLETE, p.durationMs, 0, 0, Long.MAX_VALUE,
            false, p.originalSize, ByteArray(8)))
        db.insertRecording(com.mura.mesh.data.RecordingRow(id, peer, p.fileId.toHex(), now(), p.durationMs))
    }

    /** Erases a call recording: the sealed audio file and its entry. */
    fun deleteRecording(id: String) = scope.launch {
        val r = db.recording(id) ?: return@launch
        db.file(r.fileId)?.let { File(it.path).delete() }
        db.deleteFileRow(r.fileId)
        db.deleteRecordingRow(id)
    }

    private fun failSend(msgId: String) {
        db.raiseStatus(msgId, MsgStatus.EXPIRED)
        system(db.message(msgId)?.chat ?: return, "Couldn't send — the group key hasn't arrived yet. Try again when an admin is in range.")
    }

    fun previewLabel(media: Int, name: String, durationMs: Int): String = when (media) {
        Payload.MEDIA_IMAGE -> "Photo"
        Payload.MEDIA_VIDEO -> "Video · ${formatDuration(durationMs)}"
        Payload.MEDIA_VOICE -> "Voice message · ${formatDuration(durationMs)}"
        else -> name
    }

    private fun system(chat: String, text: String) {
        db.insertMessage(MessageRow(Crypto.randomBytes(16).toHex(), chat, "", false, MsgKind.SYSTEM, text, null, 0, MsgStatus.READ,
            false, false, now(), JSONObject()))
    }

    fun markChatRead(chat: String) = scope.launch {
        db.clearUnread(chat)
        notifier.cancelChat(chat)
        val ids = db.unreadIncoming(chat)
        if (ids.isEmpty()) return@launch
        for (id in ids) db.raiseStatus(id, MsgStatus.READ)
        if (!ChatId.isGroup(chat) && readReceipts && db.contact(chat)?.state == ContactState.CONTACT) {
            queueDirect(chat, Payload.Read(Crypto.randomBytes(16), now(), ids.map { it.hexToBytes() }.take(500)), RECEIPT_TTL_MS)
        }
    }

    /** "Reply" typed straight into a notification. */
    fun replyFromNotification(chat: String, text: String) = scope.launch {
        val t = text.trim()
        if (t.isEmpty() || db.isLocked(chat)) return@launch
        if (!ChatId.isGroup(chat) && db.contact(chat)?.state != ContactState.CONTACT) return@launch
        sendText(chat, t, false, null).join()
        markChatRead(chat).join()
        notifier.addMyReply(chat, t)
    }

    /** Locked chats need fingerprint/face/screen lock to open; their notifications never show content. */
    fun setLocked(chat: String, locked: Boolean) = scope.launch {
        db.setLocked(chat, locked)
        if (locked) notifier.cancelChat(chat)
    }

    fun reportScreenshot(chat: String) = scope.launch {
        system(chat, "You took a screenshot")
        if (ChatId.isGroup(chat)) queueGroup(ChatId.groupIdOf(chat), Payload.Screenshot(Crypto.randomBytes(16), now()))
        else queueDirect(chat, Payload.Screenshot(Crypto.randomBytes(16), now()), RECEIPT_TTL_MS)
    }

    fun consumeViewOnce(msgId: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        if (m.outgoing || !m.viewOnce || m.opened) return@launch
        recentlyOpened[msgId] = now()
        db.markOpened(msgId)
        db.clearBody(msgId)
        m.fileId?.let { wipeFile(it) }
        if (m.kind == MsgKind.LOCATION) db.setMessageMeta(msgId, JSONObject())
        if (!ChatId.isGroup(m.chat)) queueDirect(m.chat, Payload.Viewed(Crypto.randomBytes(16), now(), msgId.hexToBytes()), RECEIPT_TTL_MS)
    }

    private fun wipeFile(fileId: String) {
        db.file(fileId)?.let { File(it.path).delete() }
        outgoing.remove(fileId)
        incoming.remove(fileId)
        db.setFileState(fileId, FileState.GONE)
    }

    fun deleteForMe(msgId: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        m.fileId?.let { fid -> db.file(fid)?.let { if (!it.outgoing || it.state != FileState.ACTIVE) { File(it.path).delete(); db.deleteFileRow(fid) } } }
        db.deleteMessage(msgId)
    }

    /** Delete for everyone: my own messages, or anyone's if I'm a group admin. */
    fun canDeleteForEveryone(m: MessageRow): Boolean {
        if (m.deleted || m.kind == MsgKind.SYSTEM || m.kind == MsgKind.CALL || m.meta.optBoolean("local") || m.status == MsgStatus.SCHEDULED) return false
        if (m.outgoing) return true
        if (ChatId.isGroup(m.chat)) return db.member(ChatId.groupIdOf(m.chat), identity.publicHex)?.admin == true
        return false
    }

    fun deleteForEveryone(msgId: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        if (!canDeleteForEveryone(m)) return@launch
        db.delete(msgId.hexToBytes())                // pull it back if it hasn't left my phone yet
        m.fileId?.let { wipeFile(it) }
        db.tombstoneMessage(msgId)
        queueToChat(m.chat, Payload.Delete(Crypto.randomBytes(16), now(), listOf(msgId.hexToBytes())))
    }

    fun archive(chat: String, on: Boolean) = scope.launch { db.setArchived(chat, on) }

    /** Delete chat: erase its messages and files from this phone and take it off the chat list. */
    fun deleteChatFromList(chat: String) = scope.launch {
        for (m in db.messages(chat, 100_000)) m.fileId?.let { fid -> db.file(fid)?.let { if (!(it.outgoing && it.state == FileState.ACTIVE)) File(it.path).delete() } }
        db.deleteChat(chat)
        db.setHidden(chat, true)
    }

    fun clearChat(chat: String) = scope.launch {
        for (m in db.messages(chat, 100_000)) m.fileId?.let { fid -> db.file(fid)?.let { if (!(it.outgoing && it.state == FileState.ACTIVE)) File(it.path).delete() } }
        db.deleteChat(chat)
    }

    fun acceptFile(fileId: String) = scope.launch {
        db.file(fileId) ?: return@launch
        db.setFileState(fileId, FileState.ACTIVE)
        transport.clearCooldowns()
        router.kick(reofferAll = true)
    }

    // ================================================================ everyday chat features

    private fun extrasMeta(meta: JSONObject, forwarded: Boolean, mentions: List<Mention>) {
        if (forwarded) meta.put("fwd", true)
        if (mentions.isNotEmpty()) meta.put("mentions", JSONArray().apply {
            mentions.forEach { put(JSONObject().put("pub", it.pub.toHex()).put("name", it.name)) }
        })
    }

    private fun isContent(m: MessageRow) = m.kind in MsgKind.CONTENT && !m.deleted && m.status != MsgStatus.SCHEDULED

    private fun snippet(m: MessageRow, max: Int = 60): String = when {
        m.viewOnce -> "View-once message"
        m.kind == MsgKind.POLL -> "Poll: ${m.body}"
        m.kind == MsgKind.LIST -> "List: ${m.body}"
        else -> m.body
    }.let { if (it.length > max) it.take(max - 1) + "…" else it }

    // ---- reactions

    /** Same emoji again removes it; a different one replaces mine. */
    fun react(msgId: String, emoji: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        if (!isContent(m)) return@launch
        val mine = db.reactionsForChat(m.chat)[msgId]?.firstOrNull { it.sender == identity.publicHex }?.emoji
        val e = if (mine == emoji) "" else emoji.take(16)
        db.putReaction(msgId, m.chat, identity.publicHex, e, now())
        queueToChat(m.chat, Payload.Reaction(Crypto.randomBytes(16), now(), msgId.hexToBytes(), e))
    }

    private fun onReaction(chat: String, sender: String, senderName: String, p: Payload.Reaction) {
        val target = p.target.toHex()
        db.putReaction(target, chat, sender, p.emoji, p.sentAt.coerceAtMost(now()))
        val m = db.message(target) ?: return
        if (p.emoji.isNotEmpty() && m.outgoing && m.chat == chat && openChat != chat && !db.isMuted(chat)) {
            val group = ChatId.isGroup(chat)
            val title = if (group) db.group(ChatId.groupIdOf(chat))?.name ?: "Group" else senderName
            notifier.message(chat, title, if (group) senderName else null, "Reacted ${p.emoji} to “${snippet(m)}”", locked = db.isLocked(chat), canReply = true)
        }
    }

    // ---- edits

    fun canEdit(m: MessageRow): Boolean = m.outgoing && m.kind == MsgKind.TEXT && !m.viewOnce && !m.deleted &&
        (m.status == MsgStatus.SCHEDULED || now() - m.created < EDIT_WINDOW_MS)

    fun edit(msgId: String, text: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        val t = text.trim()
        if (t.isEmpty() || t == m.body || !canEdit(m)) return@launch
        val meta = m.meta
        if (m.status != MsgStatus.SCHEDULED) meta.put("edited", now())
        dropStalePreview(meta, t)
        db.setBody(msgId, t, meta)
        if (m.status == MsgStatus.SCHEDULED) return@launch      // not sent yet: just change it here
        queueToChat(m.chat, Payload.Edit(Crypto.randomBytes(16), now(), msgId.hexToBytes(), t))
    }

    private fun dropStalePreview(meta: JSONObject, text: String) {
        val u = meta.optString("p_url", "")
        if (u.isNotEmpty() && !text.contains(u)) listOf("p_url", "p_title", "p_desc", "p_img").forEach { meta.remove(it) }
    }

    private fun onEdit(chat: String, sender: String, p: Payload.Edit) {
        val target = p.target.toHex()
        val m = db.message(target)
        if (m == null) { db.putPendingEdit(target, chat, sender, p.text.take(20_000), p.sentAt); return }
        applyEdit(m, chat, sender, p.text, p.sentAt)
    }

    private fun applyEdit(m: MessageRow, chat: String, sender: String, text: String, at: Long) {
        if (m.chat != chat || m.sender != sender || m.outgoing || m.kind != MsgKind.TEXT || m.viewOnce || m.deleted) return
        if (at - m.created > EDIT_WINDOW_MS + 10 * 60_000) return
        val prev = m.meta.optLong("edited", 0L)
        if (prev > at) return                                     // an older edit arrived late
        val meta = m.meta.put("edited", at)
        dropStalePreview(meta, text)
        db.setBody(m.id, text.take(20_000), meta)
    }

    // ---- polls

    fun sendPoll(chat: String, question: String, options: List<String>, multi: Boolean, replyTo: MessageRow? = null,
                 forwarded: Boolean = false) = scope.launch {
        val q = question.trim().take(300)
        val opts = options.map { it.trim().take(100) }.filter { it.isNotEmpty() }.distinct().take(Payload.MAX_POLL_OPTIONS)
        if (q.isEmpty() || opts.size < 2) return@launch
        val id = Crypto.randomBytes(16); val at = now()
        val reply = replyTo?.let { replyFor(it) }
        val (ttl, expireAt) = expiryFor(chat, at)
        val meta = JSONObject().put("options", JSONArray(opts)).put("multi", multi)
        putReply(meta, reply); extrasMeta(meta, forwarded, emptyList())
        db.insertMessage(MessageRow(id.toHex(), chat, identity.publicHex, true, MsgKind.POLL, q, null, 0, MsgStatus.WAITING, false, false, at, meta, expireAt = expireAt))
        if (!queueToChat(chat, Payload.Poll(id, at, q, opts, multi, reply, ttl, forwarded))) failSend(id.toHex())
    }

    /** My choices in a poll; tapping my only choice again withdraws the vote. */
    fun vote(pollId: String, choices: List<Int>) = scope.launch {
        val m = db.message(pollId) ?: return@launch
        if (m.kind != MsgKind.POLL || m.deleted) return@launch
        val n = m.meta.optJSONArray("options")?.length() ?: 0
        val c = choices.filter { it in 0 until n }.distinct().let { if (m.meta.optBoolean("multi")) it else it.take(1) }
        db.putVote(pollId, m.chat, identity.publicHex, c, now())
        queueToChat(m.chat, Payload.Vote(Crypto.randomBytes(16), now(), pollId.hexToBytes(), c))
    }

    private fun onVote(chat: String, voter: String, p: Payload.Vote) =
        db.putVote(p.poll.toHex(), chat, voter, p.choices, p.sentAt.coerceAtMost(now()))

    // ---- pins and stars

    fun canPin(chat: String): Boolean = !ChatId.isGroup(chat) || isAdmin(ChatId.groupIdOf(chat))

    fun pin(msgId: String, pinned: Boolean) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        if ((pinned && !isContent(m)) || !canPin(m.chat)) return@launch
        db.setPinned(m.chat, msgId, pinned, now())
        if (pinned) system(m.chat, "You pinned a message")
        queueToChat(m.chat, Payload.Pin(Crypto.randomBytes(16), now(), msgId.hexToBytes(), pinned))
    }

    private fun onPin(chat: String, senderName: String, p: Payload.Pin, allowed: Boolean) {
        if (!allowed) return
        db.setPinned(chat, p.target.toHex(), p.pinned, p.sentAt.coerceAtMost(now()))
        if (p.pinned) system(chat, "$senderName pinned a message")
    }

    /** Stars are private: only on this phone. */
    fun star(msgId: String, on: Boolean) = scope.launch { db.setStarred(msgId, on) }

    // ---- shared checklists

    fun sendChecklist(chat: String, title: String, items: List<String>, replyTo: MessageRow? = null, forwarded: Boolean = false) = scope.launch {
        val t = title.trim().take(100).ifEmpty { "List" }
        val its = items.map { it.trim().take(200) }.filter { it.isNotEmpty() }.take(Payload.MAX_LIST_ITEMS).map { ListItem(Crypto.randomBytes(8), it) }
        val id = Crypto.randomBytes(16); val at = now()
        val reply = replyTo?.let { replyFor(it) }
        val (ttl, expireAt) = expiryFor(chat, at)
        val meta = JSONObject()
        putReply(meta, reply); extrasMeta(meta, forwarded, emptyList())
        db.insertMessage(MessageRow(id.toHex(), chat, identity.publicHex, true, MsgKind.LIST, t, null, 0, MsgStatus.WAITING, false, false, at, meta, expireAt = expireAt))
        storeInitialItems(id.toHex(), chat, identity.publicHex, at, its)
        if (!queueToChat(chat, Payload.Checklist(id, at, t, its, reply, ttl, forwarded))) failSend(id.toHex())
    }

    /** The items a list starts with become "add" operations (same ids on every phone, so they merge). */
    private fun storeInitialItems(listId: String, chat: String, author: String, at: Long, items: List<ListItem>) {
        items.forEachIndexed { i, it -> db.putListOp(listId + it.id.toHex(), listId, chat, Payload.OP_ADD, it.id.toHex(), it.text, author, at + i) }
    }

    /** Add (itemId = null), rename, check, uncheck or remove an item. Works offline; everyone converges. */
    fun listOp(listId: String, op: Int, itemId: String?, text: String = "") = scope.launch {
        val m = db.message(listId) ?: return@launch
        if (m.kind != MsgKind.LIST || m.deleted) return@launch
        if ((op == Payload.OP_ADD || op == Payload.OP_RENAME) && text.isBlank()) return@launch
        val item = itemId ?: Crypto.randomBytes(8).toHex()
        val opId = Crypto.randomBytes(16)
        val at = now()
        db.putListOp(opId.toHex(), listId, m.chat, op, item, text.trim().take(200), identity.publicHex, at)
        queueToChat(m.chat, Payload.ListOp(opId, at, listId.hexToBytes(), op, item.hexToBytes(), text.trim().take(200)))
    }

    private fun onListOp(chat: String, author: String, p: Payload.ListOp) {
        if (p.op !in Payload.OP_ADD..Payload.OP_REMOVE) return
        db.putListOp(p.msgId.toHex(), p.list.toHex(), chat, p.op, p.item.toHex(), p.text, author, p.sentAt.coerceAtMost(now()))
    }

    // ---- doodles

    fun sendDoodle(chat: String, width: Int, height: Int, background: Int, strokes: ByteArray, replyTo: MessageRow? = null,
                   forwarded: Boolean = false) = scope.launch {
        val id = Crypto.randomBytes(16); val at = now()
        val reply = replyTo?.let { replyFor(it) }
        val (ttl, expireAt) = expiryFor(chat, at)
        val meta = JSONObject().put("w", width).put("h", height).put("bg", background)
            .put("strokes", android.util.Base64.encodeToString(strokes, android.util.Base64.NO_WRAP))
        putReply(meta, reply); extrasMeta(meta, forwarded, emptyList())
        db.insertMessage(MessageRow(id.toHex(), chat, identity.publicHex, true, MsgKind.DOODLE, "Doodle", null, 0, MsgStatus.WAITING, false, false, at, meta, expireAt = expireAt))
        if (!queueToChat(chat, Payload.Doodle(id, at, width, height, background, strokes, reply, ttl, forwarded))) failSend(id.toHex())
    }

    // ---- forward

    /** Forward to up to 5 chats. Files are re-sealed with a fresh key for each chat. View-once can't be forwarded. */
    fun forward(msgId: String, chats: List<String>) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        if (!isContent(m) || m.viewOnce) return@launch
        for (chat in chats.distinct().take(5)) {
            try {
                when (m.kind) {
                    MsgKind.TEXT -> sendText(chat, m.body, false, null, null, forwarded = true).join()
                    MsgKind.LOCATION -> sendLocation(chat, m.meta.optDouble("lat"), m.meta.optDouble("lon"), m.meta.optDouble("acc", 0.0).toFloat(), false, null, forwarded = true).join()
                    MsgKind.POLL -> {
                        val o = m.meta.optJSONArray("options") ?: continue
                        sendPoll(chat, m.body, List(o.length()) { o.optString(it) }, m.meta.optBoolean("multi"), null, forwarded = true).join()
                    }
                    MsgKind.LIST -> {
                        val items = com.mura.mesh.core.Checklists.fold(db.listOpsForChat(m.chat)[m.id] ?: emptyList()).map { it.text }
                        sendChecklist(chat, m.body, items, null, forwarded = true).join()
                    }
                    MsgKind.DOODLE -> {
                        val st = android.util.Base64.decode(m.meta.optString("strokes", ""), android.util.Base64.NO_WRAP)
                        sendDoodle(chat, m.meta.optInt("w", 600), m.meta.optInt("h", 800), m.meta.optInt("bg"), st, null, forwarded = true).join()
                    }
                    MsgKind.FILE -> {
                        val f = m.fileId?.let { db.file(it) } ?: continue
                        val have = (f.outgoing || f.state == FileState.COMPLETE) && f.state != FileState.GONE && File(f.path).exists()
                        if (!have) continue
                        val p = com.mura.mesh.media.SecureMedia.reseal(ctx, f)
                        sendPrepared(chat, p, m.meta.optString("caption", ""), false, null, forwarded = true).join()
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "forward", e) }
        }
    }

    // ---- send later

    /** Scheduled messages wait on this phone (encrypted, like everything else) and go out at [at]. */
    fun scheduleText(chat: String, text: String, at: Long, replyTo: MessageRow? = null, mentions: List<Mention> = emptyList()) = scope.launch {
        val t = text.trim()
        if (t.isEmpty()) return@launch
        val id = Crypto.randomBytes(16).toHex()
        val meta = JSONObject()
        putReply(meta, replyTo?.let { replyFor(it) })
        extrasMeta(meta, false, mentions.filter { t.contains("@" + it.name) })
        db.insertMessage(MessageRow(id, chat, identity.publicHex, true, MsgKind.TEXT, t, null, 0, MsgStatus.SCHEDULED, false, false,
            at.coerceAtLeast(now()), meta, sendAt = at.coerceAtLeast(now())))
    }

    fun sendScheduledNow(msgId: String) = scope.launch { db.reschedule(msgId, now()); sendDueScheduled() }

    private fun sendDueScheduled() {
        for (m in db.dueScheduled(now())) {
            val t = now()
            val (ttl, expireAt) = expiryFor(m.chat, t)
            db.markScheduledSent(m.id, t, expireAt)
            val reply = m.meta.optString("r_id", "").takeIf { it.isNotEmpty() }?.let {
                Reply(it.hexToBytes(), runCatching { m.meta.optString("r_sender").hexToBytes() }.getOrNull()?.takeIf { s -> s.size == 32 } ?: Reply.NOBODY,
                    m.meta.optInt("r_kind", MsgKind.TEXT), m.meta.optString("r_text", ""))
            }
            val mentions = m.meta.optJSONArray("mentions")?.let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { o -> Mention(o.getString("pub").hexToBytes(), o.getString("name")) } }
            } ?: emptyList()
            if (!queueToChat(m.chat, Payload.Text(m.id.hexToBytes(), t, m.body, false, null, reply, ttl, false, mentions))) failSend(m.id)
        }
    }

    // ---- translation (on this phone only; translations are stored encrypted with the message and never sent)

    /** Messages whose automatic translation failed this run (so auto-translate doesn't retry in a loop). */
    val translateFailed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** A valid cached translation of [m] into [to], or null. Invalidated when the message is edited. */
    fun cachedTranslation(m: MessageRow, to: String = Translate.myLanguage(db)): Pair<String, String>? {
        val t = m.meta.optString("tr_text", "")
        if (t.isEmpty() || m.meta.optString("tr_to") != to || m.meta.optInt("tr_h") != m.body.hashCode()) return null
        return m.meta.optString("tr_from") to t
    }

    /** Translates a text message into my language. Returns (fromLang, text); same language → text unchanged, nothing saved. */
    suspend fun translateMessage(msgId: String, to: String = Translate.myLanguage(db)): Pair<String, String> {
        val m = db.message(msgId) ?: throw Translate.Unavailable("Message is gone")
        cachedTranslation(m, to)?.let { return it }
        val text = if (m.kind == MsgKind.TEXT) m.body else m.meta.optString("caption", m.body)
        try {
            val (from, out) = Translate.translate(ctx, db, text, to)
            if (from != to) {
                val fresh = db.message(msgId) ?: return from to out
                db.setMessageMeta(msgId, fresh.meta.put("tr_text", out.take(20_000)).put("tr_from", from).put("tr_to", to).put("tr_h", fresh.body.hashCode()))
            }
            return from to out
        } catch (e: Exception) { translateFailed.add(msgId); throw e }
    }

    /** The language the other person writes in: from earlier translations, or by detecting their latest message. */
    suspend fun chatLanguage(chat: String): String? {
        val theirs = db.messages(chat, 40).filter { !it.outgoing && it.kind == MsgKind.TEXT && !it.deleted }
        theirs.lastOrNull { it.meta.optString("tr_from").isNotEmpty() }?.let { return it.meta.optString("tr_from") }
        val sample = theirs.takeLast(5).joinToString(" ") { it.body }
        return Translate.detect(ctx, sample)
    }

    /** Translates a draft into the other person's language before sending. Returns null if it's already in their language. */
    suspend fun translateDraft(chat: String, draft: String): Pair<String, String>? {
        val to = chatLanguage(chat) ?: throw Translate.Unavailable("Not sure which language they write in yet.")
        val (from, out) = Translate.translate(ctx, db, draft, to)
        return if (from == to) null else to to out
    }

    /** A chat's display name (contact or group). */
    fun chatTitle(chat: String): String =
        if (ChatId.isGroup(chat)) db.group(ChatId.groupIdOf(chat))?.name ?: "Group" else db.contact(chat)?.display ?: "Contact"

    fun autoTranslate(chat: String): Boolean = db.getBool(Translate.autoKey(chat), false)
    fun setAutoTranslate(chat: String, on: Boolean) { db.setBool(Translate.autoKey(chat), on); translateFailed.clear() }

    // ---- voice note transcripts (made on the phone; never sent)

    fun saveTranscript(msgId: String, text: String) = scope.launch {
        val m = db.message(msgId) ?: return@launch
        db.setMessageMeta(msgId, m.meta.put("transcript", text.take(20_000)))
    }

    // ================================================================ contacts & profile

    fun myAvatar(): ByteArray? = db.getSecret(Settings.AVATAR)

    private fun statusKey(): Pair<ByteArray, Int> {
        db.getSecret(Settings.STATUS_KEY)?.let { return it to (db.getSetting(Settings.STATUS_EPOCH, "1").toIntOrNull() ?: 1) }
        val k = Crypto.randomBytes(32)
        db.setSecret(Settings.STATUS_KEY, k)
        db.setSetting(Settings.STATUS_EPOCH, "1")
        db.setSetting(Settings.STATUS_KEY_AT, now().toString())
        return k to 1
    }

    /**
     * My profile, with my current signed prekey. A "light" profile leaves the photo out
     * (a single 0 byte means "unchanged"), for weekly key rotation.
     */
    private fun profilePayload(light: Boolean = false): Payload.Profile {
        val (k, e) = statusKey()
        val avatar = if (light) ByteArray(1) else myAvatar() ?: ByteArray(0)
        return Payload.Profile(Crypto.randomBytes(16), now(), identity.name, identity.edPub, avatar, k, e, sessions.currentPrekey(),
            online.onionAddress ?: "")
    }

    fun addContact(pubHex: String, name: String, verified: Boolean, edPub: ByteArray? = null, onion: String? = null) = scope.launch {
        if (pubHex == identity.publicHex) return@launch
        val wasContact = db.contact(pubHex)?.state == ContactState.CONTACT
        db.upsertContact(pubHex, Crypto.nodeId(pubHex.hexToBytes()).toHex(), name, ContactState.CONTACT, now(), verified)
        if (edPub != null && edPub.size == 32) db.setContactEdPub(pubHex, edPub)
        if (onion != null && Onion.valid(onion)) db.setContactOnion(pubHex, onion)
        queueDirect(pubHex, profilePayload(), RECEIPT_TTL_MS)
        if (!wasContact) defaultTimer()?.let { if (db.chatTimer(pubHex) == 0) setTimer(pubHex, it) }
        for (f in db.filesWhere("chat=? AND outgoing=0 AND state=${FileState.NEEDS_APPROVAL}", arrayOf(pubHex))) {
            if (f.size <= autoDownloadLimit()) db.setFileState(f.fileId, FileState.ACTIVE)
        }
        refreshSubscriptions()
        router.kick(reofferAll = true)
    }

    /** Add a friend by their Mura ID over Tor (no QR, no server). Returns their key. */
    suspend fun addById(raw: String): String {
        val onion = com.mura.mesh.core.MuraId.parse(raw) ?: throw java.io.IOException("That isn't a valid Mura ID — check for typos.")
        val (pub, name) = online.introduce(onion)
        if (db.contact(pub)?.state == ContactState.BLOCKED) throw java.io.IOException("You blocked this person. Unblock them first.")
        addContact(pub, db.contact(pub)?.display?.takeIf { db.contact(pub)?.state == ContactState.CONTACT } ?: name.ifBlank { "Friend" },
            verified = false, onion = onion).join()
        return pub
    }

    fun block(pubHex: String) = scope.launch {
        db.setContactState(pubHex, ContactState.BLOCKED)
        rotateStatusKey()
        refreshSubscriptions()
    }

    /**
     * Remove contact: erases them and everything about them from this phone — chat, messages, photos, files,
     * call history, recordings, statuses, encryption sessions and undelivered messages. Shared groups stay.
     * My status key is replaced, so they can't open my future statuses. If they write again, they show up
     * as a new contact request.
     */
    fun removeContact(pubHex: String) = scope.launch {
        val c = db.contact(pubHex) ?: return@launch
        if (calls.state.value.peerHex == pubHex) calls.hangup()
        for (f in db.filesWhere("chat=? OR chat=?", arrayOf(pubHex, "rec:$pubHex"))) {
            File(f.path).delete()
            outgoing.remove(f.fileId); incoming.remove(f.fileId); handedPieces.remove(f.fileId)
        }
        db.removeContactData(pubHex, c.node)
        _presence.value = _presence.value - pubHex
        notifier.cancelChat(pubHex)
        router.linkFor(pubHex.hexToBytes())?.close("contact removed")
        rotateStatusKey()
        refreshSubscriptions()
    }

    fun unblock(pubHex: String) = scope.launch {
        db.setContactState(pubHex, ContactState.CONTACT)
        queueDirect(pubHex, profilePayload(), RECEIPT_TTL_MS)
        refreshSubscriptions()
    }

    fun rename(pubHex: String, name: String) = scope.launch { db.renameContact(pubHex, name) }

    /** Name or photo changed: tell every contact. */
    fun updateProfile(name: String?, avatar: ByteArray?, removeAvatar: Boolean = false) = scope.launch {
        if (name != null && name.isNotBlank()) {
            com.mura.mesh.identity.IdentityStore.rename(ctx, name)
            identity.name = name.trim()
        }
        if (removeAvatar) db.setSecret(Settings.AVATAR, null) else if (avatar != null) db.setSecret(Settings.AVATAR, avatar)
        broadcastProfile()
    }

    private fun broadcastProfile(light: Boolean = false) {
        val p = profilePayload(light)
        for (c in db.contacts()) if (c.state == ContactState.CONTACT) {
            queueDirect(c.pub, Payload.Profile(Crypto.randomBytes(16), p.sentAt, p.name, p.edPub, p.avatar, p.statusKey, p.statusEpoch, p.prekey, p.onion), RECEIPT_TTL_MS)
        }
    }

    /** After a restore: open fresh sessions with every contact (they switch to them on receipt). */
    fun resyncSessions() = scope.launch { broadcastProfile(light = true) }

    /** After blocking someone, and at least daily, new status updates use a fresh key (forward secrecy for statuses). */
    private fun rotateStatusKey() {
        val e = (db.getSetting(Settings.STATUS_EPOCH, "1").toIntOrNull() ?: 1) + 1
        db.setSecret(Settings.STATUS_KEY, Crypto.randomBytes(32))
        db.setSetting(Settings.STATUS_EPOCH, e.toString())
        db.setSetting(Settings.STATUS_KEY_AT, now().toString())
        broadcastProfile(light = true)
    }

    // ================================================================ status

    fun postStatus(kind: Int, text: String, background: Int, image: ByteArray) = scope.launch {
        val keyAge = now() - (db.getSetting(Settings.STATUS_KEY_AT, "0").toLongOrNull() ?: 0L)
        if (db.getSecret(Settings.STATUS_KEY) != null && keyAge > STATUS_TTL_MS) rotateStatusKey()
        val (key, epoch) = statusKey()
        val id = Crypto.randomBytes(16)
        val expires = now() + STATUS_TTL_MS
        val allowCapture = db.getBool(Settings.STATUS_CAPTURE, false)
        val p = Payload.Status(id, now(), kind, text.take(700), background, image, expires, allowCapture)
        val node = GroupCrypto.statusNode(identity.publicKey)
        val wire = GroupCrypto.seal(key, epoch, id, node, identity.publicKey, identity.edSeed, p.pack())
        db.markSeen(id)
        db.put(Bundle(id, Bundle.TYPE_MSG, node, Bundle.ZERO16, 0, expires, MeshRouter.MAX_HOPS, wire), mine = true)
        db.insertStatus(StatusRow(id.toHex(), identity.publicHex, kind, text, background, image.takeIf { it.isNotEmpty() }, now(), expires, true, allowCapture))
        changedMesh()
    }

    fun deleteStatus(id: String) = scope.launch { db.deleteStatus(id); db.delete(id.hexToBytes()) }

    // ================================================================ groups (admin actions)

    private fun memberFor(pub: String, admin: Boolean): MemberRow? {
        if (pub == identity.publicHex) return MemberRow("", pub, identity.edPub, identity.name, admin)
        val c = db.contact(pub) ?: return null
        val ed = c.edpub ?: return null
        return MemberRow("", pub, ed, c.name.ifBlank { c.pname }, admin)
    }

    /** Contacts that can be added to a group (we need their signing key, learned when you exchanged profiles). */
    fun groupEligible(): List<ContactRow> = db.contactsByState(ContactState.CONTACT).filter { it.edpub != null }

    fun createGroup(name: String, memberPubs: List<String>, onDone: (String) -> Unit) = scope.launch {
        val gid = Crypto.randomBytes(16).toHex()
        val epoch = Crypto.random.nextInt()
        val key = Crypto.randomBytes(32)
        val members = listOfNotNull(memberFor(identity.publicHex, true)) +
            memberPubs.distinct().take(Payload.MAX_GROUP - 1).mapNotNull { memberFor(it, false) }
        db.upsertGroup(GroupRow(gid, name.trim().take(60), epoch, 1, false, false, now(), identity.publicHex, null),
            GroupCrypto.groupNode(gid.hexToBytes()).toHex())
        db.putGroupKey(gid, epoch, key)
        db.setMembers(gid, members.map { it.copy(gid = gid) })
        refreshSubscriptions()
        system(ChatId.group(gid), "You created the group “${name.trim()}”")
        for (m in members) if (m.pub != identity.publicHex) sendGroupKey(gid, m.pub, epoch, key, 1, name, false)
        postState(gid)
        defaultTimer()?.let { setTimer(ChatId.group(gid), it) }
        onDone(gid)
    }

    private fun sendGroupKey(gid: String, to: String, epoch: Int, key: ByteArray, version: Int, name: String, removed: Boolean) {
        queueDirect(to, Payload.GroupKey(Crypto.randomBytes(16), now(), gid.hexToBytes(), epoch, if (removed) ByteArray(32) else key,
            version, name, removed, identity.edPub))
    }

    private fun postState(gid: String) {
        val g = db.group(gid) ?: return
        val members = db.members(gid).map { GroupMember(it.pub.hexToBytes(), it.edpub, it.name, it.admin) }
        queueGroup(gid, Payload.GroupState(Crypto.randomBytes(16), now(), gid.hexToBytes(), g.version, g.epoch, g.name, g.dissolved, members))
    }

    fun isAdmin(gid: String) = db.member(gid, identity.publicHex)?.admin == true

    fun addMembers(gid: String, pubs: List<String>) = scope.launch {
        val g = db.group(gid) ?: return@launch
        if (!isAdmin(gid) || g.dissolved || g.removed) return@launch
        val current = db.members(gid)
        val room = Payload.MAX_GROUP - current.size
        val added = pubs.filter { p -> current.none { it.pub == p } }.take(room.coerceAtLeast(0)).mapNotNull { memberFor(it, false)?.copy(gid = gid) }
        if (added.isEmpty()) return@launch
        db.setMembers(gid, current + added)
        val v = g.version + 1
        db.upsertGroup(g.copy(version = v), GroupCrypto.groupNode(gid.hexToBytes()).toHex())
        val key = db.groupKey(gid, g.epoch) ?: return@launch
        for (m in added) sendGroupKey(gid, m.pub, g.epoch, key, v, g.name, false)
        system(g.chat, "You added ${added.joinToString { it.name.ifBlank { "a member" } }}")
        postState(gid)
    }

    /** Remove someone and switch to a fresh key they never receive. */
    fun removeMember(gid: String, pub: String, announce: Boolean = true) = scope.launch { removeMemberNow(gid, pub, announce) }

    private fun removeMemberNow(gid: String, pub: String, announce: Boolean) {
        val g = db.group(gid) ?: return
        if (g.dissolved || g.removed) return
        val current = db.members(gid)
        val leaving = current.firstOrNull { it.pub == pub }
        var remaining = current.filter { it.pub != pub }
        if (remaining.none { it.admin }) remaining = remaining.map { if (it.pub == identity.publicHex) it.copy(admin = true) else it }
        val newEpoch = Crypto.random.nextInt()
        val newKey = Crypto.randomBytes(32)
        val v = g.version + 1
        db.putGroupKey(gid, newEpoch, newKey)
        db.setMembers(gid, remaining)
        db.upsertGroup(g.copy(epoch = newEpoch, version = v), GroupCrypto.groupNode(gid.hexToBytes()).toHex())
        for (m in remaining) if (m.pub != identity.publicHex) sendGroupKey(gid, m.pub, newEpoch, newKey, v, g.name, false)
        if (announce) sendGroupKey(gid, pub, 0, ByteArray(32), v, g.name, true)
        if (announce) system(g.chat, "You removed ${leaving?.name?.ifBlank { null } ?: "a member"}")
        postState(gid)
    }

    fun setAdmin(gid: String, pub: String, admin: Boolean) = scope.launch {
        val g = db.group(gid) ?: return@launch
        if (!isAdmin(gid)) return@launch
        db.setMembers(gid, db.members(gid).map { if (it.pub == pub) it.copy(admin = admin) else it })
        db.upsertGroup(g.copy(version = g.version + 1), GroupCrypto.groupNode(gid.hexToBytes()).toHex())
        postState(gid)
    }

    fun renameGroup(gid: String, name: String) = scope.launch {
        val g = db.group(gid) ?: return@launch
        if (!isAdmin(gid) || name.isBlank()) return@launch
        db.upsertGroup(g.copy(name = name.trim().take(60), version = g.version + 1), GroupCrypto.groupNode(gid.hexToBytes()).toHex())
        system(g.chat, "You renamed the group to “${name.trim()}”")
        postState(gid)
    }

    fun leaveGroup(gid: String) = scope.launch {
        val g = db.group(gid) ?: return@launch
        queueGroup(gid, Payload.GroupLeft(Crypto.randomBytes(16), now(), gid.hexToBytes()))
        db.upsertGroup(g.copy(removed = true), GroupCrypto.groupNode(gid.hexToBytes()).toHex())
        db.deleteGroupKeys(gid)
        system(g.chat, "You left the group")
        refreshSubscriptions()
    }

    /** Admin: close the group for everyone. */
    fun dissolveGroup(gid: String) = scope.launch {
        val g = db.group(gid) ?: return@launch
        if (!isAdmin(gid)) return@launch
        db.upsertGroup(g.copy(dissolved = true, version = g.version + 1), GroupCrypto.groupNode(gid.hexToBytes()).toHex())
        postState(gid)
        system(g.chat, "You deleted this group for everyone")
        refreshSubscriptions()
    }

    fun deleteGroupLocally(gid: String) = scope.launch {
        for (m in db.messages(ChatId.group(gid), 100_000)) m.fileId?.let { fid -> db.file(fid)?.let { File(it.path).delete() } }
        db.deleteGroup(gid)
        refreshSubscriptions()
    }

    // ================================================================ receiving: direct messages

    override fun onMessageBundle(bundle: Bundle) {
        val aad = Bundle.aad(bundle.id, bundle.dest)
        val (senderPub, inner) = try {
            Envelope.open(identity.privateKey, identity.publicKey, aad, bundle.payload)
        } catch (e: Exception) { log("undecryptable bundle dropped"); return }
        val peer = senderPub.toHex()
        if (peer == identity.publicHex) return
        var contact = db.contact(peer)
        if (contact?.state == ContactState.BLOCKED) return
        val plain = try { sessions.decrypt(peer, inner, aad) } catch (e: Exception) { onUndecryptable(peer, contact); return }
        val p = try { Payload.unpack(plain) } catch (e: Exception) { return }
        if (p is Payload.SenderKey) { onSenderKeyPayload(peer, contact, p); return }
        val known = contact?.state == ContactState.CONTACT
        val isContent = p is Payload.Text || p is Payload.File || p is Payload.Location || p is Payload.GroupKey ||
            p is Payload.Poll || p is Payload.Checklist || p is Payload.Doodle
        if (isContent && !known) {
            if (db.getBool(Settings.BLOCK_UNKNOWN_MESSAGES, false)) return
            if (p is Payload.GroupKey && db.getBool(Settings.BLOCK_UNKNOWN_GROUPS, true) && db.group(p.groupId.toHex()) == null) return
            if (contact == null) {
                if (tooManyRequests()) { log("request dropped: too many pending"); return }
                db.upsertContact(peer, Crypto.nodeId(senderPub).toHex(), null, ContactState.REQUEST, now())
                contact = db.contact(peer)
            }
        }
        // Someone scanned my QR code and added me (I haven't added them): keep their profile — name, prekey and
        // onion address — as a contact request, so I can add them back and reply even over the internet.
        if (p is Payload.Profile && contact == null) {
            if (db.getBool(Settings.BLOCK_UNKNOWN_MESSAGES, false)) return
            if (tooManyRequests()) { log("request dropped: too many pending"); return }
            db.upsertContact(peer, Crypto.nodeId(senderPub).toHex(), null, ContactState.REQUEST, now())
            contact = db.contact(peer)
            system(peer, "${p.name.take(48).ifBlank { "Someone" }} added you by scanning your code. Add them back to chat.")
        }
        if (!isContent && contact == null) return
        db.touchSeen(peer, now())
        val name = contact?.display ?: "Unknown"
        when (p) {
            is Payload.Text, is Payload.Location, is Payload.File, is Payload.Poll, is Payload.Checklist, is Payload.Doodle -> {
                storeIncoming(peer, peer, name, p, known)
                if (p !is Payload.File) queueDirect(peer, Payload.Receipt(Crypto.randomBytes(16), now(), listOf(p.msgId)), RECEIPT_TTL_MS)
            }
            is Payload.Reaction -> onReaction(peer, peer, name, p)
            is Payload.Edit -> onEdit(peer, peer, p)
            is Payload.Vote -> onVote(peer, peer, p)
            is Payload.Pin -> onPin(peer, name, p, allowed = true)
            is Payload.ListOp -> onListOp(peer, peer, p)
            is Payload.Receipt -> for (id in p.delivered) onDelivered(peer, id.toHex())
            is Payload.Read -> if (readReceipts) for (id in p.read) {
                val m = db.message(id.toHex())
                if (m != null && m.outgoing && m.chat == peer) db.raiseStatus(m.id, MsgStatus.READ)
            }
            is Payload.Screenshot -> {
                system(peer, "$name took a screenshot")
                notifier.alert(peer, name, "took a screenshot of your chat")
            }
            is Payload.Viewed -> {
                val m = db.message(p.target.toHex())
                if (m != null && m.outgoing && m.chat == peer) {
                    if (m.viewOnce && !m.opened) recentlyOpened[m.id] = now()
                    db.markOpened(m.id); m.fileId?.let { wipeFile(it) }
                }
            }
            is Payload.Profile -> onProfile(contact!!, p)
            is Payload.Delete -> for (t in p.targets) {
                val m = db.message(t.toHex()) ?: continue
                if (m.chat == peer && m.sender == peer) { m.fileId?.let { wipeFile(it) }; db.tombstoneMessage(m.id) }
            }
            is Payload.GroupKey -> onGroupKey(peer, name, p)
            is Payload.Timer -> if (known) applyTimer(peer, name, p.ttl)
            else -> {}
        }
    }

    /**
     * A contact's message we can't open: their session state and ours no longer match (for example
     * after restoring a backup). Start a fresh session with them, at most once an hour.
     */
    private fun onUndecryptable(peer: String, contact: ContactRow?) {
        if (contact?.state != ContactState.CONTACT) return
        val t = now()
        if (t - (lastReset[peer] ?: 0L) < 3600_000) return
        lastReset[peer] = t
        log("session reset with ${peer.take(8)}")
        queueDirect(peer, profilePayload(light = true), RECEIPT_TTL_MS, freshSession = true)
    }

    private fun onSenderKeyPayload(peer: String, contact: ContactRow?, p: Payload.SenderKey) {
        val gid = p.groupId.toHex()
        val allowed = contact?.state == ContactState.CONTACT || db.member(gid, peer) != null || db.group(gid) == null
        if (!allowed) return
        sessions.onSenderKey(peer, p)
        reprocess(GroupCrypto.groupNode(p.groupId).toHex())
    }

    private fun onProfile(c: ContactRow, p: Payload.Profile) {
        // The signing key is trusted on first use (or from the QR code) and never silently replaced.
        val ed = if (c.edpub == null) p.edPub else c.edpub
        if (c.edpub != null && !c.edpub.contentEquals(p.edPub)) log("profile signing key mismatch ignored for ${c.pub.take(8)}")
        val avatar = if (p.avatar.size == 1) c.avatar else p.avatar.takeIf { it.isNotEmpty() && it.size <= 128 * 1024 }
        db.setContactProfile(c.pub, p.name.take(48), ed, avatar, p.statusKey.takeIf { c.state == ContactState.CONTACT }, p.statusEpoch)
        // Their signed prekey: only if it's signed by the key we trust, and newer than the one we have.
        val spk = p.prekey
        if (spk != null && spk.verify(ed) && spk.id > (db.contactPrekey(c.pub)?.first ?: 0)) db.setContactPrekey(c.pub, spk.id, spk.pub)
        // Their onion address (internet links). Arrives only inside their sealed, ratcheted profile.
        if (c.state != ContactState.BLOCKED && Onion.valid(p.onion) && p.onion != c.onion) { db.setContactOnion(c.pub, p.onion); online.poke() }
        refreshSubscriptions()
        // Statuses that arrived before we had the key.
        reprocess(GroupCrypto.statusNode(c.pub.hexToBytes()).toHex())
    }

    /** Shared by 1:1 and group chats. */
    private fun storeIncoming(chat: String, sender: String, senderName: String, p: Payload, known: Boolean) {
        val isGroup = ChatId.isGroup(chat)
        val at = p.sentAt.coerceAtMost(now())
        val fresh: Boolean
        val preview: String
        var mentionedMe = false
        // Disappearing messages count from when they arrive here.
        fun expiry(ttl: Int) = if (ttl > 0) now() + ttl * 1000L else 0L
        when (p) {
            is Payload.Text -> {
                val meta = JSONObject()
                p.preview?.let { pv ->
                    meta.put("p_url", pv.url).put("p_title", pv.title).put("p_desc", pv.description)
                        .put("p_img", android.util.Base64.encodeToString(pv.image, android.util.Base64.NO_WRAP))
                }
                putReply(meta, p.reply)
                extrasMeta(meta, p.forwarded, p.mentions)
                mentionedMe = p.mentions.any { it.pub.contentEquals(identity.publicKey) }
                fresh = db.insertMessage(MessageRow(p.msgId.toHex(), chat, sender, false, MsgKind.TEXT, p.text, null, 0,
                    MsgStatus.DELIVERED, p.viewOnce, false, at, meta, expireAt = expiry(p.ttl)))
                preview = if (p.viewOnce) "View-once message" else p.text
            }
            is Payload.Poll -> {
                val meta = JSONObject().put("options", JSONArray(p.options)).put("multi", p.multi)
                putReply(meta, p.reply); extrasMeta(meta, p.forwarded, emptyList())
                fresh = db.insertMessage(MessageRow(p.msgId.toHex(), chat, sender, false, MsgKind.POLL, p.question, null, 0,
                    MsgStatus.DELIVERED, false, false, at, meta, expireAt = expiry(p.ttl)))
                preview = "Poll: ${p.question}"
            }
            is Payload.Checklist -> {
                val meta = JSONObject()
                putReply(meta, p.reply); extrasMeta(meta, p.forwarded, emptyList())
                fresh = db.insertMessage(MessageRow(p.msgId.toHex(), chat, sender, false, MsgKind.LIST, p.title, null, 0,
                    MsgStatus.DELIVERED, false, false, at, meta, expireAt = expiry(p.ttl)))
                if (fresh) storeInitialItems(p.msgId.toHex(), chat, sender, at, p.items)
                preview = "List: ${p.title}"
            }
            is Payload.Doodle -> {
                if (runCatching { DoodleCodec.decode(p.strokes) }.isFailure) return
                val meta = JSONObject().put("w", p.width).put("h", p.height).put("bg", p.background)
                    .put("strokes", android.util.Base64.encodeToString(p.strokes, android.util.Base64.NO_WRAP))
                putReply(meta, p.reply); extrasMeta(meta, p.forwarded, emptyList())
                fresh = db.insertMessage(MessageRow(p.msgId.toHex(), chat, sender, false, MsgKind.DOODLE, "Doodle", null, 0,
                    MsgStatus.DELIVERED, false, false, at, meta, expireAt = expiry(p.ttl)))
                preview = "Sent a doodle"
            }
            is Payload.Location -> {
                val meta = JSONObject().put("lat", p.lat).put("lon", p.lon).put("acc", p.accuracy.toDouble())
                putReply(meta, p.reply)
                extrasMeta(meta, p.forwarded, emptyList())
                fresh = db.insertMessage(MessageRow(p.msgId.toHex(), chat, sender, false, MsgKind.LOCATION, "Location", null, 0,
                    MsgStatus.DELIVERED, p.viewOnce, false, at, meta, expireAt = expiry(p.ttl)))
                preview = "Shared a location"
            }
            is Payload.File -> {
                fresh = onFileManifest(chat, sender, known, p, expiry(p.ttl))
                preview = (if (p.viewOnce) "View-once " else "") + previewLabel(p.media, p.name, p.durationMs)
            }
            else -> return
        }
        if (!fresh) return
        // An edit that overtook its message on the way.
        db.takePendingEdit(p.msgId.toHex())?.let { e -> db.message(p.msgId.toHex())?.let { m -> applyEdit(m, e[0], e[1], e[2], e[3].toLongOrNull() ?: 0L) } }
        if (openChat == chat) markChatRead(chat) else {
            db.incUnread(chat)
            // @mentions get through even when the group is muted.
            if (!db.isMuted(chat) || mentionedMe) {
                val title = if (isGroup) (db.group(ChatId.groupIdOf(chat))?.name ?: "Group") else if (known) senderName else "$senderName (request)"
                notifier.message(chat, title, if (isGroup) senderName else null, if (mentionedMe) "mentioned you: $preview" else preview,
                    locked = db.isLocked(chat), canReply = known || isGroup)
            }
        }
    }

    private fun onFileManifest(chat: String, sender: String, known: Boolean, p: Payload.File, expireAt: Long = 0): Boolean {
        val fid = p.fileId.toHex()
        if (db.file(fid) != null) {
            if (!ChatId.isGroup(chat) && db.file(fid)?.state == FileState.COMPLETE)
                queueDirect(chat, Payload.Receipt(Crypto.randomBytes(16), now(), listOf(p.msgId)), RECEIPT_TTL_MS)
            return false
        }
        if (p.chunkSize <= 0 || p.chunkSize > 256 * 1024 || p.size <= 0 || p.chunkCount != Chunks.count(p.size, p.chunkSize)) return false
        val needsApproval = (!known && db.getBool(Settings.BLOCK_UNKNOWN_FILES, true)) || p.size > autoDownloadLimit()
        val path = File(com.mura.mesh.media.SecureMedia.mediaDir(ctx), "s_$fid").absolutePath
        db.insertFile(FileRow(fid, p.msgId.toHex(), chat, false, p.name.take(200), p.mime.take(100), p.media, p.size, p.chunkSize,
            p.chunkCount, p.sha256, p.fileKey, p.thumb.takeIf { it.isNotEmpty() }, null, 0, path,
            if (needsApproval) FileState.NEEDS_APPROVAL else FileState.ACTIVE, p.durationMs, p.width, p.height, now() + MESSAGE_TTL_MS,
            p.compressed, p.originalSize, destFor(chat)))
        val meta = JSONObject().put("caption", p.caption).put("name", p.name).put("size", p.originalSize).put("mime", p.mime)
            .put("duration", p.durationMs).put("w", p.width).put("h", p.height).put("sent_size", p.size)
        putReply(meta, p.reply)
        extrasMeta(meta, p.forwarded, emptyList())
        val fresh = db.insertMessage(MessageRow(p.msgId.toHex(), chat, sender, false, MsgKind.FILE, previewLabel(p.media, p.name, p.durationMs), fid,
            p.media, MsgStatus.DELIVERED, p.viewOnce, false, p.sentAt.coerceAtMost(now()), meta, expireAt = expireAt))
        scope.launch { router.kick(reofferAll = true) }
        return fresh
    }

    private fun onDelivered(fromPeer: String, msgIdHex: String) {
        val m = db.message(msgIdHex) ?: return
        if (!m.outgoing || m.chat != fromPeer) return
        db.raiseStatus(msgIdHex, MsgStatus.DELIVERED)
        db.delete(msgIdHex.hexToBytes())
        m.fileId?.let { fid ->
            outgoing.remove(fid); handedPieces.remove(fid)
            _sendProgress.value = _sendProgress.value - fid
            db.file(fid)?.let { f ->
                db.setFileState(fid, FileState.DELIVERED)
                if (m.viewOnce) File(f.path).delete()
            }
            db.addTombstone(fid.hexToBytes(), now())
            router.sendTombstones(listOf(fid.hexToBytes()))
        }
    }

    // ================================================================ receiving: groups and statuses

    override fun isSubscribed(dest: ByteArray): Boolean {
        val h = dest.toHex()
        return groupNodes.containsKey(h) || statusNodes.containsKey(h) || h == myStatusNode
    }

    override fun onSubscribedBundle(bundle: Bundle) {
        val node = bundle.dest.toHex()
        if (db.isProcessed(bundle.id.toHex())) return
        if (groupNodes.containsKey(node)) processGroupBundle(groupNodes.getValue(node), bundle)
        else statusNodes[node]?.let { processStatusBundle(it, bundle) }
    }

    /** Read stored group/status messages that arrived before we had the key. */
    private fun reprocess(nodeHex: String) {
        for (b in db.bundlesForDest(nodeHex)) if (!db.isProcessed(b.id.toHex())) onSubscribedBundle(b)
    }

    private fun processStatusBundle(owner: String, b: Bundle) {
        val c = db.contact(owner) ?: return
        val key = c.statusKey ?: return
        if (GroupCrypto.epochOf(b.payload) != c.statusEpoch) return
        val (sender, packed) = try {
            GroupCrypto.open(key, b.id, b.dest, b.payload) { x -> if (x.toHex() == owner) c.edpub else null }
        } catch (e: Exception) { return }
        val p = (try { Payload.unpack(packed) } catch (e: Exception) { null }) as? Payload.Status ?: return
        db.markProcessed(b.id.toHex())
        if (p.expiresAt <= now() || sender.toHex() != owner) return
        db.insertStatus(StatusRow(p.msgId.toHex(), owner, p.kind, p.text, p.background, p.image.takeIf { it.isNotEmpty() },
            p.sentAt.coerceAtMost(now()), minOf(p.expiresAt, p.sentAt + STATUS_TTL_MS), false, p.allowCapture))
    }

    private fun processGroupBundle(gid: String, b: Bundle) {
        val g = db.group(gid) ?: return
        val epoch = try { GroupCrypto.epochOf(b.payload) } catch (e: Exception) { return }
        val key = db.groupKey(gid, epoch) ?: return   // stays stored until the key arrives
        val members = db.members(gid).associateBy { it.pub }
        val (sender, body) = try {
            GroupCrypto.open(key, b.id, b.dest, b.payload) { x -> members[x.toHex()]?.edpub }
        } catch (e: Exception) { return }
        val s = sender.toHex()
        if (s == identity.publicHex) { db.markProcessed(b.id.toHex()); return }
        // Inner layer: the sender's own hash chain. If their chain hasn't reached us yet, keep the message for later.
        val packed = try { sessions.groupDecrypt(gid, s, body, b.id + b.dest) } catch (e: Exception) { db.markProcessed(b.id.toHex()); return }
        if (packed == null) { if (!db.has(b.id)) db.put(b, mine = false); return }
        val p = try { Payload.unpack(packed) } catch (e: Exception) { db.markProcessed(b.id.toHex()); return }
        db.markProcessed(b.id.toHex())
        val senderName = db.memberName(gid, s)
        when (p) {
            is Payload.GroupState -> onGroupState(g, s, members, p)
            is Payload.GroupLeft -> onGroupLeft(g, s, senderName)
            is Payload.Text, is Payload.File, is Payload.Location, is Payload.Poll, is Payload.Checklist, is Payload.Doodle -> {
                if (g.removed) return
                if (db.contact(s)?.state == ContactState.BLOCKED) return
                storeIncoming(g.chat, s, senderName, p, true)
            }
            is Payload.Reaction -> if (!g.removed) onReaction(g.chat, s, senderName, p)
            is Payload.Edit -> if (!g.removed) onEdit(g.chat, s, p)
            is Payload.Vote -> if (!g.removed) onVote(g.chat, s, p)
            is Payload.Pin -> if (!g.removed) onPin(g.chat, senderName, p, allowed = members[s]?.admin == true)
            is Payload.ListOp -> if (!g.removed) onListOp(g.chat, s, p)
            is Payload.Delete -> for (t in p.targets) {
                val m = db.message(t.toHex()) ?: continue
                if (m.chat == g.chat && (m.sender == s || members[s]?.admin == true)) { m.fileId?.let { wipeFile(it) }; db.tombstoneMessage(m.id) }
            }
            is Payload.Screenshot -> system(g.chat, "$senderName took a screenshot")
            is Payload.Timer -> if (!g.removed) applyTimer(g.chat, senderName, p.ttl)
            else -> {}
        }
    }

    private fun onGroupKey(from: String, fromName: String, p: Payload.GroupKey) {
        val gid = p.groupId.toHex()
        val node = GroupCrypto.groupNode(p.groupId).toHex()
        var g = db.group(gid)
        if (p.removed) {
            if (g != null && canManage(gid, from)) {
                db.upsertGroup(g.copy(removed = true), node)
                db.deleteGroupKeys(gid)
                system(g.chat, "$fromName removed you")
                refreshSubscriptions()
            }
            return
        }
        if (g == null) {
            g = GroupRow(gid, p.name.take(60), p.epoch, 0, false, false, now(), from, null)
            db.upsertGroup(g, node)
            // Until the signed member list arrives, trust only the admin who invited us.
            val inviterName = db.contact(from)?.display ?: fromName
            db.setMembers(gid, listOf(MemberRow(gid, from, p.adminEdPub, inviterName, true)))
            system(g.chat, "$inviterName added you to “${p.name}”")
            notifier.message(g.chat, p.name, null, "$inviterName added you to the group", locked = false, canReply = false)
        } else {
            // Keys only count from someone the group already recognises as an admin (or the successor if all admins left).
            if (!canManage(gid, from)) return
            if (g.removed && p.version > g.version) g = g.copy(removed = false)
        }
        db.putGroupKey(gid, p.epoch, p.key)
        if (p.version >= g.version) db.upsertGroup(g.copy(epoch = p.epoch, name = if (g.version == 0) p.name else g.name), node)
        refreshSubscriptions()
        reprocess(node)
    }

    private fun onGroupState(g: GroupRow, signer: String, members: Map<String, MemberRow>, p: Payload.GroupState) {
        // Accept changes from an admin — or, if every admin has left, from the member with the smallest id (the designated successor).
        val noAdmins = members.values.none { it.admin }
        val successor = members.keys.minOrNull()
        if (members[signer]?.admin != true && !(noAdmins && signer == successor)) return
        if (p.version <= g.version && g.version != 0) return
        val newMembers = p.members.take(Payload.MAX_GROUP).map { MemberRow(g.id, it.xpub.toHex(), it.edpub, it.name, it.admin) }
        val oldPubs = members.keys
        val newPubs = newMembers.map { it.pub }.toSet()
        val node = GroupCrypto.groupNode(g.id.hexToBytes()).toHex()
        val iAmIn = identity.publicHex in newPubs
        db.setMembers(g.id, newMembers)
        db.upsertGroup(g.copy(name = p.name.take(60), version = p.version, epoch = if (db.groupKey(g.id, p.epoch) != null) p.epoch else g.epoch,
            dissolved = p.dissolved, removed = !iAmIn), node)
        val by = db.memberName(g.id, signer)
        if (g.version != 0) {
            val added = newMembers.filter { it.pub !in oldPubs && it.pub != identity.publicHex }
            val gone = oldPubs.filter { it !in newPubs }
            if (added.isNotEmpty()) system(g.chat, "$by added ${added.joinToString { it.name.ifBlank { "a member" } }}")
            if (gone.isNotEmpty()) system(g.chat, "${gone.size} member${if (gone.size == 1) "" else "s"} left or removed")
            if (p.name != g.name) system(g.chat, "$by renamed the group to “${p.name}”")
        }
        if (p.dissolved && !g.dissolved) system(g.chat, "$by deleted this group")
        if (!iAmIn && !g.removed) system(g.chat, "You're no longer in this group")
        refreshSubscriptions()
    }

    private fun canManage(gid: String, pub: String): Boolean {
        val members = db.members(gid)
        if (members.any { it.pub == pub && it.admin }) return true
        return members.none { it.admin } && members.minByOrNull { it.pub }?.pub == pub
    }

    private fun onGroupLeft(g: GroupRow, leaver: String, name: String) {
        val members = db.members(g.id)
        if (members.none { it.pub == leaver }) return
        system(g.chat, "$name left")
        val remaining = members.filter { it.pub != leaver }
        db.setMembers(g.id, remaining)
        // Exactly one remaining member rotates the key: the admin with the smallest id, or, if no admin is left, the member with the smallest id.
        val admins = remaining.filter { it.admin }
        val designated = (if (admins.isNotEmpty()) admins else remaining).minByOrNull { it.pub }?.pub
        if (designated == identity.publicHex) removeMemberNow(g.id, leaver, announce = false)
    }

    // ================================================================ files

    private fun incomingFor(fileIdHex: String): IncomingFile? {
        incoming[fileIdHex]?.let { return it }
        val f = db.file(fileIdHex) ?: return null
        if (f.outgoing || f.state != FileState.ACTIVE) return null
        val inf = IncomingFile(f.fileId.hexToBytes(), f.key, f.size, f.chunkSize, f.sha, File(f.path), IncomingFile.bitmapFrom(f.bitmap))
        incoming[fileIdHex] = inf
        return inf
    }

    override fun wantChunk(meta: BundleMeta): Boolean {
        val f = incomingFor(meta.group.toHex()) ?: return false
        return !f.isComplete && !f.has(meta.seq)
    }

    override fun onChunkBundle(bundle: Bundle) {
        val fid = bundle.group.toHex()
        val f = incomingFor(fid) ?: return
        if (!f.write(bundle.seq, bundle.payload)) return
        if (!f.isComplete) { db.updateFileProgress(fid, f.bitmapBytes(), f.receivedCount, FileState.ACTIVE, notify = true); return }
        if (f.verify()) {
            incoming.remove(fid)
            db.updateFileProgress(fid, f.bitmapBytes(), f.receivedCount, FileState.COMPLETE, notify = true)
            val row = db.file(fid) ?: return
            if (!ChatId.isGroup(row.chat)) {
                // 1:1 files: tell relays they can drop the pieces, and tell the sender it arrived.
                db.addTombstone(f.fileId, now())
                router.sendTombstones(listOf(f.fileId))
                queueDirect(row.chat, Payload.Receipt(Crypto.randomBytes(16), now(), listOf(row.msgId.hexToBytes())), RECEIPT_TTL_MS)
            }
        } else {
            db.updateFileProgress(fid, ByteArray(0), 0, FileState.ACTIVE, notify = true)
        }
    }

    override fun isOwnFile(group: ByteArray): Boolean = outgoing.containsKey(group.toHex())

    override fun outgoingChunkMetas(now: Long): List<BundleMeta> {
        val out = ArrayList<BundleMeta>()
        for (f in outgoing.values) if (f.expiresAt > now) out.addAll(f.metas())
        return out
    }

    override fun loadOutgoingChunk(id: ByteArray): Bundle? {
        for (f in outgoing.values) {
            val i = f.indexOf(id)
            if (i >= 0) return try { f.build(i) } catch (e: Exception) { null }
        }
        return null
    }

    override fun onHandedOver(bundleId: ByteArray, type: Int, toDestination: Boolean) {
        if (type == Bundle.TYPE_MSG) { db.raiseStatus(bundleId.toHex(), MsgStatus.CARRIED); return }
        for ((fid, f) in outgoing) {
            val i = f.indexOf(bundleId)
            if (i < 0) continue
            val bits = handedPieces.getOrPut(fid) { BitSet() }
            bits.set(i)
            _sendProgress.value = _sendProgress.value + (fid to bits.cardinality().toFloat() / f.chunkCount)
            break
        }
    }

    // ================================================================ peers and links

    override fun onPeerReady(linkId: String, publicKey: ByteArray, name: String) {
        val pub = publicKey.toHex()
        if (db.contact(pub) != null) db.touchSeen(pub, now())
        _connected.value = _connected.value.filter { it.linkId != linkId } + ConnectedPeer(linkId, pub, name)
        if (linkId.startsWith("tor:")) online.onVerified(linkId, pub, name)
        if (foreground && sharePresence) scope.launch { delay(300); sendPresence(pub, Presence.ONLINE) }
    }

    override fun onPeerGone(linkId: String, publicKey: ByteArray?) {
        _connected.value = _connected.value.filter { it.linkId != linkId }
        if (publicKey != null) calls.onLinkLost(publicKey.toHex())
        expirePresence(now())
    }

    override fun onLinkUp(link: Link) = router.onLinkUp(link)
    override fun onLinkDown(linkId: String) = router.onLinkDown(linkId)
    override fun onFrame(link: Link, frame: Frame) = router.onFrame(link, frame)
    override fun onNearby(nodeHex: String, rssi: Int) { _nearby.value = _nearby.value + (nodeHex to NearbyPeer(nodeHex, rssi, now())) }
    override fun onStateChanged(state: BleTransport.State) { _state.value = state }

    // ================================================================ calls

    /** [callKey] carries the sender's ephemeral X25519 public key for the call (forward secrecy). */
    fun sendCallSignal(peerHex: String, action: Int, callId: ByteArray, callKey: ByteArray): Boolean {
        val peer = peerHex.hexToBytes()
        val p = Payload.CallSignal(Crypto.randomBytes(16), now(), action, callId, callKey)
        val sealed = Envelope.seal(identity.privateKey, identity.publicKey, peer, CALL_AAD, p.pack())
        return router.sendCallFrame(peer, sealed)
    }

    override fun onCallFrame(linkId: String, peerPublicKey: ByteArray, sealed: ByteArray) {
        val (sender, plain) = try { Envelope.open(identity.privateKey, identity.publicKey, CALL_AAD, sealed) }
            catch (e: Exception) { onPresenceFrame(peerPublicKey, sealed); return }
        if (!sender.contentEquals(peerPublicKey)) return
        val p = try { Payload.unpack(plain) as? Payload.CallSignal } catch (e: Exception) { null } ?: return
        if (now() - p.sentAt > 60_000) return
        val peer = sender.toHex()
        val c = db.contact(peer)
        if (c?.state == ContactState.BLOCKED) {
            if (p.action == Payload.CALL_OFFER) sendCallSignal(peer, Payload.CALL_REJECT, p.callId, ByteArray(32))
            return
        }
        if (p.action == Payload.CALL_OFFER && c?.state != ContactState.CONTACT && db.getBool(Settings.BLOCK_UNKNOWN_CALLS, true)) {
            sendCallSignal(peer, Payload.CALL_REJECT, p.callId, ByteArray(32))
            logCall(peer, outgoing = false, text = "Silenced call from unknown")
            return
        }
        calls.onSignal(linkId, peer, c?.display ?: "Unknown", p)
    }

    override fun onAudioFrame(linkId: String, seq: Long, cipher: ByteArray) = calls.onAudio(linkId, seq, cipher)

    // ================================================================ live status: online · typing · recording

    private val _presence = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    /** Contacts' live status (only while sharing is on). Read by the chat list and chat header. */
    val presence: StateFlow<Map<String, PeerPresence>> = _presence.asStateFlow()
    @Volatile var foreground = false
        private set
    private val lastActivitySent = ConcurrentHashMap<String, Long>()
    @Volatile private var lastBeat = 0L
    private var presenceLoop: kotlinx.coroutines.Job? = null

    val sharePresence: Boolean get() = db.getBool(Settings.SHARE_PRESENCE, true)

    fun setSharePresence(on: Boolean) {
        if (!on) broadcastPresence(Presence.OFFLINE)       // tell them once, then go quiet
        db.setBool(Settings.SHARE_PRESENCE, on)
        if (!on) _presence.value = emptyMap()
        else if (foreground) broadcastPresence(Presence.ONLINE)
    }

    /** The app came to the screen or left it. Online is shown only while Mura is actually open. */
    fun setForeground(on: Boolean) {
        foreground = on
        if (!sharePresence) return
        broadcastPresence(if (on) Presence.ONLINE else Presence.OFFLINE)
        lastBeat = now()
        if (on && presenceLoop?.isActive != true) presenceLoop = scope.launch {
            while (isActive && foreground) {
                delay(5_000)
                val t = now()
                if (sharePresence && t - lastBeat >= Presence.BEAT_MS) { lastBeat = t; broadcastPresence(Presence.ONLINE) }
                expirePresence(t)
            }
        }
    }

    /** Called on every keystroke in a chat; sends at most one "typing" signal every 3 s. */
    fun typing(chat: String) = activity(chat, Presence.TYPING)

    /** Called while a voice note is being recorded. */
    fun recordingVoice(chat: String) = activity(chat, Presence.RECORDING)

    /** Typing stopped (message sent or text cleared): clear it on their screen right away. */
    fun activityDone(chat: String) {
        if (lastActivitySent.remove(chat) != null && sharePresence) sendPresence(chat, if (foreground) Presence.ONLINE else Presence.OFFLINE)
    }

    private fun activity(chat: String, kind: Int) {
        if (!sharePresence || ChatId.isGroup(chat)) return
        val t = now()
        if (t - (lastActivitySent[chat] ?: 0L) < 3_000) return
        lastActivitySent[chat] = t
        sendPresence(chat, kind)
    }

    /** Opening a chat connects to that contact right away (Tor), so their status and your messages are live. */
    fun warmUp(chat: String) {
        if (ChatId.isGroup(chat) || isLinked(chat)) return
        val c = db.contact(chat) ?: return
        if (c.state == ContactState.CONTACT && c.onion != null) online.dialNow(chat)
    }

    /** Keep an internet link open while both of you are in the app, so status and messages stay instant. */
    fun keepWarm(pubHex: String): Boolean {
        if (!foreground || !sharePresence) return false
        val p = _presence.value[pubHex] ?: return false
        return p.online && now() - p.seenAt < Presence.ONLINE_MS
    }

    private fun broadcastPresence(kind: Int) {
        for (peer in _connected.value.map { it.pubHex }.toSet()) sendPresence(peer, kind)
    }

    /** Sealed to that one contact (same encryption as call signals), sent only over a direct link — never stored or carried. */
    private fun sendPresence(peerHex: String, kind: Int) = scope.launch {
        if (db.contact(peerHex)?.state != ContactState.CONTACT) return@launch
        try {
            val body = ByteArray(32).also { Crypto.random.nextBytes(it) }
            body[0] = 1; body[1] = kind.toByte()
            val t = now()
            for (i in 0 until 8) body[2 + i] = (t ushr (56 - 8 * i)).toByte()
            val sealed = Envelope.seal(identity.privateKey, identity.publicKey, peerHex.hexToBytes(), PRESENCE_AAD, body)
            router.sendCallFrame(peerHex.hexToBytes(), sealed)
        } catch (e: Exception) { /* status is best-effort */ }
    }

    private fun onPresenceFrame(peerPublicKey: ByteArray, sealed: ByteArray) {
        if (!sharePresence) return                                   // reciprocal: not sharing means not seeing
        val (sender, body) = try { Envelope.open(identity.privateKey, identity.publicKey, PRESENCE_AAD, sealed) } catch (e: Exception) { return }
        if (!sender.contentEquals(peerPublicKey) || body.size < 10 || body[0].toInt() != 1) return
        val peer = sender.toHex()
        if (db.contact(peer)?.state != ContactState.CONTACT) return     // only people you've added
        var sentAt = 0L
        for (i in 0 until 8) sentAt = (sentAt shl 8) or (body[2 + i].toLong() and 0xff)
        val t = now()
        if (kotlin.math.abs(t - sentAt) > 60_000) return                  // stale or replayed
        val kind = body[1].toInt()
        val old = _presence.value[peer] ?: PeerPresence()
        val next = when (kind) {
            Presence.OFFLINE -> PeerPresence(online = false, seenAt = t)
            Presence.ONLINE -> old.copy(online = true, activity = Presence.NONE, seenAt = t)
            Presence.TYPING, Presence.RECORDING -> old.copy(online = true, activity = kind, activityAt = t, seenAt = t)
            else -> return
        }
        _presence.value = _presence.value + (peer to next)
        if (kind == Presence.TYPING || kind == Presence.RECORDING) scope.launch { delay(Presence.ACTIVITY_MS + 300); expirePresence(now()) }
    }

    private fun expirePresence(t: Long) {
        val cur = _presence.value
        if (cur.isEmpty()) return
        val linked = _connected.value.map { it.pubHex }.toSet()
        val next = cur.mapValues { (pub, p) ->
            var q = p
            if (q.activity != Presence.NONE && t - q.activityAt >= Presence.ACTIVITY_MS) q = q.copy(activity = Presence.NONE)
            if (q.online && (t - q.seenAt >= Presence.ONLINE_MS || pub !in linked)) q = q.copy(online = false, activity = Presence.NONE)
            q
        }
        if (next != cur) _presence.value = next
    }

    fun logCall(peerHex: String, outgoing: Boolean, text: String) = scope.launch {
        if (db.contact(peerHex) == null) db.upsertContact(peerHex, Crypto.nodeId(peerHex.hexToBytes()).toHex(), null, ContactState.REQUEST, now())
        db.insertMessage(MessageRow(Crypto.randomBytes(16).toHex(), peerHex, if (outgoing) identity.publicHex else peerHex, outgoing,
            MsgKind.CALL, text, null, 0, MsgStatus.READ, false, false, now(), JSONObject()))
    }

    fun isLinked(peerHex: String): Boolean = router.linkFor(peerHex.hexToBytes()) != null

    /** Spam guard: a stranger making thousands of throwaway keys can't flood the chat list or the database. */
    private fun tooManyRequests(): Boolean = db.contacts().count { it.state == ContactState.REQUEST } >= MAX_PENDING_REQUESTS
}

fun formatDuration(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(java.util.Locale.US, s / 60, s % 60)
}
