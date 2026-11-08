package com.mura.mesh.core

/** A live connection to one nearby phone (Bluetooth LE on Android, in-memory in tests). */
interface Link {
    val id: String
    /** True if this phone initiated the connection. */
    val outgoing: Boolean
    /** Queue a frame. [priority] frames (call audio) jump ahead of file data. */
    fun send(frame: Frame, priority: Boolean = false)
    fun close(reason: String)
    /** Internet link (Tor). Only traffic for the two phones (and groups they share) crosses it — no relaying for strangers. */
    val remote: Boolean get() = false
}

/** Relay storage. Implemented with SQLite on Android, in memory in tests. */
interface BundleStore {
    fun has(id: ByteArray): Boolean
    /** Returns false if there is no room (relay budget). */
    fun put(bundle: Bundle, mine: Boolean): Boolean
    fun get(id: ByteArray): Bundle?
    fun delete(id: ByteArray)
    /** Delete relayed (not own) bundles whose id or group equals [id]. */
    fun deleteRelayedByIdOrGroup(id: ByteArray)
    fun metas(now: Long): List<BundleMeta>
    fun canStore(size: Int, group: ByteArray?): Boolean
    fun isTombstoned(id: ByteArray): Boolean
    fun addTombstone(id: ByteArray, now: Long)
    fun recentTombstones(limit: Int): List<ByteArray>
    /** Group/status bundles already processed (they keep travelling, so they are never tombstoned). */
    fun markSeen(id: ByteArray)
    fun isSeen(id: ByteArray): Boolean
}

/** The app side: decrypts what is for us, provides our own outgoing file pieces. */
interface MeshHost {
    val myPublicKey: ByteArray
    val myNodeId: ByteArray
    /** X25519(my private key, [peerPublic]) — used only to prove link identities, never stored. */
    fun sharedSecret(peerPublic: ByteArray): ByteArray
    val helloName: String
    val relayEnabled: Boolean
    fun now(): Long

    fun onMessageBundle(bundle: Bundle)
    fun wantChunk(meta: BundleMeta): Boolean
    fun onChunkBundle(bundle: Bundle)
    /** Pieces of files I am sending that are not yet confirmed delivered. */
    fun outgoingChunkMetas(now: Long): List<BundleMeta>
    fun loadOutgoingChunk(id: ByteArray): Bundle?

    fun onPeerReady(linkId: String, publicKey: ByteArray, name: String)
    fun onPeerGone(linkId: String, publicKey: ByteArray?)
    fun onCallFrame(linkId: String, peerPublicKey: ByteArray, sealed: ByteArray)
    fun onAudioFrame(linkId: String, seq: Long, cipher: ByteArray)
    /** True for pieces of a file this phone is itself sending (never store copies of our own file). */
    fun isOwnFile(group: ByteArray): Boolean = false
    /** Group and status addresses this phone listens to. */
    fun isSubscribed(dest: ByteArray): Boolean = false
    /**
     * Friend mailboxes (internet links only): this phone agrees to hold sealed mail addressed to [dest]
     * because [dest] is one of its own contacts. It can't read it; it hands it over when that friend is online.
     */
    fun isMailboxFor(dest: ByteArray): Boolean = false
    /**
     * Carry anyone's sealed mail over internet links too (not just Bluetooth): mail then hops from phone to
     * phone across everyone's Tor connections until it reaches someone who can deliver it.
     */
    val internetRelay: Boolean get() = false
    /** Offer this (own, undelivered) message to a contact over the internet, in case they can carry it. */
    fun offerToMailbox(meta: BundleMeta): Boolean = false
    /** A group or status message arrived (it is also kept and passed on to other members). */
    fun onSubscribedBundle(bundle: Bundle) {}
    /** A bundle was sent to a peer (the final recipient, or a phone that will carry it). */
    fun onHandedOver(bundleId: ByteArray, type: Int, toDestination: Boolean) {}
    fun log(msg: String) {}
}

