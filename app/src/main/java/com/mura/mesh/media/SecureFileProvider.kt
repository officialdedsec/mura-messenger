package com.mura.mesh.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.mura.mesh.MuraApp
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.toHex
import com.mura.mesh.data.FileRow
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens a sealed file for viewing (in Mura's PDF viewer or another app the user picks)
 * WITHOUT ever writing a decrypted copy to storage: Android's proxy file descriptors let us
 * decrypt each requested byte range on the fly. Links expire after 10 minutes.
 */
class SecureFileProvider : ContentProvider() {

    private class Grant(val fileId: String, val name: String, val mime: String, val expires: Long, val created: Long = System.currentTimeMillis())

    companion object {
        private val grants = ConcurrentHashMap<String, Grant>()
        private val thread by lazy { HandlerThread("mura-secure-io").apply { start() } }

        fun uriFor(ctx: Context, f: FileRow): Uri {
            val token = Crypto.randomBytes(12).toHex()
            grants[token] = Grant(f.fileId, f.name, f.mime, System.currentTimeMillis() + 10 * 60_000)
            return Uri.parse("content://${ctx.packageName}.secure/$token/${Uri.encode(f.name)}")
        }

        /**
         * Leaving Mura revokes open-file links — except ones made in the last minute: picking an app in Android's
         * "Open with" chooser sends Mura to the background, and the chosen app still needs its link.
         */
        fun revokeAll() {
            val cutoff = System.currentTimeMillis() - 60_000
            grants.entries.removeIf { it.value.created < cutoff }
        }

        /** Seekable, read-only descriptor that decrypts on demand. */
        fun open(ctx: Context, f: FileRow): ParcelFileDescriptor {
            val sm = ctx.getSystemService(StorageManager::class.java)
            val callback: ProxyFileDescriptorCallback = if (f.compressed) {
                val bytes = SecureMedia.readBytes(f)
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = bytes.size.toLong()
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        if (offset >= bytes.size) return 0
                        val n = minOf(size.toLong(), bytes.size - offset).toInt()
                        System.arraycopy(bytes, offset.toInt(), data, 0, n)
                        return n
                    }
                    override fun onRelease() { bytes.fill(0) }
                }
            } else {
                val reader = SecureMedia.reader(f)
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = reader.size
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        return try { val n = reader.read(offset, data, 0, size); if (n < 0) 0 else n }
                        catch (e: Exception) { throw ErrnoException("read", OsConstants.EIO) }
                    }
                    override fun onRelease() { reader.close() }
                }
            }
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
        }
    }

    private fun grant(uri: Uri): Grant? {
        val token = uri.pathSegments.firstOrNull() ?: return null
        val g = grants[token] ?: return null
        if (g.expires < System.currentTimeMillis()) { grants.remove(token); return null }
        return g
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = grant(uri)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val g = grant(uri) ?: return null
        val f = MuraApp.engine?.db?.file(g.fileId) ?: return null
        val cols = projection?.toList()?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map<String, Any?> { when (it) { OpenableColumns.DISPLAY_NAME -> g.name; OpenableColumns.SIZE -> f.originalSize; else -> null } }.toTypedArray())
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val g = grant(uri) ?: throw java.io.FileNotFoundException("expired")
        val f = MuraApp.engine?.db?.file(g.fileId) ?: throw java.io.FileNotFoundException("gone")
        return open(context!!, f)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
