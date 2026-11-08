package com.mura.mesh

import com.mura.mesh.core.*
import com.mura.mesh.identity.TinkCurve
import com.mura.mesh.identity.TinkEd
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Internet links: the same mesh protocol over a byte stream through a SOCKS5 proxy (a fake, local "Tor"). */
class OnlineTest {
    companion object {
        @JvmStatic @BeforeClass fun setup() { Crypto.curve = TinkCurve; Crypto.ed = TinkEd }
    }

    @Test fun internetLinks() = onlineTests()
    @Test fun torOnlyWeb() = torNetTests()
    @Test fun friendMailbox() = mailboxTests()
    @Test fun paddingAndMetadata() = paddingAndMetadataTests()
    @Test fun onionKeys() = onionKeyTests()
    @Test fun anyoneCarries() = internetRelayTests()
    @Test fun adaptiveVoice() = voiceTests()
    @Test fun muraId() = muraIdTests()
}

/** A tiny fake "Tor": accepts SOCKS5, checks the .onion name, and pipes to the real local server. */
class FakeSocks(private val onions: Map<String, Int>) {
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    var lastHost = ""
    val users = java.util.Collections.synchronizedList(ArrayList<String>())
    init {
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        val i = java.io.DataInputStream(c.getInputStream()); val o = c.getOutputStream()
                        val g = ByteArray(2); i.readFully(g); val methods = ByteArray(g[1].toInt()).also { i.readFully(it) }
                        if (2.toByte() in methods) {
                            o.write(byteArrayOf(5, 2))
                            i.readUnsignedByte(); val u = String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) }); i.readFully(ByteArray(i.readUnsignedByte()))
                            users.add(u); o.write(byteArrayOf(1, 0))
                        } else o.write(byteArrayOf(5, 0))
                        val h = ByteArray(5); i.readFully(h)
                        val host = String(ByteArray(h[4].toInt()).also { i.readFully(it) }); val port = i.readUnsignedShort()
                        lastHost = host
                        val target = onions[host]
                        if (target == null || h[3].toInt() != 3) { o.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); c.close(); return@Thread }
                        val t = Socket(InetAddress.getLoopbackAddress(), target)
                        o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                        Thread { try { t.getInputStream().copyTo(c.getOutputStream()) } catch (_: Exception) {}; c.close() }.start()
                        try { c.getInputStream().copyTo(t.getOutputStream()) } catch (_: Exception) {}; t.close()
                    } catch (_: Exception) {}
                }.start()
            }
        }.start()
    }
}

fun waitFor(ms: Long = 8000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
    return cond()
}

