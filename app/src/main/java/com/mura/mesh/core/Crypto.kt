package com.mura.mesh.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Raw X25519 operations (32-byte keys). Android uses Tink; tests use a reference implementation. */
interface Curve25519 {
    fun generatePrivate(): ByteArray
    fun publicFrom(privateKey: ByteArray): ByteArray
    fun shared(privateKey: ByteArray, peerPublic: ByteArray): ByteArray
}

/** Ed25519 signatures (32-byte seed, 32-byte public key, 64-byte signature). Used for group and status authorship. */
interface Ed25519 {
    fun publicFromSeed(seed: ByteArray): ByteArray
    fun sign(seed: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/** Lossless compression used before encryption (encrypted data can't be compressed afterwards). */
object Compression {
    /** Returns deflated bytes, or null if compression doesn't save at least 5%. */
    fun deflate(data: ByteArray): ByteArray? {
        if (data.size < 64) return null
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        d.setInput(data); d.finish()
        val out = java.io.ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(16 * 1024)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        val r = out.toByteArray()
        return if (r.size < data.size * 0.95) r else null
    }

    fun inflate(data: ByteArray, maxSize: Int): ByteArray {
        val inf = java.util.zip.Inflater(true)
        inf.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(16 * 1024)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw ProtocolException("truncated deflate")
                out.write(buf, 0, n)
                if (out.size() > maxSize) throw ProtocolException("inflated too large")
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw ProtocolException("bad deflate")
        } finally { inf.end() }
        return out.toByteArray()
    }

    /** Checks the first 256 KB: is this file worth compressing losslessly (documents, text, etc)? */
    fun worthCompressing(sample: ByteArray): Boolean {
        val c = deflate(sample) ?: return false
        return c.size < sample.size * 0.85
    }

    /**
     * Traffic padding: messages are padded to fixed size steps (256 B, 512 B, 1 KB … 128 KB) *before*
     * encryption, so phones that carry them — and anyone watching — can't tell a "yes" from a paragraph.
     * Set from Settings › Privacy (on by default).
     */
    @Volatile var padding: Boolean = true
    private const val PAD_CAP = 248 * 1024        // stays under Bundle.MAX_PAYLOAD with room for the envelope

    fun padTarget(size: Int): Int {
        var b = 256
        while (b < size && b < 128 * 1024) b *= 2
        return if (b >= size) b else ((size + 32 * 1024 - 1) / (32 * 1024)) * (32 * 1024)
    }

    /** Wrap/unwrap a message: 1 flag byte (0 raw, 1 deflated) + data. */
    fun pack(data: ByteArray): ByteArray {
        if (padding) {
            // Always deflate (a deflate stream marks its own end), then add random filler. Inflating stops at the
            // end of the stream, so the filler is ignored — older Mura versions read padded messages unchanged.
            val c = deflateAlways(data)
            val body = byteArrayOf(1) + c
            val target = padTarget(body.size)
            if (target <= PAD_CAP && target > body.size) return body + Crypto.randomBytes(target - body.size)
            return body
        }
        val c = deflate(data)
        return if (c != null) byteArrayOf(1) + c else byteArrayOf(0) + data
    }

    private fun deflateAlways(data: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
        d.setInput(data); d.finish()
        val out = java.io.ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(16 * 1024)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    fun unpack(data: ByteArray, maxSize: Int = 4 * 1024 * 1024): ByteArray {
        if (data.isEmpty()) throw ProtocolException("empty")
        val body = data.copyOfRange(1, data.size)
        return when (data[0].toInt()) {
            0 -> body
            1 -> inflate(body, maxSize)
            else -> throw ProtocolException("unknown packing")
        }
    }
}

object Crypto {
    const val KEY = 32
    const val NONCE = 12
    const val TAG = 16

    /** Must be set once at startup (MuraApp sets the Tink implementation). */
    lateinit var curve: Curve25519
    lateinit var ed: Ed25519

    /** Signing seed derived from the X25519 identity key, so one backup restores both. */
    fun edSeedFrom(x25519Private: ByteArray): ByteArray = hkdf(x25519Private, "mura-ed25519".toByteArray(), "seed".toByteArray())

    /** PBKDF2 for the backup password (Android and JVM both ship PBKDF2WithHmacSHA256). */
    fun passwordKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(javax.crypto.spec.PBEKeySpec(password, salt, iterations, 256)).encoded
    }

    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = KEY): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var off = 0
        var counter = 1
        while (off < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n
            counter++
        }
        return out
    }

    /** AES-256-GCM. Returns ciphertext||tag (nonce supplied by caller). */
    fun gcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return c.doFinal(plain)
    }

    /** Throws on tampering (AEADBadTagException). */
    fun gcmDecrypt(key: ByteArray, nonce: ByteArray, cipher: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce))
        if (aad.isNotEmpty()) c.updateAAD(aad)
        return c.doFinal(cipher)
    }

    /** Random nonce + ciphertext. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE)
        return nonce + gcmEncrypt(key, nonce, plain, aad)
    }

    fun unseal(key: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        if (data.size < NONCE + TAG) throw ProtocolException("ciphertext too short")
        return gcmDecrypt(key, data.copyOfRange(0, NONCE), data.copyOfRange(NONCE, data.size), aad)
    }

    /** 8-byte routing address derived from a public key. */
    fun nodeId(publicKey: ByteArray): ByteArray = sha256("mura-node".toByteArray(), publicKey).copyOf(8)

    /** Order-independent safety code two people compare to verify each other's keys. */
    fun safetyCode(a: ByteArray, b: ByteArray): String {
        val (x, y) = if (a.toHex() < b.toHex()) a to b else b to a
        val h = sha256("mura-safety".toByteArray(), x, y)
        val sb = StringBuilder()
        for (i in 0 until 6) {
            val v = ((h[i * 3].toInt() and 0xff) shl 16) or ((h[i * 3 + 1].toInt() and 0xff) shl 8) or (h[i * 3 + 2].toInt() and 0xff)
            sb.append(String.format(java.util.Locale.US, "%05d", v % 100000))
            if (i < 5) sb.append(' ')
        }
        return sb.toString()
    }
}

