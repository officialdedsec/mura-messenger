package com.mura.mesh.core

import java.security.MessageDigest
import java.util.Base64

/**
 * Tor v3 onion identity made on the phone, before Tor is running.
 *
 * Making the key ourselves (instead of asking Tor for "NEW") means your onion address exists from the
 * first second: it's in your QR code and profile even if Tor hasn't connected yet, so a contact you add
 * offline can still reach you over the internet later.
 *
 *  address  = base32(pubkey ‖ checksum ‖ 0x03) + ".onion",  checksum = SHA3-256(".onion checksum" ‖ pubkey ‖ 0x03)[0..2]
 *  Tor key  = "ED25519-V3:" + base64(expanded secret key)   (SHA-512 of the seed, clamped — Tor's on-disk format)
 */
object OnionKeys {

    class Identity(val address: String, val torKey: String)

    fun create(seed: ByteArray = Crypto.randomBytes(32)): Identity {
        require(seed.size == 32)
        val pub = Crypto.ed.publicFromSeed(seed)
        val h = MessageDigest.getInstance("SHA-512").digest(seed)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 127) or 64).toByte()
        val key = "ED25519-V3:" + Base64.getEncoder().encodeToString(h)
        h.fill(0)
        return Identity(address(pub), key)
    }

    fun address(pub: ByteArray): String {
        require(pub.size == 32)
        val sum = Sha3.sha3_256(".onion checksum".toByteArray(Charsets.US_ASCII) + pub + byteArrayOf(3)).copyOf(2)
        return Base32.encode(pub + sum + byteArrayOf(3)).lowercase() + ".onion"
    }

    /** True if [onion] is a well-formed v3 address with a valid checksum. */
    fun checksumOk(onion: String): Boolean = try {
        val raw = Base32.decode(onion.removeSuffix(".onion").uppercase())
        raw.size == 35 && raw[34].toInt() == 3 && address(raw.copyOf(32)) == onion.lowercase()
    } catch (e: Exception) { false }
}

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0; var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0; var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c); require(v >= 0) { "bad base32" }
            buffer = (buffer shl 5) or v; bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}

/** SHA3-256 (FIPS 202). Small and self-contained: not every Android version ships SHA-3. */
object Sha3 {
    private val RC = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7fffffffffff7f76L, -0x7fffffff7fff8000L,
        0x000000000000808bL, 0x0000000080000001L, -0x7fffffff7fff7f7fL, -0x7fffffffffff7ff7L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, -0x7fffffffffffff75L, -0x7fffffffffff7f77L, -0x7fffffffffff7ffdL,
        -0x7fffffffffff7ffeL, -0x7fffffffffffff80L, 0x000000000000800aL, -0x7fffffff7ffffff6L,
        -0x7fffffff7fff7f7fL, -0x7fffffffffff7f80L, 0x0000000080000001L, -0x7fffffff7fff7ff8L,
    )
    private val ROT = intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

    private fun keccakF(a: LongArray) {
        val b = LongArray(25); val c = LongArray(5); val d = LongArray(5)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) d[x] = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
            for (i in 0 until 25) a[i] = a[i] xor d[i % 5]
            for (x in 0 until 5) for (y in 0 until 5) {
                val i = x + 5 * y
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[i], ROT[i])
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            a[0] = a[0] xor RC[round]
        }
    }

    fun sha3_256(msg: ByteArray): ByteArray {
        val rate = 136
        val state = LongArray(25)
        val padded = ByteArray(((msg.size / rate) + 1) * rate)
        System.arraycopy(msg, 0, padded, 0, msg.size)
        padded[msg.size] = (padded[msg.size].toInt() xor 0x06).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
        for (off in padded.indices step rate) {
            for (i in 0 until rate / 8) {
                var lane = 0L
                for (k in 0 until 8) lane = lane or ((padded[off + i * 8 + k].toLong() and 0xFF) shl (8 * k))
                state[i] = state[i] xor lane
            }
            keccakF(state)
        }
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (state[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }
}

/**
 * Mura ID: your onion address, shown without ".onion". Anyone who has it can reach you through Tor and send
 * you a contact request — no server, no phone number, no search directory. Typos are caught by the
 * address's built-in checksum.
 */
object MuraId {
    fun fromOnion(onion: String): String = onion.removeSuffix(".onion")

    /** "abcd efgh …" groups of 4, easier to read out or copy by hand. */
    fun pretty(onion: String): String = fromOnion(onion).chunked(4).joinToString(" ")

    /** Accepts the ID with or without spaces, dashes, "mura:" prefix or ".onion". Returns the onion address, or null. */
    fun parse(input: String): String? {
        val s = input.trim().lowercase().removePrefix("mura:").removePrefix("id:").replace(Regex("[\\s\\-]"), "").removeSuffix(".onion")
        if (s.length != 56 || !s.all { it in 'a'..'z' || it in '2'..'7' }) return null
        val onion = "$s.onion"
        return if (OnionKeys.checksumOk(onion)) onion else null
    }
}
