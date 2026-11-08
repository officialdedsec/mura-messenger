package com.mura.mesh.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mura.mesh.core.CallCipher
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.QualityController
import com.mura.mesh.core.Resample
import com.mura.mesh.core.VoiceCodec
import com.mura.mesh.core.VoiceMode
import com.mura.mesh.core.Payload
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import com.mura.mesh.mesh.MeshEngine
import com.mura.mesh.mesh.MeshPermissions
import com.mura.mesh.mesh.formatDuration
import com.mura.mesh.service.MeshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

enum class CallStatus { IDLE, CALLING, RINGING, ACTIVE }

data class CallState(
    val status: CallStatus = CallStatus.IDLE,
    val peerHex: String = "",
    val name: String = "",
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val note: String = "",
    val recording: Boolean = false,
    val peerRecording: Boolean = false,
    /** Live audio quality, e.g. "HD voice · Bluetooth" — adapts to the connection during the call. */
    val quality: String = "",
)

/**
 * Live voice calls between two phones linked directly over Bluetooth or over Tor.
 * Audio adapts in real time (see [VoiceMode]): HD 16 kHz when the link is good, Standard 8 kHz, or Saver
 * with fewer, larger packets on a weak link — each side measures loss and lateness and asks the other to
 * step up or down. Every packet is AES-GCM encrypted. The call key comes from a fresh X25519 exchange
 * (an ephemeral key in the offer and one in the answer), and both ephemeral private keys are
 * erased once the key is made — so a call can't be decrypted later even with both phones' keys
 * (forward secrecy). Calls never travel through relays: they are live only.
 */
class CallManager(private val ctx: Context, private val engine: MeshEngine) {

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var callId = ByteArray(16)
    private var cipher: CallCipher? = null
    /** My ephemeral key while waiting for the answer (caller), and the caller's public half (callee). */
    private var myEph: ByteArray? = null
    private var peerEph: ByteArray? = null

    private fun callKey(dh: ByteArray): ByteArray = Crypto.hkdf(dh, callId, "mura-call-key-v3".toByteArray())
    private var linkId: String? = null
    private var iAmCaller = false
    @Volatile private var running = false
    @Volatile private var muted = false
    private var sendSeq = 0L
    private var lastRecvSeq = -1L
    private val playQueue = LinkedBlockingDeque<ShortArray>()
    /** Loudness of the call right now (0..1, mine or theirs, whichever is louder) — only for the call screen's ring. */
    @Volatile private var micLevel = 0f
    @Volatile private var peerLevel = 0f
    val voiceLevel: Float get() = maxOf(micLevel, peerLevel)
    private fun loudness(pcm: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0L
        var i = 0
        while (i < n) { val v = pcm[i].toInt(); sum += if (v < 0) -v else v; i += 2 }
        return kotlin.math.sqrt(((sum * 2f / n) / 4000f).coerceIn(0f, 1f))
    }
    /** What I send (asked for by the other side) and what I ask them to send me. */
    @Volatile private var sendMode = VoiceMode.STANDARD
    private var quality = QualityController()
    @Volatile private var recvMode = VoiceMode.STANDARD
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val recorder = CallRecorder(ctx)