/**
 * Epidemic store-and-forward with offer/want:
 *  1. Both sides say HELLO and share recent "already delivered" ids (tombstones).
 *  2. Each side OFFERs bundle headers in batches, most urgent first (things for this peer).
 *  3. The other side WANTs only what it is missing, has room for, and is not delivered.
 *  4. Bundles flow; the receiver either consumes them (for me) or keeps them to carry onward.
 *
 * Because only missing pieces are requested, an interrupted file transfer resumes from
 * the last complete 32 KB piece on the next contact, with any phone that holds pieces.
 */
class MeshRouter(private val host: MeshHost, private val store: BundleStore) {

    companion object {
        const val OFFER_BATCH = 96
        const val MAX_HOPS = 8
    }

    private class LinkState(val link: Link) {
        var peerPub: ByteArray? = null
        var peerNode: ByteArray? = null
        var peerName: String = ""
        var peerRelays = false
        var ready = false
        val myNonce: ByteArray = Crypto.randomBytes(16)
        var peerNonce: ByteArray? = null
        var awaitingWant = false
        var iAmDone = false
        var peerDone = false
        val offered = HashSet<String>()
        /** Ids already sent on this link in the current offer round (a peer can't make us resend the same data over and over). */
        val served = HashSet<String>()
        val peerHas = HashSet<String>()
        var lastActivity = 0L
    }

    private val links = LinkedHashMap<String, LinkState>()
    private val lock = Any()

    fun connectedPeers(): List<Pair<String, ByteArray>> = synchronized(lock) {
        links.values.filter { it.ready }.map { it.link.id to it.peerPub!! }
    }

    fun isConnected(nodeId: ByteArray): Boolean = synchronized(lock) {
        links.values.any { it.ready && it.peerNode?.contentEquals(nodeId) == true }
    }

    fun linkFor(publicKey: ByteArray): Link? = synchronized(lock) {
        links.values.firstOrNull { it.ready && it.peerPub?.contentEquals(publicKey) == true }?.link
    }

    /** Idle = both sides have nothing more to exchange. Used to drop idle links and free slots. */
    fun isIdle(linkId: String, now: Long, idleMs: Long): Boolean = synchronized(lock) {
        val s = links[linkId] ?: return true
        s.ready && s.iAmDone && s.peerDone && now - s.lastActivity > idleMs
    }

    /** Will this phone carry other people's mail on [link]? */
    private fun relays(link: Link) = host.relayEnabled && (!link.remote || host.internetRelay)

    fun onLinkUp(link: Link) = synchronized(lock) {
        links[link.id] = LinkState(link).also { it.lastActivity = host.now() }
        val s = links.getValue(link.id)
        link.send(Frame.Hello(host.myPublicKey, host.helloName, relays(link), s.myNonce))
    }

    private fun authKey(peerPub: ByteArray): ByteArray {
        val dh = try { host.sharedSecret(peerPub) } catch (e: Exception) { throw ProtocolException("bad public key") }
        // A low-order (all-zero) result would let anyone "prove" anything: refuse it.
        if (dh.all { it == 0.toByte() }) throw ProtocolException("bad public key")
        try { return Crypto.hkdf(dh, "mura-link-auth-v1".toByteArray(), ByteArray(0)) } finally { dh.fill(0) }
    }

    private fun proofMac(key: ByteArray, fromPub: ByteArray, toPub: ByteArray, fromNonce: ByteArray, toNonce: ByteArray) =
        Crypto.hmac(key, "proof".toByteArray() + fromPub + toPub + fromNonce + toNonce)

    fun onLinkDown(linkId: String) = synchronized(lock) {
        val s = links.remove(linkId) ?: return@synchronized
        if (s.ready) host.onPeerGone(linkId, s.peerPub)
    }

