package com.mura.mesh.mesh

import android.content.Context
import android.util.Log
import org.torproject.jni.TorService
import java.io.File

/**
 * Tor bridges, for networks that block or watch for Tor.
 *
 * A bridge is an unlisted Tor entry relay; a pluggable transport disguises the connection to it:
 *  • Snowflake — looks like a WebRTC video call through volunteer browsers (built in, works for most people).
 *  • obfs4 / webtunnel / meek — paste lines from https://bridges.torproject.org or the Telegram bot @GetBridgesBot.
 * The transports run inside the app (IPtProxy: Lyrebird + Snowflake); Tor talks to them on 127.0.0.1.
 * Your network then sees neither Tor nor Mura — only a WebRTC call or random-looking traffic.
 */
object Bridges {
    const val MODE = "tor_bridges"          // off | snowflake | custom
    const val LINES = "tor_bridge_lines"
    private const val TAG = "MuraBridges"

    // Snowflake settings as shipped by Tor Browser (tor-browser-build pt_config.json).
    private const val SF_URL = "https://voluble-torrone-fc39bf.netlify.app/"
    private const val SF_FRONTS = "vuejs.org"
    private const val SF_ICE = "stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
        "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443"
    private val SNOWFLAKE_LINES = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=$SF_URL fronts=$SF_FRONTS ice=$SF_ICE utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=$SF_URL fronts=$SF_FRONTS ice=$SF_ICE utls-imitate=hellorandomizedalpn",
    )
    private val TRANSPORTS = setOf("obfs4", "webtunnel", "meek_lite", "snowflake")

    private var controller: IPtProxy.Controller? = null
    private val running = HashSet<String>()

    /** Cleans pasted text into bridge lines ("Bridge " prefix and blank lines removed). */
    fun parse(text: String): List<String> = text.lines().map { it.trim().removePrefix("Bridge ").removePrefix("bridge ").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun transportOf(line: String): String? = line.substringBefore(' ').takeIf { it in TRANSPORTS }

    /** What Tor should be told: a problem to show (or null), and the settings lines for SETCONF / torrc. */
    class Config(val problem: String?, val lines: List<String>)

    /**
     * Prepares Tor's configuration for the chosen bridges: starts only the transports that are needed (and keeps
     * ones already running, so switching never restarts anything unnecessarily), writes torrc for the next start,
     * and returns the lines to apply live through Tor's control port.
     */
    @Synchronized
    fun prepare(ctx: Context, mode: String, custom: String): Config {
        val torrc = TorService.getTorrc(ctx)
        val lines = when (mode) {
            "snowflake" -> SNOWFLAKE_LINES
            "custom" -> parse(custom)
            else -> emptyList()
        }
        if (lines.isEmpty()) {
            stopUnneeded(emptySet())
            torrc.writeText("UseBridges 0\n")
            return Config(if (mode == "custom") "Paste at least one bridge line" else null, listOf("UseBridges 0"))
        }
        val needed = lines.mapNotNull { transportOf(it) }.toSet()
        val conf = ArrayList<String>()
        conf.add("UseBridges 1")
        try {
            stopUnneeded(needed)
            if (needed.isNotEmpty()) {
                val c = controller ?: IPtProxy.Controller(File(ctx.noBackupFilesDir, "pt").apply { mkdirs() }.absolutePath,
                    false, false, "ERROR", null).also { controller = it }
                c.snowflakeBrokerUrl = SF_URL
                c.snowflakeFrontDomains = SF_FRONTS
                c.snowflakeIceServers = SF_ICE
                for (t in needed) {
                    if (t !in running) { c.start(t, ""); running.add(t) }
                    val port = c.port(t)
                    if (port <= 0) return Config("Couldn't start the $t transport", emptyList())
                    conf.add("ClientTransportPlugin $t socks5 127.0.0.1:$port")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "transport", e)
            return Config("Couldn't start bridges: ${e.message ?: e.javaClass.simpleName}", emptyList())
        }
        for (l in lines) conf.add("Bridge $l")
        torrc.writeText(conf.joinToString("\n", postfix = "\n"))
        return Config(null, conf)
    }

    private fun stopUnneeded(keep: Set<String>) {
        val c = controller ?: return
        for (t in running.toList()) if (t !in keep) { try { c.stop(t) } catch (_: Throwable) {}; running.remove(t) }
    }

    @Synchronized
    fun stopTransports() {
        val c = controller ?: return
        for (t in running) try { c.stop(t) } catch (_: Throwable) {}
        running.clear()
    }
}