fun onlineTests() {
    println("\n-- internet links (Tor-style) --")
    val tmp = File(System.getProperty("java.io.tmpdir"), "mura-online-" + System.nanoTime()).apply { mkdirs() }
    val a = Node("A", File(tmp, "a").apply { mkdirs() })
    val b = Node("B", File(tmp, "b").apply { mkdirs() })
    val c = Node("C", File(tmp, "c").apply { mkdirs() })

    // B's "onion service": a local server socket.
    val bServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val onion = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz234.onion"
    val tor = FakeSocks(mapOf(onion to bServer.localPort))
    val closedReasons = ArrayList<String>()
    Thread {
        val s = bServer.accept()
        val link = StreamLink("tor:in", false, s.getInputStream(), s.getOutputStream(),
            { l, f -> b.router.onFrame(l, f) }, { l, r -> closedReasons.add("B:$r"); b.router.onLinkDown(l.id) }, s)
        b.router.onLinkUp(link); link.start()
    }.start()

    // C's sealed message for someone else sits on A: it must NOT be relayed over the internet link.
    c.sendPayload(Crypto.curve.publicFrom(Crypto.curve.generatePrivate()), Payload.Text(Crypto.randomBytes(16), 1, "for a stranger", false))
    Wire(c, a).up().pump()
    check(a.store.bundles.size == 1, "A carries C's sealed message for a stranger")

    a.sendPayload(b.myPublicKey, Payload.Text(Crypto.randomBytes(16), 2, "hello over the internet", false))
    val sock = Socks5.connect("127.0.0.1", tor.server.localPort, onion, 80, 5000)
    check(tor.lastHost == onion, "onion name handed to the proxy unresolved (no DNS)")
    val aLink = StreamLink("tor:out", true, sock.getInputStream(), sock.getOutputStream(),
        { l, f -> a.router.onFrame(l, f) }, { l, r -> closedReasons.add("A:$r"); a.router.onLinkDown(l.id) }, sock)
    a.router.onLinkUp(aLink); aLink.start()

    check(waitFor { b.inbox.any { (it.second as? Payload.Text)?.text == "hello over the internet" } }, "message delivered over the stream link")
    Thread.sleep(400)
    check(b.store.bundles.values.none { it.first.dest.contentEquals(c.store.bundles.values.firstOrNull()?.first?.dest ?: ByteArray(8)) } &&
        b.store.bundles.isEmpty(), "strangers' mail is not relayed over internet links")

    // A file over the same link
    val src = File(tmp, "photo.bin").apply { writeBytes(Crypto.randomBytes(150_000)) }
    val m = a.fileManifest(Crypto.nodeId(b.myPublicKey), src)
    a.sendPayload(b.myPublicKey, m)
    check(waitFor(15000) { b.completed.any { plainOf(it).contentEquals(src.readBytes()) } }, "150 KB file arrives intact over the stream link")

    // Unknown onion: proxy reports unreachable
    val bad = runCatching { Socks5.connect("127.0.0.1", tor.server.localPort, "nobodyhere.onion", 80, 3000) }
    check(bad.isFailure && bad.exceptionOrNull()?.message?.contains("unreachable") == true, "offline/unknown onion reported as unreachable")

    aLink.close("done")
    check(waitFor { closedReasons.any { it.startsWith("B:") } }, "closing one side closes the other")
    tor.server.close(); bServer.close()
}