    /** Call after storing a new outgoing bundle, adding a file, or receiving something relayable. */
    fun kick(reofferAll: Boolean = false) = synchronized(lock) {
        for (s in links.values) {
            if (!s.ready) continue
            if (reofferAll) { s.offered.clear(); s.served.clear() }
            s.iAmDone = false
            if (!s.awaitingWant) sendNextOffer(s)
        }
    }

    fun sendTombstones(ids: List<ByteArray>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        for (s in links.values) if (s.ready) s.link.send(Frame.Tombstones(ids.take(Frame.MAX_LIST)))
    }

    fun onFrame(link: Link, frame: Frame) = synchronized(lock) {
        val s = links[link.id] ?: return@synchronized
        s.lastActivity = host.now()
        try {
            handle(s, frame)
        } catch (e: ProtocolException) {
            host.log("protocol error from ${link.id}: ${e.message}")
            link.close("protocol")
        } catch (e: Exception) {
            // Anything unexpected from another phone's data closes only that link — it can never crash Mura.
            host.log("error handling a frame from ${link.id}: ${e.javaClass.simpleName}")
            link.close("error")
        }
    }

    private fun handle(s: LinkState, f: Frame) {
        // Nothing but the handshake is accepted until the other phone has proved who it is.
        if (f !is Frame.Hello && f !is Frame.Proof && !s.ready) throw ProtocolException("frame before authentication")
        when (f) {
            is Frame.Hello -> onHello(s, f)
            is Frame.Proof -> onProof(s, f)
            is Frame.Tombstones -> for (id in f.ids) {
                store.addTombstone(id, host.now())
                store.deleteRelayedByIdOrGroup(id)
            }
            is Frame.Offer -> onOffer(s, f)
            is Frame.Want -> onWant(s, f)
            is Frame.Data -> onData(s, f.bundle)
            is Frame.Done -> s.peerDone = true
            is Frame.Call -> host.onCallFrame(s.link.id, s.peerPub!!, f.sealed)
            is Frame.Audio -> host.onAudioFrame(s.link.id, f.seq, f.cipher)
            is Frame.Ping -> {}
        }
    }

    private fun onHello(s: LinkState, h: Frame.Hello) {
        if (s.ready || s.peerNonce != null) return
        if (h.publicKey.contentEquals(host.myPublicKey)) { s.link.close("self"); return }
        // Remember the claim and answer its challenge; it only counts once their own Proof checks out.
        s.peerPub = h.publicKey
        s.peerName = h.name
        s.peerRelays = h.relay
        s.peerNonce = h.nonce
        val key = authKey(h.publicKey)
        s.link.send(Frame.Proof(proofMac(key, host.myPublicKey, h.publicKey, s.myNonce, h.nonce)))
        key.fill(0)
    }

    private fun onProof(s: LinkState, p: Frame.Proof) {
        if (s.ready) return
        val pub = s.peerPub ?: throw ProtocolException("proof before hello")
        val peerNonce = s.peerNonce!!
        val key = authKey(pub)
        val expected = proofMac(key, pub, host.myPublicKey, peerNonce, s.myNonce)
        key.fill(0)
        if (!java.security.MessageDigest.isEqual(expected, p.mac)) { host.log("link authentication failed"); s.link.close("auth"); return }
        val h = Frame.Hello(pub, s.peerName, s.peerRelays, peerNonce)
        val node = Crypto.nodeId(h.publicKey)
        // One link per peer: keep the one started by the phone with the smaller node id.
        val dup = links.values.firstOrNull { it !== s && it.ready && it.peerNode?.contentEquals(node) == true }
        if (dup != null) {
            val myIdSmaller = host.myNodeId.toHex() < node.toHex()
            val preferredOutgoing = myIdSmaller // the smaller id should be the initiator
            val keepNew = s.link.outgoing == preferredOutgoing && dup.link.outgoing != preferredOutgoing
            if (keepNew) {
                links.remove(dup.link.id)
                dup.link.close("duplicate")
                host.onPeerGone(dup.link.id, dup.peerPub)
            } else {
                s.link.close("duplicate")
                return
            }
        }
        s.peerPub = h.publicKey
        s.peerNode = node
        s.peerName = h.name
        s.peerRelays = h.relay
        s.ready = true
        host.onPeerReady(s.link.id, h.publicKey, h.name)
        val tombs = store.recentTombstones(Frame.MAX_LIST)
        if (tombs.isNotEmpty()) s.link.send(Frame.Tombstones(tombs))
        sendNextOffer(s)
    }

