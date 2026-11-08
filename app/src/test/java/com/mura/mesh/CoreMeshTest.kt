package com.mura.mesh

import com.mura.mesh.core.*
import com.mura.mesh.identity.TinkCurve
import com.mura.mesh.identity.TinkEd
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.BitSet

fun check(cond: Boolean, msg: String) { if (!cond) throw AssertionError("FAIL: $msg") }

class MemStore(val budget: Long = 50L * 1024 * 1024) : BundleStore {
    val bundles = LinkedHashMap<String, Pair<Bundle, Boolean>>()
    val tombs = LinkedHashMap<String, ByteArray>()
    val seen = HashSet<String>()
    private fun used() = bundles.values.filter { !it.second }.sumOf { it.first.payload.size.toLong() }
    override fun has(id: ByteArray) = bundles.containsKey(id.toHex())
    override fun put(bundle: Bundle, mine: Boolean): Boolean {
        if (!mine && used() + bundle.payload.size > budget) return false
        bundles[bundle.id.toHex()] = bundle to mine; return true
    }
    override fun get(id: ByteArray) = bundles[id.toHex()]?.first
    override fun delete(id: ByteArray) { bundles.remove(id.toHex()) }
    override fun deleteRelayedByIdOrGroup(id: ByteArray) {
        bundles.entries.removeIf { !it.value.second && (it.value.first.id.contentEquals(id) || it.value.first.group.contentEquals(id)) }
    }
    override fun metas(now: Long) = bundles.values.map { it.first.meta }
    override fun canStore(size: Int, group: ByteArray?) = used() + size <= budget
    override fun isTombstoned(id: ByteArray) = tombs.containsKey(id.toHex())
    override fun addTombstone(id: ByteArray, now: Long) { tombs[id.toHex()] = id }
    override fun recentTombstones(limit: Int) = tombs.values.toList().takeLast(limit)
    override fun markSeen(id: ByteArray) { seen.add(id.toHex()) }
    override fun isSeen(id: ByteArray) = seen.contains(id.toHex())
}

class Group(val id: ByteArray, val key: ByteArray, val epoch: Int, val members: Map<String, ByteArray>) { val node = GroupCrypto.groupNode(id) }

class Node(val name: String, val dir: File, claimKey: ByteArray? = null) : MeshHost {
    val priv = TinkCurve.generatePrivate()
    override val myPublicKey = claimKey ?: TinkCurve.publicFrom(priv)
    val edSeed = Crypto.edSeedFrom(priv)
    val edPub = TinkEd.publicFromSeed(edSeed)
    override val myNodeId = Crypto.nodeId(myPublicKey)
    override fun sharedSecret(peerPublic: ByteArray): ByteArray = Crypto.curve.shared(priv, peerPublic)
    override val helloName = name
    override var relayEnabled = true
    override fun now() = System.currentTimeMillis()
    val store = MemStore()
    val router = MeshRouter(this, store)
    val inbox = ArrayList<Pair<ByteArray, Payload>>()
    val groupInbox = ArrayList<Pair<ByteArray, Payload>>()
    val outFiles = ArrayList<OutgoingFile>()
    val inFiles = HashMap<String, IncomingFile>()
    val completed = ArrayList<IncomingFile>()
    val groups = HashMap<String, Group>()

    fun sendPayload(toPub: ByteArray, p: Payload) {
        val dest = Crypto.nodeId(toPub)
        val sealed = Envelope.seal(priv, myPublicKey, toPub, Bundle.aad(p.msgId, dest), p.pack())
        store.put(Bundle(p.msgId, Bundle.TYPE_MSG, dest, Bundle.ZERO16, 0, now() + 86_400_000, MeshRouter.MAX_HOPS, sealed), mine = true)
        router.kick()
    }

    fun sendGroup(g: Group, p: Payload) {
        val wire = GroupCrypto.seal(g.key, g.epoch, p.msgId, g.node, myPublicKey, edSeed, p.pack())
        store.put(Bundle(p.msgId, Bundle.TYPE_MSG, g.node, Bundle.ZERO16, 0, now() + 86_400_000, MeshRouter.MAX_HOPS, wire), mine = true)
        router.kick()
    }

