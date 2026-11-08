package com.mura.mesh.media

import com.mura.mesh.core.Metadata
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.mura.mesh.core.Compression
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.Payload
import com.mura.mesh.core.SealedFile
import com.mura.mesh.core.SealedReader
import com.mura.mesh.core.toHex
import com.mura.mesh.data.FileRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterInputStream
import kotlin.coroutines.resume

/** A file that is ready to send: already compressed and sealed on disk. */
class Prepared(
    val sealed: File, val fileId: ByteArray, val key: ByteArray, val size: Long, val sha: ByteArray,
    val media: Int, val name: String, val mime: String, val durationMs: Int, val width: Int, val height: Int,
    val thumb: ByteArray, val compressed: Boolean, val originalSize: Long,
)

enum class PhotoQuality(val maxSide: Int, val quality: Int) { STANDARD(1600, 72), HD(2560, 88) }
enum class VideoQuality(val height: Int, val bitrate: Int) { DATA_SAVER(480, 600_000), STANDARD(720, 1_200_000), HD(1080, 2_500_000) }

/**
 * Compression + encryption for everything Mura stores or sends.
 *
 *  • Photos   → resized and re-encoded as WebP (typically 5–15× smaller than a camera JPEG).
 *  • Videos   → re-encoded to H.264 at 480/720/1080p with a low bitrate (often 10–30× smaller).
 *  • Voice    → Opus 16 kbit/s (Android 10+) or AAC 24 kbit/s (Android 9): ~2–3 KB per second.
 *  • Documents and other files → lossless deflate when it helps; the receiver gets the exact original bytes.
 *
 * Photos and videos are lossy: smaller files are re-encoded, so the original camera quality isn't
 * restored on arrival. The "HD" setting keeps more detail at the cost of size. Documents are bit-exact.
 */
object SecureMedia {
    const val MAX_SEND_BYTES = 200L * 1024 * 1024

    fun mediaDir(ctx: Context) = File(ctx.filesDir, "media").apply { mkdirs() }
    fun tempDir(ctx: Context) = File(ctx.cacheDir, "work").apply { mkdirs() }
    private fun newSealed(ctx: Context, fileId: ByteArray) = File(mediaDir(ctx), "s_" + fileId.toHex())

