package com.mura.mesh.ui.screens

import com.mura.mesh.ui.LocalInTab
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import com.mura.mesh.data.ContactRow
import com.mura.mesh.mesh.Settings
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.SettingRow
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.initials
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MeshScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val allConnected by engine.connected.collectAsState()
    val connected = allConnected.filter { !it.remote }          // the radar shows Bluetooth only
    val viaTor = allConnected.filter { it.remote }
    val online by engine.online.state.collectAsState()
    val nearby by engine.nearby.collectAsState()
    val contacts by rememberQuery(emptyList<ContactRow>()) { engine.db.contacts() }
    val relayCount by rememberQuery(0) { engine.db.relayCount() }
    val relayBytes by rememberQuery(0L) { engine.db.relayBytesUsed() }
    val relayOn by rememberQuery(true) { engine.db.getBool(Settings.RELAY, true) }
    val budgetMb by rememberQuery(200) { engine.db.getSetting(Settings.RELAY_MB, "200").toIntOrNull() ?: 200 }
    var slider by remember(budgetMb) { mutableFloatStateOf(budgetMb.toFloat()) }

    val sweep = rememberInfiniteTransition(label = "radar")
    val angle by sweep.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val measurer = rememberTextMeasurer()
    // Sonar: when a phone is first seen, it pings (a ripple from its blip and one from you). Drawn only.
    val firstSeen = remember { HashMap<String, Long>() }
    val sonar = !com.mura.mesh.ui.reducedMotion()

    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar("Your mesh", "Phones in Bluetooth reach right now", onBack = if (LocalInTab.current) null else ({ nav.back() }))

        Canvas(Modifier.fillMaxWidth().padding(horizontal = 24.dp).aspectRatio(1f)) {
            val c = center
            val r = size.minDimension / 2
            drawCircle(Mura.Surface, r, c)
            val now = android.os.SystemClock.uptimeMillis()
            fun sweepGlow(rad: Double): Float {
                // The bright edge of the sweep is at `angle`; it fades over the 60° behind it.
                val deg = ((Math.toDegrees(rad) % 360.0) + 360.0) % 360.0
                val behind = ((angle - deg) % 360.0 + 360.0) % 360.0
                return if (behind < 60.0) (1.0 - behind / 60.0).toFloat() else 0f
            }
            fun ping(key: String, pt: Offset, color: androidx.compose.ui.graphics.Color, base: Float) {
                if (!sonar) return
                val p = com.mura.mesh.ui.pingProgress(firstSeen.getOrPut(key) { now }, now)
                if (p < 0f) return
                drawCircle(color, base + 70f * p, pt, alpha = 0.7f * (1f - p), style = Stroke(3f))
                drawCircle(color, base + 30f * p, pt, alpha = 0.35f * (1f - p), style = Stroke(2f))
            }
            for (f in listOf(1f, 0.68f, 0.36f)) drawCircle(Mura.Line, r * f, c, style = Stroke(2f))
            rotate(angle, c) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Mura.Amber.copy(alpha = 0f), Mura.Amber.copy(alpha = 0.18f)), c),
                    startAngle = -60f, sweepAngle = 60f, useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                )
            }
            // Linked phones on the inner ring, others seen nearby on the outer rings (closer = stronger signal).
            val linkedPubs = connected.map { it.pubHex }
            connected.forEachIndexed { i, p ->
                val a = Math.toRadians((i * 360.0 / connected.size.coerceAtLeast(1)) - 90)
                val pt = Offset(c.x + (r * 0.52f) * cos(a).toFloat(), c.y + (r * 0.52f) * sin(a).toFloat())
                drawLine(Mura.Amber, c, pt, strokeWidth = 4f)
                ping("L" + p.pubHex, pt, Mura.Amber, 46f)
                val g = if (sonar) sweepGlow(a) else 0f
                if (g > 0f) drawCircle(Mura.Amber, 46f + 14f * g, pt, alpha = 0.25f * g)
                drawCircle(Mura.AmberDeep, 46f, pt)
                drawCircle(Mura.Amber, 46f, pt, style = Stroke(4f))
                val nm = contacts.firstOrNull { it.pub == p.pubHex }?.display ?: p.name.ifBlank { "?" }
                val t = measurer.measure(initials(nm), TextStyle(color = Mura.Amber, fontSize = 13.sp, fontFamily = Mura.Sans))
                drawText(t, topLeft = Offset(pt.x - t.size.width / 2, pt.y - t.size.height / 2))
            }
            // Phones advertise a rotating random id, so match linked phones by the id they advertised.
            val linkedNodes = engine.transport.linkedAdverts
            val others = nearby.values.filter { it.nodeHex !in linkedNodes }
            others.forEachIndexed { i, n ->
                val strength = ((n.rssi + 100).coerceIn(0, 60)) / 60f
                val dist = 0.95f - 0.4f * strength
                val a = Math.toRadians((n.nodeHex.hashCode() % 360).toDouble() + i * 7)
                val pt = Offset(c.x + r * dist * cos(a).toFloat(), c.y + r * dist * sin(a).toFloat())
                ping("N" + n.nodeHex, pt, Mura.Blue, 16f)
                val g = if (sonar) sweepGlow(a) else 0f
                if (g > 0f) drawCircle(Mura.Blue, 16f + 12f * g, pt, alpha = 0.35f * g)
                drawCircle(Mura.Surface2, 16f, pt)
                drawCircle(Mura.Blue, 16f, pt, style = Stroke(3f))
            }
            // A new phone was found in the last moment: a ripple goes out from you too.
            if (sonar) {
                val latest = firstSeen.values.maxOrNull() ?: 0L
                val p = com.mura.mesh.ui.pingProgress(latest, now, 1600L)
                if (p >= 0f) drawCircle(Mura.Amber, 52f + (r - 52f) * p, c, alpha = 0.5f * (1f - p), style = Stroke(3f))
            }
            if (firstSeen.size > 512) firstSeen.clear()                 // advertised ids rotate; keep the map small
            drawCircle(Mura.Amber, 52f, c)
            val you = measurer.measure("You", TextStyle(color = Mura.OnAmber, fontSize = 13.sp, fontFamily = Mura.Sans))
            drawText(you, topLeft = Offset(c.x - you.size.width / 2, c.y - you.size.height / 2))
        }

        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("${nearby.size}", "in reach", Mura.Amber, Modifier.weight(1f))
            Stat("${connected.size}", "linked now", Mura.Mint, Modifier.weight(1f))
            Stat("${viaTor.size}", "via Tor", Mura.Mint, Modifier.weight(1f))
            Stat("$relayCount", "carrying for others", Mura.Blue, Modifier.weight(1f))
        }

        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        com.mura.mesh.ui.OnionRings(connecting = online.status == com.mura.mesh.mesh.OnlineTransport.Status.STARTING,
            online = online.status == com.mura.mesh.mesh.OnlineTransport.Status.ON, percent = com.mura.mesh.ui.torPercent(online.note),
            color = when (online.status) { com.mura.mesh.mesh.OnlineTransport.Status.ON -> Mura.Mint; com.mura.mesh.mesh.OnlineTransport.Status.STARTING -> Mura.Amber
                com.mura.mesh.mesh.OnlineTransport.Status.ERROR -> Mura.Red; else -> Mura.Muted }, size = 14.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            when (online.status) {
                com.mura.mesh.mesh.OnlineTransport.Status.ON -> "Internet: online through Tor" +
                    if (viaTor.isNotEmpty()) " · " + viaTor.joinToString { p -> contacts.firstOrNull { it.pub == p.pubHex }?.display ?: p.name } else ""
                com.mura.mesh.mesh.OnlineTransport.Status.OFF -> "Internet: off · Settings › Internet (Tor)"
                else -> "Internet: " + online.note
            },
            style = MaterialTheme.typography.bodySmall, color = Mura.Soft,
        )
        }
        SettingRow(MIcons.Mesh, "Relay for others", "Carry sealed messages between other people. You can't read them.", relayOn) {
            engine.db.setBool(Settings.RELAY, it)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Space lent to the mesh: ${slider.toInt()} MB", style = MaterialTheme.typography.titleSmall)
            Text("Using ${"%.1f".format(java.util.Locale.US, relayBytes / 1048576.0)} MB. Oldest items expire after 7 days.",
                style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            Slider(
                value = slider, onValueChange = { slider = it }, valueRange = 20f..2000f,
                onValueChangeFinished = { engine.db.setSetting(Settings.RELAY_MB, slider.toInt().toString()) },
                colors = SliderDefaults.colors(thumbColor = Mura.Amber, activeTrackColor = Mura.Amber, inactiveTrackColor = Mura.Line),
            )
        }
        Text(
            "How delivery works: when you send to someone out of range, your phone hands sealed pieces to any Mura phone it meets. " +
                "Those phones pass them on until one meets your contact. Big files travel in 32 KB pieces that can arrive by different phones, " +
                "in any order, and resume after every interruption. Nothing is ever readable along the way. " +
                "With internet on, your phone also delivers straight to contacts who are online, through Tor — never through a server, " +
                "and it never carries strangers' mail over the internet.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted,
            modifier = Modifier.padding(20.dp).background(Mura.Surface, RoundedCornerShape(16.dp)).padding(16.dp),
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Stat(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.background(Mura.Surface, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
    }
}