/** Tor-only HTTP: nothing before Tor is ready, then requests via SOCKS with per-purpose isolation. */
fun torNetTests() {
    println("\n-- Tor-only web requests --")
    com.mura.mesh.mesh.TorNet.socksPort = 0
    check(runCatching { com.mura.mesh.mesh.TorNet.request("GET", "https://example.com/") }.exceptionOrNull() is com.mura.mesh.mesh.TorNet.NotReady,
        "no request at all before Tor is connected (no fallback to the normal internet)")
    val web = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    Thread {
        while (!web.isClosed) {
            val c = try { web.accept() } catch (e: Exception) { break }
            Thread {
                val r = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val first = r.readLine()
                var len = 0
                while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt() }
                val body = CharArray(len).also { var got = 0; while (got < len) { val n = r.read(it, got, len - got); if (n < 0) break; got += n } }
                val o = c.getOutputStream()
                when {
                    first.startsWith("GET /chunked") -> o.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n7\r\n, world\r\n0\r\n\r\n".toByteArray())
                    first.startsWith("POST") -> { val b = "got " + String(body); o.write("HTTP/1.1 201 Created\r\nContent-Length: ${b.length}\r\nLocation: /next\r\n\r\n$b".toByteArray()) }
                    else -> o.write("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                }
                o.flush(); c.close()
            }.start()
        }
    }.start()
    val host = "mura-test.example"
    val tor = FakeSocks(mapOf(host to web.localPort))
    com.mura.mesh.mesh.TorNet.allowPlainForTests = true
    com.mura.mesh.mesh.TorNet.socksPort = tor.server.localPort
    val a = com.mura.mesh.mesh.TorNet.request("GET", "http://$host/chunked", isolation = "preview").use { it.text() }
    check(a == "hello, world", "chunked response read correctly through the proxy")
    check(tor.lastHost == host, "hostname handed to Tor unresolved (DNS inside Tor)")
    val b = com.mura.mesh.mesh.TorNet.request("POST", "http://$host/up", mapOf("Content-Type" to "text/plain"), 5, isolation = "drive") { it.write("abcde".toByteArray()) }
    check(b.use { it.code == 201 && it.header("location") == "/next" && it.text() == "got abcde" }, "POST with body, status, headers")
    val c = com.mura.mesh.mesh.TorNet.request("GET", "http://$host/plain").use { it.code to it.text() }
    check(c == (200 to "ok"), "100-continue skipped, content-length body")
    check(tor.users.contains("mura-preview") && tor.users.contains("mura-drive"), "each purpose uses its own Tor circuit (SOCKS isolation)")
    com.mura.mesh.mesh.TorNet.socksPort = 0
    com.mura.mesh.mesh.TorNet.allowPlainForTests = false
    tor.server.close(); web.close()
}

/** Two nodes joined by an in-memory internet-style stream link (remote = true). Returns a closer. */
fun streamPair(a: Node, b: Node): () -> Unit {
    val aOut = java.io.PipedOutputStream(); val bIn = java.io.PipedInputStream(aOut, 1 shl 20)
    val bOut = java.io.PipedOutputStream(); val aIn = java.io.PipedInputStream(bOut, 1 shl 20)
    val la = StreamLink("tor:a-" + a.name + b.name, true, aIn, aOut, { l, f -> a.router.onFrame(l, f) }, { l, _ -> a.router.onLinkDown(l.id) })
    val lb = StreamLink("tor:b-" + a.name + b.name, false, bIn, bOut, { l, f -> b.router.onFrame(l, f) }, { l, _ -> b.router.onLinkDown(l.id) })
    a.router.onLinkUp(la); b.router.onLinkUp(lb); la.start(); lb.start()
    return { la.close("done"); lb.close("done") }
}

/** Sender and receiver are never online together: a mutual friend's phone carries the sealed message. */
fun mailboxTests() {
    println("\n-- friend mailboxes (never online at the same time) --")
    val tmp = File(System.getProperty("java.io.tmpdir"), "mura-mb-" + System.nanoTime()).apply { mkdirs() }
    val s = Node("S", File(tmp, "s").apply { mkdirs() })
    val m = Node("M", File(tmp, "m").apply { mkdirs() })
    val x = Node("X", File(tmp, "x").apply { mkdirs() })
    val r = Node("R", File(tmp, "r").apply { mkdirs() })
    s.mailboxOffer = true
    m.mailboxFor.add(Crypto.nodeId(r.myPublicKey).toHex())      // M knows R
    m.relayEnabled = false; x.relayEnabled = false
    s.sendPayload(r.myPublicKey, Payload.Text(Crypto.randomBytes(16), 1, "see you tomorrow", false))

    var close = streamPair(s, x)
    Thread.sleep(600); close()
    check(x.store.bundles.isEmpty(), "a friend who doesn't know the recipient doesn't take the message")

    close = streamPair(s, m)
    check(waitFor { m.store.bundles.size == 1 }, "a mutual friend accepts the sealed message (can't read it)")
    close()
    check(m.inbox.isEmpty(), "the mailbox never opens it")

    // Later: S is offline; R comes online and meets M.
    close = streamPair(m, r)
    check(waitFor { r.inbox.any { (it.second as? Payload.Text)?.text == "see you tomorrow" } }, "receiver gets it from the friend's phone — sender and receiver were never online together")
    close()

    // Mailbox phones refuse unsolicited mail for strangers over the internet.
    val y = Node("Y", File(tmp, "y").apply { mkdirs() })
    y.mailboxOffer = true
    y.sendPayload(x.myPublicKey, Payload.Text(Crypto.randomBytes(16), 2, "spam", false))
    close = streamPair(y, m)
    Thread.sleep(600); close()
    val xNode = Crypto.nodeId(x.myPublicKey)
    check(m.store.bundles.values.none { it.first.dest.contentEquals(xNode) }, "mail for someone the mailbox doesn't know is refused")
    check(m.store.bundles.isEmpty(), "the mailbox let go of the delivered message")
}

fun paddingAndMetadataTests() {
    println("\n-- traffic padding and hidden-detail removal --")
    Compression.padding = true
    for (n in listOf(1, 40, 300, 5000, 70_000)) {
        val data = Crypto.randomBytes(n)
        val packed = Compression.pack(data)
        check(Compression.unpack(packed).contentEquals(data), "padded message of $n bytes reads back exactly")
        check(packed.size == Compression.padTarget(packed.size) || packed.size > 128 * 1024, "size is a fixed step (${packed.size})")
    }
    check(Compression.pack("yes".toByteArray()).size == Compression.pack("I'll be there at seven, bring the map and some water".toByteArray()).size,
        "a one-word reply and a sentence look the same size")
    Compression.padding = false

    // JPEG with EXIF (GPS + model + orientation 6) and a comment
    fun seg(marker: Int, body: ByteArray): ByteArray { val l = body.size + 2; return byteArrayOf(0xFF.toByte(), marker.toByte(), (l shr 8).toByte(), l.toByte()) + body }
    val tiff = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0) +
        "GPS 48.8584N 2.2945E Pixel 9 Pro".toByteArray()
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xE0, "JFIF\u0000\u0001\u0001".toByteArray()) +
        seg(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff) + seg(0xFE, "shot on Pixel".toByteArray()) +
        seg(0xDB, ByteArray(65)) + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    val clean = Metadata.jpeg(jpeg)
    val txt = String(clean, Charsets.ISO_8859_1)
    check(!txt.contains("GPS") && !txt.contains("Pixel"), "JPEG: location, model and comments removed")
    check(txt.contains("Exif") && (0 until clean.size - 1).any { clean[it].toInt() == 0x12 && clean[it + 1].toInt() == 0 && clean[it - 1].toInt() == 0x01 }, "JPEG: rotation kept")
    check(clean.copyOfRange(clean.size - 7, clean.size).contentEquals(jpeg.copyOfRange(jpeg.size - 7, jpeg.size)), "JPEG: picture data untouched")

    // PNG with a tEXt chunk
    fun chunk(t: String, d: ByteArray): ByteArray { val l = d.size; return byteArrayOf((l shr 24).toByte(), (l shr 16).toByte(), (l shr 8).toByte(), l.toByte()) + t.toByteArray() + d + ByteArray(4) }
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
        chunk("IHDR", ByteArray(13)) + chunk("tEXt", "Model\u0000SM-S928B".toByteArray()) + chunk("IDAT", ByteArray(20)) + chunk("IEND", ByteArray(0))
    check(!String(Metadata.png(png), Charsets.ISO_8859_1).contains("SM-S928B"), "PNG: text chunks removed")

    // MP4: moov/udta with a location, plus mdat
    fun box(t: String, d: ByteArray): ByteArray { val l = d.size + 8; return byteArrayOf((l shr 24).toByte(), (l shr 16).toByte(), (l shr 8).toByte(), l.toByte()) + t.toByteArray(Charsets.ISO_8859_1) + d }
    val mp4 = box("ftyp", "isom0000".toByteArray()) + box("moov", box("mvhd", ByteArray(20)) + box("udta", box("©xyz", "+48.8584+002.2945/".toByteArray()) + box("©mod", "Pixel 9".toByteArray()))) +
        box("mdat", "VIDEODATA".toByteArray())
    val f = File(tmpDir(), "v.mp4").apply { writeBytes(mp4) }
    val n = Metadata.scrubMp4(f)
    val after = f.readBytes()
    check(n == 1 && after.size == mp4.size && !String(after, Charsets.ISO_8859_1).contains("48.8584") && !String(after, Charsets.ISO_8859_1).contains("Pixel"),
        "MP4: location and model blanked, size unchanged")
    check(String(after, Charsets.ISO_8859_1).contains("VIDEODATA"), "MP4: video data untouched")
}