    private fun candidates(s: LinkState, now: Long): List<BundleMeta> {
        val peerNode = s.peerNode!!
        val all = ArrayList<BundleMeta>()
        all.addAll(store.metas(now))
        all.addAll(host.outgoingChunkMetas(now))
        val forPeer = ArrayList<BundleMeta>()
        val msgs = ArrayList<BundleMeta>()
        val chunks = ArrayList<BundleMeta>()
        for (m in all) {
            val key = m.id.toHex()
            if (key in s.offered || key in s.peerHas) continue
            if (m.expiresAt <= now) continue
            if (m.dest.contentEquals(host.myNodeId)) continue
            when {
                m.dest.contentEquals(peerNode) -> forPeer.add(m)
                m.hops <= 0 -> {}
                // Group/status traffic is offered to everyone: members want it even if they don't relay.
                !s.peerRelays && !host.isSubscribed(m.dest) && !(s.link.remote && m.type == Bundle.TYPE_MSG && host.offerToMailbox(m)) -> {}
                m.type == Bundle.TYPE_MSG -> msgs.add(m)
                else -> chunks.add(m)
            }
        }
        val peerMsgs = forPeer.filter { it.type == Bundle.TYPE_MSG }
        val peerChunks = forPeer.filter { it.type == Bundle.TYPE_CHUNK }
            .sortedWith(compareBy<BundleMeta>({ it.group.toHex() }, { it.seq }))
        chunks.sortWith(compareBy<BundleMeta>({ it.group.toHex() }, { it.seq }))
        // Messages for the peer go first, in a batch of their own: a file's pieces are only
        // useful once its (encrypted) description has arrived, so they follow next round.
        val ordered = if (peerMsgs.isNotEmpty()) peerMsgs + msgs else peerChunks + msgs + chunks
        return ordered.take(OFFER_BATCH)
    }

    private fun sendNextOffer(s: LinkState) {
        val batch = candidates(s, host.now())
        if (batch.isEmpty()) {
            if (!s.iAmDone) { s.iAmDone = true; s.link.send(Frame.Done) }
            return
        }
        s.iAmDone = false
        for (m in batch) s.offered.add(m.id.toHex())
        s.awaitingWant = true
        s.link.send(Frame.Offer(batch))
    }

    private fun onOffer(s: LinkState, o: Frame.Offer) {
        s.peerDone = false
        val now = host.now()
        val want = ArrayList<ByteArray>()
        val reserved = HashMap<String, Int>()
        for (m in o.entries) {
            s.peerHas.add(m.id.toHex())
            if (m.expiresAt <= now) continue
            if (store.isTombstoned(m.id)) continue
            if (m.type == Bundle.TYPE_CHUNK && store.isTombstoned(m.group)) continue
            if (m.type == Bundle.TYPE_CHUNK && host.isOwnFile(m.group)) continue
            val forMe = m.dest.contentEquals(host.myNodeId)
            val subscribed = !forMe && host.isSubscribed(m.dest)
            val mailbox = !forMe && s.link.remote && m.type == Bundle.TYPE_MSG && host.isMailboxFor(m.dest)
            val relayOk = !forMe && m.hops > 0 && !store.has(m.id) && (subscribed || relays(s.link) || mailbox) &&
                store.canStore(m.size + reserved.values.sum(), if (m.type == Bundle.TYPE_CHUNK) m.group else null)
            val ok = when {
                forMe -> if (m.type == Bundle.TYPE_MSG) !store.has(m.id) else host.wantChunk(m)
                subscribed && m.type == Bundle.TYPE_MSG -> !store.has(m.id) && !store.isSeen(m.id)
                subscribed -> host.wantChunk(m) || relayOk
                else -> relayOk
            }
            if (ok) {
                want.add(m.id)
                if (!forMe) reserved[m.id.toHex()] = m.size
            }
        }
        s.link.send(Frame.Want(want))
    }

