package com.mura.mesh.core

/**
 * A bundle is the unit of store-and-forward. Relays can read the header (where it goes,
 * when it expires) but never the payload, which is end-to-end encrypted.
 */
class Bundle(
    val id: ByteArray,          // 16 bytes
    val type: Int,              // TYPE_MSG or TYPE_CHUNK
    val dest: ByteArray,        // 8-byte node id of the final recipient
    val group: ByteArray,       // 16 bytes: file id for chunks, zeros for messages
    val seq: Int,               // chunk index (0 for messages)
    val expiresAt: Long,        // epoch millis
    val hops: Int,              // remaining relay hops
    val payload: ByteArray,
) {
    val meta: BundleMeta get() = BundleMeta(id, type, dest, group, seq, payload.size, expiresAt, hops)

    fun encode(): ByteArray = ByteWriter(payload.size + 64)
        .u8(VERSION).u8(type).fixed(id, 16).fixed(dest, 8).fixed(group, 16)
        .i32(seq).u64(expiresAt).u8(hops).bytes(payload).toByteArray()

    fun withHops(h: Int) = Bundle(id, type, dest, group, seq, expiresAt, h, payload)

    companion object {
        const val VERSION = 1
        const val TYPE_MSG = 1
        const val TYPE_CHUNK = 2
        val ZERO16 = ByteArray(16)
        const val MAX_PAYLOAD = 256 * 1024

        fun decode(b: ByteArray): Bundle {
            val r = ByteReader(b)
            if (r.u8() != VERSION) throw ProtocolException("bundle version")
            val type = r.u8()
            if (type != TYPE_MSG && type != TYPE_CHUNK) throw ProtocolException("bundle type")
            return Bundle(r.fixed(16), type, r.fixed(8), r.fixed(16), r.i32(), r.u64(), r.u8(), r.bytes(MAX_PAYLOAD))
        }

        /** Header fields bound into the end-to-end encryption so relays cannot swap them. */
        fun aad(id: ByteArray, dest: ByteArray): ByteArray = id + dest
    }
}

/** What a peer advertises in an OFFER: everything except the payload. */
class BundleMeta(
    val id: ByteArray, val type: Int, val dest: ByteArray, val group: ByteArray,
    val seq: Int, val size: Int, val expiresAt: Long, val hops: Int,
) {
    fun write(w: ByteWriter) {
        w.fixed(id, 16).u8(type).fixed(dest, 8).fixed(group, 16).i32(seq).i32(size).u64(expiresAt).u8(hops)
    }

    companion object {
        fun read(r: ByteReader) = BundleMeta(r.fixed(16), r.u8(), r.fixed(8), r.fixed(16), r.i32(), r.i32(), r.u64(), r.u8())
    }
}

/** Frames exchanged directly between two linked phones. */
sealed class Frame {
    /** [nonce]: fresh random bytes the other phone must answer with a [Proof] — no one can claim a key they don't hold. */
    class Hello(val publicKey: ByteArray, val name: String, val relay: Boolean, val nonce: ByteArray = ByteArray(16)) : Frame()
    /** Proof of owning the private key behind Hello.publicKey: HMAC over both keys and both nonces with the X25519 shared secret. */
    class Proof(val mac: ByteArray) : Frame()
    class Offer(val entries: List<BundleMeta>) : Frame()
    class Want(val ids: List<ByteArray>) : Frame()
    class Data(val bundle: Bundle) : Frame()
    object Done : Frame()
    class Tombstones(val ids: List<ByteArray>) : Frame()
    /** Live call signalling, end-to-end sealed, never stored. */
    class Call(val sealed: ByteArray) : Frame()
    /** Encrypted call audio, sent on the priority channel. */
    class Audio(val seq: Long, val cipher: ByteArray) : Frame()
    object Ping : Frame()

    fun encode(): ByteArray {
        val w = ByteWriter()
        when (this) {
            is Hello -> w.u8(1).u8(PROTOCOL).fixed(publicKey, 32).str(name.take(48)).u8(if (relay) 1 else 0).fixed(nonce, 16)
            is Proof -> w.u8(10).fixed(mac, 32)
            is Offer -> { w.u8(2).u16(entries.size); entries.forEach { it.write(w) } }
            is Want -> { w.u8(3).u16(ids.size); ids.forEach { w.fixed(it, 16) } }
            is Data -> w.u8(4).bytes(bundle.encode())
            is Done -> w.u8(5)
            is Tombstones -> { w.u8(6).u16(ids.size); ids.forEach { w.fixed(it, 16) } }
            is Call -> w.u8(7).bytes(sealed)
            is Audio -> w.u8(8).u32(seq).bytes(cipher)
            is Ping -> w.u8(9)
        }
        return w.toByteArray()
    }

