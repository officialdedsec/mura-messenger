package com.mura.mesh.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.mura.mesh.data.FileRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Voice note → text, entirely on the phone.
 *
 * Uses Android's *on-device* speech recognizer only (Android 13+; it never sends audio anywhere).
 * The voice note is decrypted and decoded in memory and streamed to the recognizer through a
 * pipe, so no readable audio file is ever written. The transcript is stored encrypted with the
 * message and is never sent to anyone.
 */
object Transcriber {

    class Unavailable(msg: String) : Exception(msg)

    fun available(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    suspend fun transcribe(ctx: Context, f: FileRow): String {
        if (!available(ctx)) throw Unavailable("Transcripts need Android 13 or newer with on-device speech recognition.")
        val (pcm, rate) = withContext(Dispatchers.IO) { decodePcm(f) }
        if (pcm.isEmpty()) throw Unavailable("This voice message has no sound.")
        return withContext(Dispatchers.Main) { recognize(ctx, pcm, rate) }
    }

    /** Decrypt + decode the voice note to 16-bit mono PCM, in memory. */
    private fun decodePcm(f: FileRow): Pair<ByteArray, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(SealedDataSource(SecureMedia.reader(f)))
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); throw Unavailable("Not an audio message") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        val out = ByteArrayOutputStream()
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size)
                    buf.position(info.offset); buf.get(chunk)
                    out.write(if (channels > 1) downmix(chunk, channels) else chunk)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                if (out.size() > 60 * 1024 * 1024) break   // ~30 minutes at 16 kHz: plenty
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release(); ex.release()
        }
        return out.toByteArray() to rate
    }

    private fun downmix(pcm: ByteArray, channels: Int): ByteArray {
        val frames = pcm.size / (2 * channels)
        val mono = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val k = (i * channels + c) * 2
                sum += (pcm[k].toInt() and 0xff) or (pcm[k + 1].toInt() shl 8)
            }
            val v = sum / channels
            mono[i * 2] = v.toByte(); mono[i * 2 + 1] = (v shr 8).toByte()
        }
        return mono
    }

    private suspend fun recognize(ctx: Context, pcm: ByteArray, rate: Int): String = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < 33) { cont.resumeWithException(Unavailable("Needs Android 13")); return@suspendCancellableCoroutine }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        val pipe = ParcelFileDescriptor.createPipe()
        val text = StringBuilder()
        var finished = false
        fun finish(err: Exception? = null) {
            if (finished) return
            finished = true
            try { rec.destroy() } catch (_: Exception) {}
            try { pipe[0].close() } catch (_: Exception) {}
            val t = text.toString().trim()
            if (t.isNotEmpty() || err == null) cont.resume(t) else cont.resumeWithException(err)
        }
        fun add(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) text.append(it.trim()).append(' ') } }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onSegmentResults(segmentResults: Bundle) { add(segmentResults) }
            override fun onEndOfSegmentedSession() { finish() }
            override fun onResults(results: Bundle?) { add(results); finish() }
            override fun onError(error: Int) {
                finish(Unavailable(when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "The on-device speech pack for this language isn't installed. Add it in Android Settings › System › Languages › On-device speech recognition."
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech found in this message."
                    else -> "Couldn't transcribe this message (error $error)."
                }))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
            // Long notes: keep going through pauses and report each segment.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        rec.startListening(intent)
        Thread {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } } catch (_: Exception) {}
        }.start()
        cont.invokeOnCancellation { try { rec.destroy() } catch (_: Exception) {} }
    }
}
