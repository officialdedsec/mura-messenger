package com.mura.mesh.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mura.mesh.core.Bundle
import com.mura.mesh.core.BundleMeta
import com.mura.mesh.core.BundleStore
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import org.json.JSONObject

object ContactState { const val REQUEST = 0; const val CONTACT = 1; const val BLOCKED = 2 }

object MsgKind {
    const val TEXT = 1; const val FILE = 2; const val LOCATION = 3; const val CALL = 4; const val SYSTEM = 5
    const val POLL = 6; const val LIST = 7; const val DOODLE = 8
    /** Only used inside reply quotes: the reply is to a status (story), not a chat message. */
    const val STATUS = 9
    /** Kinds people write (can be replied to, reacted to, forwarded, searched). */
    val CONTENT = setOf(TEXT, FILE, LOCATION, POLL, LIST, DOODLE)
}

object MsgStatus {
    const val WAITING = 0      // stored on my phone, nobody has it yet
    const val CARRIED = 1      // a nearby phone is carrying it
    const val DELIVERED = 2
    const val READ = 3
    const val EXPIRED = 4
    const val SCHEDULED = 5    // "send later": waits on this phone until its time
}

data class ReactionRow(val sender: String, val emoji: String, val at: Long)

object FileState { const val ACTIVE = 0; const val COMPLETE = 1; const val NEEDS_APPROVAL = 2; const val DELIVERED = 3; const val GONE = 4 }

/** Chat ids: a 1:1 chat is the contact's public key (hex); a group chat is "g" + group id (hex). */
object ChatId {
    fun group(groupIdHex: String) = "g$groupIdHex"
    fun isGroup(chat: String) = chat.startsWith("g")
    fun groupIdOf(chat: String) = chat.removePrefix("g")
}

data class ContactRow(
    val pub: String, val node: String, val name: String, val pname: String, val edpub: ByteArray?, val avatar: ByteArray?,
    val statusKey: ByteArray?, val statusEpoch: Int, val state: Int, val verified: Boolean, val lastSeen: Long, val added: Long,
    /** Their Tor onion address (v3), learned in person from the QR code or from their encrypted profile. */
    val onion: String? = null,
) {
    val display: String get() = name.ifBlank { pname }.ifBlank { "Unknown · ${pub.take(4).uppercase()}" }
}

data class GroupRow(
    val id: String, val name: String, val epoch: Int, val version: Int, val dissolved: Boolean, val removed: Boolean,
    val created: Long, val inviter: String, val avatar: ByteArray?,
) {
    val chat: String get() = ChatId.group(id)
}

data class MemberRow(val gid: String, val pub: String, val edpub: ByteArray, val name: String, val admin: Boolean)

data class MessageRow(
    val id: String, val chat: String, val sender: String, val outgoing: Boolean, val kind: Int, val body: String,
    val fileId: String?, val media: Int, val status: Int, val viewOnce: Boolean, val opened: Boolean,
    val created: Long, val meta: JSONObject, val deleted: Boolean = false,
    /** Disappearing messages: erased from this phone at this time (0 = keep). */
    val expireAt: Long = 0,
    val starred: Boolean = false,
    /** Send later: when it will be sent (0 = not scheduled). */
    val sendAt: Long = 0,
)

/** A call recording: the audio itself is a sealed file (files table, chat "rec:<peer>"), never a chat message. */
data class RecordingRow(val id: String, val peer: String, val fileId: String, val created: Long, val durationMs: Int)

data class FileRow(
    val fileId: String, val msgId: String, val chat: String, val outgoing: Boolean, val name: String,
    val mime: String, val media: Int, val size: Long, val chunkSize: Int, val chunkCount: Int,
    val sha: ByteArray, val key: ByteArray, val thumb: ByteArray?, val bitmap: ByteArray?, val received: Int,
    val path: String, val state: Int, val durationMs: Int, val width: Int, val height: Int, val expires: Long,
    val compressed: Boolean, val originalSize: Long, val dest: ByteArray,
) {
    val progress: Float get() = if (chunkCount == 0) 1f else received.toFloat() / chunkCount
}

data class StatusRow(
    val id: String, val owner: String, val kind: Int, val text: String, val background: Int, val image: ByteArray?,
    val created: Long, val expires: Long, val viewed: Boolean,
    /** The owner lets contacts screenshot / screen-record it. */
    val allowCapture: Boolean = false,
)

data class ChatSummary(
    val chat: String, val isGroup: Boolean, val title: String, val avatar: ByteArray?, val lastText: String?,
    val lastAt: Long, val lastOutgoing: Boolean, val lastStatus: Int, val unread: Int, val state: Int, val members: Int,
    val pinned: Boolean, val muted: Boolean, val locked: Boolean = false,
    val archived: Boolean = false,
)