    companion object {
        /** 4: links are authenticated (Hello nonce + Proof). */
        const val PROTOCOL = 4
        const val MAX_LIST = 512

        fun decode(b: ByteArray): Frame {
            val r = ByteReader(b)
            return when (r.u8()) {
                1 -> {
                    if (r.u8() != PROTOCOL) throw ProtocolException("protocol version")
                    Hello(r.fixed(32), r.str(), r.u8() == 1, r.fixed(16))
                }
                2 -> { val n = r.u16(); if (n > MAX_LIST) throw ProtocolException("offer"); Offer(List(n) { BundleMeta.read(r) }) }
                3 -> { val n = r.u16(); if (n > MAX_LIST) throw ProtocolException("want"); Want(List(n) { r.fixed(16) }) }
                4 -> Data(Bundle.decode(r.bytes(Bundle.MAX_PAYLOAD + 1024)))
                5 -> Done
                6 -> { val n = r.u16(); if (n > MAX_LIST) throw ProtocolException("tomb"); Tombstones(List(n) { r.fixed(16) }) }
                7 -> Call(r.bytes(4096))
                8 -> Audio(r.u32(), r.bytes(4096))
                9 -> Ping
                10 -> Proof(r.fixed(32))
                else -> throw ProtocolException("unknown frame")
            }
        }
    }
}

/** Preview of a link, fetched by the sender's phone so the receiver never contacts the website. */
class LinkPreview(val url: String, val title: String, val description: String, val image: ByteArray)

class GroupMember(val xpub: ByteArray, val edpub: ByteArray, val name: String, val admin: Boolean)

/** A v3 onion address: 56 base32 characters + ".onion". */
object Onion {
    private val RE = Regex("^[a-z2-7]{56}\\.onion$")
    fun valid(s: String) = RE.matches(s)
}

/** "@Name" in a group message: who is mentioned, and the name as the sender typed it. */
class Mention(val pub: ByteArray, val name: String)

/** A quoted message ("reply"). The snippet lets the quote show even if the original isn't on this phone. */
class Reply(val msgId: ByteArray, val sender: ByteArray, val kind: Int, val snippet: String) {
    fun write(w: ByteWriter) { w.fixed(msgId, 16).fixed(sender, 32).u8(kind).str(snippet.take(160)) }
    companion object {
        val NOBODY = ByteArray(32)
        fun read(r: ByteReader) = Reply(r.fixed(16), r.fixed(32), r.u8(), r.str().take(160))
    }
}

/** What is inside the end-to-end envelope. */
sealed class Payload(val msgId: ByteArray, val sentAt: Long) {

    class Text(msgId: ByteArray, sentAt: Long, val text: String, val viewOnce: Boolean, val preview: LinkPreview? = null,
               val reply: Reply? = null, val ttl: Int = 0, val forwarded: Boolean = false, val mentions: List<Mention> = emptyList()) : Payload(msgId, sentAt)

    class File(
        msgId: ByteArray, sentAt: Long,
        val fileId: ByteArray, val media: Int, val name: String, val mime: String,
        val size: Long, val chunkSize: Int, val chunkCount: Int,
        val sha256: ByteArray, val fileKey: ByteArray,
        val durationMs: Int, val width: Int, val height: Int,
        val thumb: ByteArray, val caption: String, val viewOnce: Boolean,
        /** True if the sealed content is deflate-compressed; [originalSize] is the size after inflating. */
        val compressed: Boolean = false, val originalSize: Long = size,
        val reply: Reply? = null, val ttl: Int = 0, val forwarded: Boolean = false,
    ) : Payload(msgId, sentAt)

    class Location(msgId: ByteArray, sentAt: Long, val lat: Double, val lon: Double, val accuracy: Float, val viewOnce: Boolean,
                   val reply: Reply? = null, val ttl: Int = 0, val forwarded: Boolean = false) : Payload(msgId, sentAt)

