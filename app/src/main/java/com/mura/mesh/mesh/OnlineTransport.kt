package com.mura.mesh.mesh

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.Frame
import com.mura.mesh.core.Onion
import com.mura.mesh.core.Socks5
import com.mura.mesh.core.StreamLink
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import com.mura.mesh.data.ContactState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.torproject.jni.TorService
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Internet links with no server: every Mura phone is its own Tor onion service.
 *
 *  • Tor runs inside the app. Your phone publishes a hidden service whose address only your
 *    contacts learn (from your QR code in person, or your end-to-end encrypted profile).
 *  • To deliver, a phone connects through Tor to the recipient's onion address. Tor encrypts
 *    end to end between the two onion services and hides both phones' IP addresses — nobody,
 *    not even Tor relays, learns who talks to whom.
 *  • Inside that tunnel runs the exact same mesh protocol as Bluetooth, carrying the same
 *    sealed, forward-secret bundles. Only contacts may connect; strangers are dropped at "hello".
 *  • Both phones need to be online at the same time; until then messages wait on your phone
 *    (and still hop over Bluetooth when people meet).
 */
class OnlineTransport(private val ctx: Context, private val engine: MeshEngine) {

    enum class Status { OFF, NO_NETWORK, STARTING, ON, ERROR }
    data class State(val status: Status = Status.OFF, val note: String = "", val linked: Int = 0)