    fun displayName(ctx: Context, uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) c.getString(i)?.let { return it } }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun sizeOf(ctx: Context, uri: Uri): Long {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !c.isNull(i)) return c.getLong(i) }
        }
        return -1
    }

    @Suppress("DEPRECATION")
    private fun webp(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bmp.compress(fmt, quality, out)
        return out.toByteArray()
    }

    /** Tiny blurred-preview image sent inside the encrypted message (about 1 KB). */
    fun thumbnail(src: Bitmap): ByteArray {
        val w = 48
        val h = (src.height * w / src.width.coerceAtLeast(1)).coerceIn(8, 96)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val r = webp(small, 50)
        if (small !== src) small.recycle()
        return r
    }

    private fun seal(ctx: Context, plain: ByteArray): Triple<File, ByteArray, Pair<ByteArray, Pair<Long, ByteArray>>> {
        val fileId = Crypto.randomBytes(16); val key = Crypto.randomBytes(32)
        val f = newSealed(ctx, fileId)
        return Triple(f, fileId, key to SealedFile.seal(plain, f, key, fileId))
    }

    private fun sealStream(ctx: Context, input: InputStream): Triple<File, ByteArray, Pair<ByteArray, Pair<Long, ByteArray>>> {
        val fileId = Crypto.randomBytes(16); val key = Crypto.randomBytes(32)
        val f = newSealed(ctx, fileId)
        return Triple(f, fileId, key to SealedFile.seal(input, f, key, fileId))
    }

    fun decodeBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val scale = maxSide.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }

    fun prepareImage(ctx: Context, uri: Uri, q: PhotoQuality): Prepared {
        val bmp = decodeBitmap(ctx, uri, q.maxSide)
        val bytes = webp(bmp, q.quality)
        val thumb = thumbnail(bmp)
        val (f, id, rest) = seal(ctx, bytes)
        val (key, sz) = rest
        val p = Prepared(f, id, key, sz.first, sz.second, Payload.MEDIA_IMAGE, "photo.webp", "image/webp", 0, bmp.width, bmp.height, thumb, false, sz.first)
        bmp.recycle()
        return p
    }

    /** Small square profile photo / status image, kept inline in messages. */
    fun smallImage(ctx: Context, uri: Uri, maxSide: Int, quality: Int, square: Boolean): ByteArray {
        var bmp = decodeBitmap(ctx, uri, if (square) maxSide * 2 else maxSide)
        if (square) {
            val s = minOf(bmp.width, bmp.height)
            val cropped = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
            val scaled = Bitmap.createScaledBitmap(cropped, maxSide, maxSide, true)
            if (cropped !== bmp) bmp.recycle()
            bmp = scaled
        }
        return webp(bmp, quality).also { bmp.recycle() }
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcode(ctx: Context, uri: Uri, out: File, q: VideoQuality): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(q.height))))
                .build()
            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(q.bitrate).build())
                .build()
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            cont.invokeOnCancellation { try { transformer.cancel() } catch (_: Exception) {} }
            try { transformer.start(item, out.absolutePath) } catch (e: Exception) { if (cont.isActive) cont.resume(false) }
        }
    }

    /**
     * [strip]: blank the video's hidden details (location, dates, phone make/model) — see [Metadata].
     * Photos never need this: they're always re-encoded, which leaves no metadata at all.
     */
    suspend fun prepareVideo(ctx: Context, uri: Uri, q: VideoQuality, strip: Boolean = true): Prepared {
        val original = sizeOf(ctx, uri)
        val tmp = File(tempDir(ctx), "v_" + Crypto.randomBytes(6).toHex() + ".mp4")
        val raw = File(tempDir(ctx), "r_" + Crypto.randomBytes(6).toHex())
        try {
            val ok = transcode(ctx, uri, tmp, q) && tmp.length() > 0 && (original <= 0 || tmp.length() < original)
            if (ok && strip) withContext(Dispatchers.IO) { Metadata.scrubMp4(tmp) }
            // Sending the original (compression didn't help): scrub a private copy, never the user's file.
            val useRaw = !ok && strip && withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { i -> raw.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                raw.length() > 0 && Metadata.isMp4Family(raw.inputStream().use { val h = ByteArray(12); it.read(h); h }) &&
                    run { Metadata.scrubMp4(raw); true }
            }
            val sourceSize = if (ok) tmp.length() else original
            if (sourceSize > MAX_SEND_BYTES) throw IllegalArgumentException("Videos up to 200 MB (after compression) can be sent")
            return withContext(Dispatchers.IO) {
                val mmr = MediaMetadataRetriever()
                var dur = 0; var w = 0; var h = 0; var thumb = ByteArray(0)
                try {
                    if (ok) mmr.setDataSource(tmp.absolutePath) else if (useRaw) mmr.setDataSource(raw.absolutePath) else mmr.setDataSource(ctx, uri)
                    dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                    w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    mmr.getFrameAtTime(0)?.let { thumb = thumbnail(it); it.recycle() }
                } catch (_: Exception) {
                } finally { try { mmr.release() } catch (_: Exception) {} }
                val input = if (ok) tmp.inputStream() else if (useRaw) raw.inputStream() else (ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("can't open video"))
                val (f, id, rest) = input.use { sealStream(ctx, it) }
                val (key, sz) = rest
                Prepared(f, id, key, sz.first, sz.second, Payload.MEDIA_VIDEO, displayName(ctx, uri).substringBeforeLast('.') + ".mp4",
                    if (ok) "video/mp4" else (ctx.contentResolver.getType(uri) ?: "video/mp4"), dur, w, h, thumb, false, sz.first)
            }
        } finally { tmp.delete(); raw.delete() }
    }

    /** Does this file carry the kind of hidden details [Metadata] can remove (photo or video)? */
    fun hasStrippableMetadata(ctx: Context, uri: Uri): Boolean {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    /** Documents (PDF, Word, …) and any other file: lossless deflate when it helps. Photos/videos sent as files can be scrubbed first. */
    fun prepareDocument(ctx: Context, uri: Uri, strip: Boolean = true): Prepared {
        val size = sizeOf(ctx, uri)
        if (size > MAX_SEND_BYTES) throw IllegalArgumentException("Files up to 200 MB can be sent over Bluetooth")
        if (strip && hasStrippableMetadata(ctx, uri)) {
            val clean = File(tempDir(ctx), "d_" + Crypto.randomBytes(6).toHex())
            try {
                ctx.contentResolver.openInputStream(uri)?.use { i -> clean.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                    ?: throw IllegalStateException("can't open file")
                val head = clean.inputStream().use { val h = ByteArray(16); val n = it.read(h); h.copyOf(maxOf(n, 0)) }
                when {
                    Metadata.isMp4Family(head) -> Metadata.scrubMp4(clean)
                    clean.length() <= 64L * 1024 * 1024 -> {
                        val b = clean.readBytes()
                        val s = Metadata.stripImage(b)
                        if (s !== b) clean.writeBytes(s)
                    }
                }
                return prepareFrom(ctx, { clean.inputStream() }, displayName(ctx, uri), ctx.contentResolver.getType(uri) ?: "application/octet-stream")
            } finally { clean.delete() }
        }
        return prepareFrom(ctx, { ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("can't open file") },
            displayName(ctx, uri), ctx.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    private fun prepareFrom(ctx: Context, open: () -> InputStream, name: String, mime: String): Prepared {
        val sample = open().use { s ->
            val b = ByteArray(256 * 1024); var n = 0
            while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
            b.copyOf(n)
        }
        val compress = Compression.worthCompressing(sample)
        val raw = open()
        val counter = CountingStream(raw)
        val input: InputStream = if (compress) DeflaterInputStream(counter, Deflater(Deflater.BEST_COMPRESSION, true), 64 * 1024) else counter
        val (f, id, rest) = input.use { sealStream(ctx, it) }
        val (key, sz) = rest
        return Prepared(f, id, key, sz.first, sz.second, Payload.MEDIA_FILE, name, mime, 0, 0, 0, ByteArray(0), compress, counter.count)
    }

    /** Seal a file we produced ourselves (voice note, call recording), then delete the plain temp file. */
    fun sealLocal(ctx: Context, plain: File, media: Int, name: String, mime: String, durationMs: Int): Prepared {
        try {
            // Voice notes and recordings are our own files: always blank the container's metadata (device, dates).
            try { if (Metadata.isMp4Family(plain.inputStream().use { val h = ByteArray(12); it.read(h); h })) Metadata.scrubMp4(plain) } catch (_: Exception) {}
            val (f, id, rest) = plain.inputStream().use { sealStream(ctx, it) }
            val (key, sz) = rest
            return Prepared(f, id, key, sz.first, sz.second, media, name, mime, durationMs, 0, 0, ByteArray(0), false, sz.first)
        } finally { plain.delete() }
    }

    /** Seals bytes that only ever existed in memory (call recordings): nothing readable touches storage. */
    fun sealLocalBytes(ctx: Context, plain: ByteArray, media: Int, name: String, mime: String, durationMs: Int): Prepared {
        try {
            val (f, id, rest) = seal(ctx, plain)
            val (key, sz) = rest
            return Prepared(f, id, key, sz.first, sz.second, media, name, mime, durationMs, 0, 0, ByteArray(0), false, sz.first)
        } finally { plain.fill(0) }
    }

    /**
     * Forwarding: the same content sealed again under a new file id and key (a fresh copy for each chat,
     * so the original's key never leaves this phone). Streams; nothing readable touches the disk.
     */
    fun reseal(ctx: Context, f: FileRow): Prepared {
        val r = reader(f)
        val input = object : InputStream() {
            var pos = 0L
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val n = r.read(pos, b, off, len); if (n > 0) pos += n; return n }
            override fun close() = r.close()
        }
        val (file, id, rest) = input.use { sealStream(ctx, it) }
        val (key, sz) = rest
        return Prepared(file, id, key, sz.first, sz.second, f.media, f.name, f.mime, f.durationMs, f.width, f.height, f.thumb ?: ByteArray(0),
            f.compressed, f.originalSize)
    }

    // ---------------------------------------------------------------- reading

    /** f.size is the size of the stored content (after deflate, for compressed documents). */
    fun reader(f: FileRow): SealedReader = SealedReader(File(f.path), f.key, hexId(f.fileId), f.chunkSize, f.size)

    private fun hexId(h: String) = ByteArray(h.length / 2) { i -> ((Character.digit(h[i * 2], 16) shl 4) or Character.digit(h[i * 2 + 1], 16)).toByte() }

    /** Whole file, decrypted and decompressed, in memory (documents, photos). */
    fun readBytes(f: FileRow, max: Long = 128L * 1024 * 1024): ByteArray {
        val stored = reader(f).use { it.readAll(max) }
        return if (f.compressed) Compression.inflate(stored, max.toInt()) else stored
    }

    fun decodeImage(f: FileRow, maxSide: Int): Bitmap? = try {
        val bytes = readBytes(f, 64L * 1024 * 1024)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

    fun videoFrame(f: FileRow): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(SealedDataSource(reader(f)))
            mmr.getFrameAtTime(0)
        } catch (e: Exception) { null } finally { try { mmr.release() } catch (_: Exception) {} }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    /** Remove any decrypted leftovers from an earlier session. */
    /** Plain temporary files (camera shots before sealing, transcodes) never outlive a run of the app. */
    fun wipeTemp(ctx: Context) {
        tempDir(ctx).listFiles()?.forEach { it.delete() }
        File(ctx.cacheDir, "capture").listFiles()?.forEach { it.delete() }
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) count++; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) count += r; return r }
    }
}

/** Lets Android's media player read a sealed file directly, decrypting as it plays — no plaintext copy. */
class SealedDataSource(private val reader: SealedReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (size == 0) 0 else reader.read(position, buffer, offset, size)
    override fun getSize(): Long = reader.size
    override fun close() = reader.close()
}