    private val timeout = Runnable {
        val s = _state.value
        when (s.status) {
            CallStatus.CALLING -> { engine.sendCallSignal(s.peerHex, Payload.CALL_END, callId, ByteArray(32)); finish("No answer", true) }
            CallStatus.RINGING -> { engine.notifier.missedCall(s.peerHex, s.name); finish("Missed call", false) }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- user actions

    /** Returns null on success, or a reason the call can't start. */
    @Synchronized
    fun startCall(peerHex: String, name: String): String? {
        if (_state.value.status != CallStatus.IDLE) return "Already in a call"
        if (!MeshPermissions.granted(ctx, Manifest.permission.RECORD_AUDIO)) return "Microphone permission needed"
        val link = engine.router.linkFor(peerHex.hexToBytes()) ?: return "$name is not in Bluetooth range. Send a voice message instead — it will be delivered when possible."
        callId = Crypto.randomBytes(16)
        val eph = Crypto.curve.generatePrivate()
        myEph = eph
        cipher = null
        iAmCaller = true
        linkId = link.id
        if (!engine.sendCallSignal(peerHex, Payload.CALL_OFFER, callId, Crypto.curve.publicFrom(eph))) return "Lost the link to $name"
        _state.value = CallState(CallStatus.CALLING, peerHex, name)
        main.postDelayed(timeout, 45_000)
        return null
    }

    @Synchronized
    fun answer() {
        val s = _state.value
        if (s.status != CallStatus.RINGING) return
        if (!MeshPermissions.granted(ctx, Manifest.permission.RECORD_AUDIO)) return
        val theirs = peerEph ?: return
        main.removeCallbacks(timeout)
        engine.notifier.cancelCall()
        val eph = Crypto.curve.generatePrivate()
        val dh = Crypto.curve.shared(eph, theirs)
        cipher = CallCipher(callKey(dh), iAmCaller = false)
        dh.fill(0)
        engine.sendCallSignal(s.peerHex, Payload.CALL_ANSWER, callId, Crypto.curve.publicFrom(eph))
        eph.fill(0)
        peerEph = null
        goActive()
    }

    @Synchronized
    fun decline() {
        val s = _state.value
        if (s.status != CallStatus.RINGING) return
        engine.sendCallSignal(s.peerHex, Payload.CALL_REJECT, callId, ByteArray(32))
        finish("Declined call", false)
    }

    @Synchronized
    fun hangup() {
        val s = _state.value
        if (s.status == CallStatus.IDLE) return
        engine.sendCallSignal(s.peerHex, Payload.CALL_END, callId, ByteArray(32))
        val text = if (s.status == CallStatus.ACTIVE) "Call · ${formatDuration((System.currentTimeMillis() - s.startedAt).toInt())}" else "Cancelled call"
        finish(text, iAmCaller)
    }

    /** Record both sides of the call. The other person is told, and sees a banner while it's on. */
    @Synchronized
    fun toggleRecording(): String? {
        val s = _state.value
        if (s.status != CallStatus.ACTIVE) return "Recording starts once the call is connected"
        return if (!recorder.isRecording) {
            if (!recorder.start()) return "This phone couldn't start the recorder"
            engine.sendCallSignal(s.peerHex, Payload.CALL_RECORDING_ON, callId, ByteArray(32))
            _state.value = s.copy(recording = true)
            null
        } else {
            saveRecording()
            null
        }
    }

    private fun saveRecording() {
        val s = _state.value
        if (!recorder.isRecording) return
        engine.sendCallSignal(s.peerHex, Payload.CALL_RECORDING_OFF, callId, ByteArray(32))
        _state.value = s.copy(recording = false)
        Thread {
            val prepared = recorder.stop()
            if (prepared != null && s.peerHex.isNotEmpty()) engine.saveCallRecording(s.peerHex, prepared)   // call history only, never in chat
        }.start()
    }

    fun toggleMute() {
        muted = !muted
        _state.value = _state.value.copy(muted = muted)
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val on = !_state.value.speaker
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audio.setCommunicationDevice(it) }
            } else audio.clearCommunicationDevice()
        } else {
            audio.isSpeakerphoneOn = on
        }
        _state.value = _state.value.copy(speaker = on)
    }

    // ---------------------------------------------------------------- signalling from the peer

    @Synchronized
    fun onSignal(fromLink: String, peerHex: String, name: String, p: Payload.CallSignal) {
        val s = _state.value
        when (p.action) {
            Payload.CALL_OFFER -> {
                if (s.status != CallStatus.IDLE) {
                    engine.sendCallSignal(peerHex, Payload.CALL_BUSY, p.callId, ByteArray(32))
                    engine.logCall(peerHex, false, "Missed call (you were busy)")
                    return
                }
                callId = p.callId
                peerEph = p.callKey.copyOf()
                cipher = null
                iAmCaller = false
                linkId = fromLink
                _state.value = CallState(CallStatus.RINGING, peerHex, name)
                engine.notifier.incomingCall(peerHex, name)
                main.postDelayed(timeout, 45_000)
            }
            Payload.CALL_ANSWER -> if (s.status == CallStatus.CALLING && p.callId.contentEquals(callId) && peerHex == s.peerHex) {
                val eph = myEph ?: return
                val dh = Crypto.curve.shared(eph, p.callKey)
                cipher = CallCipher(callKey(dh), iAmCaller = true)
                dh.fill(0); eph.fill(0); myEph = null
                main.removeCallbacks(timeout)
                goActive()
            }
            Payload.CALL_REJECT -> if (p.callId.contentEquals(callId) && peerHex == s.peerHex) finish("Call declined", true)
            Payload.CALL_BUSY -> if (p.callId.contentEquals(callId) && peerHex == s.peerHex) finish("Busy", true)
            Payload.CALL_RECORDING_ON -> if (p.callId.contentEquals(callId) && peerHex == s.peerHex) _state.value = s.copy(peerRecording = true)
            Payload.CALL_RECORDING_OFF -> if (p.callId.contentEquals(callId) && peerHex == s.peerHex) _state.value = s.copy(peerRecording = false)
            Payload.CALL_END -> if (p.callId.contentEquals(callId) && peerHex == s.peerHex) {
                when (s.status) {
                    CallStatus.RINGING -> { engine.notifier.missedCall(peerHex, s.name); finish("Missed call", false) }
                    CallStatus.ACTIVE -> finish("Call · ${formatDuration((System.currentTimeMillis() - s.startedAt).toInt())}", iAmCaller)
                    else -> finish("Call ended", iAmCaller)
                }
            }
        }
    }

    fun onLinkLost(peerHex: String) {
        val s = _state.value
        if (s.status != CallStatus.IDLE && s.peerHex == peerHex) {
            synchronized(this) { finish("Call dropped — out of range", iAmCaller) }
        }
    }

    fun onAudio(fromLink: String, seq: Long, data: ByteArray) {
        if (!running || fromLink != linkId) return
        if (seq <= lastRecvSeq) return
        val c = cipher ?: return
        val d = try { VoiceCodec.decode(c.decrypt(seq, data)) } catch (e: Exception) { return }
        lastRecvSeq = seq
        sendMode = d.wanted                                   // the other side tells me what it can take
        val changed = quality.onPacket(seq, d.mode.frameMs, System.currentTimeMillis())
        if (changed || d.mode != recvMode) {
            recvMode = d.mode
            val via = if (linkId?.startsWith("tor:") == true) "Tor" else "Bluetooth"
            _state.value = _state.value.copy(quality = "${d.mode.label} · $via")
        }
        recorder.onRemote(Resample.down2(d.pcm16k))
        // Keep latency low: never hold more than ~0.5 s of audio.
        while (playQueue.sumOf { it.size } > 8000) playQueue.pollFirst()
        playQueue.offerLast(d.pcm16k)
    }

    // ---------------------------------------------------------------- audio

    private fun goActive() {
        _state.value = _state.value.copy(status = CallStatus.ACTIVE, startedAt = System.currentTimeMillis())
        engine.notifier.ongoingCall(_state.value.name)
        MeshService.setMicrophone(ctx, true)
        startAudio()
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (running) return
        running = true
        sendSeq = 0; lastRecvSeq = -1; playQueue.clear(); muted = false
        sendMode = VoiceMode.STANDARD; recvMode = VoiceMode.STANDARD; quality = QualityController()
        _state.value = _state.value.copy(quality = "Standard · " + if (linkId?.startsWith("tor:") == true) "Tor" else "Bluetooth")
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
        audio.requestAudioFocus(focusRequest!!)

        val rate = 16000                       // captured and played at 16 kHz; each packet's mode decides what's sent
        val frame = 320                        // 20 ms read size
        captureThread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 8))
            } catch (e: Exception) { Log.w("MuraCall", "no mic", e); return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@Thread }
            val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
            val buf = ShortArray(frame)
            var pending = ShortArray(0)
            try {
                rec.startRecording()
                while (running) {
                    var off = 0
                    while (off < frame && running) {
                        val n = rec.read(buf, off, frame - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (!running) break
                    if (off < frame) continue
                    if (muted) buf.fill(0)
                    micLevel = if (muted) 0f else loudness(buf, frame)
                    recorder.onMic(Resample.down2(buf), frame / 2)
                    pending += buf
                    val mode = sendMode
                    val need = 16 * mode.frameMs              // samples at 16 kHz for one packet in this mode
                    if (pending.size < need) continue
                    val chunk = pending.copyOf(need)
                    pending = pending.copyOfRange(need, pending.size)
                    val c = cipher ?: break
                    val seq = sendSeq++
                    val id = linkId ?: break
                    engine.router.sendAudio(id, seq, c.encrypt(seq, VoiceCodec.encode(chunk, mode, quality.wanted)))
                }
            } catch (e: Exception) {
                Log.w("MuraCall", "capture", e)
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                aec?.release(); ns?.release()
                rec.release()
                micLevel = 0f
            }
        }, "mura-call-capture").apply { start() }

        playThread = Thread({
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, frame * 2 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                while (running) {
                    val pcm = playQueue.pollFirst(200, TimeUnit.MILLISECONDS) ?: run { peerLevel = 0f; null } ?: continue
                    peerLevel = loudness(pcm, pcm.size)
                    track.write(pcm, 0, pcm.size)
                }
            } catch (e: Exception) {
                Log.w("MuraCall", "playback", e)
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
                peerLevel = 0f
            }
        }, "mura-call-play").apply { start() }
    }

    @Suppress("DEPRECATION")
    private fun stopAudio() {
        if (!running) return
        running = false
        captureThread = null; playThread = null
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
        MeshService.setMicrophone(ctx, false)
    }

    private fun finish(logText: String, outgoing: Boolean) {
        main.removeCallbacks(timeout)
        if (recorder.isRecording) saveRecording()
        val s = _state.value
        stopAudio()
        engine.notifier.cancelCall()
        if (s.peerHex.isNotEmpty()) engine.logCall(s.peerHex, outgoing, logText)
        cipher = null
        myEph?.fill(0); myEph = null
        peerEph = null
        linkId = null
        _state.value = CallState(note = logText)
    }
}