    /** Emoji reaction to a message ("" removes mine). */
    class Reaction(msgId: ByteArray, sentAt: Long, val target: ByteArray, val emoji: String) : Payload(msgId, sentAt)

    /** New text for one of my own messages. */
    class Edit(msgId: ByteArray, sentAt: Long, val target: ByteArray, val text: String) : Payload(msgId, sentAt)

    /** A poll (shown as a message). Votes are visible to everyone in the chat. */
    class Poll(msgId: ByteArray, sentAt: Long, val question: String, val options: List<String>, val multi: Boolean,
               val reply: Reply? = null, val ttl: Int = 0, val forwarded: Boolean = false) : Payload(msgId, sentAt)

    /** My current choices in a poll (indexes; empty = vote withdrawn). */
    class Vote(msgId: ByteArray, sentAt: Long, val poll: ByteArray, val choices: List<Int>) : Payload(msgId, sentAt)

    /** Pin or unpin a message for everyone in the chat. */
    class Pin(msgId: ByteArray, sentAt: Long, val target: ByteArray, val pinned: Boolean) : Payload(msgId, sentAt)

    /** A shared checklist (shown as a message). Items are [ListItem]s; changes travel as [ListOp]s. */
    class Checklist(msgId: ByteArray, sentAt: Long, val title: String, val items: List<ListItem>,
                    val reply: Reply? = null, val ttl: Int = 0, val forwarded: Boolean = false) : Payload(msgId, sentAt)

    /** One change to a checklist: add / rename / check / uncheck / remove an item. */
    class ListOp(msgId: ByteArray, sentAt: Long, val list: ByteArray, val op: Int, val item: ByteArray, val text: String) : Payload(msgId, sentAt)

    /** A hand-drawn sketch, sent as compact vector strokes (see [DoodleCodec]). */
    class Doodle(msgId: ByteArray, sentAt: Long, val width: Int, val height: Int, val background: Int, val strokes: ByteArray,
                 val reply: Reply? = null, val ttl: Int = 0, val forwarded: Boolean = false) : Payload(msgId, sentAt)

    class Receipt(msgId: ByteArray, sentAt: Long, val delivered: List<ByteArray>) : Payload(msgId, sentAt)
    class Read(msgId: ByteArray, sentAt: Long, val read: List<ByteArray>) : Payload(msgId, sentAt)
    class Screenshot(msgId: ByteArray, sentAt: Long) : Payload(msgId, sentAt)
    class Viewed(msgId: ByteArray, sentAt: Long, val target: ByteArray) : Payload(msgId, sentAt)

    /** Name, signing key, profile photo and the key that unlocks my status updates. */
    class Profile(msgId: ByteArray, sentAt: Long, val name: String, val edPub: ByteArray, val avatar: ByteArray,
                  val statusKey: ByteArray, val statusEpoch: Int, val prekey: SignedPreKey? = null,
                  /** My Tor onion address ("" if internet is off) — only ever sent to contacts, end-to-end encrypted. */
                  val onion: String = "") : Payload(msgId, sentAt)

    class CallSignal(msgId: ByteArray, sentAt: Long, val action: Int, val callId: ByteArray, val callKey: ByteArray) : Payload(msgId, sentAt)

    /** Delete for everyone. */
    class Delete(msgId: ByteArray, sentAt: Long, val targets: List<ByteArray>) : Payload(msgId, sentAt)

    /** Sent privately to each member by an admin: the current group key. */
    class GroupKey(msgId: ByteArray, sentAt: Long, val groupId: ByteArray, val epoch: Int, val key: ByteArray,
                   val version: Int, val name: String, val removed: Boolean, val adminEdPub: ByteArray) : Payload(msgId, sentAt)

    /** Signed by an admin, sent inside the group: who is in it and who runs it. */
    class GroupState(msgId: ByteArray, sentAt: Long, val groupId: ByteArray, val version: Int, val epoch: Int, val name: String,
                     val dissolved: Boolean, val members: List<GroupMember>) : Payload(msgId, sentAt)

    class GroupLeft(msgId: ByteArray, sentAt: Long, val groupId: ByteArray) : Payload(msgId, sentAt)

    /** My current group sender-key chain, sent privately to one member. */
    class SenderKey(msgId: ByteArray, sentAt: Long, val groupId: ByteArray, val epoch: Int, val keyId: Int, val iteration: Int,
                    val chainKey: ByteArray) : Payload(msgId, sentAt)

