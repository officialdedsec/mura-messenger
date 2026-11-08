package com.mura.mesh.core

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet

object Chunks {
    const val SIZE = 32 * 1024

    fun count(size: Long, chunkSize: Int = SIZE): Int = ((size + chunkSize - 1) / chunkSize).toInt()

    /** Deterministic piece id: every phone computes the same id for the same piece. */
    fun id(fileId: ByteArray, index: Int): ByteArray =
        Crypto.sha256("mura-chunk".toByteArray(), fileId, ByteWriter(4).i32(index).toByteArray()).copyOf(16)

    fun plainLength(size: Long, chunkSize: Int, index: Int): Int {
        val start = index.toLong() * chunkSize
        return minOf(chunkSize.toLong(), size - start).toInt()
    }

    fun sealedOffset(chunkSize: Int, index: Int): Long = index.toLong() * (chunkSize + Crypto.TAG)
    fun sealedSize(size: Long, chunkSize: Int): Long = size + count(size, chunkSize).toLong() * Crypto.TAG
}

/**
 * Every file Mura keeps — sent or received — lives on disk only in this sealed form:
 * fixed 32 KB pieces, each AES-256-GCM encrypted with the file's own random key.
 * The same sealed pieces travel over Bluetooth unchanged, so a received file is
 * never written to storage in readable form. The file key itself is stored in the
 * encrypted database, protected by a key that never leaves the Android Keystore.
 */
object SealedFile {
    /** Encrypts [input] into [dest]. Returns (plaintext size, SHA-256 of plaintext). */
    fun seal(input: InputStream, dest: File, key: ByteArray, fileId: ByteArray, chunkSize: Int = Chunks.SIZE): Pair<Long, ByteArray> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunkSize)
        var index = 0
        var total = 0L
        dest.parentFile?.mkdirs()
        dest.outputStream().buffered(64 * 1024).use { out ->
            while (true) {
                var n = 0
                while (n < chunkSize) {
                    val r = input.read(buf, n, chunkSize - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                val plain = if (n == chunkSize) buf else buf.copyOf(n)
                md.update(plain, 0, n)
                out.write(FileCipher.encryptChunk(key, fileId, index, plain))
                total += n
                index++
                if (n < chunkSize) break
            }
        }
        return total to md.digest()
    }

    fun seal(plain: ByteArray, dest: File, key: ByteArray, fileId: ByteArray): Pair<Long, ByteArray> =
        seal(plain.inputStream(), dest, key, fileId)
}

/** Random-access reader that decrypts a sealed file on the fly (for playing video/voice without a plaintext copy). */
class SealedReader(
    file: File,
    private val key: ByteArray,
    private val fileId: ByteArray,
    private val chunkSize: Int,
    val size: Long,
) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private var cachedIndex = -1
    private var cached = ByteArray(0)

    private fun chunk(index: Int): ByteArray {
        if (index == cachedIndex) return cached
        val len = Chunks.plainLength(size, chunkSize, index) + Crypto.TAG
        val c = ByteArray(len)
        synchronized(raf) {
            raf.seek(Chunks.sealedOffset(chunkSize, index))
            raf.readFully(c)
        }
        cached = FileCipher.decryptChunk(key, fileId, index, c)
        cachedIndex = index
        return cached
    }

    /** Reads up to [len] bytes at [position]; returns -1 at end of file. */
    @Synchronized
    fun read(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        var pos = position
        var o = off
        var remaining = minOf(len.toLong(), size - position).toInt()
        var copied = 0
        while (remaining > 0) {
            val idx = (pos / chunkSize).toInt()
            val inChunk = (pos % chunkSize).toInt()
            val c = chunk(idx)
            val n = minOf(remaining, c.size - inChunk)
            System.arraycopy(c, inChunk, buf, o, n)
            pos += n; o += n; remaining -= n; copied += n
        }
        return copied
    }

    fun copyTo(out: OutputStream) {
        for (i in 0 until Chunks.count(size, chunkSize)) out.write(chunk(i))
    }

    fun readAll(max: Long = 256L * 1024 * 1024): ByteArray {
        if (size > max) throw ProtocolException("file too large to open in memory")
        val out = java.io.ByteArrayOutputStream(size.toInt())
        copyTo(out)
        return out.toByteArray()
    }

    override fun close() = raf.close()
}