private fun tmpDir() = File(System.getProperty("java.io.tmpdir"), "mura-meta-" + System.nanoTime()).apply { mkdirs() }

/** Onion identity made on the phone (so it's in the QR code before Tor ever connects). */
fun onionKeyTests() {
    println("\n-- onion address made on the phone --")
    check(Sha3.sha3_256("abc".toByteArray()).joinToString("") { "%02x".format(it) } == "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", "SHA3-256 test vector")
    check(OnionKeys.checksumOk("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"), "real v3 address validates")
    check(!OnionKeys.checksumOk("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczae.onion"), "corrupted address rejected")
    val id = OnionKeys.create()
    check(Onion.valid(id.address) && OnionKeys.checksumOk(id.address), "new address is a valid v3 onion")
    check(id.torKey.startsWith("ED25519-V3:") && java.util.Base64.getDecoder().decode(id.torKey.substringAfter(':')).size == 64, "Tor key is a 64-byte expanded ed25519 key")
    val seed = Crypto.randomBytes(32)
    check(OnionKeys.create(seed).address == OnionKeys.create(seed).address, "same seed, same address")
}

/** Anyone can carry: a phone that doesn't know the recipient carries the sealed message over the internet too. */
fun internetRelayTests() {
    println("\n-- anyone carries over the internet --")
    val tmp = File(System.getProperty("java.io.tmpdir"), "mura-ir-" + System.nanoTime()).apply { mkdirs() }
    val s = Node("S", File(tmp, "s").apply { mkdirs() })
    val x = Node("X", File(tmp, "x").apply { mkdirs() })
    val y = Node("Y", File(tmp, "y").apply { mkdirs() })
    val r = Node("R", File(tmp, "r").apply { mkdirs() })
    for (n in listOf(s, x, y, r)) n.internetRelay = true
    s.sendPayload(r.myPublicKey, Payload.Text(Crypto.randomBytes(16), 1, "via strangers", false))
    var close = streamPair(s, x); check(waitFor { x.store.bundles.size == 1 }, "a phone that doesn't know the recipient carries it"); close()
    close = streamPair(x, y); check(waitFor { y.store.bundles.size == 1 }, "…and passes it to the next phone"); close()
    close = streamPair(y, r); check(waitFor { r.inbox.any { (it.second as? Payload.Text)?.text == "via strangers" } }, "…which delivers it: three hops, nobody online together"); close()
    check(x.inbox.isEmpty() && y.inbox.isEmpty(), "carriers can't open it")
    // Off: back to friends-only
    val z = Node("Z", File(tmp, "z").apply { mkdirs() })
    z.relayEnabled = true; z.internetRelay = false
    val s2 = Node("S2", File(tmp, "s2").apply { mkdirs() }); s2.internetRelay = true
    s2.sendPayload(r.myPublicKey, Payload.Text(Crypto.randomBytes(16), 2, "x", false))
    close = streamPair(s2, z); Thread.sleep(600); close()
    check(z.store.bundles.isEmpty(), "with internet carrying off, nothing is taken over the internet")
}