    /** Disappearing-messages timer changed for this chat (seconds, 0 = off). */
    class Timer(msgId: ByteArray, sentAt: Long, val ttl: Int) : Payload(msgId, sentAt)

    /** A 24-hour status update. */
    class Status(msgId: ByteArray, sentAt: Long, val kind: Int, val text: String, val background: Int,
                 val image: ByteArray, val expiresAt: Long,
                 /** The owner allows contacts to screenshot / screen-record this status (trailing byte; older versions ignore it). */
                 val allowCapture: Boolean = false) : Payload(msgId, sentAt)

    fun encode(): ByteArray {
        val w = ByteWriter().u8(VERSION)
        w.u8(kindCode)
        w.fixed(msgId, 16).u64(sentAt)
        when (this) {
            is Text -> {
                w.u8((if (viewOnce) 1 else 0) or (if (preview != null) 2 else 0)).str(text)
                preview?.let { w.str(it.url).str(it.title).str(it.description).bytes(it.image) }
                extras(w, reply, ttl, forwarded, mentions)
            }
            is File -> {
                w.u8((if (viewOnce) 1 else 0) or (if (compressed) 2 else 0)).fixed(fileId, 16).u8(media).str(name).str(mime)
                    .u64(size).i32(chunkSize).i32(chunkCount).fixed(sha256, 32).fixed(fileKey, 32)
                    .i32(durationMs).i32(width).i32(height).bytes(thumb).str(caption).u64(originalSize)
                extras(w, reply, ttl, forwarded)
            }
            is Location -> { w.u8(if (viewOnce) 1 else 0).f64(lat).f64(lon).f32(accuracy); extras(w, reply, ttl, forwarded) }
            is Reaction -> w.fixed(target, 16).str(emoji.take(16))
            is Edit -> w.fixed(target, 16).str(text)
            is Poll -> {
                w.str(question).u8(if (multi) 1 else 0).u8(options.size)
                options.forEach { w.str(it.take(100)) }
                extras(w, reply, ttl, forwarded)
            }
            is Vote -> { w.fixed(poll, 16).u8(choices.size); choices.forEach { w.u8(it) } }
            is Pin -> w.fixed(target, 16).u8(if (pinned) 1 else 0)
            is Checklist -> {
                w.str(title).u16(items.size)
                items.forEach { w.fixed(it.id, 8).str(it.text.take(200)) }
                extras(w, reply, ttl, forwarded)
            }
            is ListOp -> w.fixed(list, 16).u8(op).fixed(item, 8).str(text.take(200))
            is Doodle -> { w.u16(width).u16(height).i32(background).bytes(strokes); extras(w, reply, ttl, forwarded) }
            is Receipt -> ids(w, delivered)
            is Read -> ids(w, read)
            is Screenshot -> {}
            is Viewed -> w.fixed(target, 16)
            is Profile -> {
                w.str(name).fixed(edPub, 32).bytes(avatar).fixed(statusKey, 32).i32(statusEpoch)
                if (prekey == null) w.i32(0) else w.i32(prekey.id).fixed(prekey.pub, 32).fixed(prekey.sig, 64)
                w.str(onion.take(80))
            }
            is SenderKey -> w.fixed(groupId, 16).i32(epoch).i32(keyId).i32(iteration).fixed(chainKey, 32)
            is Timer -> w.u32(ttl.toLong())
            is CallSignal -> w.u8(action).fixed(callId, 16).fixed(callKey, 32)
            is Delete -> ids(w, targets)
            is GroupKey -> w.fixed(groupId, 16).i32(epoch).fixed(key, 32).i32(version).str(name).u8(if (removed) 1 else 0).fixed(adminEdPub, 32)
            is GroupState -> {
                w.fixed(groupId, 16).i32(version).i32(epoch).str(name).u8(if (dissolved) 1 else 0).u16(members.size)
                for (m in members) w.fixed(m.xpub, 32).fixed(m.edpub, 32).str(m.name.take(48)).u8(if (m.admin) 1 else 0)
            }
            is GroupLeft -> w.fixed(groupId, 16)
            is Status -> w.u8(kind).str(text).i32(background).bytes(image).u64(expiresAt).u8(if (allowCapture) 1 else 0)
        }
        return w.toByteArray()
    }

