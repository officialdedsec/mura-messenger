package com.mura.mesh.core

import java.io.ByteArrayOutputStream

/** Thrown when bytes from the network do not parse. Always caught by the caller. */
class ProtocolException(msg: String) : Exception(msg)

fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(chars[v ushr 4]).append(chars[v and 0x0f])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}

/** Big-endian binary writer used by every wire format in the app. */
class ByteWriter(initial: Int = 64) {
    private val out = ByteArrayOutputStream(initial)

    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun u32(v: Long) = apply { u16((v ushr 16).toInt()); u16(v.toInt()) }
    fun i32(v: Int) = u32(v.toLong() and 0xffffffffL)
    fun u64(v: Long) = apply { u32(v ushr 32); u32(v and 0xffffffffL) }
    fun f64(v: Double) = u64(java.lang.Double.doubleToRawLongBits(v))
    fun f32(v: Float) = i32(java.lang.Float.floatToRawIntBits(v))
    fun raw(b: ByteArray) = apply { out.write(b, 0, b.size) }

    /** Fixed-size field: must be exactly [n] bytes. */
    fun fixed(b: ByteArray, n: Int) = apply {
        require(b.size == n) { "expected $n bytes, got ${b.size}" }
        raw(b)
    }

    /** Length-prefixed (u32) byte array. */
    fun bytes(b: ByteArray) = apply { u32(b.size.toLong()); raw(b) }

    /** Length-prefixed (u16) UTF-8 string, max 65535 bytes. */
    fun str(s: String) = apply {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 0xffff) { "string too long" }
        u16(b.size); raw(b)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Bounds-checked reader. Any malformed input throws [ProtocolException]. */
class ByteReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || n > remaining) throw ProtocolException("truncated: need $n, have $remaining")
    }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xff }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
    fun i32(): Int = u32().toInt()
    fun u64(): Long = (u32() shl 32) or u32()
    fun f64(): Double = java.lang.Double.longBitsToDouble(u64())
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())

    fun fixed(n: Int): ByteArray {
        need(n)
        val r = buf.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun bytes(max: Int = 64 * 1024 * 1024): ByteArray {
        val n = u32()
        if (n > max) throw ProtocolException("field too large: $n")
        return fixed(n.toInt())
    }

    fun str(): String = String(fixed(u16()), Charsets.UTF_8)

    fun rest(): ByteArray = fixed(remaining)
}
