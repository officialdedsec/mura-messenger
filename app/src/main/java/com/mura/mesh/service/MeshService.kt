package com.mura.mesh.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mura.mesh.MuraApp
import com.mura.mesh.mesh.MeshPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the mesh alive in the background, like a messenger's always-on connection.
 * Android requires a visible notification for this; the user can minimise it in system
 * settings. Type "connectedDevice" has no daily time limit and may start at boot.
 */
class MeshService : Service() {

    companion object {
        private const val ACTION_MIC_ON = "mic_on"
        private const val ACTION_MIC_OFF = "mic_off"

        /** True while the service is alive (the keep-alive job checks it). */
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            if (MuraApp.engine == null || !MeshPermissions.bluetoothGranted(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses background starts in some states; we retry when the app opens.
                Log.w("MuraService", "start refused", e)
            }
        }

        /** While on a call the service also needs microphone access in the background. */
        fun setMicrophone(ctx: Context, on: Boolean) {
            try {
                ContextCompat.startForegroundService(ctx,
                    Intent(ctx, MeshService::class.java).setAction(if (on) ACTION_MIC_ON else ACTION_MIC_OFF))
            } catch (e: Exception) { Log.w("MuraService", "mic type change refused", e) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var micOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val engine = MuraApp.engine
        if (engine == null) { stopSelf(); return }
        running = true
        KeepAlive.schedule(this)          // brings the service back if the system kills it
        engine.start()
        scope.launch {
            combine(engine.connected, engine.nearby, engine.state) { c, n, s -> Triple(c.size, n.size, s) }.collect { (c, n, s) ->
                val text = when (s) {
                    com.mura.mesh.mesh.BleTransport.State.OFF -> "Bluetooth is off — messages wait until it's on"
                    com.mura.mesh.mesh.BleTransport.State.NO_PERMISSION -> "Open Mura to allow Bluetooth"
                    com.mura.mesh.mesh.BleTransport.State.UNSUPPORTED -> "This phone has no Bluetooth LE"
                    else -> when {
                        c > 0 -> "Linked to $c phone${if (c == 1) "" else "s"} · ${engine.db.relayCount()} carried for others"
                        n > 0 -> "$n phone${if (n == 1) "" else "s"} nearby"
                        else -> "Listening for nearby phones"
                    }
                }
                engine.notifier.updateService(text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MIC_ON -> { micOn = true; goForeground() }
            ACTION_MIC_OFF -> { micOn = false; goForeground() }
            else -> goForeground()
        }
        MuraApp.engine?.start()
        return START_STICKY
    }

    private fun goForeground() {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            // Android 10-11: BLE scanning in the background is gated by location access.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && MeshPermissions.granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (micOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && MeshPermissions.granted(this, Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        val notifier = MuraApp.engine?.notifier ?: Notifier(this)
        try {
            ServiceCompat.startForeground(this, Notifier.ID_SERVICE, notifier.serviceNotification("Starting mesh…"), type)
        } catch (e: Exception) {
            Log.w("MuraService", "startForeground failed with type $type", e)
            if (micOn) {
                micOn = false
                try { ServiceCompat.startForeground(this, Notifier.ID_SERVICE, notifier.serviceNotification("Mesh on"),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) } catch (_: Exception) { stopSelf() }
            } else stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents must not stop the mesh.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
}