    private fun onWant(s: LinkState, w: Frame.Want) {
        s.awaitingWant = false
        for (id in w.ids) {
            // Only what we offered on this link, and each item once per round: no fetching by guessed ids,
            // and no memory flood from repeated requests.
            val key = id.toHex()
            if (key !in s.offered || !s.served.add(key)) continue
            val b = store.get(id) ?: host.loadOutgoingChunk(id) ?: continue
            if (b.expiresAt <= host.now()) continue
            val toDest = b.dest.contentEquals(s.peerNode!!)
            // Relays receive one fewer remaining hop; the destination gets it as is.
            val out = if (toDest) b else b.withHops(b.hops - 1)
            s.link.send(Frame.Data(out))
            host.onHandedOver(b.id, b.type, toDest)
        }
        sendNextOffer(s)
    }

    private fun onData(s: LinkState, b: Bundle) {
        s.peerHas.add(b.id.toHex())
        val now = host.now()
        if (b.expiresAt <= now || store.isTombstoned(b.id)) return
        if (b.dest.contentEquals(host.myNodeId)) {
            if (b.type == Bundle.TYPE_MSG) {
                if (store.has(b.id)) return
                store.addTombstone(b.id, now)
                host.onMessageBundle(b)
                for (o in links.values) if (o.ready && o !== s) o.link.send(Frame.Tombstones(listOf(b.id)))
                s.link.send(Frame.Tombstones(listOf(b.id)))
            } else {
                if (store.isTombstoned(b.group)) return
                host.onChunkBundle(b)
            }
            return
        }
        if (host.isSubscribed(b.dest)) {
            if (b.type == Bundle.TYPE_MSG) {
                if (store.has(b.id) || store.isSeen(b.id)) return
                store.markSeen(b.id)
                val kept = b.hops > 0 && store.put(b, mine = false)
                host.onSubscribedBundle(b)
                if (kept) announce(s)
            } else {
                if (host.wantChunk(b.meta)) host.onChunkBundle(b)
                if (b.hops > 0 && !host.isOwnFile(b.group) && !store.has(b.id) && store.put(b, mine = false)) announce(s)
            }
            return
        }
        if (store.has(b.id)) return
        // Over the internet only friend-mailbox mail is accepted (and never strangers'); over Bluetooth, normal relaying.
        val allowed = relays(s.link) || (s.link.remote && b.type == Bundle.TYPE_MSG && host.isMailboxFor(b.dest))
        if (!allowed) return
        if (store.put(b, mine = false)) announce(s)
    }

    /** Something new to carry: let the other connected phones know right away. */
    private fun announce(from: LinkState) {
        for (o in links.values) {
            if (o === from || !o.ready) continue
            o.iAmDone = false
            if (!o.awaitingWant) sendNextOffer(o)
        }
    }

    // ---- live calls (direct links only) ----

    fun sendCallFrame(peerPublicKey: ByteArray, sealed: ByteArray): Boolean = synchronized(lock) {
        val l = links.values.firstOrNull { it.ready && it.peerPub?.contentEquals(peerPublicKey) == true } ?: return@synchronized false
        l.link.send(Frame.Call(sealed), priority = true)
        true
    }

    fun sendAudio(linkId: String, seq: Long, cipher: ByteArray): Boolean = synchronized(lock) {
        val l = links[linkId] ?: return@synchronized false
        l.link.send(Frame.Audio(seq, cipher), priority = true)
        true
    }

    fun closeAll() = synchronized(lock) {
        for (s in links.values.toList()) s.link.close("shutdown")
        links.clear()
    }
}