    companion object {
        private const val TAG = "MuraOnline"
        const val ONION_KEY = "onion_key"          // secret (Vault): ED25519-V3 key of my onion service
        const val ONION_ADDR = "onion_addr"
        const val MODE = "online_mode"             // off | wifi | any
        const val ROTATE = "tor_rotate_min"        // new Tor circuits every N minutes (0 = only when asked)
        private const val VIRTUAL_PORT = 80
        private const val IDLE_MS = 60_000L
        private const val MAX_LINKS = 32             // open Tor connections at most (flood guard)
        private const val HANDSHAKE_MS = 30_000L     // unproven connections are dropped after this
        private const val CONNECT_TIMEOUT_MS = 150_000   // longer than Tor's own onion timeout, so Tor's exact reason arrives first
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val mode: String get() = engine.db.getSetting(MODE, "any")
    val onionAddress: String? get() = engine.db.getSetting(ONION_ADDR, "").ifBlank { null } ?: ensureIdentity()

    /**
     * My onion identity, made on the phone the first time it's needed — so the address is in my QR code
     * and profile even before Tor has ever connected (otherwise contacts added offline could never reach me).
     */
    @Synchronized
    fun ensureIdentity(): String? {
        engine.db.getSetting(ONION_ADDR, "").ifBlank { null }?.let { return it }
        if (engine.db.getSecret(ONION_KEY) != null) return null       // made by Tor earlier; the address arrives on publish
        val id = com.mura.mesh.core.OnionKeys.create()
        engine.db.setSecret(ONION_KEY, id.torKey.toByteArray(Charsets.UTF_8))
        engine.db.setSetting(ONION_ADDR, id.address)
        return id.address
    }

    @Volatile private var tor: TorService? = null
    @Volatile private var socksPort = 0
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var torReady = false
    private var bound = false
    private var receiverRegistered = false
    private val exec = Executors.newCachedThreadPool()

    private class Remote(val link: StreamLink, val expected: String?, val onHello: ((String, String) -> Unit)? = null) {
        /** Who the first HELLO claims to be — not trusted until the router has checked their proof. */
        @Volatile var claimed: String? = null
        /** Set only after the other phone has proved it holds that key (MeshRouter's Proof check). */
        @Volatile var peer: String? = null
        val since = System.currentTimeMillis()
    }
    private val links = ConcurrentHashMap<String, Remote>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()
    private val backoff = ConcurrentHashMap<String, Pair<Int, Long>>()     // pub -> (failures, next attempt)
    private val lastSync = ConcurrentHashMap<String, Long>()              // pub -> when we last finished a sync
    @Volatile private var poked = true
    /** Per-contact SOCKS credentials → each contact gets its own Tor circuit. Replaced on every rotation. */
    private val isolation = ConcurrentHashMap<String, String>()
    @Volatile private var lastRotate = 0L
    /** Just connected to Tor: knock on every contact once, to collect messages they've been holding for us. */
    @Volatile private var checkInDue = false
    @Volatile private var bridgeFailedAt = 0L
    /** Tor has finished connecting (bootstrapped 100%) — only then do we dial anyone or allow web requests. */
    @Volatile private var circuitUp = false
    /** My onion service is registered with Tor. */
    @Volatile private var published = false
    private val fullyUp: Boolean get() = torReady && circuitUp && published
    val rotateMinutes: Int get() = engine.db.getSetting(ROTATE, "10").toIntOrNull() ?: 10

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            tor = (binder as? TorService.LocalBinder)?.service
            // Tor may already be running in this process (we never restart it): pick it up directly.
            if (tor?.torControlConnection != null) torReady = true
            if (torReady) exec.execute { publish() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { tor = null; torReady = false; setStatus(Status.STARTING, "Tor stopped, restarting…") }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_ON -> { torReady = true; exec.execute { publish() } }
                TorService.STATUS_STARTING -> setStatus(Status.STARTING, "Connecting to the Tor network…")
                TorService.STATUS_OFF -> { torReady = false; circuitUp = false; published = false; TorNet.socksPort = 0; if (running) setStatus(Status.STARTING, "Tor is restarting…") }
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Synchronized
    fun start() {
        if (running || mode == "off") return
        running = true
        exec.execute { loop() }
    }

    /** Internet turned off in Settings: Tor stays loaded but goes completely offline (it can't be restarted in-process). */
    @Synchronized
    fun stop() {
        running = false
        pauseTor()
        setStatus(Status.OFF, "")
    }

    /** Only for panic erase / restore: fully unload Tor. */
    @Synchronized
    fun shutdown() { running = false; stopTor() }

    @Volatile private var paused = false

    private fun setConf(lines: List<String>): Boolean = try { tor?.torControlConnection?.setConf(lines); tor?.torControlConnection != null } catch (e: Exception) { Log.w(TAG, "setconf", e); false }

    /** No network, or internet off: close links and tell Tor to stop using the network — without stopping Tor. */
    private fun pauseTor() {
        for (r in links.values) r.link.close("internet off")
        links.clear()
        circuitUp = false
        TorNet.socksPort = 0
        if (!paused && bound) {
            paused = true; setConf(listOf("DisableNetwork 1"))
            // When the network comes back, Android wakes Mura (even if it was killed meanwhile) to reconnect and sync.
            com.mura.mesh.service.KeepAlive.whenNetworkReturns(ctx)
        }
    }

    private fun resumeTor() {
        if (!paused) return
        paused = false
        setConf(listOf("DisableNetwork 0"))
        lastProgressAt = 0L; stuckSince = System.currentTimeMillis(); stuckPct = -1
    }

    @Volatile private var lastProgressAt = 0L
    @Volatile private var stuckPct = -1
    @Volatile private var stuckSince = 0L

    @Volatile private var extendedErrors = false

    /** Ask Tor for precise onion-service error codes (descriptor not found, introduction failed, …) on its SOCKS port. */
    private fun enableExtendedErrors() {
        if (extendedErrors) return
        val c = tor?.torControlConnection ?: return
        // Tor keeps an existing "auto" listener (and its old flags), so move to a fresh local port to apply the flag.
        try {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            c.setConf(listOf("SocksPort 127.0.0.1:$port ExtendedErrors")); extendedErrors = true
        }
        catch (e: Exception) { Log.w(TAG, "extended errors", e) }
    }

    /** Tor's real SOCKS port, read from Tor itself (TorService can report -1 or a stale port). */
    private fun readSocksPort(): Int {
        val info = try { tor?.torControlConnection?.getInfo("net/listeners/socks") } catch (e: Exception) { null }
        val p = info?.let { Regex(":(\\d+)").findAll(it).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }
        return p ?: (tor?.socksPort ?: -1)
    }

    private fun goOnline() {
        enableExtendedErrors()
        // Tor reports its SOCKS port only once its control thread is up; read it again now (it starts as -1).
        readSocksPort().takeIf { it > 0 }?.let { socksPort = it }
        if (socksPort <= 0) { setStatus(Status.STARTING, "Connected to Tor · waiting for its local port…"); circuitUp = false; return }
        setStatus(Status.ON, "Online through Tor · your IP address stays hidden")
        selfTestDue = true
        // Only now may anything in the app use the internet — and only through Tor.
        TorNet.socksPort = socksPort
        lastRotate = System.currentTimeMillis()
        lastRotateBase = lastRotate
        backoff.clear()
        checkInDue = true
        poke()
    }

    /** "Connecting to Tor… 45% · Loading relay information": read from Tor itself every few seconds. */
    private fun bootstrapProgress() {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < 2_500) return
        lastProgressAt = now
        val phase = try { tor?.torControlConnection?.getInfo("status/bootstrap-phase") } catch (e: Exception) { null } ?: return
        val pct = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (pct >= 100) {
            circuitUp = true
            if (published) goOnline() else setStatus(Status.STARTING, "Connected to Tor · publishing your private address…")
            return
        }
        val summary = Regex("SUMMARY=\"([^\"]*)\"").find(phase)?.groupValues?.get(1) ?: ""
        val bridges = engine.db.getSetting(Bridges.MODE, "off")
        val via = when (bridges) { "snowflake" -> " via Snowflake"; "custom" -> " via your bridges"; else -> "" }
        if (pct != stuckPct) { stuckPct = pct; stuckSince = now }
        val stuck = now - stuckSince > 150_000
        val hint = when {
            !stuck -> ""
            bridges == "snowflake" -> " — Snowflake is slow or blocked on this network. Try Bridges: Off, or paste obfs4/webtunnel bridges."
            bridges == "custom" -> " — these bridges aren't answering. Try fresh ones or Snowflake."
            else -> " — Tor may be blocked here. Try Bridges: Snowflake."
        }
        setStatus(Status.STARTING, "Connecting to Tor$via… $pct%" + (if (summary.isNotBlank()) " · $summary" else "") + hint)
    }

    /**
     * Bridges changed in Settings: applied live through Tor's control port. Tor is never stopped and started
     * again inside the app — that crashes native Tor — so switching Snowflake / bridges / off is instant and safe.
     */
    fun restartTor() {
        exec.execute {
            bridgeFailedAt = 0L
            val cfg = Bridges.prepare(ctx, engine.db.getSetting(Bridges.MODE, "off"), engine.db.getSetting(Bridges.LINES, ""))
            if (cfg.problem != null) { setStatus(Status.ERROR, cfg.problem); return@execute }
            if (!bound) { poke(); return@execute }          // not started yet: torrc is already written for the start
            val ok = try {
                val c = tor?.torControlConnection
                if (c == null) false else {
                    if (cfg.lines == listOf("UseBridges 0")) c.resetConf(listOf("Bridge", "ClientTransportPlugin"))
                    c.setConf(cfg.lines); true
                }
            } catch (e: Exception) { Log.w(TAG, "bridges", e); false }
            for (r in links.values) r.link.close("bridges changed")
            circuitUp = false; TorNet.socksPort = 0; lastProgressAt = 0L; stuckSince = System.currentTimeMillis(); stuckPct = -1
            setStatus(Status.STARTING, if (ok) "Reconnecting to Tor with the new setting…" else "Saved — applies when Tor is ready")
            poke()
        }
    }

    /** Mode changed in Settings. */
    fun applyMode() { if (mode == "off") stop() else { start(); poke() } }

    /** Something new to deliver: check who to connect to now. */
    fun poke() { poked = true }

    private fun networkAllowed(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return mode != "wifi" || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Synchronized
    private fun startTor() {
        if (bound) return
        if (System.currentTimeMillis() - bridgeFailedAt < 60_000) return
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(ctx, statusReceiver, IntentFilter(TorService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        // Bridges (if chosen) are written into Tor's configuration and their transports started first.
        val bridgeMode = engine.db.getSetting(Bridges.MODE, "off")
        val problem = Bridges.prepare(ctx, bridgeMode, engine.db.getSetting(Bridges.LINES, "")).problem
        if (problem != null) { bridgeFailedAt = System.currentTimeMillis(); setStatus(Status.ERROR, problem); return }
        setStatus(Status.STARTING, when (bridgeMode) { "snowflake" -> "Connecting to Tor through Snowflake…"; "custom" -> "Connecting to Tor through your bridges…"; else -> "Starting Tor…" })
        bound = ctx.bindService(Intent(ctx, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) setStatus(Status.ERROR, "Couldn't start Tor on this phone")
    }

    @Synchronized
    private fun stopTor() {
        for (r in links.values) r.link.close("internet off")
        links.clear()
        try { server?.close() } catch (_: Exception) {}
        server = null
        if (bound) { try { ctx.unbindService(connection) } catch (_: Exception) {}; bound = false }
        Bridges.stopTransports()
        if (receiverRegistered) { try { ctx.unregisterReceiver(statusReceiver) } catch (_: Exception) {}; receiverRegistered = false }
        tor = null; torReady = false; circuitUp = false; published = false; socksPort = 0; TorNet.socksPort = 0
    }

    private fun setStatus(s: Status, note: String) {
        _state.value = State(s, note, links.values.count { it.peer != null })
    }

    // ------------------------------------------------------------ onion service

    /** Publish my onion service (same address every time: the key is kept, encrypted, in the Vault). */
    private fun publish() {
        val t = tor ?: return
        try {
            socksPort = t.socksPort
            val srv = server ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { server = it; acceptLoop(it) }
            val control = t.torControlConnection ?: throw IllegalStateException("no Tor control connection")
            ensureIdentity()
            val stored = engine.db.getSecret(ONION_KEY)?.toString(Charsets.UTF_8)
            val res = try {
                control.addOnion(stored ?: "NEW:ED25519-V3", mapOf(VIRTUAL_PORT to "127.0.0.1:${srv.localPort}"))
            } catch (e: Exception) {
                // Already published in this Tor session (e.g. after our service reconnected).
                if (e.message?.contains("collision", ignoreCase = true) == true) emptyMap() else throw e
            }
            if (stored == null) res["onionPrivKey"]?.let { engine.db.setSecret(ONION_KEY, it.toByteArray(Charsets.UTF_8)) }
            res["onionAddress"]?.let { a ->
                val addr = "$a.onion"
                if (onionAddress != addr) {
                    engine.db.setSetting(ONION_ADDR, addr)
                    engine.onOnionChanged()
                }
            }
            published = true
            if (circuitUp) goOnline() else lastProgressAt = 0L
        } catch (e: Exception) {
            Log.w(TAG, "publish", e)
            setStatus(Status.ERROR, "Tor started but the onion service failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun acceptLoop(srv: ServerSocket) = exec.execute {
        while (!srv.isClosed) {
            val s = try { srv.accept() } catch (e: Exception) { break }
            // Flood guard: a stranger opening hundreds of connections can't exhaust the phone.
            if (links.size >= MAX_LINKS) { try { s.close() } catch (_: Exception) {}; continue }
            try {
                s.tcpNoDelay = true
                attach(s, outgoing = false, expected = null)
            } catch (e: Exception) { try { s.close() } catch (_: Exception) {} }
        }
    }

    private fun attach(s: java.net.Socket, outgoing: Boolean, expected: String?, onHello: ((String, String) -> Unit)? = null): StreamLink {
        val id = "tor:" + Crypto.randomBytes(6).toHex()
        val link = StreamLink(id, outgoing, s.getInputStream(), s.getOutputStream(),
            onFrame = { l, f -> onFrame(l, f) },
            onClosed = { l, reason ->
                val gone = links.remove(l.id)
                gone?.peer?.let { lastSync[it] = System.currentTimeMillis() }
                // Reached their phone but the link never got verified: say why, instead of silently retrying.
                if (gone != null && gone.peer == null && gone.expected != null) {
                    val why = when {
                        reason.contains("protocol") -> "Their Mura is a different version — install the same Mura version on both phones"
                        reason.contains("auth") || reason.contains("identity") -> "Their phone answered but couldn't prove its identity (old QR code? scan again)"
                        reason.contains("blocked") -> "Connection refused"
                        else -> "Reached their phone, but the connection dropped ($reason)"
                    }
                    dialInfo[gone.expected] = DialInfo(why, System.currentTimeMillis(), why)
                }
                engine.onLinkDown(l.id)
                setStatus(_state.value.status, _state.value.note)
            },
            closeable = s)
        links[id] = Remote(link, expected, onHello)
        engine.onLinkUp(link)
        link.start()
        return link
    }

    /** First frame must be HELLO from the contact we called — or, for incoming calls, from any contact. Strangers are cut off. */
    private fun onFrame(l: StreamLink, f: Frame) {
        val r = links[l.id] ?: return
        if (r.claimed == null) {
            if (f !is Frame.Hello) { l.close("no hello"); return }
            val pub = f.publicKey.toHex()
            // Outgoing: must be the contact we called. Incoming: anyone who knows my onion address (only shared
            // in person by QR, or in my encrypted profile) — except blocked people. Someone who scanned my code
            // but whom I haven't added yet shows up as a contact request, exactly like over Bluetooth. Over the
            // internet, non-contacts can never make this phone carry mail (see MeshRouter).
            val ok = if (r.expected != null) pub == r.expected else engine.db.contact(pub)?.state != ContactState.BLOCKED
            if (!ok) { l.close("blocked"); return }
            r.claimed = pub
        }
        engine.onFrame(l, f)
    }

    /** Called by the engine once the router has verified the other phone's proof of key on a Tor link. */
    fun onVerified(linkId: String, pub: String, name: String) {
        val r = links[linkId] ?: return
        if (r.claimed != pub) { r.link.close("identity mismatch"); return }
        r.peer = pub
        backoff.remove(pub)
        attempts.remove(pub)
        dialInfo[pub] = DialInfo("Connected through Tor", System.currentTimeMillis())
        r.onHello?.invoke(pub, name)
        setStatus(Status.ON, _state.value.note)
    }

    // ------------------------------------------------------------ dialing

    fun isLinked(pub: String) = links.values.any { it.peer == pub }

    /**
     * Add a friend by their Mura ID (their onion address) — no QR code, no server. Tor connects us to
     * whoever holds that address (onion addresses can't be faked), their phone introduces itself with its
     * keys, and we add them. Returns (their key, their name) or throws with a reason.
     */
    suspend fun introduce(onion: String, timeoutMs: Long = 120_000): Pair<String, String> {
        if (!fullyUp) throw java.io.IOException("Connect to Tor first (Settings › Internet), then try again.")
        if (onion == onionAddress) throw java.io.IOException("That's your own Mura ID.")
        val result = kotlinx.coroutines.CompletableDeferred<Pair<String, String>>()
        exec.execute {
            try {
                val s = Socks5.connect("127.0.0.1", socksPort, onion, VIRTUAL_PORT, CONNECT_TIMEOUT_MS,
                    username = "intro-" + Crypto.randomBytes(6).toHex(), password = Socks5.randomPassword())
                attach(s, outgoing = true, expected = null) { pub, name -> result.complete(pub to name) }
            } catch (e: Exception) {
                result.completeExceptionally(java.io.IOException(
                    if (e.message?.contains("unreachable") == true) "Couldn't find that Mura ID on Tor. Check it, or try when they're online."
                    else "Couldn't connect: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        return kotlinx.coroutines.withTimeout(timeoutMs) { result.await() }
    }

    /**
     * New Mura ID: a fresh onion address. The old one stops working at once (anyone who only knows the old ID
     * can't reach you any more); your contacts get the new one automatically in your encrypted profile.
     */
    fun resetIdentity() = exec.execute {
        val old = engine.db.getSetting(ONION_ADDR, "")
        engine.db.setSecret(ONION_KEY, null)
        engine.db.setSetting(ONION_ADDR, "")
        ensureIdentity()
        try { if (old.isNotBlank()) tor?.torControlConnection?.delOnion(old.removeSuffix(".onion")) } catch (_: Exception) {}
        published = false
        if (torReady) publish()
        engine.onOnionChanged()
    }

    /** Last internet-connection attempt to each contact, for the chat screen ("why isn't it sending?"). */
    data class DialInfo(val text: String, val at: Long, val lastError: String = "")
    private val attempts = ConcurrentHashMap<String, Int>()
    private val dialInfo = ConcurrentHashMap<String, DialInfo>()
    fun dialInfo(pub: String): DialInfo? = dialInfo[pub]

    /** Is my own onion address reachable through Tor? (Tor can connect to its own onion services.) */
    @Volatile var selfReachable: Boolean? = null
        private set
    @Volatile private var selfTestDue = false
    /** Why the last self-test failed (shown in Settings). */
    @Volatile var selfError: String? = null
        private set

    /** "Test my address" in Settings. */
    fun testNow(): Boolean { if (!fullyUp) return false; selfReachable = null; exec.execute { selfTest() }; return true }
    @Volatile private var lastRotateBase = 0L

    private val selfTesting = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun selfTest() {
        selfTestDue = false
        if (!selfTesting.compareAndSet(false, true)) return
        try { selfTestRun() } finally { selfTesting.set(false) }
    }

    private fun selfTestRun() {
        val me = onionAddress ?: return
        for (attempt in 0 until 20) {
            if (!fullyUp) return
            try {
                Socks5.connect("127.0.0.1", socksPort, me, VIRTUAL_PORT, CONNECT_TIMEOUT_MS, username = "self", password = Socks5.randomPassword()).close()
                selfReachable = true; selfError = null
                setStatus(Status.ON, "Online through Tor · your address is reachable · IP hidden")
                Log.i(TAG, "own onion reachable")
                return
            } catch (e: Exception) {
                selfReachable = false
                selfError = e.message ?: e.javaClass.simpleName
                setStatus(Status.ON, "Online through Tor · publishing your address (contacts can reach you in a minute or two)…")
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { return }
            }
        }
        setStatus(Status.ON, "Online through Tor, but your address isn't reachable yet — check Bridges or try again later")
    }

    /** Connect to a contact now (ignores backoff). Used before a call. */
    fun dialNow(pub: String) { backoff.remove(pub); poke(); exec.execute { dial(pub) } }

    private fun dial(pub: String) {
        if (!fullyUp || socksPort == 0) return
        val onion = engine.db.contact(pub)?.onion?.takeIf { Onion.valid(it) } ?: return
        if (isLinked(pub) || links.values.any { it.expected == pub } || !dialing.add(pub)) return
        readSocksPort().takeIf { it > 0 }?.let { socksPort = it; TorNet.socksPort = it }
        val attempt = (attempts[pub] ?: 0) + 1
        attempts[pub] = attempt
        dialInfo[pub] = DialInfo("Connecting through Tor… (try $attempt)", System.currentTimeMillis(),
            dialInfo[pub]?.lastError ?: "")
        Log.i(TAG, "dialing a contact")
        try {
            val user = "c-" + isolation.getOrPut(pub) { Crypto.randomBytes(8).toHex() }
            val s = Socks5.connect("127.0.0.1", socksPort, onion, VIRTUAL_PORT, CONNECT_TIMEOUT_MS, username = user, password = Socks5.randomPassword())
            attach(s, outgoing = true, expected = pub)
            dialInfo[pub] = DialInfo("Reached their phone · checking keys…", System.currentTimeMillis())
            Log.i(TAG, "connected to a contact")
        } catch (e: Exception) {
            val (n, _) = backoff[pub] ?: (0 to 0L)
            // Right after going online, other phones' addresses may still be publishing: retry quickly at first.
            val sinceOnline = System.currentTimeMillis() - lastRotateBase
            val cap = if (sinceOnline < 15 * 60_000L) 60_000L else 10 * 60_000L
            val wait = minOf(cap, 20_000L shl minOf(n, 5))
            val m = e.message ?: ""
            val why = when {
                m.startsWith("onion") || m.startsWith("bad onion") -> m.replaceFirstChar { it.uppercase() }
                m.contains("unreachable") -> "Their phone isn't reachable on Tor right now (offline, or their address is still publishing)"
                m.contains("TTL") -> "Tor couldn't build a path to them yet"
                e is java.net.SocketTimeoutException -> "Timed out reaching them through Tor"
                e is java.net.ConnectException -> "Tor's local port isn't answering — turn internet off and on in Settings"
                else -> "Couldn't reach them: ${m.ifBlank { e.javaClass.simpleName }}"
            }
            dialInfo[pub] = DialInfo("$why · retrying in ${wait / 1000} s", System.currentTimeMillis(), why)
            Log.i(TAG, "dial failed: ${e.message}")
            backoff[pub] = (n + 1) to System.currentTimeMillis() + wait
        } finally {
            dialing.remove(pub)
        }
    }


    private fun loop() {
        var last = 0L
        while (running) {
            try {
                val allowed = networkAllowed()
                if (!allowed) {
                    if (bound) pauseTor()
                    setStatus(Status.NO_NETWORK, if (mode == "wifi") "Waiting for Wi-Fi" else "No internet — Bluetooth only for now")
                } else {
                    if (!bound) startTor() else resumeTor()
                    if (bound && !fullyUp) bootstrapProgress()
                    if (fullyUp && selfTestDue) exec.execute { selfTest() }
                    val now = System.currentTimeMillis()
                    // Like Tor Browser's "New circuit": fresh paths through the Tor network every few minutes.
                    val rot = rotateMinutes
                    if (fullyUp && rot > 0 && now - lastRotate > rot * 60_000L) newCircuits()
                    if (fullyUp && checkInDue) {
                        checkInDue = false
                        for (c in engine.db.contacts()) {
                            if (c.state != ContactState.CONTACT || c.onion == null || isLinked(c.pub)) continue
                            val delayMs = (Crypto.randomBytes(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) } % 20_000).toLong()
                            exec.execute { try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}; dial(c.pub) }
                        }
                    }
                    if (fullyUp && (poked || now - last > 20_000)) {
                        poked = false; last = now
                        // Close links that have finished syncing.
                        // (kept open while you're both in the app with live status on, so typing/online and messages are instant)
                        for (r in links.values) if (r.peer != null && engine.router.isIdle(r.link.id, now, IDLE_MS) && !engine.keepWarm(r.peer!!)) r.link.close("idle")
                        // Close connections that never proved who they are.
                        for (r in links.values) if (r.peer == null && now - r.since > HANDSHAKE_MS) r.link.close("handshake timeout")
                        // Connect to contacts we have something for.
                        for (c in engine.db.contacts()) {
                            if (c.state != ContactState.CONTACT || c.onion == null) continue
                            val (_, next) = backoff[c.pub] ?: (0 to 0L)
                            if (now < next || isLinked(c.pub)) continue
                            if (engine.hasPendingFor(c.pub, lastSync[c.pub] ?: 0L)) exec.execute { dial(c.pub) }
                        }
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "loop", e) }
            Thread.sleep(if (poked) 300 else 2_000)
        }
    }

    /**
     * New Tor circuits for everything opened from now on (Tor's NEWNYM signal, the same thing Tor Browser's
     * "New Tor circuit" does) and new per-contact isolation, so each contact also gets a fresh path.
     * Your entry guard stays the same on purpose — rotating guards would make you easier to trace.
     * Onion-to-onion links never use an exit relay, so there is no "exit IP" to change for messages.
     */
    fun newCircuits(): Boolean {
        lastRotate = System.currentTimeMillis()
        isolation.clear()
        val t = tor ?: return false
        return try { t.torControlConnection?.signal("NEWNYM"); true } catch (e: Exception) { Log.w(TAG, "newnym", e); false }
    }

    /** For calls: make sure there's a live link to this contact (Bluetooth or Tor), waiting up to [timeoutMs]. */
    suspend fun ensureLink(pub: String, timeoutMs: Long): Boolean {
        if (engine.router.linkFor(pub.hexToBytes()) != null) return true
        if (_state.value.status != Status.ON) return false
        dialNow(pub)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (engine.router.linkFor(pub.hexToBytes()) != null) return true
            delay(250)
        }
        return false
    }
}
