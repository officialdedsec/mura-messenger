package com.mura.mesh.core

/**
 * Group and status encryption ("sender keys").
 *
 * A group message is encrypted once with the group's shared key and addressed to the
 * group's 8-byte mesh address, so a message to 200 people costs the same as a message to one.
 * Inside, the sender signs it with their Ed25519 key, so members know exactly who wrote it
 * and no member can impersonate another. When someone is removed, admins switch to a fresh
 * key ("epoch") that the removed person never receives.
 *
 * Status updates use the same mechanism with a personal status key shared privately with contacts.
 *
 *   wire  = epoch(u32) || nonce || AES-GCM_key( senderX25519 || signature || packedPayload )
 *   aad   = bundleId || dest || epoch
 *   sig   = Ed25519( "mura-gsig" || bundleId || dest || epoch || packedPayload )
 */
object GroupCrypto {
    private val SIG_CONTEXT = "mura-gsig".toByteArray()

    fun groupNode(groupId: ByteArray): ByteArray = Crypto.sha256("mura-group".toByteArray(), groupId).copyOf(8)
    fun statusNode(ownerX25519: ByteArray): ByteArray = Crypto.sha256("mura-status".toByteArray(), ownerX25519).copyOf(8)

    private fun aad(bundleId: ByteArray, dest: ByteArray, epoch: Int) = ByteWriter(32).raw(bundleId).raw(dest).i32(epoch).toByteArray()

    fun seal(
        key: ByteArray, epoch: Int, bundleId: ByteArray, dest: ByteArray,
        senderX25519: ByteArray, senderEdSeed: ByteArray, packedPayload: ByteArray,
    ): ByteArray {
        val a = aad(bundleId, dest, epoch)
        val sig = Crypto.ed.sign(senderEdSeed, SIG_CONTEXT + a + packedPayload)
        val inner = ByteWriter(packedPayload.size + 100).fixed(senderX25519, 32).fixed(sig, 64).raw(packedPayload).toByteArray()
        return ByteWriter().i32(epoch).raw(Crypto.seal(key, inner, a)).toByteArray()
    }

    fun epochOf(wire: ByteArray): Int = ByteReader(wire).i32()

    /**
     * Decrypts and verifies. [edPubFor] returns the signing key of a member (null if unknown → rejected).
     * Returns (senderX25519, packedPayload).
     */
    fun open(
        key: ByteArray, bundleId: ByteArray, dest: ByteArray, wire: ByteArray,
        edPubFor: (ByteArray) -> ByteArray?,
    ): Pair<ByteArray, ByteArray> {
        val r = ByteReader(wire)
        val epoch = r.i32()
        val a = aad(bundleId, dest, epoch)
        val inner = Crypto.unseal(key, r.rest(), a)
        if (inner.size < 96) throw ProtocolException("short group message")
        val sender = inner.copyOfRange(0, 32)
        val sig = inner.copyOfRange(32, 96)
        val payload = inner.copyOfRange(96, inner.size)
        val ed = edPubFor(sender) ?: throw ProtocolException("unknown sender")
        if (!Crypto.ed.verify(ed, SIG_CONTEXT + a + payload, sig)) throw ProtocolException("bad signature")
        return sender to payload
    }
}

/**
 * Encrypted backup stream: the archive is cut into 1 MB records, each AES-GCM sealed with a
 * counter nonce; the last record is marked, so a truncated or reordered backup is rejected.
 */
class EncryptedRecordWriter(private val out: java.io.OutputStream, private val key: ByteArray) : java.io.OutputStream() {
    private val buf = java.io.ByteArrayOutputStream(RECORD)
    private var counter = 0L
    private var closed = false

    companion object { const val RECORD = 1024 * 1024 }

    private fun nonce(c: Long) = ByteWriter(12).u32(0x42414b31).u64(c).toByteArray()
    private fun aad(c: Long, last: Boolean) = ByteWriter(9).u64(c).u8(if (last) 1 else 0).toByteArray()

    private fun flushRecord(last: Boolean) {
        val plain = buf.toByteArray()
        buf.reset()
        val ct = Crypto.gcmEncrypt(key, nonce(counter), plain, aad(counter, last))
        val head = ByteWriter(5).u8(if (last) 1 else 0).i32(ct.size).toByteArray()
        out.write(head); out.write(ct)
        counter++
    }

    override fun write(b: Int) { buf.write(b); if (buf.size() >= RECORD) flushRecord(false) }
    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off; var l = len
        while (l > 0) {
            val n = minOf(l, RECORD - buf.size())
            buf.write(b, o, n); o += n; l -= n
            if (buf.size() >= RECORD) flushRecord(false)
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        flushRecord(true)
        out.flush()
        out.close()
    }
}

class EncryptedRecordReader(private val input: java.io.InputStream, private val key: ByteArray) : java.io.InputStream() {
    private var current = ByteArray(0)
    private var pos = 0
    private var counter = 0L
    private var done = false