    val kindCode: Int get() = when (this) {
        is Text -> K_TEXT; is File -> K_FILE; is Location -> K_LOCATION; is Receipt -> K_RECEIPT; is Read -> K_READ
        is Screenshot -> K_SCREENSHOT; is Viewed -> K_VIEWED; is Profile -> K_PROFILE; is CallSignal -> K_CALL
        is Delete -> K_DELETE; is GroupKey -> K_GROUP_KEY; is GroupState -> K_GROUP_STATE; is GroupLeft -> K_GROUP_LEFT
        is Status -> K_STATUS; is SenderKey -> K_SENDER_KEY; is Timer -> K_TIMER
        is Reaction -> K_REACTION; is Edit -> K_EDIT; is Poll -> K_POLL; is Vote -> K_VOTE; is Pin -> K_PIN
        is Checklist -> K_LIST; is ListOp -> K_LIST_OP; is Doodle -> K_DOODLE
    }

    /** Compressed form used inside every envelope. */
    fun pack(): ByteArray = Compression.pack(encode())

    companion object {
        const val VERSION = 3
        const val K_TEXT = 1; const val K_FILE = 2; const val K_LOCATION = 3; const val K_RECEIPT = 4
        const val K_READ = 5; const val K_SCREENSHOT = 6; const val K_VIEWED = 7; const val K_PROFILE = 8; const val K_CALL = 9
        const val K_DELETE = 10; const val K_GROUP_KEY = 11; const val K_GROUP_STATE = 12; const val K_GROUP_LEFT = 13
        const val K_STATUS = 14; const val K_SENDER_KEY = 15; const val K_TIMER = 16
        const val K_REACTION = 17; const val K_EDIT = 18; const val K_POLL = 19; const val K_VOTE = 20; const val K_PIN = 21
        const val K_LIST = 22; const val K_LIST_OP = 23; const val K_DOODLE = 24

        const val MAX_POLL_OPTIONS = 12
        const val MAX_LIST_ITEMS = 300
        const val OP_ADD = 1; const val OP_RENAME = 2; const val OP_CHECK = 3; const val OP_UNCHECK = 4; const val OP_REMOVE = 5

        const val MEDIA_IMAGE = 1; const val MEDIA_VIDEO = 2; const val MEDIA_VOICE = 3; const val MEDIA_FILE = 4

        const val CALL_OFFER = 1; const val CALL_ANSWER = 2; const val CALL_REJECT = 3; const val CALL_END = 4; const val CALL_BUSY = 5
        const val CALL_RECORDING_ON = 6; const val CALL_RECORDING_OFF = 7

        const val STATUS_TEXT = 1; const val STATUS_IMAGE = 2
        const val MAX_GROUP = 200

        /** Disappearing-message choices, in seconds. */
        val TIMERS = listOf(0, 3600, 86_400, 7 * 86_400, 30 * 86_400)

        /**
         * Trailer shared by content messages: [flags][reply?][ttl u32][mentions?].
         * flags: 1 = reply, 2 = forwarded, 4 = mentions. New fields go after ttl, so 2.1 phones still read it.
         */
        private fun extras(w: ByteWriter, reply: Reply?, ttl: Int, forwarded: Boolean = false, mentions: List<Mention> = emptyList()) {
            w.u8((if (reply != null) 1 else 0) or (if (forwarded) 2 else 0) or (if (mentions.isNotEmpty()) 4 else 0))
            reply?.write(w)
            w.u32(ttl.coerceAtLeast(0).toLong())
            if (mentions.isNotEmpty()) {
                w.u8(mentions.size.coerceAtMost(50))
                mentions.take(50).forEach { w.fixed(it.pub, 32).str(it.name.take(48)) }
            }
        }

        private class Extras(val reply: Reply?, val ttl: Int, val forwarded: Boolean, val mentions: List<Mention>)

        private fun readExtras(r: ByteReader): Extras {
            val f = r.u8()
            val reply = if (f and 1 != 0) Reply.read(r) else null
            val ttl = r.u32().coerceAtMost(365L * 86_400).toInt()
            val mentions = if (f and 4 != 0) List(r.u8()) { Mention(r.fixed(32), r.str().take(48)) } else emptyList()
            return Extras(reply, ttl, f and 2 != 0, mentions)
        }

        private fun ids(w: ByteWriter, list: List<ByteArray>) { w.u16(list.size); list.forEach { w.fixed(it, 16) } }
        private fun ids(r: ByteReader): List<ByteArray> { val n = r.u16(); if (n > 2000) throw ProtocolException("list"); return List(n) { r.fixed(16) } }

        fun unpack(b: ByteArray): Payload = decode(Compression.unpack(b))

        fun decode(b: ByteArray): Payload {
            val r = ByteReader(b)
            if (r.u8() != VERSION) throw ProtocolException("payload version")
            val kind = r.u8()
            val id = r.fixed(16)
            val at = r.u64()
            return when (kind) {
                K_TEXT -> {
                    val f = r.u8(); val text = r.str()
                    val preview = if (f and 2 != 0) LinkPreview(r.str(), r.str(), r.str(), r.bytes(256 * 1024)) else null
                    val x = readExtras(r)
                    Text(id, at, text, f and 1 != 0, preview, x.reply, x.ttl, x.forwarded, x.mentions)
                }
                K_FILE -> {
                    val f = r.u8()
                    val fid = r.fixed(16); val media = r.u8(); val name = r.str(); val mime = r.str(); val size = r.u64()
                    val cs = r.i32(); val cc = r.i32(); val sha = r.fixed(32); val fk = r.fixed(32)
                    val dur = r.i32(); val w = r.i32(); val h = r.i32(); val thumb = r.bytes(64 * 1024); val cap = r.str(); val orig = r.u64()
                    val x = readExtras(r)
                    File(id, at, fid, media, name, mime, size, cs, cc, sha, fk, dur, w, h, thumb, cap, f and 1 != 0, f and 2 != 0, orig, x.reply, x.ttl, x.forwarded)
                }
                K_LOCATION -> {
                    val vo = r.u8() == 1; val lat = r.f64(); val lon = r.f64(); val acc = r.f32()
                    val x = readExtras(r)
                    Location(id, at, lat, lon, acc, vo, x.reply, x.ttl, x.forwarded)
                }
                K_REACTION -> Reaction(id, at, r.fixed(16), r.str().take(16))
                K_EDIT -> Edit(id, at, r.fixed(16), r.str())
                K_POLL -> {
                    val q = r.str(); val multi = r.u8() == 1; val n = r.u8()
                    if (n < 2 || n > MAX_POLL_OPTIONS) throw ProtocolException("poll options")
                    val opts = List(n) { r.str().take(100) }
                    val x = readExtras(r)
                    Poll(id, at, q.take(300), opts, multi, x.reply, x.ttl, x.forwarded)
                }
                K_VOTE -> { val poll = r.fixed(16); val n = r.u8(); Vote(id, at, poll, List(n) { r.u8() }.filter { it < MAX_POLL_OPTIONS }.distinct()) }
                K_PIN -> Pin(id, at, r.fixed(16), r.u8() == 1)
                K_LIST -> {
                    val title = r.str(); val n = r.u16()
                    if (n > MAX_LIST_ITEMS) throw ProtocolException("list too long")
                    val items = List(n) { ListItem(r.fixed(8), r.str().take(200)) }
                    val x = readExtras(r)
                    Checklist(id, at, title.take(100), items, x.reply, x.ttl, x.forwarded)
                }
                K_LIST_OP -> ListOp(id, at, r.fixed(16), r.u8(), r.fixed(8), r.str().take(200))
                K_DOODLE -> {
                    val w = r.u16(); val h = r.u16(); val bg = r.i32(); val st = r.bytes(DoodleCodec.MAX_BYTES)
                    val x = readExtras(r)
                    Doodle(id, at, w, h, bg, st, x.reply, x.ttl, x.forwarded)
                }
                K_RECEIPT -> Receipt(id, at, ids(r))
                K_READ -> Read(id, at, ids(r))
                K_SCREENSHOT -> Screenshot(id, at)
                K_VIEWED -> Viewed(id, at, r.fixed(16))
                K_PROFILE -> {
                    val name = r.str(); val ed = r.fixed(32); val av = r.bytes(128 * 1024); val sk = r.fixed(32); val ep = r.i32()
                    val spkId = r.i32()
                    val spk = if (spkId != 0) SignedPreKey(spkId, r.fixed(32), r.fixed(64)) else null
                    val onion = if (r.remaining >= 2) r.str().take(80) else ""      // 2.2 phones don't send it
                    Profile(id, at, name, ed, av, sk, ep, spk, onion)
                }
                K_SENDER_KEY -> SenderKey(id, at, r.fixed(16), r.i32(), r.i32(), r.i32(), r.fixed(32))
                K_TIMER -> Timer(id, at, r.u32().coerceAtMost(365L * 86_400).toInt())
                K_CALL -> CallSignal(id, at, r.u8(), r.fixed(16), r.fixed(32))
                K_DELETE -> Delete(id, at, ids(r))
                K_GROUP_KEY -> GroupKey(id, at, r.fixed(16), r.i32(), r.fixed(32), r.i32(), r.str(), r.u8() == 1, r.fixed(32))
                K_GROUP_STATE -> {
                    val gid = r.fixed(16); val ver = r.i32(); val ep = r.i32(); val name = r.str(); val diss = r.u8() == 1
                    val n = r.u16(); if (n > MAX_GROUP + 50) throw ProtocolException("group too large")
                    GroupState(id, at, gid, ver, ep, name, diss, List(n) { GroupMember(r.fixed(32), r.fixed(32), r.str(), r.u8() == 1) })
                }
                K_GROUP_LEFT -> GroupLeft(id, at, r.fixed(16))
                K_STATUS -> Status(id, at, r.u8(), r.str(), r.i32(), r.bytes(512 * 1024), r.u64(),
                    allowCapture = r.remaining >= 1 && r.u8() == 1)          // statuses from older versions: not allowed
                else -> throw ProtocolException("unknown payload")
            }
        }
    }
}

