package com.mura.mesh.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Which runtime permissions each Android version needs for the mesh. */
object MeshPermissions {

    /** Needed for the Bluetooth mesh to run at all. */
    fun bluetooth(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        // Android 9-11: Bluetooth scanning is tied to location permission.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun notifications(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun bluetoothGranted(ctx: Context): Boolean = bluetooth().all { granted(ctx, it) }

    fun notificationsGranted(ctx: Context): Boolean = notifications().all { granted(ctx, it) }

    /**
     * Background location is no longer requested (Play policy, and privacy): on Android 9-11 the mesh's
     * foreground service (type "location") keeps scanning with the normal location permission.
     */
    @Suppress("UNUSED_PARAMETER")
    fun needsBackgroundLocation(ctx: Context): Boolean = false

    /** On Android 9-11 the location switch must be on for Bluetooth scans to return results. */
    fun locationServicesNeeded(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
