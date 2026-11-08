package com.mura.mesh.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mura.mesh.MuraApp
import com.mura.mesh.mesh.MeshPermissions
import com.mura.mesh.service.MeshService
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.PrimaryButton
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast

@Composable
fun PermissionsScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val ready = remember(tick) { MeshPermissions.bluetoothGranted(ctx) }

    Column(
        Modifier.fillMaxSize().background(Mura.Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("STEP 2 OF 2", style = MaterialTheme.typography.labelSmall, color = Mura.Amber)
        Text("Let Mura reach\nnearby phones.", style = MaterialTheme.typography.headlineMedium)
        Text("Mura only talks over Bluetooth. These switches let it find phones around you, deliver messages in the background, and ring you for calls.",
            style = MaterialTheme.typography.bodyMedium, color = Mura.Soft)
        PermissionChecklist(tick) { tick++ }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Continue", Modifier.fillMaxWidth(), enabled = ready) {
            MeshService.start(ctx)
            MuraApp.engine?.start()
            nav.reset(Screen.Chats)
        }
        if (!ready) Text("Bluetooth access is required for Mura to work.", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
    }
}

/** Live checklist of everything Mura needs, with one-tap fixes. Also shown in Settings. */
@SuppressLint("MissingPermission")
@Composable
fun PermissionChecklist(tick: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val permsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }

    val bt = remember(tick) { MeshPermissions.bluetoothGranted(ctx) }
    val btOn = remember(tick) { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.isEnabled == true }
    val notif = remember(tick) { MeshPermissions.notificationsGranted(ctx) }
    val locServices = remember(tick) { !MeshPermissions.locationServicesNeeded(ctx) }
    val bgLoc = remember(tick) { !MeshPermissions.needsBackgroundLocation(ctx) }
    val battery = remember(tick) { MeshPermissions.ignoringBatteryOptimizations(ctx) }
    val mic = remember(tick) { MeshPermissions.granted(ctx, Manifest.permission.RECORD_AUDIO) }
    val fullScreen = remember(tick) {
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Step(MIcons.Bluetooth, "Nearby devices", if (Build.VERSION.SDK_INT >= 31) "Find and connect to Mura phones around you" else "Android 9–11 needs location access to scan for Bluetooth phones. Mura never records where you are.", bt, required = true) {
            permsLauncher.launch(MeshPermissions.bluetooth())
        }
        Step(MIcons.Bluetooth, "Bluetooth on", "Messages wait safely while it's off", btOn) {
            if (!bt) ctx.toast("Allow nearby devices first")
            else try { activityLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) {
                activityLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT < 31) {
            Step(MIcons.Pin, "Location switch on", "Required by Android 9–11 for Bluetooth scanning", locServices) {
                activityLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Step(MIcons.Bell, "Notifications", "Pop-up alerts for messages and calls", notif) { permsLauncher.launch(MeshPermissions.notifications()) }
        }
        Step(MIcons.Mic, "Microphone", "Voice messages and calls", mic) { permsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        Step(MIcons.Battery, "Run in background", "Stops Android from pausing the mesh to save battery", battery) {
            try {
                activityLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            } catch (e: Exception) {
                activityLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            Step(MIcons.Phone, "Full-screen calls", "Show incoming calls over the lock screen", fullScreen) {
                try {
                    activityLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                } catch (e: Exception) { ctx.toast("Open Settings › Apps › Mura to allow full-screen notifications") }
            }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, body: String, done: Boolean, required: Boolean = false, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Mura.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, if (!done && required) Mura.Amber else Mura.Line, RoundedCornerShape(16.dp))
            .clickable(enabled = !done, role = Role.Button, onClick = onFix).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).background(if (done) Mura.Surface2 else Mura.AmberDeep, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(if (done) MIcons.Check else icon, null, tint = if (done) Mura.Mint else Mura.Amber, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title + if (required) " · required" else "", style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
        }
        Text(if (done) "Done" else "Allow", style = MaterialTheme.typography.labelLarge, color = if (done) Mura.Mint else Mura.Amber)
    }
}