    fun fileManifest(dest: ByteArray, f: File): Payload.File {
        val fileId = Crypto.randomBytes(16); val key = Crypto.randomBytes(32)
        val sealed = File(dir, "out-" + fileId.toHex())
        val (size, sha) = SealedFile.seal(f.inputStream(), sealed, key, fileId)
        val of = OutgoingFile(fileId, dest, key, sealed, size, Chunks.SIZE, now() + 86_400_000)
        outFiles.add(of)
        return Payload.File(Crypto.randomBytes(16), now(), fileId, Payload.MEDIA_FILE, f.name, "application/octet-stream",
            size, Chunks.SIZE, of.chunkCount, sha, key, 0, 0, 0, ByteArray(10), "", false)
    }

    private fun accept(p: Payload) {
        if (p is Payload.File) inFiles[p.fileId.toHex()] = IncomingFile(p.fileId, p.fileKey, p.size, p.chunkSize, p.sha256, File(dir, "in-" + p.fileId.toHex()), BitSet())
    }

    override fun onMessageBundle(bundle: Bundle) {
        val (from, plain) = Envelope.open(priv, myPublicKey, Bundle.aad(bundle.id, bundle.dest), bundle.payload)
        val p = Payload.unpack(plain); inbox.add(from to p); accept(p)
    }
    override fun isSubscribed(dest: ByteArray) = groups.containsKey(dest.toHex())
    override fun onSubscribedBundle(bundle: Bundle) {
        val g = groups[bundle.dest.toHex()] ?: return
        val (from, packed) = GroupCrypto.open(g.key, bundle.id, bundle.dest, bundle.payload) { x -> g.members[x.toHex()] }
        val p = Payload.unpack(packed); groupInbox.add(from to p); accept(p)
    }
    override fun wantChunk(meta: BundleMeta): Boolean { val f = inFiles[meta.group.toHex()] ?: return false; return !f.isComplete && !f.has(meta.seq) }
    override fun onChunkBundle(bundle: Bundle) {
        val f = inFiles[bundle.group.toHex()] ?: return
        if (f.write(bundle.seq, bundle.payload) && f.isComplete && f.verify()) {
            completed.add(f)
            if (!isSubscribed(bundle.dest)) { store.addTombstone(f.fileId, now()); router.sendTombstones(listOf(f.fileId)) }
        }
    }
    val delivered = HashSet<String>()
    override fun outgoingChunkMetas(now: Long) = outFiles.filter { it.fileId.toHex() !in delivered }.flatMap { it.metas() }
    override fun loadOutgoingChunk(id: ByteArray): Bundle? { for (f in outFiles) { val i = f.indexOf(id); if (i >= 0) return f.build(i) }; return null }
    override fun onPeerReady(linkId: String, publicKey: ByteArray, name: String) {}
    override fun onPeerGone(linkId: String, publicKey: ByteArray?) {}
    override fun onCallFrame(linkId: String, peerPublicKey: ByteArray, sealed: ByteArray) {}
    override fun onAudioFrame(linkId: String, seq: Long, cipher: ByteArray) {}
    // Friend mailboxes
    val mailboxFor = HashSet<String>()
    var mailboxOffer = false
    override var internetRelay = false
    override fun isMailboxFor(dest: ByteArray) = dest.toHex() in mailboxFor
    override fun offerToMailbox(meta: BundleMeta) = mailboxOffer
}

class Wire(val a: Node, val b: Node, val budgetFrames: Int = Int.MAX_VALUE) {
    val queue = ArrayDeque<Pair<Boolean, ByteArray>>()
    var delivered = 0; var open = true
    val codecA = PacketCodec(); val codecB = PacketCodec(); val enc = PacketCodec()
    inner class End(val fromA: Boolean) : Link {
        override val id = "${a.name}-${b.name}-${if (fromA) "a" else "b"}"
        override val outgoing = fromA
        override fun send(frame: Frame, priority: Boolean) { if (!open) return; for (p in enc.split(if (priority) 1 else 0, frame.encode(), 180)) queue.addLast(fromA to p) }
        override fun close(reason: String) { open = false }
    }
    val endA = End(true); val endB = End(false)
    fun up() = apply { a.router.onLinkUp(endA); b.router.onLinkUp(endB) }
    fun pump(): Boolean {
        while (open && queue.isNotEmpty()) {
            val (toB, pkt) = queue.removeFirst()
            val res = (if (toB) codecB else codecA).feed(pkt) ?: continue
            val frame = Frame.decode(res.second)
            if (toB) b.router.onFrame(endB, frame) else a.router.onFrame(endA, frame)
            delivered++
            if (delivered >= budgetFrames) { open = false; break }
        }
        val cut = !open
        a.router.onLinkDown(endA.id); b.router.onLinkDown(endB.id)
        return cut
    }
}