/**
 * End-to-end sealed envelope ("sealed sender"):
 *
 *   e      = fresh ephemeral X25519 key
 *   k1     = HKDF(DH(e, R))                          hides the sender from relays
 *   k2     = HKDF(DH(e, R) || DH(S, R))              authenticates the sender
 *   wire   = e.pub || seal_k1( S.pub || seal_k2(payload) )
 *
 * Relays only ever see the 8-byte destination address and ciphertext.
 * Only the holder of S's private key could have produced k2, so the recipient
 * knows the message really came from S (and was not modified).
 */
object Envelope {
    private val INFO_OUTER = "mura-outer-v1".toByteArray()
    private val INFO_INNER = "mura-inner-v1".toByteArray()

    fun seal(senderPriv: ByteArray, senderPub: ByteArray, recipientPub: ByteArray, aad: ByteArray, payload: ByteArray): ByteArray {
        val c = Crypto.curve
        val ePriv = c.generatePrivate()
        val ePub = c.publicFrom(ePriv)
        val dh1 = c.shared(ePriv, recipientPub)
        val dh2 = c.shared(senderPriv, recipientPub)
        val salt = ePub + recipientPub
        val k1 = Crypto.hkdf(dh1, salt, INFO_OUTER)
        val k2 = Crypto.hkdf(dh1 + dh2, salt + senderPub, INFO_INNER)
        val inner = Crypto.seal(k2, payload, aad)
        val outer = Crypto.seal(k1, senderPub + inner, aad)
        return ePub + outer
    }

    /** Returns (senderPublicKey, payload) or throws if not for us / tampered. */
    fun open(myPriv: ByteArray, myPub: ByteArray, aad: ByteArray, wire: ByteArray): Pair<ByteArray, ByteArray> {
        if (wire.size < 32 + Crypto.NONCE + 32 + Crypto.NONCE + 2 * Crypto.TAG) throw ProtocolException("envelope too short")
        val c = Crypto.curve
        val ePub = wire.copyOfRange(0, 32)
        val dh1 = c.shared(myPriv, ePub)
        if (dh1.all { it == 0.toByte() }) throw ProtocolException("bad ephemeral key")     // low-order point
        val salt = ePub + myPub
        val k1 = Crypto.hkdf(dh1, salt, INFO_OUTER)
        val outerPlain = Crypto.unseal(k1, wire.copyOfRange(32, wire.size), aad)
        if (outerPlain.size < 32) throw ProtocolException("bad outer")
        val senderPub = outerPlain.copyOfRange(0, 32)
        val dh2 = c.shared(myPriv, senderPub)
        if (dh2.all { it == 0.toByte() }) throw ProtocolException("bad sender key")       // low-order point: no real identity
        val k2 = Crypto.hkdf(dh1 + dh2, salt + senderPub, INFO_INNER)
        val payload = Crypto.unseal(k2, outerPlain.copyOfRange(32, outerPlain.size), aad)
        return senderPub to payload
    }
}

/** Per-file encryption: every 32 KB piece is sealed on its own, so pieces can travel by different phones. */
object FileCipher {
    fun chunkNonce(index: Int): ByteArray = ByteWriter(12).u32(0x4d555241L).u64(index.toLong()).toByteArray()
    fun chunkAad(fileId: ByteArray, index: Int): ByteArray = ByteWriter(24).fixed(fileId, 16).u32(index.toLong()).toByteArray()

    fun encryptChunk(fileKey: ByteArray, fileId: ByteArray, index: Int, plain: ByteArray): ByteArray =
        Crypto.gcmEncrypt(fileKey, chunkNonce(index), plain, chunkAad(fileId, index))

    fun decryptChunk(fileKey: ByteArray, fileId: ByteArray, index: Int, cipher: ByteArray): ByteArray =
        Crypto.gcmDecrypt(fileKey, chunkNonce(index), cipher, chunkAad(fileId, index))
}
