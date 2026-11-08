package com.mura.mesh.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.call.CallStatus
import com.mura.mesh.mesh.MeshPermissions
import com.mura.mesh.mesh.formatDuration
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.initials
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.delay

@Composable
fun CallScreen() {
    val engine = requireEngine()
    val ctx = LocalContext.current
    val call by engine.calls.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.status) { while (call.status == CallStatus.ACTIVE) { now = System.currentTimeMillis(); delay(500) } }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) engine.calls.answer() else ctx.toast("Microphone permission is needed to answer")
    }

    val breathe = rememberInfiniteTransition(label = "halo")
    val halo by breathe.animateFloat(1f, 1.14f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "h")
    val (bg, fg) = Mura.avatarColors(call.peerHex)

    Column(
        Modifier.fillMaxSize().background(Mura.Ink).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.background(Mura.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MIcons.Lock, null, tint = Mura.Mint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.size(6.dp))
            Text("End-to-end encrypted · fresh keys for this call", style = MaterialTheme.typography.labelSmall, color = Mura.Soft)
        }
        Spacer(Modifier.height(64.dp))
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            // Aurora ring: flows around the caller and swells with the voice during the call.
            com.mura.mesh.ui.AuroraRing(call.status == CallStatus.ACTIVE, { engine.calls.voiceLevel }, Modifier.size(220.dp))
            Box(Modifier.size(200.dp).graphicsLayer { scaleX = halo; scaleY = halo; alpha = 2f - halo * 1.5f }
                .border(2.dp, Mura.Amber, RoundedCornerShape(64.dp)))
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(48.dp)).background(bg), contentAlignment = Alignment.Center) {
                Text(initials(call.name), fontFamily = Mura.Display, fontSize = 44.sp, color = fg)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(call.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            when (call.status) {
                CallStatus.CALLING -> "Ringing…"
                CallStatus.RINGING -> "Incoming call"
                CallStatus.ACTIVE -> formatDuration((now - call.startedAt).toInt())
                CallStatus.IDLE -> call.note
            },
            fontFamily = Mura.Mono, fontSize = 16.sp, color = Mura.Amber,
        )
        if (call.status == CallStatus.ACTIVE && call.quality.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(call.quality, style = MaterialTheme.typography.labelSmall, color = Mura.Soft)
        }
        if (call.recording || call.peerRecording) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.background(Color(0x33E5484D), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(MIcons.Record, null, tint = Mura.Red, modifier = Modifier.size(12.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (call.recording) "You're recording this call" else "${call.name} is recording this call",
                    style = MaterialTheme.typography.labelMedium, color = Mura.Text)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Calls work when you're in Bluetooth range or both online through Tor. Sound quality adjusts by itself to the connection — " +
            "HD when it's strong, fewer and larger packets when it's weak.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.background(Mura.Surface, RoundedCornerShape(16.dp)).padding(14.dp))
        Spacer(Modifier.weight(1f))

        if (call.status == CallStatus.RINGING) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                RoundButton(MIcons.Hangup, "Decline", Mura.Red) { engine.calls.decline() }
                RoundButton(MIcons.Phone, "Answer", Mura.Mint) {
                    if (MeshPermissions.granted(ctx, Manifest.permission.RECORD_AUDIO)) engine.calls.answer()
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton(if (call.muted) MIcons.MicOff else MIcons.Mic, if (call.muted) "Unmute" else "Mute", call.muted) { engine.calls.toggleMute() }
                RoundButton(MIcons.Hangup, "End call", Mura.Red) { engine.calls.hangup() }
                SmallButton(MIcons.Speaker, "Speaker", call.speaker) { engine.calls.toggleSpeaker() }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                SmallButton(MIcons.Record, if (call.recording) "Stop recording" else "Record", call.recording) {
                    engine.calls.toggleRecording()?.let { ctx.toast(it) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp).clip(RoundedCornerShape(28.dp)).background(color).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mura.Soft)
    }
}

@Composable
private fun SmallButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(62.dp).clip(RoundedCornerShape(22.dp)).background(if (active) Mura.Amber else Mura.Surface)
            .border(1.dp, Mura.Line, RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (active) Mura.OnAmber else Mura.Text, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mura.Soft)
    }
}