fun plainOf(f: IncomingFile): ByteArray = SealedReader(f.sealedFile, f.fileKey, f.fileId, f.chunkSize, f.size).use { it.readAll() }

class CoreMeshTest {
    companion object {
        @JvmStatic @BeforeClass fun setup() { Crypto.curve = TinkCurve; Crypto.ed = TinkEd; Compression.padding = false }
    }

    @Test fun impostorIsRejected() {
        val tmp = kotlin.io.path.createTempDirectory("auth").toFile()
        val alice = Node("alice", tmp); val bob = Node("bob", tmp)
        val honest = Wire(alice, bob).up(); honest.pump()
        check(honest.open, "two honest phones prove their keys to each other")
        bob.sendPayload(alice.myPublicKey, Payload.Text(Crypto.randomBytes(16), 1, "for alice only", false))
        val mallory = Node("mallory", tmp, claimKey = alice.myPublicKey)
        val w = Wire(mallory, bob).up(); w.pump()
        check(!w.open, "a phone pretending to be someone else is cut off")
        check(mallory.inbox.isEmpty() && mallory.store.metas(0).isEmpty(), "the impostor receives nothing meant for them")
        val w2 = Wire(alice, bob).up(); w2.pump()
        check(alice.inbox.any { (it.second as? Payload.Text)?.text == "for alice only" }, "the real owner still gets it")
    }

