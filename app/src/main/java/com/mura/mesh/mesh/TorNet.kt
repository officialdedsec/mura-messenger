package com.mura.mesh.mesh

import com.mura.mesh.core.Socks5
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tor-only HTTPS for everything Mura fetches from the web (link previews).
 *
 * There is no direct path and no fallback: every request is a TLS connection tunnelled through
 * Tor's SOCKS port with the hostname handed to Tor unresolved, so even DNS happens inside Tor.
 * Until Tor has connected, [request] throws [NotReady] and nothing leaves the phone.
 * (Android's own HttpURLConnection is deliberately not used: with a SOCKS proxy it resolves
 * hostnames locally, which would leak DNS lookups.)
 *
 * Each purpose gets its own Tor circuit (SOCKS username isolation), so a link preview and your
 * backup never share a path through the network.
 */
object TorNet {
    class NotReady : IOException("Waiting for Tor — Mura sends nothing over the normal internet until Tor is connected.")

    /** Tor's SOCKS port, set by [OnlineTransport] only once Tor is fully connected. 0 = not ready. */
    @Volatile var socksPort: Int = 0
    val ready: Boolean get() = socksPort > 0

    /** For unit tests only: allow plain http through the proxy. */
    @Volatile internal var allowPlainForTests = false

    class Response(val code: Int, private val headers: Map<String, String>, val body: InputStream, private val socket: Socket) : Closeable {
        fun header(name: String): String? = headers[name.lowercase()]
        val contentLength: Long get() = header("content-length")?.toLongOrNull() ?: -1L
        val ok: Boolean get() = code in 200..299
        fun text(max: Int = 4 * 1024 * 1024): String = readLimited(body, max).toString(Charsets.UTF_8)
        /** Throws with the server's message when the status isn't 2xx. */
        fun requireOk(what: String): Response { if (!ok) { val t = runCatching { text(4096) }.getOrDefault(""); close(); throw IOException("$what error $code: ${t.take(200)}") }; return this }
        override fun close() { try { socket.close() } catch (_: Exception) {} }
    }

    /**
     * One HTTP/1.1 request through Tor. [body] writes exactly [bodyLength] bytes (or is null).
     * Redirects are not followed; callers decide.
     */
    fun request(
        method: String, url: String, headers: Map<String, String> = emptyMap(),
        bodyLength: Long = -1, isolation: String = "web", timeoutMs: Int = 60_000,
        body: ((OutputStream) -> Unit)? = null,
    ): Response {
        val port = socksPort
        if (port <= 0) throw NotReady()
        val u = URL(url)
        val tls = when (u.protocol) { "https" -> true; "http" -> if (allowPlainForTests) false else throw IOException("Only https is allowed"); else -> throw IOException("Unsupported URL") }
        val host = u.host
        val p = if (u.port > 0) u.port else if (tls) 443 else 80
        var sock: Socket = Socks5.connect("127.0.0.1", port, host, p, timeoutMs, username = "mura-$isolation", password = Socks5.randomPassword())
        try {
            if (tls) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(sock, host, p, true) as SSLSocket
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) { ssl.close(); throw IOException("Certificate doesn't match $host") }
                sock = ssl
            }
            sock.soTimeout = timeoutMs
            val out = java.io.BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
            val path = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $host${if (u.port > 0) ":${u.port}" else ""}\r\nConnection: close\r\nAccept-Encoding: identity\r\n")
            for ((k, v) in headers) { require('\r' !in v && '\n' !in v); sb.append(k).append(": ").append(v).append("\r\n") }
            if (body != null || method == "POST" || method == "PUT" || method == "PATCH") sb.append("Content-Length: ${bodyLength.coerceAtLeast(0)}\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) body(out)
            out.flush()

            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            var status = readLine(input) ?: throw IOException("No response")
            var code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad response")
            var hdrs = readHeaders(input)
            while (code == 100) {                                   // "100 Continue": the real status follows
                status = readLine(input) ?: throw IOException("No response")
                code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad response")
                hdrs = readHeaders(input)
            }
            val stream: InputStream = when {
                method == "HEAD" || code == 204 || code == 304 -> java.io.ByteArrayInputStream(ByteArray(0))
                hdrs["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> ChunkedInput(input)
                hdrs["content-length"]?.toLongOrNull() != null -> LimitedInput(input, hdrs.getValue("content-length").toLong())
                else -> input                                       // until the server closes
            }
            return Response(code, hdrs, stream, sock)
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
            if (b.size() > 16 * 1024) throw IOException("Header too long")
        }
    }

    private fun readHeaders(i: InputStream): Map<String, String> {
        val m = HashMap<String, String>()
        while (true) {
            val line = readLine(i) ?: break
            if (line.isEmpty()) break
            val k = line.substringBefore(':', "").trim().lowercase()
            if (k.isNotEmpty() && m.size < 200) m[k] = line.substringAfter(':').trim()
        }
        return m
    }

    fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private class LimitedInput(i: InputStream, private var left: Long) : FilterInputStream(i) {
        override fun read(): Int { if (left <= 0) return -1; val c = super.read(); if (c >= 0) left--; return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private class ChunkedInput(private val i: InputStream) : InputStream() {
        private var left = 0L
        private var done = false
        private fun next(): Boolean {
            if (done) return false
            if (left == 0L) {
                val line = readLine(i) ?: run { done = true; return false }
                val sizeLine = if (line.isEmpty()) (readLine(i) ?: run { done = true; return false }) else line
                left = sizeLine.substringBefore(';').trim().toLong(16)
                if (left == 0L) { while (true) { val t = readLine(i) ?: break; if (t.isEmpty()) break }; done = true; return false }
            }
            return true
        }
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = i.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) { done = true; return -1 }
            left -= n
            return n
        }
    }
}
