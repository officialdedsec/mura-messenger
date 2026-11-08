package com.mura.mesh.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mura.mesh.MuraApp
import java.util.concurrent.TimeUnit

/**
 * Brings Mura back after the system (or a phone maker's battery manager) has killed it.
 *
 * Mura has no server and can't use Google's push service, so it keeps its own connection open in a
 * foreground service. Android's job scheduler (WorkManager) still runs our check every 15 minutes and
 * the moment the internet comes back — even when Mura's process was killed — and restarts the service.
 * (A *Force stop* from Settings is the one thing no app can recover from: Android blocks every wake-up
 * until the user opens the app again. WhatsApp is affected the same way.)
 */
class KeepAliveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val engine = MuraApp.engine ?: return Result.success()      // not set up yet
        MeshService.start(applicationContext)                       // no-op if it's already running
        engine.start()                                              // Bluetooth + Tor (idempotent)
        engine.online.poke()                                        // check for mail right away
        // If Android refused to restart the service from the background (battery optimisation still on),
        // stay awake inside this job for a couple of minutes so pending messages can still sync.
        if (!MeshService.running) {
            val until = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < until && !MeshService.running) kotlinx.coroutines.delay(2_000)   // cancelled if Android stops the job
        }
        return Result.success()
    }
}

object KeepAlive {
    private const val PERIODIC = "mura-keepalive"
    private const val NET_BACK = "mura-network-back"

    /** Every 15 minutes (Android's minimum), make sure the service is running. */
    fun schedule(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (e: Exception) { Log.w("MuraKeepAlive", "schedule", e) }
    }

    /** The network dropped: when it comes back, wake Mura up (even if it was killed meanwhile) and sync. */
    fun whenNetworkReturns(ctx: Context) {
        try {
            val req = OneTimeWorkRequestBuilder<KeepAliveWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NET_BACK, ExistingWorkPolicy.KEEP, req)
        } catch (e: Exception) { Log.w("MuraKeepAlive", "network", e) }
    }

    /**
     * Opens the phone maker's "auto-start" / background-launch screen, where Mura must be allowed on Xiaomi,
     * Oppo, Realme, OnePlus, Vivo, Huawei/Honor, Samsung and Asus phones. Falls back to Mura's app settings.
     */
    fun openAutoStartSettings(ctx: Context) {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",          // Xiaomi / Redmi / POCO
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",    // Oppo / Realme (ColorOS)
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",        // ColorOS 12+
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",     // OnePlus
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",   // Vivo
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", // Huawei / Honor
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",                 // Samsung
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",                 // Asus
        )
        for ((pkg, cls) in candidates) {
            try {
                ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { /* not this brand */ }
        }
        try {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Android's own "Unrestricted battery" request for Mura. */
    fun requestUnrestrictedBattery(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}