fun voiceTests() {
    println("\n-- adaptive call audio --")
    val pcm = ShortArray(640) { (8000 * kotlin.math.sin(it * 2 * Math.PI * 440 / 16000)).toInt().toShort() }
    for (mode in VoiceMode.entries) {
        val n = 16 * mode.frameMs
        val src = ShortArray(n) { pcm[it % pcm.size] }
        val pkt = VoiceCodec.encode(src, mode, VoiceMode.HD)
        val d = VoiceCodec.decode(pkt)
        var err = 0.0; var sig = 0.0
        for (i in 40 until n - 40) { val e = (d.pcm16k[i] - src[i]).toDouble(); err += e * e; sig += src[i].toDouble() * src[i] }
        val snr = 10 * kotlin.math.log10(sig / err.coerceAtLeast(1.0))
        check(d.mode == mode && d.wanted == VoiceMode.HD && d.pcm16k.size == n, "${mode.name}: ${pkt.size} bytes per ${mode.frameMs} ms packet, decodes to the right length")
        check(snr > 15, "${mode.name}: clear audio (SNR %.1f dB)".format(snr))
    }
    val q = QualityController(VoiceMode.STANDARD)
    var t = 0L; var seq = 0L
    repeat(400) { seq++; t += 40; if (seq % 5 != 0L) q.onPacket(seq, 40, t) }     // 20 % loss
    check(q.wanted == VoiceMode.SAVER, "heavy loss → asks for Saver")
    repeat(1500) { seq++; t += 40; q.onPacket(seq, 40, t) }                          // clean link for a minute
    check(q.wanted == VoiceMode.HD, "clean link → climbs back to HD")
}

fun muraIdTests() {
    println("\n-- Mura ID --")
    val id = OnionKeys.create().address
    check(MuraId.parse(MuraId.pretty(id)) == id, "pretty form (groups of 4) parses back")
    check(MuraId.parse("mura:" + MuraId.fromOnion(id).uppercase()) == id, "shared form with prefix, any case")
    val typo = MuraId.fromOnion(id).let { it.substring(0, 10) + (if (it[10] == 'a') 'b' else 'a') + it.substring(11) }
    check(MuraId.parse(typo) == null, "a typo is caught by the checksum")
    check(MuraId.parse("hello") == null, "garbage rejected")
}
