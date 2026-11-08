package com.mura.mesh.core

/**
 * ITU-T G.711 mu-law: 8 kHz telephone-quality voice at 8 KB/s.
 * Chosen because it needs no codec library, works identically on every Android
 * version, and fits comfortably in a single Bluetooth LE link.
 */
object G711 {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encodeSample(pcm: Short): Byte {
        var s = pcm.toInt()
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0f
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun decodeSample(u: Byte): Short {
        val v = u.toInt().inv() and 0xff
        val sign = v and 0x80
        val exponent = (v shr 4) and 0x07
        val mantissa = v and 0x0f
        var s = ((mantissa shl 3) + BIAS) shl exponent
        s -= BIAS
        return (if (sign != 0) -s else s).toShort()
    }

    fun encode(pcm: ShortArray, count: Int = pcm.size): ByteArray = ByteArray(count) { encodeSample(pcm[it]) }
    fun decode(data: ByteArray): ShortArray = ShortArray(data.size) { decodeSample(data[it]) }
}

/** Encrypts call audio with a per-call key. Each side uses its own nonce prefix, so nonces never repeat. */
class CallCipher(private val key: ByteArray, private val iAmCaller: Boolean) {
    private fun nonce(fromCaller: Boolean, seq: Long) =
        ByteWriter(12).u32(if (fromCaller) 1L else 2L).u64(seq).toByteArray()

    fun encrypt(seq: Long, audio: ByteArray): ByteArray = Crypto.gcmEncrypt(key, nonce(iAmCaller, seq), audio, ByteArray(0))
    fun decrypt(seq: Long, cipher: ByteArray): ByteArray = Crypto.gcmDecrypt(key, nonce(!iAmCaller, seq), cipher, ByteArray(0))
}
