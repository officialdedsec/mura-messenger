package com.mura.mesh.call

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.mura.mesh.core.Payload
import com.mura.mesh.media.Prepared
import com.mura.mesh.media.SecureMedia
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Records both sides of a call into one small AAC stream (16 kHz mono, 24 kbit/s ≈ 180 KB per minute).
 * Your microphone frames drive the clock; the other person's audio is mixed in as it arrives,
 * so the recording stays in sync.
 *
 * Privacy: the encoded audio is kept only in memory while recording (ADTS frames, no container file),
 * then sealed (AES-256-GCM, its own random key) straight into the app's private storage. No readable
 * copy is ever written to the phone's storage, not even temporarily.
 */
class CallRecorder(private val ctx: Context) {
    private val remote = ConcurrentLinkedQueue<ShortArray>()
    private var codec: MediaCodec? = null
    private var ptsUs = 0L
    private var buffer: WipeableBuffer? = null
    private var startedAt = 0L
    private val info = MediaCodec.BufferInfo()

    val isRecording: Boolean get() = codec != null

    @Synchronized
    fun start(): Boolean {
        if (codec != null) return true
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c; ptsUs = 0; remote.clear()
            buffer = WipeableBuffer()
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            release(); false
        }
    }

    fun onRemote(pcm8k: ShortArray) {
        if (codec == null) return
        if (remote.size > 50) remote.poll()
        remote.offer(pcm8k)
    }

    /** Called for every 40 ms of microphone audio (8 kHz). */
    @Synchronized
    fun onMic(pcm8k: ShortArray, count: Int) {
        val c = codec ?: return
        val other = remote.poll()
        // Mix both voices, then upsample 8 kHz -> 16 kHz (linear) for a cleaner AAC encode.
        val mixed = ShortArray(count) { i ->
            val v = pcm8k[i].toInt() + (if (other != null && i < other.size) other[i].toInt() else 0)
            v.coerceIn(-32768, 32767).toShort()
        }
        val up = ShortArray(count * 2)
        for (i in 0 until count) {
            val a = mixed[i].toInt()
            val b = if (i + 1 < count) mixed[i + 1].toInt() else a
            up[i * 2] = a.toShort(); up[i * 2 + 1] = ((a + b) / 2).toShort()
        }
        val bytes = ByteBuffer.allocate(up.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(up)
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                val n = minOf(buf.remaining(), up.size * 2)
                buf.put(bytes.array(), 0, n)
                c.queueInputBuffer(idx, 0, n, ptsUs, 0)
                ptsUs += (n / 2) * 1_000_000L / SAMPLE_RATE
            }
            drain(c, false)
        } catch (e: Exception) { /* keep the call going even if recording hiccups */ }
    }

    private fun drain(c: MediaCodec, eos: Boolean) {
        var tries = 0
        while (true) {
            val out = c.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!eos || ++tries > 50) return }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                out >= 0 -> {
                    val b = c.getOutputBuffer(out)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val sink = buffer
                    if (b != null && info.size > 0 && !config && sink != null && sink.size() < MAX_BYTES) {
                        val frame = ByteArray(info.size)
                        b.position(info.offset); b.get(frame, 0, info.size)
                        sink.write(adtsHeader(info.size)); sink.write(frame)
                        frame.fill(0)
                    }
                    c.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** 7-byte ADTS header: AAC-LC, 16 kHz, mono — makes the raw frames a playable .aac stream. */
    private fun adtsHeader(payload: Int): ByteArray {
        val len = payload + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (FREQ_INDEX shl 2) or (CHANNELS shr 2)).toByte(),
            (((CHANNELS and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** Stop and return the sealed recording (or null if nothing was captured). */
    @Synchronized
    fun stop(): Prepared? {
        val c = codec ?: return null
        val durationMs = (System.currentTimeMillis() - startedAt).toInt()
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) c.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(c, true)
        } catch (_: Exception) {}
        val data = buffer?.toByteArray()
        release()
        if (data == null || data.isEmpty() || durationMs < 1000) { data?.fill(0); return null }
        return SecureMedia.sealLocalBytes(ctx, data, Payload.MEDIA_VOICE, "call-recording.aac", "audio/aac", durationMs)
    }

    private fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null; remote.clear()
        buffer?.wipe()   // overwrite the plain audio in memory
        buffer = null
    }

    /** A byte buffer whose contents can be overwritten with zeros, not just forgotten. */
    private class WipeableBuffer : java.io.ByteArrayOutputStream(256 * 1024) {
        @Synchronized fun wipe() { java.util.Arrays.fill(buf, 0.toByte()); reset() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FREQ_INDEX = 8          // 16 kHz in the ADTS sampling-frequency table
        const val CHANNELS = 1
        const val MAX_BYTES = 96 * 1024 * 1024   // about 9 hours at 24 kbit/s
    }
}
