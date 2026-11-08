package com.mura.mesh.core

/**
 * Adaptive call audio.
 *
 *  • HD voice   — 16 kHz wideband, IMA-ADPCM, 40 ms packets  (~64 kbit/s)
 *  • Standard   — 8 kHz, IMA-ADPCM, 40 ms packets            (~32 kbit/s)
 *  • Saver      — 8 kHz, IMA-ADPCM, 100 ms packets           (~32 kbit/s, 60 % fewer packets: best on slow
 *                 Bluetooth hops and busy Tor circuits, where per-packet overhead and delay dominate)
 *
 * Every packet says which mode it is in *and* which mode its sender would like to receive, so each side
 * measures what actually arrives (loss, lateness) and tells the other side to step up or down — in real
 * time, a few seconds after the connection changes. Each packet is decodable on its own (no state carried
 * across packets), so a lost packet costs one gap, never a burst of noise.
 */
enum class VoiceMode(val code: Int, val rate: Int, val frameMs: Int, val label: String) {
    SAVER(0, 8000, 100, "Saver · weak connection"),
    STANDARD(1, 8000, 40, "Standard"),
    HD(2, 16000, 40, "HD voice");

    val samples: Int get() = rate * frameMs / 1000
    fun down() = entries[maxOf(0, ordinal - 1)]
    fun up() = entries[minOf(entries.size - 1, ordinal + 1)]

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: STANDARD }
}

object VoiceCodec {
    /** Packet = [mode][wanted mode][ADPCM]. Input/output PCM is always 16 kHz mono. */
    fun encode(pcm16k: ShortArray, mode: VoiceMode, wanted: VoiceMode): ByteArray {
        val pcm = if (mode.rate == 16000) pcm16k else Resample.down2(pcm16k)
        return byteArrayOf(mode.code.toByte(), wanted.code.toByte()) + Adpcm.encode(pcm)
    }

    class Decoded(val mode: VoiceMode, val wanted: VoiceMode, val pcm16k: ShortArray)

    fun decode(packet: ByteArray): Decoded {
        require(packet.size >= 5)
        val mode = VoiceMode.of(packet[0].toInt())
        val pcm = Adpcm.decode(packet.copyOfRange(2, packet.size))
        return Decoded(mode, VoiceMode.of(packet[1].toInt()), if (mode.rate == 16000) pcm else Resample.up2(pcm))
    }
}

/** IMA ADPCM, 4 bits per sample, with a 3-byte header per packet (predictor, step index). */
object Adpcm {
    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
        1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    fun encode(pcm: ShortArray): ByteArray {
        val first = if (pcm.isNotEmpty()) pcm[0].toInt() else 0
        var pred = first
        var index = 0
        // Start the step size near the signal level so the first samples don't smear.
        if (pcm.size > 1) { val d = kotlin.math.abs(pcm[1] - pcm[0]); while (index < 88 && STEP[index] < d) index++ }
        val out = ByteArray(3 + (pcm.size + 1) / 2)
        out[0] = (pred and 0xFF).toByte(); out[1] = ((pred shr 8) and 0xFF).toByte(); out[2] = index.toByte()
        for (i in pcm.indices) {
            val step = STEP[index]
            var diff = pcm[i] - pred
            var code = 0
            if (diff < 0) { code = 8; diff = -diff }
            var delta = step shr 3
            if (diff >= step) { code = code or 4; diff -= step; delta += step }
            if (diff >= step shr 1) { code = code or 2; diff -= step shr 1; delta += step shr 1 }
            if (diff >= step shr 2) { code = code or 1; delta += step shr 2 }
            pred = if (code and 8 != 0) pred - delta else pred + delta
            pred = pred.coerceIn(-32768, 32767)
            index = (index + INDEX[code]).coerceIn(0, 88)
            val pos = 3 + i / 2
            out[pos] = if (i % 2 == 0) (code and 0x0F).toByte() else (out[pos].toInt() or (code shl 4)).toByte()
        }
        return out
    }

    fun decode(b: ByteArray): ShortArray {
        if (b.size < 3) return ShortArray(0)
        var pred = ((b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)).toShort().toInt()
        var index = (b[2].toInt() and 0xFF).coerceIn(0, 88)
        val n = (b.size - 3) * 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val byte = b[3 + i / 2].toInt() and 0xFF
            val code = if (i % 2 == 0) byte and 0x0F else byte shr 4
            val step = STEP[index]
            var delta = step shr 3
            if (code and 4 != 0) delta += step
            if (code and 2 != 0) delta += step shr 1
            if (code and 1 != 0) delta += step shr 2
            pred = if (code and 8 != 0) pred - delta else pred + delta
            pred = pred.coerceIn(-32768, 32767)
            index = (index + INDEX[code]).coerceIn(0, 88)
            out[i] = pred.toShort()
        }
        return out
    }
}

object Resample {
    /** 16 kHz → 8 kHz with a small low-pass (average of neighbours). */
    fun down2(x: ShortArray): ShortArray = ShortArray(x.size / 2) { i ->
        val a = x[2 * i].toInt(); val b = x[2 * i + 1].toInt(); val c = if (2 * i + 2 < x.size) x[2 * i + 2].toInt() else b
        ((a + 2 * b + c) / 4).toShort()
    }

    /** 8 kHz → 16 kHz, linear interpolation. */
    fun up2(x: ShortArray): ShortArray = ShortArray(x.size * 2) { j ->
        val i = j / 2
        if (j % 2 == 0) x[i] else ((x[i].toInt() + (if (i + 1 < x.size) x[i + 1].toInt() else x[i].toInt())) / 2).toShort()
    }
}

/**
 * Decides which mode to ask the other side for, from what actually arrives: packet loss over the last
 * few seconds and how late packets come (jitter). Steps down fast when things get bad, steps up slowly.
 */
class QualityController(start: VoiceMode = VoiceMode.STANDARD) {
    var wanted: VoiceMode = start
        private set
    private var expected = 0
    private var received = 0
    private var lastSeq = -1L
    private var lastArrival = 0L
    private var jitter = 0.0               // smoothed |arrival gap − expected gap|, ms
    private var windowStart = 0L
    private var goodSince = 0L

    /** Call for every packet that arrives. Returns true if [wanted] changed. */
    fun onPacket(seq: Long, frameMs: Int, now: Long): Boolean {
        if (windowStart == 0L) { windowStart = now; goodSince = now }
        if (lastSeq >= 0 && seq > lastSeq) expected += (seq - lastSeq).toInt().coerceAtMost(100) else expected += 1
        received++
        if (lastArrival > 0 && seq > lastSeq) {
            val gap = (now - lastArrival).toDouble() / (seq - lastSeq)
            jitter += (kotlin.math.abs(gap - frameMs) - jitter) / 8.0
        }
        if (seq > lastSeq) lastSeq = seq
        lastArrival = now
        if (now - windowStart < 3_000) return false
        val loss = if (expected > 0) 1.0 - received.toDouble() / expected else 0.0
        expected = 0; received = 0; windowStart = now
        val before = wanted
        when {
            loss > 0.08 || jitter > 90 -> { wanted = wanted.down(); goodSince = now }
            loss < 0.02 && jitter < 35 && now - goodSince > 10_000 -> { wanted = wanted.up(); goodSince = now }
            loss >= 0.02 || jitter >= 35 -> goodSince = now
        }
        return wanted != before
    }
}