/** A file I am sending. Its sealed pieces are read straight from disk; nothing is re-encrypted or stored twice. */
class OutgoingFile(
    val fileId: ByteArray,
    val dest: ByteArray,
    val fileKey: ByteArray,
    val sealed: File,
    val size: Long,
    val chunkSize: Int,
    val expiresAt: Long,
) {
    val chunkCount = Chunks.count(size, chunkSize)

    fun meta(index: Int) = BundleMeta(
        Chunks.id(fileId, index), Bundle.TYPE_CHUNK, dest, fileId, index,
        Chunks.plainLength(size, chunkSize, index) + Crypto.TAG, expiresAt, MeshRouter.MAX_HOPS,
    )

    fun metas(): List<BundleMeta> = List(chunkCount) { meta(it) }

    fun build(index: Int): Bundle {
        require(index in 0 until chunkCount)
        val c = ByteArray(Chunks.plainLength(size, chunkSize, index) + Crypto.TAG)
        RandomAccessFile(sealed, "r").use { raf ->
            raf.seek(Chunks.sealedOffset(chunkSize, index))
            raf.readFully(c)
        }
        return Bundle(Chunks.id(fileId, index), Bundle.TYPE_CHUNK, dest, fileId, index, expiresAt, MeshRouter.MAX_HOPS, c)
    }

    private val index: Map<String, Int> by lazy {
        HashMap<String, Int>(chunkCount * 2).also { m -> for (i in 0 until chunkCount) m[Chunks.id(fileId, i).toHex()] = i }
    }

    fun indexOf(id: ByteArray): Int = index[id.toHex()] ?: -1
}

/**
 * A file I am receiving. Pieces can arrive in any order, from any phone, across many sessions.
 * Each piece is authenticated before it is stored (still encrypted), and the whole file is
 * checked against its SHA-256 at the end, so a broken transfer never yields a corrupted file.
 */
class IncomingFile(
    val fileId: ByteArray,
    val fileKey: ByteArray,
    val size: Long,
    val chunkSize: Int,
    val sha256: ByteArray,
    val sealedFile: File,
    val received: BitSet,
) {
    val chunkCount = Chunks.count(size, chunkSize)
    val receivedCount: Int get() = received.cardinality()
    val isComplete: Boolean get() = receivedCount >= chunkCount

    fun has(index: Int) = received.get(index)

    /** Returns true if this piece was new and authentic. Invalid pieces are dropped. */
    fun write(index: Int, cipher: ByteArray): Boolean {
        if (index < 0 || index >= chunkCount || received.get(index)) return false
        if (cipher.size != Chunks.plainLength(size, chunkSize, index) + Crypto.TAG) return false
        try { FileCipher.decryptChunk(fileKey, fileId, index, cipher) } catch (e: Exception) { return false }
        sealedFile.parentFile?.mkdirs()
        RandomAccessFile(sealedFile, "rw").use { raf ->
            val full = Chunks.sealedSize(size, chunkSize)
            if (raf.length() < full) raf.setLength(full)
            raf.seek(Chunks.sealedOffset(chunkSize, index))
            raf.write(cipher)
            raf.fd.sync()
        }
        received.set(index)
        return true
    }

    /** Verify the whole file. On mismatch progress is reset so it downloads again cleanly. */
    fun verify(): Boolean {
        if (!isComplete) return false
        val md = MessageDigest.getInstance("SHA-256")
        val ok = try {
            SealedReader(sealedFile, fileKey, fileId, chunkSize, size).use { r ->
                for (i in 0 until chunkCount) {
                    val buf = ByteArray(Chunks.plainLength(size, chunkSize, i))
                    r.read(i.toLong() * chunkSize, buf, 0, buf.size)
                    md.update(buf)
                }
            }
            md.digest().contentEquals(sha256)
        } catch (e: Exception) { false }
        if (!ok) received.clear()
        return ok
    }

    fun bitmapBytes(): ByteArray = received.toByteArray()

    companion object {
        fun bitmapFrom(bytes: ByteArray?): BitSet = if (bytes == null) BitSet() else BitSet.valueOf(bytes)
    }
}
