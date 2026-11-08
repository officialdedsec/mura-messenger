package com.mura.mesh.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mura.mesh.core.Frame
import com.mura.mesh.core.Link
import com.mura.mesh.core.PacketCodec
import com.mura.mesh.core.ProtocolException
import com.mura.mesh.core.toHex
import java.util.ArrayDeque
import java.util.UUID

/**
 * Bluetooth LE mesh transport.
 *
 * Every phone is both a GATT server (others connect to it) and a GATT client (it connects
 * to others). Phones find each other by advertising a Mura service UUID, with a random 8-byte
 * id in the scan response that changes every 15 minutes, so a phone can't be tracked by its
 * Bluetooth advertisement. There is no Bluetooth pairing or bonding. Each connection carries a byte pipe:
 *   client -> server : writes to RX
 *   server -> client : notifications on TX
 * Frames are split into packets by [PacketCodec]; any lost or reordered packet is detected
 * and the link is dropped and re-established, so data is never silently corrupted.
 *
 * All Bluetooth work runs on one background thread, in order.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val ctx: Context,
    private val myNodeId: ByteArray,
    private val listener: Listener,
) {
    interface Listener {
        fun onLinkUp(link: Link)
        fun onLinkDown(linkId: String)
        fun onFrame(link: Link, frame: Frame)
        fun onNearby(nodeHex: String, rssi: Int)
        fun onStateChanged(state: State)
    }

    enum class State { OFF, NO_PERMISSION, RUNNING, UNSUPPORTED }

    companion object {
        private const val TAG = "MuraBle"
        val SERVICE: UUID = UUID.fromString("6d757261-6d65-7368-0001-0000c0ffee01")
        val RX: UUID = UUID.fromString("6d757261-6d65-7368-0002-0000c0ffee01")
        val TX: UUID = UUID.fromString("6d757261-6d65-7368-0003-0000c0ffee01")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MAX_LINKS = 6
        private const val SETUP_TIMEOUT_MS = 20_000L
        private const val WRITE_TIMEOUT_MS = 8_000L
        private const val SCAN_RESTART_MS = 20 * 60_000L
        private const val MIN_SCAN_GAP_MS = 7_000L
        private const val RECONNECT_COOLDOWN_MS = 15_000L
        private const val FALLBACK_CONNECT_MS = 12_000L
        /** The advertised id rotates, like Android's random Bluetooth address. */
        private const val ADVERT_ROTATE_MS = 15 * 60_000L
    }

    private var advertId: ByteArray = com.mura.mesh.core.Crypto.randomBytes(8)
    private var advertIdAt = SystemClock.elapsedRealtime()
    /** Advertised ids of phones we're linked with right now (for the mesh radar). */
    @Volatile var linkedAdverts: Set<String> = emptySet()
        private set
    private fun refreshLinkedAdverts() { linkedAdverts = links.values.filter { it.isUp }.mapNotNull { it.advertNode }.toSet() }

    private val thread = HandlerThread("mura-ble").apply { start() }
    val handler = Handler(thread.looper)

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var scanning = false
    private var started = false
    @Volatile var foreground = false
        set(v) { field = v; handler.post { if (started) restartScan() } }

    private val links = HashMap<String, BleLink>()               // by link id
    private val serverMtu = HashMap<String, Int>()               // by device address
    private val connecting = HashMap<String, Long>()             // address -> started
    private val cooldownUntil = HashMap<String, Long>()          // node hex or address -> time
    private val firstSeen = HashMap<String, Long>()              // node hex -> time
    private val addressNode = HashMap<String, String>()          // address -> node hex

    // ------------------------------------------------------------ lifecycle

    fun start() = handler.post { startInternal() }

    fun stop() = handler.post { stopInternal() }

    fun shutdown() {
        handler.post {
            stopInternal()
            try { ctx.unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    private var receiverRegistered = false
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> handler.postDelayed({ startInternal() }, 1000)
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handler.post { stopInternal(); listener.onStateChanged(State.OFF) }
            }
        }
    }

    fun hasPermissions(): Boolean = MeshPermissions.bluetoothGranted(ctx)

    private fun startInternal() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(ctx, btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        val a = adapter
        if (a == null || !ctx.packageManager.hasSystemFeature("android.hardware.bluetooth_le")) { listener.onStateChanged(State.UNSUPPORTED); return }
        if (!hasPermissions()) { listener.onStateChanged(State.NO_PERMISSION); return }
        if (!a.isEnabled) { listener.onStateChanged(State.OFF); return }
        if (started) return
        started = true
        try {
            openServer()
            startAdvertising()
            restartScan()
            listener.onStateChanged(State.RUNNING)
            handler.postDelayed(maintenance, 5_000)
        } catch (e: SecurityException) {
            started = false
            listener.onStateChanged(State.NO_PERMISSION)
        }
    }

    private fun stopInternal() {
        handler.removeCallbacks(maintenance)
        handler.removeCallbacks(delayedRestart); scanRestartPending = false
        started = false
        try { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
        try { if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        advertising = false
        for (l in links.values.toList()) l.close("stopping")
        links.clear()
        connecting.clear()
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private var lastScanStart = 0L
    private val maintenance = object : Runnable {
        override fun run() {
            if (!started) return
            val now = SystemClock.elapsedRealtime()
            // Android downgrades scans older than 30 min, so restart well before that.
            // A failed scan is retried at most once a minute (Android limits scan starts).
            if (now - lastScanStart > SCAN_RESTART_MS || (!scanning && now - lastScanStart > 60_000)) restartScan()
            for ((addr, t) in connecting.toList()) if (now - t > SETUP_TIMEOUT_MS) {
                connecting.remove(addr)
                links.values.firstOrNull { it.address == addr && !it.isUp }?.close("setup timeout")
            }
            for (l in links.values.toList()) l.checkWatchdog(now)
            if (now - advertIdAt > ADVERT_ROTATE_MS) {
                try { if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
                advertising = false
                advertId = com.mura.mesh.core.Crypto.randomBytes(8)
                advertIdAt = now
            }
            if (!advertising) startAdvertising()
            refreshLinkedAdverts()
            handler.postDelayed(this, 5_000)
        }
    }

    // ------------------------------------------------------------ advertising

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true }
        override fun onStartFailure(errorCode: Int) {
            advertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED
            Log.w(TAG, "advertise failed $errorCode")
        }
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(SERVICE), advertId)
            .build()
        try { adv.startAdvertising(settings, data, scanResponse, advertiseCallback) } catch (e: Exception) { Log.w(TAG, "advertise", e) }
    }

    // ------------------------------------------------------------ scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post { onScan(result) } }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { val copy = results.toList(); handler.post { copy.forEach { onScan(it) } } }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed $errorCode"); scanning = false }
    }

    private var scanRestartPending = false
    private val delayedRestart = Runnable { scanRestartPending = false; if (started) restartScan() }

    private fun restartScan() {
        // Android silently blocks apps that start more than 5 scans in 30 s (no error, just no results), so space
        // restarts at least 7 s apart — e.g. when Mura is opened and closed quickly several times.
        val since = SystemClock.elapsedRealtime() - lastScanStart
        if (lastScanStart > 0 && since < MIN_SCAN_GAP_MS) {
            if (!scanRestartPending) { scanRestartPending = true; handler.postDelayed(delayedRestart, MIN_SCAN_GAP_MS - since) }
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: return
        try { if (scanning) scanner.stopScan(scanCallback) } catch (_: Exception) {}
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(if (foreground) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        lastScanStart = SystemClock.elapsedRealtime()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (e: Exception) { Log.w(TAG, "scan start", e) }
    }

    private fun onScan(r: ScanResult) {
        if (!started) return
        val address = r.device.address
        val nodeBytes = r.scanRecord?.getServiceData(ParcelUuid(SERVICE))
        val node = if (nodeBytes != null && nodeBytes.size == 8) nodeBytes.toHex() else addressNode[address]
        if (node != null) {
            if (node == advertId.toHex()) return
            addressNode[address] = node
            listener.onNearby(node, r.rssi)
        }
        maybeConnect(r.device, node)
    }

    private fun maybeConnect(device: BluetoothDevice, node: String?) {
        val now = SystemClock.elapsedRealtime()
        val address = device.address
        if (connecting.containsKey(address)) return
        if (links.values.any { it.address == address }) return
        if (node != null && links.values.any { it.peerNode == node || it.advertNode == node }) return
        if ((cooldownUntil[address] ?: 0) > now) return
        if (node != null && (cooldownUntil[node] ?: 0) > now) return
        if (links.size >= MAX_LINKS) return
        val key = node ?: address
        val seen = firstSeen.getOrPut(key) { now }
        // To avoid both phones dialing each other at once, the smaller id dials first;
        // the other one dials too if nothing happened after a few seconds.
        val iDial = node == null || advertId.toHex() < node || now - seen > FALLBACK_CONNECT_MS
        if (!iDial) return
        connectTo(device, node)
    }

    /** Called when the app has something new to deliver: retry nearby phones right away. */
    fun clearCooldowns() = handler.post { cooldownUntil.clear() }

    private fun connectTo(device: BluetoothDevice, node: String?) {
        val link = ClientLink(device, node)
        connecting[device.address] = SystemClock.elapsedRealtime()
        links[link.id] = link
        link.connect()
    }

    // ------------------------------------------------------------ GATT server

    private fun openServer() {
        if (server != null) return
        val s = manager?.openGattServer(ctx, serverCallback) ?: throw IllegalStateException("no gatt server")
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        s.addService(service)
        server = s
        txChar = tx
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) handler.post {
                links["s:${device.address}"]?.close("peer disconnected")
                serverMtu.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                serverMtu[device.address] = mtu
                (links["s:${device.address}"] as? ServerLink)?.mtu = mtu
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (_: Exception) {}
            if (characteristic.uuid != RX || value == null) return
            val copy = value.copyOf()
            handler.post { (links["s:${device.address}"] as? ServerLink)?.onPacket(copy) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (_: Exception) {}
            if (descriptor.uuid != CCCD || value == null) return
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            handler.post {
                val id = "s:${device.address}"
                if (enable) {
                    if (links.containsKey(id)) return@post
                    if (links.size >= MAX_LINKS + 2) { try { server?.cancelConnection(device) } catch (_: Exception) {}; return@post }
                    val l = ServerLink(device, serverMtu[device.address] ?: 23)
                    links[id] = l
                    l.up()
                } else links[id]?.close("unsubscribed")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { (links["s:${device.address}"] as? ServerLink)?.onSent(status == BluetoothGatt.GATT_SUCCESS) }
        }
    }

    // ------------------------------------------------------------ links

    abstract inner class BleLink(val address: String) : Link {
        var peerNode: String? = null
        /** The id this phone was advertising when we found it (rotates; only used to avoid double links). */
        var advertNode: String? = null
        var isUp = false
        private var closed = false
        private val codecIn = PacketCodec()
        private val codecOut = PacketCodec()
        private val audioQueue = ArrayDeque<ByteArray>()
        private val dataQueue = ArrayDeque<ByteArray>()
        protected var inFlight = false
        private var inFlightSince = 0L

        abstract val packetSize: Int
        protected abstract fun writePacket(p: ByteArray): Boolean
        protected abstract fun disconnect()

        override fun send(frame: Frame, priority: Boolean) {
            val bytes = frame.encode()
            handler.post {
                if (closed) return@post
                val packets = codecOut.split(if (priority) 1 else 0, bytes, packetSize)
                if (priority) {
                    // Live audio: if the queue is backing up, drop old audio rather than add delay.
                    if (frame is Frame.Audio && audioQueue.size > 40) audioQueue.clear()
                    audioQueue.addAll(packets)
                } else dataQueue.addAll(packets)
                pump()
            }
        }

        protected fun pump() {
            if (closed || inFlight || !isUp) return
            val next = audioQueue.pollFirst() ?: dataQueue.pollFirst() ?: return
            inFlight = true
            inFlightSince = SystemClock.elapsedRealtime()
            if (!writePacket(next)) {
                // Stack busy: put it back and retry shortly.
                inFlight = false
                if (next[0].toInt() == 1) audioQueue.addFirst(next) else dataQueue.addFirst(next)
                handler.postDelayed({ pump() }, 15)
            }
        }

        fun onSent(ok: Boolean) {
            inFlight = false
            if (!ok) { close("write failed"); return }
            pump()
        }

        fun onPacket(p: ByteArray) {
            if (closed) return
            val res = try { codecIn.feed(p) } catch (e: ProtocolException) { close("stream: ${e.message}"); return } ?: return
            val frame = try { Frame.decode(res.second) } catch (e: Exception) { close("frame: ${e.message}"); return }
            if (frame is Frame.Hello) peerNode = com.mura.mesh.core.Crypto.nodeId(frame.publicKey).toHex()
            listener.onFrame(this, frame)
        }

        fun up() {
            isUp = true
            connecting.remove(address)
            listener.onLinkUp(this)
            pump()
        }

        fun checkWatchdog(now: Long) {
            if (inFlight && now - inFlightSince > WRITE_TIMEOUT_MS) close("write timeout")
        }

        override fun close(reason: String) {
            handler.post { closeNow(reason) }
        }

        private fun closeNow(reason: String) {
            if (closed) return
            closed = true
            Log.i(TAG, "link $id closed: $reason")
            audioQueue.clear(); dataQueue.clear()
            links.remove(id)
            connecting.remove(address)
            val until = SystemClock.elapsedRealtime() + RECONNECT_COOLDOWN_MS
            if (reason != "duplicate") {
                cooldownUntil[address] = until
                peerNode?.let { cooldownUntil[it] = until }
            } else {
                cooldownUntil[address] = SystemClock.elapsedRealtime() + 60_000
            }
            try { disconnect() } catch (_: Exception) {}
            if (isUp) listener.onLinkDown(id)
            isUp = false
        }
    }

    /** I connected to the peer's GATT server. */
    inner class ClientLink(private val device: BluetoothDevice, node: String?) : BleLink(device.address) {
        override val id = "c:${device.address}"
        override val outgoing = true
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null
        private var mtu = 23
        override val packetSize: Int get() = (mtu - 3).coerceIn(20, 512)

        init { peerNode = node; advertNode = node }

        fun connect() {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK, handler)
            } else {
                device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            if (gatt == null) close("connectGatt null")
        }

        override fun writePacket(p: ByteArray): Boolean {
            val g = gatt ?: return false
            val c = rx ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = p
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }

        override fun disconnect() {
            gatt?.let { try { it.disconnect() } catch (_: Exception) {}; try { it.close() } catch (_: Exception) {} }
            gatt = null
        }

        // Callbacks arrive on our handler thread on Android 8+; we post anyway to be safe on 9.
        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                        if (adapter?.isLe2MPhySupported == true) {
                            try { g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED) } catch (_: Exception) {}
                        }
                        if (!g.requestMtu(517)) g.discoverServices()
                    } else {
                        close("gatt state $newState status $status")
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                handler.post {
                    if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                    if (!g.discoverServices()) close("discover failed")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                handler.post {
                    val svc = g.getService(SERVICE)
                    val rxc = svc?.getCharacteristic(RX)
                    val txc = svc?.getCharacteristic(TX)
                    if (status != BluetoothGatt.GATT_SUCCESS || rxc == null || txc == null) { close("no mura service"); return@post }
                    rx = rxc
                    g.setCharacteristicNotification(txc, true)
                    val d = txc.getDescriptor(CCCD) ?: run { close("no cccd"); return@post }
                    val ok = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                    if (!ok) close("cccd write failed")
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                handler.post {
                    if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD) up() else close("subscribe failed $status")
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                handler.post { onSent(status == BluetoothGatt.GATT_SUCCESS) }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid != TX) return
                val copy = value.copyOf()
                handler.post { onPacket(copy) }
            }

            @Deprecated("Android 12 and older")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33 || characteristic.uuid != TX) return
                @Suppress("DEPRECATION")
                val copy = characteristic.value?.copyOf() ?: return
                handler.post { onPacket(copy) }
            }
        }
    }

    /** The peer connected to my GATT server and subscribed. */
    inner class ServerLink(private val device: BluetoothDevice, var mtu: Int) : BleLink(device.address) {
        override val id = "s:${device.address}"
        override val outgoing = false
        override val packetSize: Int get() = (mtu - 3).coerceIn(20, 512)

        init { peerNode = addressNode[device.address]; advertNode = addressNode[device.address] }

        override fun writePacket(p: ByteArray): Boolean {
            val s = server ?: return false
            val c = txChar ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, c, false, p) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = p
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, c, false)
            }
        }

        override fun disconnect() {
            try { server?.cancelConnection(device) } catch (_: Exception) {}
        }
    }
}