/**
 * SQLite storage where every sensitive column is a [Vault]-encrypted blob.
 * What stays readable is only what's needed to route and sort (random ids, timestamps,
 * pseudonymous key ids) — no names, words, pictures or keys.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "mura2.db", null, 7), BundleStore {

    /** Files are always looked up in this phone's private media folder (paths survive a restore). */
    private val mediaDir = java.io.File(ctx.filesDir, "media")

    @Volatile var onChange: (() -> Unit)? = null
    private fun changed() { onChange?.invoke() }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        // PRAGMAs that report a value must go through rawQuery (execSQL throws on newer Android).
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE contacts(pub TEXT PRIMARY KEY, node TEXT NOT NULL, name BLOB, pname BLOB, edpub BLOB, avatar BLOB,
            status_key BLOB, status_epoch INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL, verified INTEGER NOT NULL DEFAULT 0,
            added INTEGER NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX contacts_node ON contacts(node)")
        db.execSQL("""CREATE TABLE groups(id TEXT PRIMARY KEY, name BLOB, epoch INTEGER NOT NULL, version INTEGER NOT NULL,
            dissolved INTEGER NOT NULL DEFAULT 0, removed INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, inviter TEXT NOT NULL,
            avatar BLOB, node TEXT NOT NULL)""")
        db.execSQL("CREATE TABLE group_keys(gid TEXT NOT NULL, epoch INTEGER NOT NULL, key BLOB NOT NULL, PRIMARY KEY(gid, epoch))")
        db.execSQL("""CREATE TABLE group_members(gid TEXT NOT NULL, pub TEXT NOT NULL, edpub BLOB NOT NULL, name BLOB,
            admin INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(gid, pub))""")
        db.execSQL("""CREATE TABLE chat_meta(chat TEXT PRIMARY KEY, unread INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0,
            muted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE messages(id TEXT PRIMARY KEY, chat TEXT NOT NULL, sender TEXT NOT NULL, outgoing INTEGER NOT NULL,
            kind INTEGER NOT NULL, body BLOB, file_id TEXT, media INTEGER NOT NULL DEFAULT 0, status INTEGER NOT NULL DEFAULT 0,
            view_once INTEGER NOT NULL DEFAULT 0, opened INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, meta BLOB,
            deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX messages_chat ON messages(chat, created)")
        db.execSQL("""CREATE TABLE files(file_id TEXT PRIMARY KEY, msg_id TEXT NOT NULL, chat TEXT NOT NULL, outgoing INTEGER NOT NULL,
            name BLOB, mime BLOB, media INTEGER NOT NULL, size INTEGER NOT NULL, chunk_size INTEGER NOT NULL,
            chunk_count INTEGER NOT NULL, sha BLOB NOT NULL, fkey BLOB NOT NULL, thumb BLOB, bitmap BLOB, received INTEGER NOT NULL DEFAULT 0,
            path TEXT NOT NULL, state INTEGER NOT NULL, duration INTEGER NOT NULL DEFAULT 0, width INTEGER NOT NULL DEFAULT 0,
            height INTEGER NOT NULL DEFAULT 0, expires INTEGER NOT NULL, compressed INTEGER NOT NULL DEFAULT 0,
            original_size INTEGER NOT NULL DEFAULT 0, dest BLOB NOT NULL)""")
        db.execSQL("""CREATE TABLE statuses(id TEXT PRIMARY KEY, owner TEXT NOT NULL, kind INTEGER NOT NULL, text BLOB, bg INTEGER NOT NULL,
            image BLOB, created INTEGER NOT NULL, expires INTEGER NOT NULL, viewed INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE bundles(id TEXT PRIMARY KEY, grp TEXT NOT NULL, dest BLOB NOT NULL, dest_hex TEXT NOT NULL, type INTEGER NOT NULL,
            seq INTEGER NOT NULL, size INTEGER NOT NULL, expires INTEGER NOT NULL, hops INTEGER NOT NULL, mine INTEGER NOT NULL,
            data BLOB NOT NULL, created INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX bundles_grp ON bundles(grp)")
        db.execSQL("CREATE INDEX bundles_dest ON bundles(dest_hex)")
        db.execSQL("CREATE TABLE tombstones(id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE processed(id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        createV2(db)
        createV3(db)
        createV4(db)
        createV5(db)
        createV6(db)
        createV7(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) createV3(db)
        if (oldVersion < 4) createV4(db)
        if (oldVersion < 5) createV5(db)
        if (oldVersion < 6) createV6(db)
        if (oldVersion < 7) createV7(db)
    }

    /** Version 7: whether a status's owner allows screenshots / screen recording of it. */
    private fun createV7(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE statuses ADD COLUMN capture INTEGER NOT NULL DEFAULT 0")
    }

    /** Version 6: call recordings — kept only in call history (never in chats); audio sealed in the files table. */
    private fun createV6(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recordings(id TEXT PRIMARY KEY, peer TEXT NOT NULL, file_id TEXT NOT NULL, created INTEGER NOT NULL, duration INTEGER NOT NULL)")
    }

    /** Version 5: archived chats, and chats removed from the list (they come back when a new message arrives). */
    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_meta ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_meta ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
    }

    /** Version 4: contacts' onion addresses (internet over Tor). */
    private fun createV4(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN onion BLOB")
    }

    /** Version 3: reactions, polls, pins, checklists, starred messages, send later, early edits. */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN send_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX messages_send_at ON messages(send_at) WHERE send_at > 0")
        db.execSQL("CREATE TABLE reactions(msg_id TEXT NOT NULL, chat TEXT NOT NULL, sender TEXT NOT NULL, emoji BLOB NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(msg_id, sender))")
        db.execSQL("CREATE INDEX reactions_chat ON reactions(chat)")
        db.execSQL("CREATE TABLE votes(poll_id TEXT NOT NULL, chat TEXT NOT NULL, voter TEXT NOT NULL, choices TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(poll_id, voter))")
        db.execSQL("CREATE INDEX votes_chat ON votes(chat)")
        db.execSQL("CREATE TABLE pins(chat TEXT NOT NULL, msg_id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(chat, msg_id))")
        db.execSQL("CREATE TABLE list_ops(op_id TEXT PRIMARY KEY, list_id TEXT NOT NULL, chat TEXT NOT NULL, op INTEGER NOT NULL, item TEXT NOT NULL, text BLOB, author TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX list_ops_chat ON list_ops(chat)")
        db.execSQL("CREATE TABLE pending_edits(target TEXT PRIMARY KEY, chat TEXT NOT NULL, sender TEXT NOT NULL, text BLOB NOT NULL, at INTEGER NOT NULL)")
    }

    /** Version 2: forward secrecy (ratchet sessions, prekeys, sender keys), disappearing messages, locked chats. */
    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(peer TEXT NOT NULL, sid TEXT NOT NULL, state BLOB NOT NULL, used INTEGER NOT NULL, PRIMARY KEY(peer, sid))")
        db.execSQL("CREATE TABLE prekeys(id INTEGER PRIMARY KEY, priv BLOB NOT NULL, pub BLOB NOT NULL, sig BLOB NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE contacts ADD COLUMN spk_id INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE contacts ADD COLUMN spk BLOB")
        db.execSQL("CREATE TABLE my_chains(gid TEXT PRIMARY KEY, epoch INTEGER NOT NULL, state BLOB NOT NULL)")
        db.execSQL("CREATE TABLE sk_sent(gid TEXT NOT NULL, key_id INTEGER NOT NULL, pub TEXT NOT NULL, PRIMARY KEY(gid, key_id, pub))")
        db.execSQL("""CREATE TABLE sender_keys(gid TEXT NOT NULL, pub TEXT NOT NULL, key_id INTEGER NOT NULL, state BLOB NOT NULL,
            updated INTEGER NOT NULL, PRIMARY KEY(gid, pub, key_id))""")
        db.execSQL("ALTER TABLE messages ADD COLUMN expire_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX messages_expire ON messages(expire_at) WHERE expire_at > 0")
        db.execSQL("ALTER TABLE chat_meta ADD COLUMN timer INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_meta ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
    }

    /** Flush the write-ahead log into the main file (before a backup copies it). */
    fun checkpoint() { writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }

    private inline fun <T> tx(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val r = block(db); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
    }

    private fun Cursor.blob(i: Int): ByteArray? = if (isNull(i)) null else getBlob(i)
    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    // ================================================================ settings

    fun getSetting(k: String, def: String): String =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else def }

    fun setSetting(k: String, v: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun getBool(k: String, def: Boolean) = getSetting(k, if (def) "1" else "0") == "1"
    fun setBool(k: String, v: Boolean) = setSetting(k, if (v) "1" else "0")

    /** Secret settings (backup key, my status key) are stored encrypted. */
    fun getSecret(k: String): ByteArray? {
        val v = getSetting(k, "")
        if (v.isEmpty()) return null
        return Vault.dec(android.util.Base64.decode(v, android.util.Base64.NO_WRAP))
    }

    fun setSecret(k: String, v: ByteArray?) {
        if (v == null) { writableDatabase.delete("kv", "k=?", arrayOf(k)); changed(); return }
        setSetting(k, android.util.Base64.encodeToString(Vault.enc(v), android.util.Base64.NO_WRAP))
    }

    // ================================================================ contacts

    private val contactCols = "pub, node, name, pname, edpub, avatar, status_key, status_epoch, state, verified, last_seen, added, onion"

    private fun readContact(c: Cursor) = ContactRow(
        pub = c.getString(0), node = c.getString(1), name = Vault.decS(c.blob(2)), pname = Vault.decS(c.blob(3)), edpub = c.blob(4),
        avatar = Vault.dec(c.blob(5)), statusKey = Vault.dec(c.blob(6)), statusEpoch = c.getInt(7), state = c.getInt(8),
        verified = c.getInt(9) == 1, lastSeen = c.getLong(10), added = c.getLong(11),
        onion = Vault.decS(c.blob(12)).ifBlank { null },
    )

    fun contact(pub: String): ContactRow? = readableDatabase.rawQuery("SELECT $contactCols FROM contacts WHERE pub=?", arrayOf(pub)).use {
        if (it.moveToFirst()) readContact(it) else null
    }

    fun contacts(): List<ContactRow> = readableDatabase.rawQuery("SELECT $contactCols FROM contacts", null).use { c ->
        buildList { while (c.moveToNext()) add(readContact(c)) }
    }

    fun contactsByState(state: Int): List<ContactRow> = contacts().filter { it.state == state }.sortedBy { it.display.lowercase() }

    fun upsertContact(pub: String, node: String, name: String?, state: Int, now: Long, verified: Boolean? = null) {
        val existing = contact(pub)
        if (existing == null) {
            writableDatabase.insert("contacts", null, ContentValues().apply {
                put("pub", pub); put("node", node); put("name", Vault.encS(name ?: "")); put("pname", Vault.encS(""))
                put("state", state); put("verified", if (verified == true) 1 else 0); put("added", now)
            })
        } else {
            writableDatabase.update("contacts", ContentValues().apply {
                if (!name.isNullOrBlank()) put("name", Vault.encS(name))
                put("state", state)
                if (verified != null) put("verified", if (verified) 1 else 0)
            }, "pub=?", arrayOf(pub))
        }
        changed()
    }

    fun setContactProfile(pub: String, pname: String, edpub: ByteArray?, avatar: ByteArray?, statusKey: ByteArray?, statusEpoch: Int) {
        writableDatabase.update("contacts", ContentValues().apply {
            put("pname", Vault.encS(pname))
            if (edpub != null) put("edpub", edpub)
            put("avatar", Vault.enc(avatar))
            put("status_key", Vault.enc(statusKey))
            put("status_epoch", statusEpoch)
        }, "pub=?", arrayOf(pub))
        changed()
    }

    fun setContactOnion(pub: String, onion: String) {
        writableDatabase.update("contacts", ContentValues().apply { put("onion", Vault.encS(onion)) }, "pub=?", arrayOf(pub)); changed()
    }

    fun setContactEdPub(pub: String, edpub: ByteArray) {
        writableDatabase.update("contacts", ContentValues().apply { put("edpub", edpub) }, "pub=?", arrayOf(pub)); changed()
    }

    fun setContactState(pub: String, state: Int) {
        writableDatabase.update("contacts", ContentValues().apply { put("state", state) }, "pub=?", arrayOf(pub)); changed()
    }

    fun renameContact(pub: String, name: String) {
        writableDatabase.update("contacts", ContentValues().apply { put("name", Vault.encS(name)) }, "pub=?", arrayOf(pub)); changed()
    }

    fun touchSeen(pub: String, now: Long) {
        writableDatabase.update("contacts", ContentValues().apply { put("last_seen", now) }, "pub=?", arrayOf(pub))
    }

    // ================================================================ chats

    private fun meta(chat: String): Triple<Int, Boolean, Boolean> =
        readableDatabase.rawQuery("SELECT unread, pinned, muted FROM chat_meta WHERE chat=?", arrayOf(chat)).use {
            if (it.moveToFirst()) Triple(it.getInt(0), it.getInt(1) == 1, it.getInt(2) == 1) else Triple(0, false, false)
        }

    /** Disappearing-messages timer for a chat, in seconds (0 = off). */
    fun chatTimer(chat: String): Int = readableDatabase.rawQuery("SELECT timer FROM chat_meta WHERE chat=?", arrayOf(chat)).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }
    fun setChatTimer(chat: String, seconds: Int) {
        ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET timer=? WHERE chat=?", arrayOf<Any>(seconds, chat)); changed()
    }
    fun isLocked(chat: String): Boolean = readableDatabase.rawQuery("SELECT locked FROM chat_meta WHERE chat=?", arrayOf(chat)).use {
        it.moveToFirst() && it.getInt(0) == 1
    }
    fun setLocked(chat: String, v: Boolean) {
        ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET locked=? WHERE chat=?", arrayOf<Any>(if (v) 1 else 0, chat)); changed()
    }

    private fun ensureMeta(chat: String) {
        writableDatabase.insertWithOnConflict("chat_meta", null, ContentValues().apply { put("chat", chat) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun incUnread(chat: String) { ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET unread = unread + 1 WHERE chat=?", arrayOf(chat)) }
    fun clearUnread(chat: String) { ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET unread = 0 WHERE chat=?", arrayOf(chat)); changed() }
    fun setPinned(chat: String, v: Boolean) { ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET pinned=? WHERE chat=?", arrayOf<Any>(if (v) 1 else 0, chat)); changed() }
    fun setMuted(chat: String, v: Boolean) { ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET muted=? WHERE chat=?", arrayOf<Any>(if (v) 1 else 0, chat)); changed() }
    fun isMuted(chat: String) = meta(chat).third

    /** (archived, hidden) */
    private fun listFlags(chat: String): Pair<Boolean, Boolean> =
        readableDatabase.rawQuery("SELECT archived, hidden FROM chat_meta WHERE chat=?", arrayOf(chat)).use {
            if (it.moveToFirst()) (it.getInt(0) == 1) to (it.getInt(1) == 1) else false to false
        }
    fun setArchived(chat: String, v: Boolean) {
        ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET archived=?, pinned=0 WHERE chat=?", arrayOf<Any>(if (v) 1 else 0, chat)); changed()
    }
    fun setHidden(chat: String, v: Boolean) {
        ensureMeta(chat); writableDatabase.execSQL("UPDATE chat_meta SET hidden=? WHERE chat=?", arrayOf<Any>(if (v) 1 else 0, chat)); changed()
    }

    /** Chat list: contacts (not blocked) and groups, newest activity first, pinned on top. */
    fun chats(): List<ChatSummary> {
        val last = HashMap<String, MessageRow>()
        readableDatabase.rawQuery(
            "SELECT $MSG_COLS FROM messages m JOIN (SELECT chat AS c, MAX(created) AS mc FROM messages GROUP BY chat) t ON m.chat = t.c AND m.created = t.mc", null,
        ).use { c -> while (c.moveToNext()) { val m = readMessage(c); last[m.chat] = m } }
        val out = ArrayList<ChatSummary>()
        fun preview(m: MessageRow?): String? = when {
            m == null -> null
            m.deleted -> "This message was deleted"
            m.viewOnce -> "View-once message"
            m.status == MsgStatus.SCHEDULED -> "Scheduled message"
            m.kind == MsgKind.POLL -> "Poll: ${m.body}"
            m.kind == MsgKind.LIST -> "List: ${m.body}"
            else -> m.body
        }
        for (k in contacts()) {
            if (k.state == ContactState.BLOCKED) continue
            val m = last[k.pub]
            if (m == null && k.state == ContactState.REQUEST) continue
            val (unread, pinned, muted) = meta(k.pub)
            val (archived, hidden) = listFlags(k.pub)
            if (hidden) continue
            out.add(ChatSummary(k.pub, false, k.display, k.avatar, preview(m), m?.created ?: k.added, m?.outgoing == true, m?.status ?: 0,
                unread, k.state, 0, pinned, muted, isLocked(k.pub), archived))
        }
        for (g in groups()) {
            val m = last[g.chat]
            val (unread, pinned, muted) = meta(g.chat)
            val memberCount = memberCount(g.id)
            val senderName = if (m != null && !m.outgoing && m.kind != MsgKind.SYSTEM) memberName(g.id, m.sender) else null
            val text = preview(m)?.let { if (senderName != null && !m!!.deleted) "$senderName: $it" else it }
            val (archived, hidden) = listFlags(g.chat)
            if (hidden) continue
            out.add(ChatSummary(g.chat, true, g.name, g.avatar, text, m?.created ?: g.created, m?.outgoing == true, m?.status ?: 0,
                unread, ContactState.CONTACT, memberCount, pinned, muted, isLocked(g.chat), archived))
        }
        return out.sortedWith(compareByDescending<ChatSummary> { it.pinned }.thenByDescending { it.lastAt })
    }

    fun deleteChat(chat: String) {
        tx { db ->
            db.delete("messages", "chat=?", arrayOf(chat))
            db.delete("files", "chat=? AND NOT (outgoing=1 AND state=${FileState.ACTIVE})", arrayOf(chat))
            db.execSQL("UPDATE chat_meta SET unread=0 WHERE chat=?", arrayOf(chat))
            for (t in listOf("reactions", "votes", "pins", "list_ops", "pending_edits")) db.delete(t, "chat=?", arrayOf(chat))
        }
        changed()
    }

    // ================================================================ groups

    private val groupCols = "id, name, epoch, version, dissolved, removed, created, inviter, avatar"

    private fun readGroup(c: Cursor) = GroupRow(c.getString(0), Vault.decS(c.blob(1)), c.getInt(2), c.getInt(3), c.getInt(4) == 1,
        c.getInt(5) == 1, c.getLong(6), c.getString(7), Vault.dec(c.blob(8)))

    fun group(id: String): GroupRow? = readableDatabase.rawQuery("SELECT $groupCols FROM groups WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) readGroup(it) else null
    }

    fun groups(): List<GroupRow> = readableDatabase.rawQuery("SELECT $groupCols FROM groups", null).use { c ->
        buildList { while (c.moveToNext()) add(readGroup(c)) }
    }

    fun groupByNode(nodeHex: String): GroupRow? = readableDatabase.rawQuery("SELECT $groupCols FROM groups WHERE node=?", arrayOf(nodeHex)).use {
        if (it.moveToFirst()) readGroup(it) else null
    }

    fun activeGroupNodes(): List<String> = readableDatabase.rawQuery("SELECT node FROM groups WHERE removed=0 AND dissolved=0", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun upsertGroup(g: GroupRow, nodeHex: String) {
        writableDatabase.insertWithOnConflict("groups", null, ContentValues().apply {
            put("id", g.id); put("name", Vault.encS(g.name)); put("epoch", g.epoch); put("version", g.version)
            put("dissolved", if (g.dissolved) 1 else 0); put("removed", if (g.removed) 1 else 0); put("created", g.created)
            put("inviter", g.inviter); put("avatar", Vault.enc(g.avatar)); put("node", nodeHex)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun putGroupKey(gid: String, epoch: Int, key: ByteArray) {
        writableDatabase.insertWithOnConflict("group_keys", null, ContentValues().apply {
            put("gid", gid); put("epoch", epoch); put("key", Vault.enc(key))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun groupKey(gid: String, epoch: Int): ByteArray? =
        readableDatabase.rawQuery("SELECT key FROM group_keys WHERE gid=? AND epoch=?", arrayOf(gid, epoch.toString())).use {
            if (it.moveToFirst()) Vault.dec(it.getBlob(0)) else null
        }

    fun deleteGroupKeys(gid: String) { writableDatabase.delete("group_keys", "gid=?", arrayOf(gid)) }

    fun members(gid: String): List<MemberRow> = readableDatabase.rawQuery(
        "SELECT gid, pub, edpub, name, admin FROM group_members WHERE gid=?", arrayOf(gid),
    ).use { c -> buildList { while (c.moveToNext()) add(MemberRow(c.getString(0), c.getString(1), c.getBlob(2), Vault.decS(c.blob(3)), c.getInt(4) == 1)) } }
        .sortedWith(compareByDescending<MemberRow> { it.admin }.thenBy { it.name.lowercase() })

    fun member(gid: String, pub: String): MemberRow? = readableDatabase.rawQuery(
        "SELECT gid, pub, edpub, name, admin FROM group_members WHERE gid=? AND pub=?", arrayOf(gid, pub),
    ).use { c -> if (c.moveToFirst()) MemberRow(c.getString(0), c.getString(1), c.getBlob(2), Vault.decS(c.blob(3)), c.getInt(4) == 1) else null }

    fun memberCount(gid: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM group_members WHERE gid=?", arrayOf(gid)).use { it.moveToFirst(); it.getInt(0) }

    fun memberName(gid: String, pub: String): String {
        contact(pub)?.let { if (it.state != ContactState.REQUEST || it.name.isNotBlank()) return it.display }
        return member(gid, pub)?.name?.ifBlank { null } ?: "Member ${pub.take(4).uppercase()}"
    }

    fun setMembers(gid: String, members: List<MemberRow>) {
        tx { db ->
            db.delete("group_members", "gid=?", arrayOf(gid))
            for (m in members) db.insert("group_members", null, ContentValues().apply {
                put("gid", gid); put("pub", m.pub); put("edpub", m.edpub); put("name", Vault.encS(m.name)); put("admin", if (m.admin) 1 else 0)
            })
        }
        changed()
    }

    fun deleteGroup(gid: String) {
        tx { db ->
            db.delete("groups", "id=?", arrayOf(gid)); db.delete("group_keys", "gid=?", arrayOf(gid))
            db.delete("group_members", "gid=?", arrayOf(gid))
            db.delete("my_chains", "gid=?", arrayOf(gid)); db.delete("sk_sent", "gid=?", arrayOf(gid))
            db.delete("sender_keys", "gid=?", arrayOf(gid)); db.delete("chat_meta", "chat=?", arrayOf(ChatId.group(gid)))
        }
        deleteChat(ChatId.group(gid))
    }

    // ================================================================ messages

    fun insertMessage(m: MessageRow): Boolean {
        val r = writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
            put("id", m.id); put("chat", m.chat); put("sender", m.sender); put("outgoing", if (m.outgoing) 1 else 0); put("kind", m.kind)
            put("body", Vault.encS(m.body)); put("file_id", m.fileId); put("media", m.media); put("status", m.status)
            put("view_once", if (m.viewOnce) 1 else 0); put("opened", if (m.opened) 1 else 0); put("created", m.created)
            put("meta", Vault.encS(m.meta.toString())); put("deleted", if (m.deleted) 1 else 0); put("expire_at", m.expireAt)
            put("starred", if (m.starred) 1 else 0); put("send_at", m.sendAt)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        // A chat removed from the list comes back with its next message (archived chats stay archived).
        if (r != -1L) writableDatabase.execSQL("UPDATE chat_meta SET hidden=0 WHERE chat=? AND hidden=1", arrayOf(m.chat))
        changed()
        return r != -1L
    }

    fun message(id: String): MessageRow? = readableDatabase.rawQuery("SELECT $MSG_COLS FROM messages WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) readMessage(it) else null
    }

    /** Call history across all chats, newest first. */
    fun callLog(limit: Int = 300): List<MessageRow> = readableDatabase.rawQuery(
        "SELECT $MSG_COLS FROM messages WHERE kind=${MsgKind.CALL} ORDER BY created DESC LIMIT $limit", null,
    ).use { c -> buildList { while (c.moveToNext()) add(readMessage(c)) } }

    fun insertRecording(r: RecordingRow) {
        writableDatabase.insertWithOnConflict("recordings", null, ContentValues().apply {
            put("id", r.id); put("peer", r.peer); put("file_id", r.fileId); put("created", r.created); put("duration", r.durationMs)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    fun recordings(limit: Int = 500): List<RecordingRow> = readableDatabase.rawQuery(
        "SELECT id, peer, file_id, created, duration FROM recordings ORDER BY created DESC LIMIT $limit", null,
    ).use { c -> buildList { while (c.moveToNext()) add(RecordingRow(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))) } }

    fun recording(id: String): RecordingRow? = recordings(100_000).firstOrNull { it.id == id }

    fun deleteRecordingRow(id: String) { writableDatabase.delete("recordings", "id=?", arrayOf(id)); changed() }

    /**
     * Removes a contact and everything about them on this phone (one transaction): chat, messages, reactions, votes,
     * pins, lists, edits, call history, recordings, their statuses, encryption sessions, my undelivered mail to them
     * and the contact itself. Shared groups are left alone. Sealed files must be erased by the caller first.
     */
    fun removeContactData(pub: String, nodeHex: String) {
        tx { db ->
            db.delete("messages", "chat=?", arrayOf(pub))
            db.delete("files", "chat=? OR chat=?", arrayOf(pub, "rec:$pub"))
            for (t in listOf("reactions", "votes", "pins", "list_ops", "pending_edits", "chat_meta")) db.delete(t, "chat=?", arrayOf(pub))
            db.delete("recordings", "peer=?", arrayOf(pub))
            db.delete("sessions", "peer=?", arrayOf(pub))
            db.delete("statuses", "owner=?", arrayOf(pub))
            db.delete("bundles", "dest_hex=? AND mine=1", arrayOf(nodeHex))
            db.delete("sk_sent", "pub=?", arrayOf(pub))
            db.delete("contacts", "pub=?", arrayOf(pub))
        }
        changed()
    }

    fun clearCallLog() { writableDatabase.delete("messages", "kind=${MsgKind.CALL}", null); changed() }

    fun messages(chat: String, limit: Int = 600): List<MessageRow> = readableDatabase.rawQuery(
        "SELECT * FROM (SELECT $MSG_COLS FROM messages WHERE chat=? ORDER BY created DESC LIMIT $limit) ORDER BY created ASC", arrayOf(chat),
    ).use { c -> buildList { while (c.moveToNext()) add(readMessage(c)) } }

    fun unreadIncoming(chat: String): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM messages WHERE chat=? AND outgoing=0 AND status < ${MsgStatus.READ} AND kind IN (1,2,3,6,7,8) AND deleted=0", arrayOf(chat),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun raiseStatus(id: String, status: Int) {
        writableDatabase.execSQL("UPDATE messages SET status=? WHERE id=? AND status < ?", arrayOf<Any>(status, id, status)); changed()
    }

    fun setMessageMeta(id: String, meta: JSONObject) {
        writableDatabase.update("messages", ContentValues().apply { put("meta", Vault.encS(meta.toString())) }, "id=?", arrayOf(id)); changed()
    }

    fun markOpened(id: String) {
        writableDatabase.update("messages", ContentValues().apply { put("opened", 1) }, "id=?", arrayOf(id)); changed()
    }

    fun clearBody(id: String) {
        writableDatabase.update("messages", ContentValues().apply { put("body", Vault.encS("")) }, "id=?", arrayOf(id)); changed()
    }

    /** "This message was deleted": wipe content but keep a placeholder. */
    fun tombstoneMessage(id: String) {
        writableDatabase.update("messages", ContentValues().apply {
            put("deleted", 1); put("body", Vault.encS("")); put("meta", Vault.encS("{}")); putNull("file_id")
        }, "id=?", arrayOf(id))
        changed()
    }

    fun deleteMessage(id: String) {
        tx { db ->
            db.delete("messages", "id=?", arrayOf(id))
            db.delete("reactions", "msg_id=?", arrayOf(id)); db.delete("votes", "poll_id=?", arrayOf(id))
            db.delete("pins", "msg_id=?", arrayOf(id)); db.delete("list_ops", "list_id=?", arrayOf(id))
        }
        changed()
    }

    // ================================================================ reactions, edits, polls, pins, lists, stars, send later

    fun putReaction(msgId: String, chat: String, sender: String, emoji: String, at: Long) {
        if (emoji.isEmpty()) writableDatabase.delete("reactions", "msg_id=? AND sender=?", arrayOf(msgId, sender))
        else {
            // Keep only the newest reaction per person (reactions can arrive out of order).
            val older = readableDatabase.rawQuery("SELECT at FROM reactions WHERE msg_id=? AND sender=?", arrayOf(msgId, sender)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            if (older > at) return
            writableDatabase.insertWithOnConflict("reactions", null, ContentValues().apply {
                put("msg_id", msgId); put("chat", chat); put("sender", sender); put("emoji", Vault.encS(emoji)); put("at", at)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        changed()
    }

    /** msgId -> reactions, only those that came from inside this chat. */
    fun reactionsForChat(chat: String): Map<String, List<ReactionRow>> = readableDatabase.rawQuery(
        "SELECT msg_id, sender, emoji, at FROM reactions WHERE chat=? ORDER BY at", arrayOf(chat),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to ReactionRow(c.getString(1), Vault.decS(c.blob(2)), c.getLong(3))) } }
        .groupBy({ it.first }, { it.second })

    fun setBody(id: String, body: String, meta: JSONObject) {
        writableDatabase.update("messages", ContentValues().apply { put("body", Vault.encS(body)); put("meta", Vault.encS(meta.toString())) }, "id=?", arrayOf(id))
        changed()
    }

    fun putPendingEdit(target: String, chat: String, sender: String, text: String, at: Long) {
        writableDatabase.insertWithOnConflict("pending_edits", null, ContentValues().apply {
            put("target", target); put("chat", chat); put("sender", sender); put("text", Vault.encS(text)); put("at", at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** (chat, sender, text, at) of an edit that arrived before its message, removed once read. */
    fun takePendingEdit(target: String): List<String>? {
        val r = readableDatabase.rawQuery("SELECT chat, sender, text, at FROM pending_edits WHERE target=?", arrayOf(target)).use {
            if (it.moveToFirst()) listOf(it.getString(0), it.getString(1), Vault.decS(it.blob(2)), it.getLong(3).toString()) else null
        } ?: return null
        writableDatabase.delete("pending_edits", "target=?", arrayOf(target))
        return r
    }

    fun putVote(pollId: String, chat: String, voter: String, choices: List<Int>, at: Long) {
        val older = readableDatabase.rawQuery("SELECT at FROM votes WHERE poll_id=? AND voter=?", arrayOf(pollId, voter)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (older > at) return
        if (choices.isEmpty()) writableDatabase.delete("votes", "poll_id=? AND voter=?", arrayOf(pollId, voter))
        else writableDatabase.insertWithOnConflict("votes", null, ContentValues().apply {
            put("poll_id", pollId); put("chat", chat); put("voter", voter); put("choices", choices.joinToString(",")); put("at", at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    /** pollId -> (voter -> choices) */
    fun votesForChat(chat: String): Map<String, Map<String, List<Int>>> = readableDatabase.rawQuery(
        "SELECT poll_id, voter, choices FROM votes WHERE chat=?", arrayOf(chat),
    ).use { c ->
        val out = HashMap<String, HashMap<String, List<Int>>>()
        while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getString(2).split(",").mapNotNull { it.toIntOrNull() }
        out
    }

    /** Up to 3 pinned messages per chat, newest first. */
    fun setPinned(chat: String, msgId: String, pinned: Boolean, at: Long) {
        tx { db ->
            if (!pinned) db.delete("pins", "chat=? AND msg_id=?", arrayOf(chat, msgId))
            else {
                db.insertWithOnConflict("pins", null, ContentValues().apply { put("chat", chat); put("msg_id", msgId); put("at", at) }, SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("DELETE FROM pins WHERE chat=? AND msg_id NOT IN (SELECT msg_id FROM pins WHERE chat=? ORDER BY at DESC LIMIT 3)", arrayOf(chat, chat))
            }
        }
        changed()
    }

    fun pins(chat: String): List<String> = readableDatabase.rawQuery("SELECT msg_id FROM pins WHERE chat=? ORDER BY at DESC", arrayOf(chat)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun putListOp(opId: String, listId: String, chat: String, op: Int, item: String, text: String, author: String, at: Long) {
        writableDatabase.insertWithOnConflict("list_ops", null, ContentValues().apply {
            put("op_id", opId); put("list_id", listId); put("chat", chat); put("op", op); put("item", item)
            put("text", Vault.encS(text)); put("author", author); put("at", at)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    /** listId -> operations (folded into the list by [com.mura.mesh.core.Checklists]). */
    fun listOpsForChat(chat: String): Map<String, List<com.mura.mesh.core.Checklists.Op>> = readableDatabase.rawQuery(
        "SELECT list_id, op_id, op, item, text, at, author FROM list_ops WHERE chat=?", arrayOf(chat),
    ).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to com.mura.mesh.core.Checklists.Op(c.getString(1), c.getInt(2), c.getString(3), Vault.decS(c.blob(4)), c.getLong(5), c.getString(6))) }
    }.groupBy({ it.first }, { it.second })

    fun setStarred(id: String, on: Boolean) {
        writableDatabase.execSQL("UPDATE messages SET starred=? WHERE id=?", arrayOf<Any>(if (on) 1 else 0, id)); changed()
    }

    fun starred(): List<MessageRow> = readableDatabase.rawQuery(
        "SELECT $MSG_COLS FROM messages WHERE starred=1 AND deleted=0 ORDER BY created DESC", null,
    ).use { c -> buildList { while (c.moveToNext()) add(readMessage(c)) } }

    fun dueScheduled(now: Long): List<MessageRow> = readableDatabase.rawQuery(
        "SELECT $MSG_COLS FROM messages WHERE send_at > 0 AND send_at <= ? AND status=${MsgStatus.SCHEDULED}", arrayOf(now.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(readMessage(c)) } }

    /** A scheduled message is going out now: it moves to "now" in the chat and starts its journey. */
    fun markScheduledSent(id: String, now: Long, expireAt: Long) {
        writableDatabase.execSQL("UPDATE messages SET status=${MsgStatus.WAITING}, send_at=0, created=?, expire_at=? WHERE id=?", arrayOf<Any>(now, expireAt, id))
        changed()
    }

    fun reschedule(id: String, at: Long) {
        writableDatabase.execSQL("UPDATE messages SET send_at=?, created=? WHERE id=? AND status=${MsgStatus.SCHEDULED}", arrayOf<Any>(at, at, id)); changed()
    }

    /**
     * Search message text. Everything is encrypted in the database, so this decrypts in memory,
     * newest first, and stops after [limit] hits. Deleted and view-once messages are never searched.
     */
    fun search(query: String, chat: String? = null, skipChats: Set<String> = emptySet(), limit: Int = 200): List<MessageRow> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val out = ArrayList<MessageRow>()
        val sql = "SELECT $MSG_COLS FROM messages WHERE deleted=0 AND view_once=0 AND kind IN (1,2,3,6,7)" + (if (chat != null) " AND chat=?" else "") + " ORDER BY created DESC"
        readableDatabase.rawQuery(sql, if (chat != null) arrayOf(chat) else null).use { c ->
            while (c.moveToNext() && out.size < limit) {
                if (c.getString(1) in skipChats) continue
                val m = readMessage(c)
                val hay = buildString {
                    append(m.body.lowercase()); append(' ')
                    append(m.meta.optString("caption", "").lowercase()); append(' ')
                    m.meta.optJSONArray("options")?.let { for (i in 0 until it.length()) { append(it.optString(i).lowercase()); append(' ') } }
                    append(m.meta.optString("transcript", "").lowercase())
                }
                if (hay.contains(q)) out.add(m)
            }
        }
        return out
    }

    private fun readMessage(c: Cursor) = MessageRow(
        id = c.getString(0), chat = c.getString(1), sender = c.getString(2), outgoing = c.getInt(3) == 1, kind = c.getInt(4),
        body = Vault.decS(c.blob(5)), fileId = c.str(6), media = c.getInt(7), status = c.getInt(8), viewOnce = c.getInt(9) == 1,
        opened = c.getInt(10) == 1, created = c.getLong(11),
        meta = try { JSONObject(Vault.decS(c.blob(12)).ifBlank { "{}" }) } catch (e: Exception) { JSONObject() },
        deleted = c.getInt(13) == 1, expireAt = c.getLong(14), starred = c.getInt(15) == 1, sendAt = c.getLong(16),
    )

    // ================================================================ files

    fun insertFile(f: FileRow) {
        writableDatabase.insertWithOnConflict("files", null, ContentValues().apply {
            put("file_id", f.fileId); put("msg_id", f.msgId); put("chat", f.chat); put("outgoing", if (f.outgoing) 1 else 0)
            put("name", Vault.encS(f.name)); put("mime", Vault.encS(f.mime)); put("media", f.media); put("size", f.size)
            put("chunk_size", f.chunkSize); put("chunk_count", f.chunkCount); put("sha", f.sha); put("fkey", Vault.enc(f.key))
            put("thumb", Vault.enc(f.thumb)); put("bitmap", f.bitmap); put("received", f.received); put("path", f.path)
            put("state", f.state); put("duration", f.durationMs); put("width", f.width); put("height", f.height); put("expires", f.expires)
            put("compressed", if (f.compressed) 1 else 0); put("original_size", f.originalSize); put("dest", f.dest)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    fun file(fileId: String): FileRow? = readableDatabase.rawQuery("SELECT $FILE_COLS FROM files WHERE file_id=?", arrayOf(fileId)).use {
        if (it.moveToFirst()) readFile(it) else null
    }

    fun filesWhere(where: String, args: Array<String>? = null): List<FileRow> =
        readableDatabase.rawQuery("SELECT $FILE_COLS FROM files WHERE $where", args).use { c -> buildList { while (c.moveToNext()) add(readFile(c)) } }

    fun allFilePaths(): List<String> = readableDatabase.rawQuery("SELECT path FROM files", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun updateFileProgress(fileId: String, bitmap: ByteArray, received: Int, state: Int, notify: Boolean) {
        writableDatabase.update("files", ContentValues().apply { put("bitmap", bitmap); put("received", received); put("state", state) },
            "file_id=?", arrayOf(fileId))
        if (notify) changed()
    }

    fun setFileState(fileId: String, state: Int) {
        writableDatabase.update("files", ContentValues().apply { put("state", state) }, "file_id=?", arrayOf(fileId)); changed()
    }

    fun deleteFileRow(fileId: String) { writableDatabase.delete("files", "file_id=?", arrayOf(fileId)) }

    private fun readFile(c: Cursor) = FileRow(
        fileId = c.getString(0), msgId = c.getString(1), chat = c.getString(2), outgoing = c.getInt(3) == 1, name = Vault.decS(c.blob(4)),
        mime = Vault.decS(c.blob(5)), media = c.getInt(6), size = c.getLong(7), chunkSize = c.getInt(8), chunkCount = c.getInt(9),
        sha = c.getBlob(10), key = Vault.dec(c.blob(11)) ?: ByteArray(32), thumb = Vault.dec(c.blob(12)),
        bitmap = c.blob(13), received = c.getInt(14), path = java.io.File(mediaDir, java.io.File(c.getString(15)).name).absolutePath, state = c.getInt(16),
        durationMs = c.getInt(17), width = c.getInt(18), height = c.getInt(19), expires = c.getLong(20),
        compressed = c.getInt(21) == 1, originalSize = c.getLong(22), dest = c.getBlob(23),
    )

    // ================================================================ statuses

    fun insertStatus(s: StatusRow) {
        writableDatabase.insertWithOnConflict("statuses", null, ContentValues().apply {
            put("id", s.id); put("owner", s.owner); put("kind", s.kind); put("text", Vault.encS(s.text)); put("bg", s.background)
            put("image", Vault.enc(s.image)); put("created", s.created); put("expires", s.expires); put("viewed", if (s.viewed) 1 else 0)
            put("capture", if (s.allowCapture) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    fun statuses(now: Long): List<StatusRow> = readableDatabase.rawQuery(
        "SELECT id, owner, kind, text, bg, image, created, expires, viewed, capture FROM statuses WHERE expires > ? ORDER BY created ASC", arrayOf(now.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(StatusRow(c.getString(0), c.getString(1), c.getInt(2), Vault.decS(c.blob(3)), c.getInt(4),
        Vault.dec(c.blob(5)), c.getLong(6), c.getLong(7), c.getInt(8) == 1, c.getInt(9) == 1)) } }

    fun markStatusViewed(id: String) {
        writableDatabase.update("statuses", ContentValues().apply { put("viewed", 1) }, "id=?", arrayOf(id)); changed()
    }

    fun deleteStatus(id: String) { writableDatabase.delete("statuses", "id=?", arrayOf(id)); changed() }

    // ================================================================ disappearing messages

    /** Messages whose timer ran out (id, fileId). */
    fun expiredMessages(now: Long): List<Pair<String, String?>> = readableDatabase.rawQuery(
        "SELECT id, file_id FROM messages WHERE expire_at > 0 AND expire_at <= ?", arrayOf(now.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.str(1)) } }

    fun nextExpiry(): Long = readableDatabase.rawQuery("SELECT MIN(expire_at) FROM messages WHERE expire_at > 0", null).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
    }

    fun setExpireAt(id: String, at: Long) {
        writableDatabase.execSQL("UPDATE messages SET expire_at=? WHERE id=?", arrayOf<Any>(at, id)); changed()
    }

    // ================================================================ forward secrecy: sessions and keys

    /** Ratchet sessions with a peer, most recently used first. */
    fun sessions(peer: String): List<ByteArray> = readableDatabase.rawQuery(
        "SELECT state FROM sessions WHERE peer=? ORDER BY used DESC", arrayOf(peer),
    ).use { c -> buildList { while (c.moveToNext()) Vault.dec(c.getBlob(0))?.let { add(it) } } }

    fun putSession(peer: String, sid: String, state: ByteArray, used: Long, keep: Int = 5) {
        tx { db ->
            db.insertWithOnConflict("sessions", null, ContentValues().apply {
                put("peer", peer); put("sid", sid); put("state", Vault.enc(state)); put("used", used)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM sessions WHERE peer=? AND sid NOT IN (SELECT sid FROM sessions WHERE peer=? ORDER BY used DESC LIMIT $keep)",
                arrayOf(peer, peer))
        }
    }

    fun deleteSessions(peer: String) { writableDatabase.delete("sessions", "peer=?", arrayOf(peer)) }

    /** After a restore: forget sessions and group chains (they get re-established automatically). */
    fun resetForwardSecrecy() {
        tx { db -> for (t in listOf("sessions", "my_chains", "sk_sent", "sender_keys")) db.delete(t, null, null) }
    }

    class PreKeyRow(val id: Int, val priv: ByteArray, val pub: ByteArray, val sig: ByteArray, val created: Long)

    fun prekeys(): List<PreKeyRow> = readableDatabase.rawQuery("SELECT id, priv, pub, sig, created FROM prekeys ORDER BY id DESC", null).use { c ->
        buildList { while (c.moveToNext()) Vault.dec(c.getBlob(1))?.let { add(PreKeyRow(c.getInt(0), it, c.getBlob(2), c.getBlob(3), c.getLong(4))) } }
    }

    fun putPrekey(id: Int, priv: ByteArray, pub: ByteArray, sig: ByteArray, created: Long) {
        writableDatabase.insertWithOnConflict("prekeys", null, ContentValues().apply {
            put("id", id); put("priv", Vault.enc(priv)); put("pub", pub); put("sig", sig); put("created", created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePrekey(id: Int) { writableDatabase.delete("prekeys", "id=?", arrayOf(id.toString())) }

    fun contactPrekey(pub: String): Pair<Int, ByteArray>? = readableDatabase.rawQuery(
        "SELECT spk_id, spk FROM contacts WHERE pub=?", arrayOf(pub),
    ).use { if (it.moveToFirst() && it.getInt(0) != 0 && !it.isNull(1)) it.getInt(0) to it.getBlob(1) else null }

    fun setContactPrekey(pub: String, id: Int, key: ByteArray) {
        writableDatabase.update("contacts", ContentValues().apply { put("spk_id", id); put("spk", key) }, "pub=?", arrayOf(pub))
    }

    fun myChain(gid: String): Pair<Int, ByteArray>? = readableDatabase.rawQuery("SELECT epoch, state FROM my_chains WHERE gid=?", arrayOf(gid)).use {
        if (it.moveToFirst()) Vault.dec(it.getBlob(1))?.let { s -> it.getInt(0) to s } else null
    }

    fun putMyChain(gid: String, epoch: Int, state: ByteArray) {
        writableDatabase.insertWithOnConflict("my_chains", null, ContentValues().apply {
            put("gid", gid); put("epoch", epoch); put("state", Vault.enc(state))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun senderKeySentTo(gid: String, keyId: Int): Set<String> = readableDatabase.rawQuery(
        "SELECT pub FROM sk_sent WHERE gid=? AND key_id=?", arrayOf(gid, keyId.toString()),
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    fun markSenderKeySent(gid: String, keyId: Int, pubs: Collection<String>) {
        tx { db ->
            db.delete("sk_sent", "gid=? AND key_id<>?", arrayOf(gid, keyId.toString()))
            for (p in pubs) db.insertWithOnConflict("sk_sent", null, ContentValues().apply { put("gid", gid); put("key_id", keyId); put("pub", p) },
                SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun senderChain(gid: String, pub: String, keyId: Int): ByteArray? = readableDatabase.rawQuery(
        "SELECT state FROM sender_keys WHERE gid=? AND pub=? AND key_id=?", arrayOf(gid, pub, keyId.toString()),
    ).use { if (it.moveToFirst()) Vault.dec(it.getBlob(0)) else null }

    fun putSenderChain(gid: String, pub: String, keyId: Int, state: ByteArray, now: Long, onlyIfNew: Boolean = false) {
        tx { db ->
            db.insertWithOnConflict("sender_keys", null, ContentValues().apply {
                put("gid", gid); put("pub", pub); put("key_id", keyId); put("state", Vault.enc(state)); put("updated", now)
            }, if (onlyIfNew) SQLiteDatabase.CONFLICT_IGNORE else SQLiteDatabase.CONFLICT_REPLACE)
            // Keep the 3 newest chains per sender (late messages from an older chain still open).
            db.execSQL("DELETE FROM sender_keys WHERE gid=? AND pub=? AND key_id NOT IN " +
                "(SELECT key_id FROM sender_keys WHERE gid=? AND pub=? ORDER BY updated DESC LIMIT 3)", arrayOf(gid, pub, gid, pub))
        }
    }

    fun senderKeyCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sender_keys", null).use { it.moveToFirst(); it.getInt(0) }

    /** Sender keys for groups this phone hasn't joined (yet): kept apart so strangers can't crowd out real groups. */
    fun unknownGroupSenderKeyCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM sender_keys WHERE gid NOT IN (SELECT id FROM groups)", null).use { it.moveToFirst(); it.getInt(0) }

    // ================================================================ BundleStore (relay storage)

    val relayBudgetBytes: Long get() = getSetting("relay_mb", "200").toLongOrNull()?.times(1024 * 1024) ?: (200L * 1024 * 1024)

    fun relayBytesUsed(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(size),0) FROM bundles WHERE mine=0", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    /** Newest message bundle per destination (hex) — what the internet link has to deliver. */
    fun pendingByDest(now: Long): Map<String, Long> = readableDatabase.rawQuery(
        "SELECT dest_hex, MAX(created) FROM bundles WHERE expires > ? AND type=${Bundle.TYPE_MSG} GROUP BY dest_hex", arrayOf(now.toString()),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }

    /** My own 1:1 messages still waiting for delivery, created before [before]: candidates for friend mailboxes. */
    fun undeliveredOwn(now: Long, before: Long): Map<String, Long> = readableDatabase.rawQuery(
        "SELECT id, created FROM bundles WHERE mine=1 AND type=${Bundle.TYPE_MSG} AND expires > ? AND created < ? LIMIT 500",
        arrayOf(now.toString(), before.toString()),
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) } }

    /** Newest sealed message this phone is carrying for someone else (0 = none). */
    fun newestCarried(now: Long): Long = readableDatabase.rawQuery(
        "SELECT MAX(created) FROM bundles WHERE mine=0 AND expires > ?", arrayOf(now.toString()),
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    /** Groups (ids) someone is a member of. */
    fun groupsOf(pub: String): List<String> = readableDatabase.rawQuery("SELECT gid FROM group_members WHERE pub=?", arrayOf(pub)).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun relayCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM bundles WHERE mine=0", null).use { it.moveToFirst(); it.getInt(0) }

    override fun has(id: ByteArray): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bundles WHERE id=?", arrayOf(id.toHex())).use { it.moveToFirst() }

    override fun put(bundle: Bundle, mine: Boolean): Boolean {
        if (!mine && !canStore(bundle.payload.size, if (bundle.type == Bundle.TYPE_CHUNK) bundle.group else null)) return false
        val r = writableDatabase.insertWithOnConflict("bundles", null, ContentValues().apply {
            put("id", bundle.id.toHex()); put("grp", bundle.group.toHex()); put("dest", bundle.dest); put("dest_hex", bundle.dest.toHex())
            put("type", bundle.type); put("seq", bundle.seq); put("size", bundle.payload.size); put("expires", bundle.expiresAt)
            put("hops", bundle.hops); put("mine", if (mine) 1 else 0); put("data", bundle.payload); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return r != -1L
    }

    private fun readBundle(c: Cursor) =
        Bundle(c.getString(0).hexToBytes(), c.getInt(1), c.getBlob(2), c.getString(3).hexToBytes(), c.getInt(4), c.getLong(5), c.getInt(6), c.getBlob(7))

    override fun get(id: ByteArray): Bundle? = readableDatabase.rawQuery(
        "SELECT id, type, dest, grp, seq, expires, hops, data FROM bundles WHERE id=?", arrayOf(id.toHex()),
    ).use { c -> if (!c.moveToFirst()) null else readBundle(c) }

    /** Stored group/status messages for an address (used to read them once the key arrives). */
    fun bundlesForDest(destHex: String): List<Bundle> = readableDatabase.rawQuery(
        "SELECT id, type, dest, grp, seq, expires, hops, data FROM bundles WHERE dest_hex=? AND type=${Bundle.TYPE_MSG}", arrayOf(destHex),
    ).use { c -> buildList { while (c.moveToNext()) add(readBundle(c)) } }

    override fun delete(id: ByteArray) { writableDatabase.delete("bundles", "id=?", arrayOf(id.toHex())) }

    override fun deleteRelayedByIdOrGroup(id: ByteArray) {
        val h = id.toHex()
        writableDatabase.delete("bundles", "mine=0 AND (id=? OR grp=?)", arrayOf(h, h))
    }

    override fun metas(now: Long): List<BundleMeta> = readableDatabase.rawQuery(
        "SELECT id, type, dest, grp, seq, size, expires, hops FROM bundles WHERE expires > ?", arrayOf(now.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(BundleMeta(c.getString(0).hexToBytes(), c.getInt(1), c.getBlob(2), c.getString(3).hexToBytes(),
                c.getInt(4), c.getInt(5), c.getLong(6), c.getInt(7)))
        }
    }

    override fun canStore(size: Int, group: ByteArray?): Boolean {
        if (relayBytesUsed() + size > relayBudgetBytes) return false
        if (group != null) {
            val used = readableDatabase.rawQuery("SELECT COALESCE(SUM(size),0) FROM bundles WHERE mine=0 AND grp=?", arrayOf(group.toHex())).use {
                it.moveToFirst(); it.getLong(0)
            }
            if (used + size > MAX_PER_FILE) return false
        }
        return true
    }

    override fun isTombstoned(id: ByteArray): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM tombstones WHERE id=?", arrayOf(id.toHex())).use { it.moveToFirst() }

    override fun addTombstone(id: ByteArray, now: Long) {
        writableDatabase.insertWithOnConflict("tombstones", null, ContentValues().apply { put("id", id.toHex()); put("at", now) },
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun recentTombstones(limit: Int): List<ByteArray> = readableDatabase.rawQuery(
        "SELECT id FROM tombstones ORDER BY at DESC LIMIT $limit", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0).hexToBytes()) } }

    override fun markSeen(id: ByteArray) {
        writableDatabase.insertWithOnConflict("seen", null, ContentValues().apply { put("id", id.toHex()); put("at", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun isSeen(id: ByteArray): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM seen WHERE id=?", arrayOf(id.toHex())).use { it.moveToFirst() }

    fun markProcessed(id: String) {
        writableDatabase.insertWithOnConflict("processed", null, ContentValues().apply { put("id", id); put("at", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun isProcessed(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM processed WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    /** Housekeeping. Returns ids of my own bundles that expired undelivered. */
    fun purge(now: Long): List<String> {
        val db = writableDatabase
        val expiredMine = db.rawQuery("SELECT id FROM bundles WHERE mine=1 AND expires <= ?", arrayOf(now.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        db.delete("bundles", "expires <= ?", arrayOf(now.toString()))
        val old = (now - 14L * 24 * 3600 * 1000).toString()
        db.delete("tombstones", "at < ?", arrayOf(old))
        db.delete("seen", "at < ?", arrayOf(old))
        db.delete("processed", "at < ?", arrayOf(old))
        db.delete("statuses", "expires <= ?", arrayOf(now.toString()))
        db.delete("sender_keys", "updated < ?", arrayOf((now - 30L * 24 * 3600 * 1000).toString()))
        // Keys for groups never joined only wait a few days for the invitation.
        db.delete("sender_keys", "gid NOT IN (SELECT id FROM groups) AND updated < ?", arrayOf((now - 3L * 24 * 3600 * 1000).toString()))
        return expiredMine
    }

    companion object {
        const val MAX_PER_FILE = 64L * 1024 * 1024

        private const val MSG_COLS = "id, chat, sender, outgoing, kind, body, file_id, media, status, view_once, opened, created, meta, deleted, expire_at, starred, send_at"
        private const val FILE_COLS = "file_id, msg_id, chat, outgoing, name, mime, media, size, chunk_size, chunk_count, sha, fkey, thumb, " +
            "bitmap, received, path, state, duration, width, height, expires, compressed, original_size, dest"
    }
}
