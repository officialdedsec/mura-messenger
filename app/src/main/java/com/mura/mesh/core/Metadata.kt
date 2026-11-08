package com.mura.mesh.core

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile

/**
 * Removes hidden details from photos and videos without re-encoding them (no quality loss):
 * GPS location, date and time, phone make and model, camera serials, editing software, XMP/IPTC.
 *
 *  • JPEG: drops EXIF/XMP (APP1), IPTC (APP13), vendor blocks (APP3–APP12, APP15) and comments.
 *    The picture's rotation is kept by writing back a tiny EXIF block holding only the orientation.
 *  • PNG: drops text, EXIF and timestamp chunks.
 *  • WebP: drops EXIF and XMP chunks.
 *  • MP4 / MOV / 3GP: blanks the `udta`, `meta` and `uuid` boxes (location ©xyz, make, model, dates,
 *    XMP) in place — renamed to `free` and zeroed, so the file size and offsets stay identical.
 */
object Metadata {

    fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
    fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
    fun isWebp(b: ByteArray) = b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"

    /** Strips any supported image format; other data is returned unchanged. */
    fun stripImage(b: ByteArray): ByteArray = when {
        isJpeg(b) -> jpeg(b)
        isPng(b) -> png(b)
        isWebp(b) -> webp(b)
        else -> b
    }

    // ------------------------------------------------------------------ JPEG

    fun jpeg(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var orientation = 1
        var i = 2
        var wroteOrientation = false
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return b                      // not a marker: leave the file alone
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }                    // fill byte
            if (marker == 0xDA) {                                    // start of scan: copy the rest verbatim
                if (!wroteOrientation && orientation != 1) { writeOrientation(out, orientation); wroteOrientation = true }
                out.write(b, i, b.size - i)
                return out.toByteArray()
            }
            if (marker in 0xD0..0xD7 || marker == 0x01) { out.write(b, i, 2); i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return b
            val seg = i + 4
            val drop = when (marker) {
                0xE1 -> { if (isExif(b, seg)) orientation = readOrientation(b, seg + 6, len - 8); true }   // EXIF / XMP
                0xED, 0xFE -> true                                    // IPTC, comment
                in 0xE3..0xEC, 0xEF -> true                           // vendor blocks (maker notes, models…)
                0xE2 -> !startsWith(b, seg, "ICC_PROFILE")           // keep colour profile only
                else -> false                                         // APP0 JFIF, APP14 Adobe, tables, frame headers
            }
            if (!drop) {
                // Put the orientation right after JFIF/APP0 (or before the first non-APP segment).
                if (!wroteOrientation && orientation != 1 && marker != 0xE0 && marker !in 0xE1..0xEF) {
                    writeOrientation(out, orientation); wroteOrientation = true
                }
                out.write(b, i, 2 + len)
            }
            i += 2 + len
        }
        return b
    }

    private fun startsWith(b: ByteArray, at: Int, s: String): Boolean =
        at + s.length <= b.size && String(b, at, s.length, Charsets.ISO_8859_1) == s

    private fun isExif(b: ByteArray, at: Int) = startsWith(b, at, "Exif\u0000\u0000")

    /** Orientation tag (0x0112) from IFD0 of a TIFF block. */
    private fun readOrientation(b: ByteArray, t: Int, len: Int): Int {
        try {
            if (len < 8) return 1
            val le = b[t] == 'I'.code.toByte()
            fun u16(p: Int) = if (le) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            fun u32(p: Int) = if (le) u16(p) or (u16(p + 2) shl 16) else (u16(p) shl 16) or u16(p + 2)
            val ifd = t + u32(t + 4)
            val n = u16(ifd)
            for (k in 0 until n) {
                val e = ifd + 2 + k * 12
                if (e + 12 > t + len) break
                if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 } ?: 1
            }
        } catch (_: Exception) {}
        return 1
    }

    /** A minimal big-endian EXIF block containing only the orientation. */
    private fun writeOrientation(out: ByteArrayOutputStream, o: Int) {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,          // header, IFD0 at 8
            0, 1,                                                                // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0,                  // Orientation, SHORT, 1, value
            0, 0, 0, 0,                                                          // no next IFD
        )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = payload.size + 2
        out.write(0xFF); out.write(0xE1); out.write(len shr 8); out.write(len and 0xFF)
        out.write(payload)
    }

    // ------------------------------------------------------------------ PNG

    private val pngDrop = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

    fun png(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var i = 8
        while (i + 12 <= b.size) {
            val len = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 0 || i + 12 + len > b.size) return b
            val type = String(b, i + 4, 4, Charsets.US_ASCII)
            if (type !in pngDrop) out.write(b, i, 12 + len)
            i += 12 + len
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ WebP

    fun webp(b: ByteArray): ByteArray {
        val chunks = ByteArrayOutputStream(b.size)
        var i = 12
        while (i + 8 <= b.size) {
            val fourcc = String(b, i, 4, Charsets.US_ASCII)
            val size = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            val padded = size + (size and 1)
            if (size < 0 || i + 8 + padded > b.size) return b
            when (fourcc) {
                "EXIF", "XMP " -> {}
                "VP8X" -> {
                    val c = b.copyOfRange(i, i + 8 + padded)
                    c[8] = (c[8].toInt() and 0x08.inv() and 0x04.inv()).toByte()   // clear EXIF and XMP flags
                    chunks.write(c)
                }
                else -> chunks.write(b, i, 8 + padded)
            }
            i += 8 + padded
        }
        val body = chunks.toByteArray()
        val out = ByteArrayOutputStream(body.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val riff = body.size + 4
        out.write(riff and 0xFF); out.write((riff shr 8) and 0xFF); out.write((riff shr 16) and 0xFF); out.write((riff shr 24) and 0xFF)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ MP4 family

    private val boxesToBlank = setOf("udta", "meta", "uuid", "XMP_")
    private val containers = setOf("moov", "trak", "mdia", "minf")

    /** Blanks metadata boxes of an MP4/MOV/3GP file in place. Returns how many boxes were blanked. */
    fun scrubMp4(file: java.io.File): Int {
        RandomAccessFile(file, "rw").use { f -> return scrubBoxes(f, 0, f.length(), 0) }
    }

    private fun scrubBoxes(f: RandomAccessFile, start: Long, end: Long, depth: Int): Int {
        var pos = start
        var count = 0
        val head = ByteArray(16)
        while (pos + 8 <= end) {
            f.seek(pos)
            f.readFully(head, 0, 8)
            var size = ((head[0].toLong() and 0xFF) shl 24) or ((head[1].toLong() and 0xFF) shl 16) or ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            if (size == 1L) {
                f.readFully(head, 8, 8)
                size = 0L; for (k in 8 until 16) size = (size shl 8) or (head[k].toLong() and 0xFF)
                headerLen = 16
            } else if (size == 0L) size = end - pos
            if (size < headerLen || pos + size > end) break
            when {
                type in boxesToBlank -> {
                    // Rename to "free" and zero the contents: players skip it, nothing else moves.
                    f.seek(pos + 4); f.write("free".toByteArray(Charsets.ISO_8859_1))
                    f.seek(pos + headerLen)
                    var left = size - headerLen
                    val zeros = ByteArray(64 * 1024)
                    while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); f.write(zeros, 0, n); left -= n }
                    count++
                }
                type in containers && depth < 6 -> count += scrubBoxes(f, pos + headerLen, pos + size, depth + 1)
            }
            pos += size
        }
        return count
    }

    fun isMp4Family(head: ByteArray): Boolean = head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
}
