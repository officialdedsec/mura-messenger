package com.mura.mesh.media

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Current location from the phone's own GPS / network providers (no Google services,
 * no internet needed — GPS works fully offline).
 */
object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 25_000): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
        }
        if (providers.isEmpty()) return null

        // A fix from the last 60 seconds is good enough and instant.
        val fresh = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 60_000 }
            .minByOrNull { it.accuracy }
        if (fresh != null && fresh.accuracy < 50) return fresh

        val got = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                for (p in providers) {
                    try { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
            }
        }
        return got ?: fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
}
