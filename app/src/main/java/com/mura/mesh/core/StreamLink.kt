package com.mura.mesh.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A mesh [Link] over any byte stream — used for internet links through Tor onion services.
 * Same frames, same offer/want sync and the same end-to-end sealed bundles as Bluetooth:
 * the stream only ever carries ciphertext (and Tor encrypts it again, end to end, between
 * the two onion services).
 *
 * Wire format (v2): "MUR2", then records [u32 length][u8 kind][body]. kind 0 = a frame
 * ([u32 frame length][frame][random padding]); kind 1 = cover traffic (random bytes, ignored).
 * With [padding] on, every record is padded to a multiple of 512 bytes (4 KB for big ones) and random
 * cover records are sent while the link is open, so an observer can't learn message sizes or rhythm.
 * Call audio frames jump the queue.
 */
class StreamLink(
    override val id: String,
    override val outgoing: Boolean,
    private val input: InputStream,
    private val output: OutputStream,
    private val onFrame: (StreamLink, Frame) -> Unit,
    private val onClosed: (StreamLink, String) -> Unit,
    private val closeable: AutoCloseable? = null,
) : Link {

    override val remote: Boolean get() = true

    companion object {
        const val MAX_FRAME = Bundle.MAX_PAYLOAD + 8192
        private const val MAX_RECORD = MAX_FRAME + 8192
        /** Over Tor an empty link is a 60-second keepalive away from being dropped by a middlebox. */
        const val PING_MS = 45_000L
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'U'.code.toByte(), 'R'.code.toByte(), '2'.code.toByte())
        private const val KIND_FRAME = 0
        private const val KIND_COVER = 1
        private val CLOSE = ByteArray(0)
        /** Traffic padding + cover traffic (Settings › Privacy). */
        @Volatile var padding: Boolean = true

        fun paddedSize(n: Int): Int = if (n <= 16 * 1024) ((n + 511) / 512) * 512 else ((n + 4095) / 4096) * 4096
        private fun rand(bound: Int): Int = ((Crypto.randomBytes(4).let { (it[0].toInt() and 0x7f shl 24) or (it[1].toInt() and 0xff shl 16) or (it[2].toInt() and 0xff shl 8) or (it[3].toInt() and 0xff) }) % bound)
    }

    private val queue = LinkedBlockingDeque<ByteArray>()
    private val closed = AtomicBoolean(false)
    @Volatile var lastActivity = System.currentTimeMillis()
        private set

    fun start() {
        Thread({ readLoop() }, "mura-link-r-$id").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "mura-link-w-$id").apply { isDaemon = true }.start()
    }

    override fun send(frame: Frame, priority: Boolean) {
        if (closed.get()) return
        val b = frame.encode()
        if (priority) queue.offerFirst(b) else queue.offerLast(b)
    }

    override fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        queue.offerFirst(CLOSE)
        try { closeable?.close() } catch (_: Exception) {}
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        onClosed(this, reason)
    }

    val isClosed: Boolean get() = closed.get()

    private fun readLoop() {
        val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
        try {
            val magic = ByteArray(4); din.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw ProtocolException("other side runs an older Mura")
            while (!closed.get()) {
                val len = din.readInt()
                if (len < 1 || len > MAX_RECORD) throw ProtocolException("record size $len")
                val buf = ByteArray(len)
                din.readFully(buf)
                lastActivity = System.currentTimeMillis()
                if (buf[0].toInt() != KIND_FRAME) continue                 // cover traffic
                if (len < 5) throw ProtocolException("short record")
                val fl = ((buf[1].toInt() and 0xff) shl 24) or ((buf[2].toInt() and 0xff) shl 16) or ((buf[3].toInt() and 0xff) shl 8) or (buf[4].toInt() and 0xff)
                if (fl < 1 || fl > MAX_FRAME || 5 + fl > len) throw ProtocolException("frame size $fl")
                val frame = Frame.decode(buf.copyOfRange(5, 5 + fl))
                if (frame is Frame.Ping) continue
                onFrame(this, frame)
            }
        } catch (e: Exception) {
            close(if (closed.get()) "closed" else "read: ${e.javaClass.simpleName}")
        }
    }

    private fun writeRecord(dout: DataOutputStream, kind: Int, body: ByteArray?, coverSize: Int = 0) {
        val pad = padding
        val core = if (body != null) 1 + 4 + body.size else 1 + coverSize
        val total = if (pad) paddedSize(core + 4) - 4 else core
        dout.writeInt(total)
        dout.writeByte(kind)
        if (body != null) { dout.writeInt(body.size); dout.write(body) }
        val filler = total - (if (body != null) 5 + body.size else 1)
        if (filler > 0) dout.write(Crypto.randomBytes(filler))
    }

    private fun writeLoop() {
        val dout = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
        val ping = Frame.Ping.encode()
        try {
            dout.write(MAGIC); dout.flush()
            while (!closed.get()) {
                // With padding on, idle gaps are filled with cover records at random moments (10–40 s).
                val wait = if (padding) 10_000L + rand(30_000) else PING_MS
                val b = queue.poll(wait, TimeUnit.MILLISECONDS)
                when {
                    b === CLOSE -> break
                    b != null -> writeRecord(dout, KIND_FRAME, b)
                    padding -> writeRecord(dout, KIND_COVER, null, coverSize = 256 + rand(3840))
                    else -> writeRecord(dout, KIND_FRAME, ping)
                }
                // Flush when the queue is drained (batches small frames into one Tor cell stream write).
                if (queue.isEmpty()) dout.flush()
                lastActivity = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            close("write: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * Minimal SOCKS5 client (RFC 1928), enough to reach a .onion address through Tor's SOCKS port.
 * The hostname is passed to Tor unresolved, so no DNS lookup ever leaves the phone.
 */
object Socks5 {
    class Failed(msg: String) : java.io.IOException(msg)

    /** A fresh random password: with Tor, a new username/password pair means a new, separate circuit. */
    fun randomPassword(): String = Crypto.randomBytes(9).toHex()

    /**
     * [username]/[password] (RFC 1929) are used by Tor for *stream isolation*: connections with
     * different credentials never share a circuit, so each contact gets its own path through Tor.
     */
    fun connect(proxyHost: String, proxyPort: Int, host: String, port: Int, timeoutMs: Int,
                username: String? = null, password: String? = null): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            val auth = username != null
            out.write(if (auth) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0)); out.flush()   // offer user/pass or no auth
            val greet = ByteArray(2); inp.readFully(greet)
            if (greet[0].toInt() != 5) throw Failed("proxy refused")
            when (greet[1].toInt()) {
                0 -> {}
                2 -> {
                    if (!auth) throw Failed("proxy wants a password")
                    val u = username!!.toByteArray(Charsets.UTF_8).take(255).toByteArray()
                    val pw = (password ?: "x").toByteArray(Charsets.UTF_8).take(255).toByteArray()
                    out.write(ByteWriter(3 + u.size + pw.size).u8(1).u8(u.size).raw(u).u8(pw.size).raw(pw).toByteArray()); out.flush()
                    val r = ByteArray(2); inp.readFully(r)
                    if (r[1].toInt() != 0) throw Failed("proxy rejected the credentials")
                }
                else -> throw Failed("proxy refused")
            }
            val hb = host.toByteArray(Charsets.US_ASCII)
            require(hb.size in 1..255) { "bad host" }
            val req = ByteWriter(7 + hb.size).u8(5).u8(1).u8(0).u8(3).u8(hb.size).raw(hb).u16(port).toByteArray()
            out.write(req); out.flush()
            val head = ByteArray(4); inp.readFully(head)
            if (head[0].toInt() != 5) throw Failed("bad proxy reply")
            val rep = head[1].toInt() and 0xff
            if (rep != 0) throw Failed(when (rep) {
                1 -> "proxy failure"; 3 -> "network unreachable"; 4 -> "host unreachable (offline)"; 5 -> "connection refused"
                6 -> "TTL expired"
                // Tor's extended onion-service errors (SocksPort … ExtendedErrors): say exactly what went wrong.
                0xF0 -> "onion not found: their address isn't published on Tor right now"
                0xF1 -> "onion descriptor invalid"
                0xF2 -> "onion introduction failed: found their address but their phone didn't answer"
                0xF3 -> "onion rendezvous failed: the path to their phone broke"
                0xF4, 0xF5 -> "onion client authorization error"
                0xF6 -> "bad onion address"
                0xF7 -> "onion introduction timed out: their phone was too slow to answer"
                else -> "proxy error $rep"
            })
            when (head[3].toInt()) {                                         // skip the bound address
                1 -> inp.readFully(ByteArray(4 + 2))
                3 -> inp.readFully(ByteArray((inp.readUnsignedByte()) + 2))
                4 -> inp.readFully(ByteArray(16 + 2))
                else -> throw Failed("bad address type")
            }
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }
}