/**
 * Splits frames into BLE-sized packets and reassembles them.
 * Two independent channels: 0 = data, 1 = call audio (always sent first, so calls
 * never wait behind a big file piece).
 *
 * Packet: [channel:1][flags:1][bytes...]   flags bit0 = first packet of a frame, bit1 = last.
 * The first packet of a frame also carries the total frame length (u32) so a broken
 * stream is detected immediately instead of silently producing garbage.
 */
class PacketCodec(private val maxFrame: Int = Bundle.MAX_PAYLOAD + 4096) {

    fun split(channel: Int, frame: ByteArray, packetSize: Int): List<ByteArray> {
        require(packetSize >= 16)
        val out = ArrayList<ByteArray>()
        val body = ByteWriter(frame.size + 4).i32(frame.size).raw(frame).toByteArray()
        val room = packetSize - 2
        var off = 0
        while (off < body.size) {
            val n = minOf(room, body.size - off)
            var flags = 0
            if (off == 0) flags = flags or 1
            if (off + n == body.size) flags = flags or 2
            val p = ByteArray(n + 2)
            p[0] = channel.toByte(); p[1] = flags.toByte()
            System.arraycopy(body, off, p, 2, n)
            out.add(p)
            off += n
        }
        return out
    }

    private class Assembly { var expected = -1; var buf = java.io.ByteArrayOutputStream() }
    private val assemblies = arrayOf(Assembly(), Assembly())

