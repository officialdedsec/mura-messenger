package com.mura.mesh.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.toHex
import com.mura.mesh.data.FileRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Hold-to-record voice notes.
 * Android 10+: Opus 16 kbit/s in Ogg (~2 KB per second, excellent for speech).
 * Android 9:   AAC 24 kbit/s (~3 KB per second).
 * The recording exists in plain form only in the app's private cache until it is sealed.
 */
class VoiceRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null
    val usesOpus = Build.VERSION.SDK_INT >= 29

    fun start(): Boolean {
        stopQuietly()
        val dest = File(SecureMedia.tempDir(ctx), "rec_" + Crypto.randomBytes(6).toHex() + if (usesOpus) ".ogg" else ".m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (usesOpus) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioEncodingBitRate(16_000)
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(24_000)
            }
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setOutputFile(dest.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = dest
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            dest.delete()
            false
        }
    }

    fun elapsedMs(): Long = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Loudest point since the last call, 0..1 (for the recording orb). Safe to call at any time. */
    fun level(): Float = try {
        val a = recorder?.maxAmplitude ?: 0
        kotlin.math.sqrt((a / 20000f).coerceIn(0f, 1f))
    } catch (e: Exception) { 0f }

    /** Finish and return the sealed note, or null if too short / failed. */
    fun stop(): Prepared? {
        val r = recorder ?: return null
        val f = file
        val dur = elapsedMs().toInt()
        recorder = null; file = null
        val ok = try { r.stop(); true } catch (e: Exception) { false }
        try { r.release() } catch (_: Exception) {}
        if (!ok || f == null || dur < 700) { f?.delete(); return null }
        return SecureMedia.sealLocal(ctx, f, com.mura.mesh.core.Payload.MEDIA_VOICE,
            if (usesOpus) "voice.ogg" else "voice.m4a", if (usesOpus) "audio/ogg" else "audio/mp4", dur)
    }

    fun cancel() = stopQuietly()

    private fun stopQuietly() {
        val r = recorder ?: return
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        file?.delete()
        recorder = null; file = null
    }
}

/** Plays sealed voice notes and call recordings straight from encrypted storage. */
object VoicePlayer {
    data class Playing(val id: String = "", val positionMs: Int = 0, val durationMs: Int = 0, val playing: Boolean = false)

    private val _state = MutableStateFlow(Playing())
    val state: StateFlow<Playing> = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            try { _state.value = _state.value.copy(positionMs = p.currentPosition) } catch (_: Exception) {}
            handler.postDelayed(this, 100)
        }
    }

    fun toggle(id: String, file: FileRow) {
        val cur = _state.value
        val existing = player
        if (cur.id == id && existing != null) {
            if (existing.isPlaying) { existing.pause(); _state.value = cur.copy(playing = false); handler.removeCallbacks(tick) }
            else { existing.start(); _state.value = cur.copy(playing = true); handler.post(tick) }
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(SealedDataSource(SecureMedia.reader(file)))
            p.prepare()
            p.setOnCompletionListener { stop() }
            p.start()
            player = p
            _state.value = Playing(id, 0, p.duration, true)
            handler.post(tick)
        } catch (e: Exception) {
            p.release()
        }
    }

    fun stop() {
        handler.removeCallbacks(tick)
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        _state.value = Playing()
    }
}
