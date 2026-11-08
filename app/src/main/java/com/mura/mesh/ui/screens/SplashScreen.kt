package com.mura.mesh.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.ui.theme.Mura
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bluetooth pulses ripple out across a small mesh while one relay path lights up,
 * node by node — the app's idea in two seconds.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val enter = remember { Animatable(0f) }
    val path = remember { Animatable(0f) }
    // Mesh splash: the nodes fly in and assemble into the Mura mark (instant with "Remove animations").
    val reduced = com.mura.mesh.ui.reducedMotion()
    val gather = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        launch { gather.animateTo(1f, tween(950, easing = FastOutSlowInEasing)) }
        launch { enter.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        launch { delay(350); path.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        delay(2100)
        onDone()
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val blink by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "blink")
    val bar by pulse.animateFloat(0.1f, 0.9f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar")

    Column(
        Modifier.fillMaxSize().background(Mura.Ink).statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("NO TOWERS · NO SERVERS · NO NUMBERS", fontFamily = Mura.Mono, fontSize = 11.sp, letterSpacing = 2.sp,
            color = Mura.Muted, modifier = Modifier.alpha(enter.value))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(280.dp).graphicsLayer { scaleX = 0.85f + 0.15f * enter.value; scaleY = scaleX; alpha = enter.value }) {
                val c = Offset(size.width / 2, size.height / 2)
                val maxR = size.minDimension * 0.48f
                // Expanding rings
                for (i in 0 until 3) {
                    val p = (t + i / 3f) % 1f
                    drawCircle(
                        color = if (i == 2) Mura.Blue else Mura.Amber,
                        radius = maxR * (0.3f + 0.7f * p),
                        center = c,
                        alpha = (1f - p) * 0.8f,
                        style = Stroke(width = 3f),
                    )
                }
                val k = gather.value
                val spread = size.minDimension * 0.55f
                fun at(x: Float, y: Float) = Offset(size.width * x, size.height * y)
                val a = com.mura.mesh.ui.assemble(at(0.24f, 0.30f), c, k, spread, 0.9f)
                val b = com.mura.mesh.ui.assemble(at(0.77f, 0.27f), c, k, spread, -0.7f)
                val d = com.mura.mesh.ui.assemble(at(0.82f, 0.67f), c, k, spread, 0.8f)
                val e = com.mura.mesh.ui.assemble(at(0.27f, 0.75f), c, k, spread, -0.9f)
                val mesh = listOf(c to a, c to b, c to d, c to e, a to b, b to d, e to d, a to e)
                for ((p1, p2) in mesh) drawLine(Mura.Line, p1, p2, strokeWidth = 4f, cap = StrokeCap.Round, alpha = k)
                // The relay path a -> center -> d lights up progressively
                val route = Path().apply { moveTo(a.x, a.y); lineTo(c.x, c.y); lineTo(d.x, d.y) }
                val len1 = (c - a).getDistance(); val len2 = (d - c).getDistance(); val total = len1 + len2
                val dash = total * path.value
                drawPath(route, Mura.Amber, style = Stroke(width = 6f, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.coerceAtLeast(0.01f), total * 2), 0f)))
                for ((pt, col, r) in listOf(Triple(a, Mura.Amber, 22f), Triple(b, Mura.Blue, 17f), Triple(d, Mura.Amber, 22f), Triple(e, Mura.Blue, 17f))) {
                    drawCircle(Mura.Surface, r, pt)
                    drawCircle(col, r, pt, alpha = blink, style = Stroke(width = 5f))
                }
                drawCircle(Mura.Amber, 68f * (0.4f + 0.6f * k), c)
                drawCircle(Mura.OnAmber, 14f * (0.4f + 0.6f * k), c)
            }
            Spacer(Modifier.height(24.dp))
            Text("mura", fontFamily = Mura.Display, fontWeight = FontWeight.Bold, fontSize = 48.sp, color = Mura.Text,
                letterSpacing = (-1).sp, modifier = Modifier.alpha(enter.value))
            Spacer(Modifier.height(10.dp))
            Text("Messages that travel phone to phone — even when the person isn't there yet.",
                color = Mura.Soft, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center,
                fontFamily = Mura.Sans, modifier = Modifier.width(270.dp).alpha(enter.value))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(200.dp).height(3.dp).clip(RoundedCornerShape(3.dp)).background(Mura.Surface2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(bar).clip(RoundedCornerShape(3.dp)).background(Mura.Amber))
            }
            Spacer(Modifier.height(12.dp))
            Text("Waking the mesh · scanning for peers", fontFamily = Mura.Mono, fontSize = 12.sp, color = Mura.Muted)
        }
    }
}