    /** Feed one packet; returns (channel, frame) when a frame is complete. Throws on a broken stream. */
    fun feed(packet: ByteArray): Pair<Int, ByteArray>? {
        if (packet.size < 3) throw ProtocolException("short packet")
        val ch = packet[0].toInt()
        if (ch != 0 && ch != 1) throw ProtocolException("bad channel")
        val flags = packet[1].toInt()
        val a = assemblies[ch]
        if (flags and 1 != 0) {
            if (a.expected >= 0) throw ProtocolException("new frame before previous finished")
            a.buf = java.io.ByteArrayOutputStream()
            a.buf.write(packet, 2, packet.size - 2)
            if (a.buf.size() < 4) throw ProtocolException("short first packet")
            val head = a.buf.toByteArray()
            a.expected = ByteReader(head).i32()
            if (a.expected < 0 || a.expected > maxFrame) throw ProtocolException("frame size ${a.expected}")
        } else {
            if (a.expected < 0) throw ProtocolException("continuation without start")
            a.buf.write(packet, 2, packet.size - 2)
        }
        val have = a.buf.size() - 4
        if (have > a.expected) throw ProtocolException("frame overflow")
        if (flags and 2 != 0) {
            if (have != a.expected) throw ProtocolException("frame length mismatch")
            val all = a.buf.toByteArray()
            a.expected = -1
            a.buf = java.io.ByteArrayOutputStream()
            return ch to all.copyOfRange(4, all.size)
        }
        return null
    }
}
