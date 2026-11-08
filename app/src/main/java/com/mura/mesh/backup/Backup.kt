package com.mura.mesh.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mura.mesh.MuraApp
import com.mura.mesh.R
import com.mura.mesh.core.BackupArchive
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.EncryptedRecordReader
import com.mura.mesh.core.EncryptedRecordWriter
import com.mura.mesh.core.Payload
import com.mura.mesh.data.Db
import com.mura.mesh.data.Vault
import com.mura.mesh.identity.IdentityStore
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.service.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Encrypted backup files — no Google, no cloud account, no server.
 *
 * Everything is encrypted on the phone with a key derived from the user's backup password
 * (PBKDF2-SHA256, 310,000 rounds, random salt) and a fresh random key per backup file (AES-256-GCM,
 * authenticated, records chained so a cut or reordered file is rejected). The file goes to a folder the
 * user picks (phone storage, SD card, USB drive or any cloud app's folder); it's a meaningless blob to
 * anyone else and opens only in Mura, with the right password.
 */
object BackupManager {
    const val ITERATIONS = 310_000
    const val KEY_SECRET = "backup_key"
    const val SALT = "backup_salt"
    const val FREQUENCY = "backup_frequency"      // off | daily | weekly | monthly | quarterly | yearly
    /** The folder (Storage Access Framework tree) automatic backups are written to. */
    const val FOLDER = "backup_folder"
    const val INCLUDE_VIDEOS = "backup_videos"
    const val LAST_TIME = "backup_last_time"
    const val LAST_SIZE = "backup_last_size"
    const val EXT = ".mura"
    private const val PREFIX = "Mura-backup-"
    private const val KEEP = 3

    data class Status(val running: Boolean = false, val phase: String = "", val progress: Float = 0f, val error: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    fun report(s: Status) { _status.value = s }

    fun hasPassword(db: Db) = db.getSecret(KEY_SECRET) != null

    /** Set or change the backup password. The derived key is kept (encrypted) so scheduled backups can run unattended. */
    fun setPassword(db: Db, password: CharArray) {
        val salt = Crypto.randomBytes(16)
        val key = Crypto.passwordKey(password, salt, ITERATIONS)
        db.setSetting(SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
        db.setSecret(KEY_SECRET, key)
        password.fill(' ')
    }

    /** Write the encrypted archive to a temp file. */
    fun createArchive(ctx: Context, onProgress: (Float) -> Unit): File {
        val app = MuraApp.instance
        val engine = MuraApp.engine ?: throw IllegalStateException("not set up")
        val db = app.db
        val key = db.getSecret(KEY_SECRET) ?: throw IllegalStateException("Set a backup password first")
        val salt = android.util.Base64.decode(db.getSetting(SALT, ""), android.util.Base64.NO_WRAP)
        val includeVideos = db.getBool(INCLUDE_VIDEOS, false)
        db.checkpoint()
        // Back up a copy without the forward-secrecy keys: a backup must never be able to decrypt
        // messages captured off the air. Sessions restart automatically after a restore.
        val dbFile = File(SecureMedia.tempDir(ctx), "backup.db")
        val out = File(SecureMedia.tempDir(ctx), "backup.bin")
        try {
        ctx.getDatabasePath("mura2.db").copyTo(dbFile, overwrite = true)
        android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { copy ->
            for (t in listOf("sessions", "my_chains", "sk_sent", "sender_keys")) {
                try { copy.delete(t, null, null) } catch (_: Exception) {}
            }
            try { copy.delete("bundles", "mine=0", null) } catch (_: Exception) {}   // other people's sealed mail isn't ours to back up
            copy.execSQL("VACUUM")
        }
        val videoPaths = db.filesWhere("media=${Payload.MEDIA_VIDEO}").map { it.path }.toSet()
        val media = SecureMedia.mediaDir(ctx).listFiles()?.filter { it.isFile && (includeVideos || it.absolutePath !in videoPaths) } ?: emptyList()
        val total = (dbFile.length() + media.sumOf { it.length() }).coerceAtLeast(1)
        var done = 0L
        out.outputStream().buffered(256 * 1024).use { raw ->
            val header = BackupArchive.Header(salt, ITERATIONS, System.currentTimeMillis())   // fresh random file nonce
            header.write(raw)
            val enc = EncryptedRecordWriter(raw, header.recordKey(key))      // a different key for every backup
            val meta = JSONObject().put("v", 1).put("name", engine.identity.name).put("created", System.currentTimeMillis()).toString().toByteArray()
            BackupArchive.writeEntry(enc, "meta", meta.size.toLong(), meta.inputStream())
            BackupArchive.writeEntry(enc, "identity", 32, engine.identity.privateKey.inputStream())
            BackupArchive.writeEntry(enc, "datakey", 32, Vault.dataKey.inputStream())
            dbFile.inputStream().use { BackupArchive.writeEntry(enc, "db", dbFile.length(), it) }
            done += dbFile.length(); onProgress(done.toFloat() / total)
            for (f in media) {
                f.inputStream().use { BackupArchive.writeEntry(enc, "media/" + f.name, f.length(), it) }
                done += f.length(); onProgress(done.toFloat() / total)
            }
            BackupArchive.writeEnd(enc)
            enc.close()
        }
        return out
        } catch (e: Exception) {
            out.delete(); throw e
        } finally {
            dbFile.delete()   // the database copy never outlives the backup, even if it fails
        }
    }

    // ---------------------------------------------------------------- files the user can see and move

    private fun fileName() = PREFIX + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date()) + EXT

    /** Encrypts a fresh backup and writes it into the chosen folder; keeps the newest few Mura backups there. Returns its size. */
    fun backupToFolder(ctx: Context, onProgress: (Float) -> Unit): Long {
        val db = MuraApp.instance.db
        val tree = db.getSetting(FOLDER, "").takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) }
            ?: throw IllegalStateException("Choose a backup folder first (Settings › Chat backup)")
        val temp = createArchive(ctx, onProgress)
        try {
            val cr = ctx.contentResolver
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val doc = try { android.provider.DocumentsContract.createDocument(cr, parent, "application/octet-stream", fileName()) }
                catch (e: Exception) { null } ?: throw IllegalStateException("Can't write to the backup folder — choose it again in Settings › Chat backup")
            (cr.openOutputStream(doc, "w") ?: throw IllegalStateException("Can't write to the backup folder")).use { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
            pruneOld(ctx, tree)
            return temp.length()
        } finally { temp.delete() }
    }

    /** Older Mura backups in the folder beyond the newest [KEEP] are deleted (other files are never touched). */
    private fun pruneOld(ctx: Context, tree: android.net.Uri) {
        try {
            val cr = ctx.contentResolver
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val ours = ArrayList<Pair<String, String>>()   // (name, documentId)
            cr.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { c ->
                while (c.moveToNext()) { val n = c.getString(0) ?: continue; if (n.startsWith(PREFIX) && n.endsWith(EXT)) ours.add(n to c.getString(1)) }
            }
            for ((_, id) in ours.sortedByDescending { it.first }.drop(KEEP)) {
                try { android.provider.DocumentsContract.deleteDocument(cr, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** "Save a copy…": an encrypted backup written to a file the user picked. */
    fun exportTo(ctx: Context, target: android.net.Uri, onProgress: (Float) -> Unit): Long {
        val temp = createArchive(ctx, onProgress)
        try {
            (ctx.contentResolver.openOutputStream(target, "w") ?: throw IllegalStateException("Can't write there"))
                .use { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
            return temp.length()
        } finally { temp.delete() }
    }

    /** Copies the backup file the user picked into Mura's private storage (it stays encrypted) so it can be restored. */
    fun importFrom(ctx: Context, source: android.net.Uri): File {
        val dest = File(SecureMedia.tempDir(ctx), "restore.bin")
        (ctx.contentResolver.openInputStream(source) ?: throw IllegalStateException("Can't open that file")).use { input ->
            dest.outputStream().use { input.copyTo(it, 256 * 1024) }
        }
        // Quick check that it's a Mura backup before asking for the password.
        try { dest.inputStream().use { BackupArchive.Header.read(it) } } catch (e: Exception) { dest.delete(); throw IllegalStateException("That isn't a Mura backup file") }
        return dest
    }

    /** Returns the display name stored in the backup, after checking the password. */
    fun peekName(file: File, password: CharArray): String {
        file.inputStream().buffered().use { raw ->
            val h = BackupArchive.Header.read(raw)
            val key = h.recordKey(Crypto.passwordKey(password, h.salt, h.iterations))
            var name = ""
            try {
                BackupArchive.read(EncryptedRecordReader(raw, key)) { n, _, data ->
                    if (n == "meta") { name = JSONObject(String(data.readBytes())).optString("name"); throw StopRead() }
                }
            } catch (_: StopRead) {}
            return name
        }
    }

    private class StopRead : RuntimeException()

    /** Restore everything from an archive onto this phone (used from the welcome screen). */
    fun restore(ctx: Context, file: File, password: CharArray, onProgress: (Float) -> Unit) {
        val app = MuraApp.instance
        val total = file.length().coerceAtLeast(1)
        var identity: ByteArray? = null
        var dataKey: ByteArray? = null
        var name = ""
        val staging = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val counting = object : java.io.FilterInputStream(file.inputStream().buffered(256 * 1024)) {
            var n = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) { n += r; onProgress(n.toFloat() / total) }; return r }
        }
        counting.use { raw ->
            val h = BackupArchive.Header.read(raw)
            val key = h.recordKey(Crypto.passwordKey(password, h.salt, h.iterations))
            BackupArchive.read(EncryptedRecordReader(raw, key)) { n, _, data ->
                when {
                    n == "meta" -> name = JSONObject(String(data.readBytes())).optString("name")
                    n == "identity" -> identity = data.readBytes()
                    n == "datakey" -> dataKey = data.readBytes()
                    n == "db" -> File(staging, "db").outputStream().use { data.copyTo(it) }
                    n.startsWith("media/") -> {
                        val safe = n.removePrefix("media/").replace("/", "").replace("..", "")
                        File(staging, "m_$safe").outputStream().use { data.copyTo(it) }
                    }
                }
            }
        }
        val id = identity ?: throw IllegalStateException("Backup has no identity")
        val dk = dataKey ?: throw IllegalStateException("Backup has no data key")
        // Everything decrypted correctly: now replace local data.
        app.resetForRestore()
        app.db.close()
        val dbPath = ctx.getDatabasePath("mura2.db")
        listOf(dbPath, File(dbPath.path + "-wal"), File(dbPath.path + "-shm")).forEach { it.delete() }
        File(staging, "db").copyTo(dbPath, overwrite = true)
        val mediaDir = SecureMedia.mediaDir(ctx)
        staging.listFiles()?.filter { it.name.startsWith("m_") }?.forEach { it.copyTo(File(mediaDir, it.name.removePrefix("m_")), overwrite = true) }
        staging.deleteRecursively()
        file.delete()
        Vault.install(ctx, dk)
        val identityObj = IdentityStore.create(ctx, name.ifBlank { "Me" }, id)
        // Ratchet sessions and group chains in the backup are out of date (and shouldn't live in a backup):
        // drop them and start fresh sessions with every contact.
        app.db.resetForwardSecrecy()
        // Media paths in the database are absolute; this phone's are the same app-private location.
        app.initEngine(identityObj).resyncSessions()
        id.fill(0)
        password.fill(' ')
    }
}

/** Runs scheduled (and "back up now") backups with WorkManager. Local only: no network is used. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = MuraApp.instance
        val db = app.db
        if (MuraApp.engine == null || !BackupManager.hasPassword(db)) return Result.success()
        try { setForeground(foregroundInfo()) } catch (_: Exception) { /* fine: small backups finish quickly anyway */ }
        if (db.getSetting(BackupManager.FOLDER, "").isEmpty()) {
            val msg = "Choose a backup folder in Settings › Chat backup"
            BackupManager.report(BackupManager.Status(false, error = msg))
            return Result.success()
        }
        return try {
            BackupManager.report(BackupManager.Status(true, "Encrypting chats", 0f))
            val size = BackupManager.backupToFolder(applicationContext) { BackupManager.report(BackupManager.Status(true, "Encrypting chats", it)) }
            db.setSetting(BackupManager.LAST_TIME, System.currentTimeMillis().toString())
            db.setSetting(BackupManager.LAST_SIZE, size.toString())
            BackupManager.report(BackupManager.Status(false, "Backed up"))
            Result.success()
        } catch (e: Exception) {
            val msg = e.message ?: "Backup failed"
            BackupManager.report(BackupManager.Status(false, error = msg))
            if (msg.contains("folder")) { MuraApp.engine?.notifier?.info(77, "Chat backup paused", msg); Result.success() }
            else if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifier.CH_MESH)
            .setSmallIcon(R.drawable.ic_stat_mura)
            .setContentTitle("Backing up chats")
            .setContentText("Encrypted with your backup password")
            .setOngoing(true).setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(42, n)
    }
}

object BackupScheduler {
    private const val PERIODIC = "mura-backup"
    private const val NOW = "mura-backup-now"

    fun days(freq: String): Long? = when (freq) {
        "daily" -> 1; "weekly" -> 7; "monthly" -> 30; "quarterly" -> 91; "yearly" -> 365; else -> null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun constraints(db: Db) = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    fun ensureScheduled(ctx: Context) {
        val db = MuraApp.instance.db
        val d = days(db.getSetting(BackupManager.FREQUENCY, "off"))
        val wm = WorkManager.getInstance(ctx)
        if (d == null || !BackupManager.hasPassword(db) || db.getSetting(BackupManager.FOLDER, "").isEmpty()) { wm.cancelUniqueWork(PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(d, TimeUnit.DAYS).setConstraints(constraints(db)).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun backupNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>().build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }
}