    private fun nonce(c: Long) = ByteWriter(12).u32(0x42414b31).u64(c).toByteArray()
    private fun aad(c: Long, last: Boolean) = ByteWriter(9).u64(c).u8(if (last) 1 else 0).toByteArray()

    private fun readFully(n: Int): ByteArray {
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = input.read(b, o, n - o)
            if (r < 0) throw ProtocolException("backup is truncated")
            o += r
        }
        return b
    }

    private fun next(): Boolean {
        if (done) return false
        val head = readFully(5)
        val last = head[0].toInt() == 1
        val len = ByteReader(head, 1).i32()
        if (len < 16 || len > EncryptedRecordWriter.RECORD + 64) throw ProtocolException("bad record")
        current = try { Crypto.gcmDecrypt(key, nonce(counter), readFully(len), aad(counter, last)) }
            catch (e: Exception) { throw ProtocolException("wrong password or damaged backup") }
        pos = 0
        counter++
        if (last) done = true
        return true
    }

    override fun read(): Int {
        while (pos >= current.size) if (!next()) return -1
        return current[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= current.size) if (!next()) return -1
        val n = minOf(len, current.size - pos)
        System.arraycopy(current, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = input.close()
}

/** Simple archive of named entries inside the encrypted stream. */
object BackupArchive {
    const val MAGIC = "MURABAK2"
    /** v3: every backup carries its own random 16-byte value, so each backup is encrypted under a different key. */
    const val MAGIC3 = "MURABAK3"
    private val INFO_V3 = "mura-backup-file-v3".toByteArray()

    class Header(val salt: ByteArray, val iterations: Int, val createdAt: Long, val fileNonce: ByteArray? = Crypto.randomBytes(16)) {
        fun write(out: java.io.OutputStream) {
            val n = fileNonce ?: throw IllegalStateException("new backups always have a file nonce")
            out.write(ByteWriter().raw(MAGIC3.toByteArray()).fixed(salt, 16).i32(iterations).u64(createdAt).fixed(n, 16).toByteArray())
        }

        /**
         * The key the records are sealed with. The record counter restarts at 0 in every backup, so the key must be
         * unique per backup (AES-GCM must never see the same key and nonce twice): v3 derives it from the file nonce.
         * v2 backups (made before 2.7.2) used the password key directly; they can still be read.
         */
        fun recordKey(passwordKey: ByteArray): ByteArray = fileNonce?.let { Crypto.hkdf(passwordKey, it, INFO_V3) } ?: passwordKey

        companion object {
            const val SIZE = 8 + 16 + 4 + 8
            private fun readFully(input: java.io.InputStream, n: Int): ByteArray {
                val b = ByteArray(n)
                var o = 0
                while (o < n) { val r = input.read(b, o, n - o); if (r < 0) throw ProtocolException("not a Mura backup"); o += r }
                return b
            }
            fun read(input: java.io.InputStream): Header {
                val rd = ByteReader(readFully(input, SIZE))
                val magic = String(rd.fixed(8))
                if (magic != MAGIC && magic != MAGIC3) throw ProtocolException("not a Mura backup")
                val salt = rd.fixed(16); val it = rd.i32(); val created = rd.u64()
                if (it < 10_000 || it > 10_000_000) throw ProtocolException("damaged backup")
                val nonce = if (magic == MAGIC3) readFully(input, 16) else null
                return Header(salt, it, created, nonce)
            }
        }
    }

    fun writeEntry(out: java.io.OutputStream, name: String, size: Long, data: java.io.InputStream) {
        out.write(ByteWriter().u8(1).str(name).u64(size).toByteArray())
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw java.io.IOException("source ended early")
            out.write(buf, 0, r); left -= r
        }
    }

    fun writeEnd(out: java.io.OutputStream) { out.write(byteArrayOf(0)) }

    /** Calls [onEntry] for each entry; the callback must consume exactly `size` bytes from the stream given. */
    fun read(input: java.io.InputStream, onEntry: (name: String, size: Long, data: java.io.InputStream) -> Unit) {
        val din = java.io.DataInputStream(input)
        while (true) {
            val type = din.read()
            if (type == 0) return
            if (type != 1) throw ProtocolException("damaged backup")
            val nameLen = din.readUnsignedShort()
            val nb = ByteArray(nameLen); din.readFully(nb)
            val size = din.readLong()
            if (size < 0) throw ProtocolException("damaged backup")
            val bounded = BoundedStream(din, size)
            onEntry(String(nb, Charsets.UTF_8), size, bounded)
            bounded.skipRest()
        }
    }

    private class BoundedStream(private val src: java.io.InputStream, private var left: Long) : java.io.InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = src.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
        fun skipRest() { val buf = ByteArray(8192); while (left > 0) { if (read(buf, 0, buf.size) < 0) throw ProtocolException("damaged backup") } }
    }
}