    @Test fun endToEnd() {
    check(TinkCurve.shared("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4".hexToBytes(),
        "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c".hexToBytes()).toHex() ==
        "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", "X25519 RFC 7748 vector")
    val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
    check(TinkEd.publicFromSeed(seed).toHex() == "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "Ed25519 RFC 8032 public key")
    val sig = TinkEd.sign(seed, ByteArray(0))
    check(sig.toHex() == "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b", "Ed25519 RFC 8032 signature")
    check(TinkEd.verify(TinkEd.publicFromSeed(seed), ByteArray(0), sig) && !TinkEd.verify(TinkEd.publicFromSeed(seed), byteArrayOf(1), sig), "Ed25519 verify")
    check(Crypto.hkdf("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(), "000102030405060708090a0b0c".hexToBytes(), "f0f1f2f3f4f5f6f7f8f9".hexToBytes(), 42).toHex() ==
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", "HKDF RFC 5869 vector")

    // Compression
    val text = "hello hello hello hello, are we meeting at the ridge at six? ".repeat(20).toByteArray()
    val packed = Compression.pack(text)
    check(packed.size < text.size / 4 && Compression.unpack(packed).contentEquals(text), "text compressed ${text.size} -> ${packed.size} bytes and restored exactly")
    val rnd = Crypto.randomBytes(5000)
    check(Compression.pack(rnd).size == rnd.size + 1, "random data left uncompressed")

    // Payload v2 round trips
    val tp = Payload.unpack(Payload.Text(Crypto.randomBytes(16), 1, "see https://example.org", false,
        LinkPreview("https://example.org", "Example", "A page", ByteArray(300) { 7 })).pack()) as Payload.Text
    check(tp.preview?.title == "Example" && tp.preview?.image?.size == 300, "text with link preview codec")
    val gs = Payload.GroupState(Crypto.randomBytes(16), 1, Crypto.randomBytes(16), 3, 77, "Trail", false,
        List(200) { GroupMember(Crypto.randomBytes(32), Crypto.randomBytes(32), "Member $it", it == 0) })
    val gsPacked = gs.pack()
    val gs2 = Payload.unpack(gsPacked) as Payload.GroupState
    check(gs2.members.size == 200 && gs2.members[0].admin && gs2.epoch == 77, "200-member group state codec (${gsPacked.size} bytes)")
    val pf = Payload.unpack(Payload.File(Crypto.randomBytes(16), 1, Crypto.randomBytes(16), 4, "a.pdf", "application/pdf", 10, 32768, 1,
        Crypto.randomBytes(32), Crypto.randomBytes(32), 0, 0, 0, ByteArray(0), "", false, compressed = true, originalSize = 99).pack()) as Payload.File
    check(pf.compressed && pf.originalSize == 99L, "compressed file manifest codec")

    // Envelope
    val sPriv = TinkCurve.generatePrivate(); val sPub = TinkCurve.publicFrom(sPriv)
    val rPriv = TinkCurve.generatePrivate(); val rPub = TinkCurve.publicFrom(rPriv)
    val aad = Crypto.randomBytes(24)
    val wire = Envelope.seal(sPriv, sPub, rPub, aad, "hello".toByteArray())
    val (from, body) = Envelope.open(rPriv, rPub, aad, wire)
    check(from.contentEquals(sPub) && String(body) == "hello", "envelope round trip + sender authenticated")
    check(runCatching { Envelope.open(rPriv, rPub, Crypto.randomBytes(24), wire) }.isFailure, "swapped header rejected")

    // Group crypto
    val gKey = Crypto.randomBytes(32); val gid = Crypto.randomBytes(16); val node = GroupCrypto.groupNode(gid)
    val sEd = Crypto.edSeedFrom(sPriv); val sEdPub = TinkEd.publicFromSeed(sEd)
    val bid = Crypto.randomBytes(16)
    val gw = GroupCrypto.seal(gKey, 5, bid, node, sPub, sEd, "group hi".toByteArray())
    val (gFrom, gBody) = GroupCrypto.open(gKey, bid, node, gw) { if (it.contentEquals(sPub)) sEdPub else null }
    check(gFrom.contentEquals(sPub) && String(gBody) == "group hi" && GroupCrypto.epochOf(gw) == 5, "group message decrypts and signature verifies")
    check(runCatching { GroupCrypto.open(gKey, bid, node, gw) { TinkEd.publicFromSeed(Crypto.randomBytes(32)) } }.isFailure, "group member can't impersonate another (bad signature)")
    check(runCatching { GroupCrypto.open(Crypto.randomBytes(32), bid, node, gw) { sEdPub } }.isFailure, "removed member with old key can't read new epoch")

    // Backup stream
    val tmp = java.nio.file.Files.createTempDirectory("mura").toFile()
    val salt = Crypto.randomBytes(16)
    val bkey = Crypto.passwordKey("correct horse".toCharArray(), salt, 10_000)
    val bfile = File(tmp, "backup.bin")
    val big = Crypto.randomBytes(2_500_000)
    bfile.outputStream().use { raw ->
        BackupArchive.Header(salt, 10_000, 123).write(raw)
        val enc = EncryptedRecordWriter(raw, bkey)
        BackupArchive.writeEntry(enc, "db", big.size.toLong(), big.inputStream())
        BackupArchive.writeEntry(enc, "identity", 32, ByteArray(32) { 3 }.inputStream())
        BackupArchive.writeEnd(enc)
        enc.close()
    }
    val got = HashMap<String, ByteArray>()
    bfile.inputStream().use { raw ->
        val h = BackupArchive.Header.read(raw)
        val k = Crypto.passwordKey("correct horse".toCharArray(), h.salt, h.iterations)
        BackupArchive.read(EncryptedRecordReader(raw, k)) { n, _, d -> got[n] = d.readBytes() }
    }
    check(got["db"]!!.contentEquals(big) && got["identity"]!!.all { it == 3.toByte() }, "encrypted backup round trip (2.5 MB)")
    check(runCatching { bfile.inputStream().use { raw ->
        val h = BackupArchive.Header.read(raw)
        BackupArchive.read(EncryptedRecordReader(raw, Crypto.passwordKey("wrong".toCharArray(), h.salt, h.iterations))) { _, _, d -> d.readBytes() } } }.isFailure, "wrong backup password rejected")
    val cut = File(tmp, "cut.bin"); cut.writeBytes(bfile.readBytes().copyOf((bfile.length() - 500).toInt()))
    check(runCatching { cut.inputStream().use { raw ->
        val h = BackupArchive.Header.read(raw)
        BackupArchive.read(EncryptedRecordReader(raw, Crypto.passwordKey("correct horse".toCharArray(), h.salt, h.iterations))) { _, _, d -> d.readBytes() } } }.isFailure, "truncated backup rejected")

    // Sealed file random access
    val src = File(tmp, "video.bin"); src.writeBytes(Crypto.randomBytes(1_000_000 + 123))
    val fk = Crypto.randomBytes(32); val fid = Crypto.randomBytes(16); val sealed = File(tmp, "sealed.bin")
    val (sz, _) = SealedFile.seal(src.inputStream(), sealed, fk, fid)
    SealedReader(sealed, fk, fid, Chunks.SIZE, sz).use { r ->
        val buf = ByteArray(5000); r.read(40_000, buf, 0, 5000)
        check(buf.contentEquals(src.readBytes().copyOfRange(40_000, 45_000)) && sealed.length() == Chunks.sealedSize(sz, Chunks.SIZE), "random-access decrypt across piece boundary")
    }

    // ---------- mesh scenarios ----------
    val A = Node("A", File(tmp, "A")); val B = Node("B", File(tmp, "B")); val C = Node("C", File(tmp, "C")); val D = Node("D", File(tmp, "D"))

    A.sendPayload(C.myPublicKey, Payload.Text(Crypto.randomBytes(16), 1, "meet at the ridge", false))
    Wire(A, B).up().pump()
    check(B.store.bundles.size == 1 && C.inbox.isEmpty(), "B carries the sealed message")
    Wire(B, C).up().pump()
    check(C.inbox.size == 1 && (C.inbox[0].second as Payload.Text).text == "meet at the ridge" && C.inbox[0].first.contentEquals(A.myPublicKey), "C receives via B, sender verified as A")
    Wire(B, C).up().pump()
    check(B.store.bundles.isEmpty(), "B deletes its copy after C confirms")

    // Large file, interrupted, resumed across phones; content stays sealed on disk
    val mf = A.fileManifest(C.myNodeId, src)
    A.sendPayload(C.myPublicKey, mf)
    check(Wire(A, B, budgetFrames = 9).up().pump(), "A-B link cut mid transfer")
    Wire(A, B, budgetFrames = 25).up().pump()
    Wire(B, C, budgetFrames = 14).up().pump()
    val partial = C.inFiles.values.first().receivedCount
    check(partial in 1 until 31 && C.completed.isEmpty(), "C has partial file ($partial/31 pieces)")
    Wire(A, C, budgetFrames = 12).up().pump(); Wire(B, C).up().pump(); Wire(A, C).up().pump()
    check(C.completed.size == 1 && plainOf(C.completed[0]).contentEquals(src.readBytes()), "file resumed across phones and verified byte-identical")
    val onDisk = C.completed[0].sealedFile.readBytes()
    check(java.util.Collections.indexOfSubList(onDisk.toList(), src.readBytes().copyOfRange(1000, 1032).toList()) < 0, "received file is stored encrypted, never in plain form")

    // Group of A, C, D. B is a stranger who only relays.
    val members = mapOf(A.myPublicKey.toHex() to A.edPub, C.myPublicKey.toHex() to C.edPub, D.myPublicKey.toHex() to D.edPub)
    val g = Group(Crypto.randomBytes(16), Crypto.randomBytes(32), 1, members)
    for (n in listOf(A, C, D)) n.groups[g.node.toHex()] = g
    A.sendGroup(g, Payload.Text(Crypto.randomBytes(16), 2, "group: leaving at 7", false))
    Wire(A, B).up().pump()
    check(B.groupInbox.isEmpty() && B.store.bundles.values.any { it.first.dest.contentEquals(g.node) }, "stranger B carries the group message but can't read it")
    Wire(B, C).up().pump()
    check(C.groupInbox.size == 1 && C.groupInbox[0].first.contentEquals(A.myPublicKey), "member C receives it, signed by A")
    Wire(C, D).up().pump()
    check(D.groupInbox.size == 1, "member D receives it from C (members pass group messages on)")
    Wire(B, D).up().pump()
    check(D.groupInbox.size == 1, "no duplicate group delivery")

    // Group file: one encrypted copy reaches everyone
    val gsrc = File(tmp, "g.bin"); gsrc.writeBytes(Crypto.randomBytes(200_000))
    val gm = A.fileManifest(g.node, gsrc)
    A.sendGroup(g, gm)
    Wire(A, C).up().pump()
    Wire(C, D).up().pump()
    check(C.completed.any { plainOf(it).contentEquals(gsrc.readBytes()) } && D.completed.any { plainOf(it).contentEquals(gsrc.readBytes()) }, "group file reaches C and D intact")

    }
}
