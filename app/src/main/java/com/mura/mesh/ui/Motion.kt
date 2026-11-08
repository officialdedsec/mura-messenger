package com.mura.mesh.ui

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mura.mesh.data.MsgStatus
import com.mura.mesh.ui.theme.Mura
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Mura motion — small, purely visual touches (2.7.9). None of them changes what a screen does.
 *
 * Built to stay smooth on every phone:
 *  - everything is drawn in the draw phase (Canvas / drawBehind / drawWithCache), so an animation frame never
 *    recomposes or re-measures the screen; brushes, paths and text are built once per size, not per frame;
 *  - one-shot effects are short (≤ 0.75 s) and stop by themselves; looping ones only run while the thing they show
 *    is on screen (typing, recording, connecting, a call) and stop when it leaves composition;
 *  - no blur, no shadows, no bitmaps — just a few circles, arcs and gradients;
 *  - "Remove animations" in Android's accessibility settings (or animator scale 0) turns every effect off;
 *    on low-RAM phones the extra glow layers are skipped.
 */

object MotionPolicy {
    @Volatile private var checkedAt = 0L
    @Volatile private var reduced = false
    @Volatile private var lowRam: Boolean? = null

    /** Android's "Remove animations" / animator duration scale 0. Re-read at most every few seconds. */
    fun reduced(ctx: Context): Boolean {
        val now = SystemClock.uptimeMillis()
        if (checkedAt == 0L || now - checkedAt > 5_000) {
            reduced = try {
                android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            } catch (e: Exception) { false }
            checkedAt = now
        }
        return reduced
    }

    /** Low-RAM phones (Android Go etc.) get the plain version of glows. */
    fun lite(ctx: Context): Boolean = lowRam ?: (try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    } catch (e: Exception) { false }).also { lowRam = it }
}

@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { MotionPolicy.reduced(ctx) }
}

@Composable
private fun liteMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { MotionPolicy.lite(ctx) }
}

private val SHINE = Color(0xFFFFF4DC)
private val VIOLET = Color(0xFFB58CFF)

private fun easeInOut(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
private fun easeOutCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }

// =====================================================================================
// 2 · Seal & send — a lock-shimmer sweeps the bubble you just sent, then it settles with a soft bounce.
// =====================================================================================

private const val SEAL_MS = 720

/** Put on a message bubble (before its clip). [play] must be decided once per message (e.g. "sent in the last 2 s"). */
@Composable
fun sealSendEffect(play: Boolean, fromEnd: Boolean): Modifier {
    if (!play || reducedMotion()) return Modifier
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(SEAL_MS, easing = LinearEasing)) }
    return Modifier
        .graphicsLayer {
            val p = t.value
            val running = p < 1f
            // Offscreen only while the shimmer runs, so it lands only on the bubble's own pixels.
            compositingStrategy = if (running) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            val s = if (running) 1f - 0.07f * exp(-5.5f * p) * cos(11f * p) else 1f
            scaleX = s; scaleY = s
            transformOrigin = if (fromEnd) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
        }
        .drawWithCache {
            val band = size.width * 0.5f + 1f
            val brush = Brush.linearGradient(
                listOf(Color.Transparent, SHINE.copy(alpha = 0.35f), Color.White.copy(alpha = 0.75f), SHINE.copy(alpha = 0.35f), Color.Transparent),
                start = Offset(0f, 0f), end = Offset(band, size.height * 0.3f),
            )
            val bandSize = Size(band, size.height)
            onDrawWithContent {
                drawContent()
                val p = t.value
                if (p >= 0.68f) return@onDrawWithContent
                val x = -band + (size.width + band) * easeInOut(p / 0.68f)
                translate(left = x) { drawRect(brush, topLeft = Offset.Zero, size = bandSize, blendMode = BlendMode.SrcAtop) }
            }
        }
}

// =====================================================================================
// 3 · Hop trail — dots hop along the mesh while a message waits or is being carried; ticks glow when it lands.
// =====================================================================================

/** Three dots; one hops across them. Shown only while [status] is WAITING or CARRIED. */
@Composable
fun HopTrail(status: Int, color: Color, modifier: Modifier = Modifier) {
    if (status != MsgStatus.WAITING && status != MsgStatus.CARRIED) return
    val reduced = reducedMotion()
    val phase = if (reduced) null else rememberInfiniteTransition(label = "hop")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(if (status == MsgStatus.CARRIED) 900 else 1600, easing = LinearEasing)), label = "hp")
    Canvas(modifier.size(18.dp, 10.dp)) {
        val y0 = size.height * 0.72f
        val gap = size.width / 3f
        val r = 1.3.dp.toPx()
        for (i in 0..2) drawCircle(color, r, Offset(gap * (i + 0.5f), y0), alpha = 0.35f)
        val p = phase?.value ?: 0.5f
        val pos = p * 2f
        val seg = if (pos >= 1f) 1 else 0
        val f = pos - seg
        val x = gap * (seg + 0.5f) + gap * f
        val y = y0 - sin(f * PI.toFloat()) * size.height * 0.55f
        drawCircle(color, r * 1.5f, Offset(x, y))
    }
}

/** Wraps the delivery ticks: a short glow when a message turns delivered or read while you watch. */
@Composable
fun StatusGlow(status: Int, glow: Color, content: @Composable () -> Unit) {
    var last by remember { mutableIntStateOf(status) }
    val t = remember { Animatable(1f) }
    val reduced = reducedMotion()
    LaunchedEffect(status) {
        val was = last
        last = status
        val landed = was < MsgStatus.DELIVERED && (status == MsgStatus.DELIVERED || status == MsgStatus.READ)
        val read = was == MsgStatus.DELIVERED && status == MsgStatus.READ
        if (!reduced && (landed || read)) { t.snapTo(0f); t.animateTo(1f, tween(700, easing = LinearEasing)) }
    }
    Box(
        Modifier.drawBehind {
            val p = t.value
            if (p >= 1f) return@drawBehind
            val d = size.minDimension
            drawCircle(glow, radius = d * (0.55f + 0.5f * p), center = center, alpha = 0.40f * (1f - p))
            drawCircle(glow, radius = d * (0.5f + 1.3f * p), center = center, alpha = 0.8f * (1f - p), style = Stroke(1.5.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

// =====================================================================================
// 4 · Cipher typing — "typing" followed by three morphing cipher glyphs.
// =====================================================================================

private const val TYPE_GLYPHS = "ΔΣΞΨΩλπ#%&*<>=?0123456789"

@Composable
fun CipherTyping(label: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = color, maxLines = 1)
        CipherGlyphs(color, style)
    }
}

@Composable
private fun CipherGlyphs(color: Color, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    val mono = remember(style, color) { style.copy(color = color, fontFamily = FontFamily.Monospace) }
    val glyphs = remember(mono, measurer) { TYPE_GLYPHS.map { measurer.measure(it.toString(), mono) } }
    val dot = remember(mono, measurer) { measurer.measure(".", mono) }
    val cellW = remember(glyphs) { glyphs.maxOf { it.size.width } }
    val cellH = remember(glyphs) { glyphs.maxOf { it.size.height } }
    val density = LocalDensity.current
    val reduced = reducedMotion()
    val phase = if (reduced) null else rememberInfiniteTransition(label = "cipher")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "cp")
    val w = with(density) { (cellW * 3).toDp() }
    val h = with(density) { cellH.toDp() }
    Canvas(Modifier.padding(start = 3.dp).size(w, h)) {
        val p = phase?.value
        for (i in 0 until 3) {
            if (p == null) {                                   // reduced motion: plain dots
                drawText(dot, topLeft = Offset(i * cellW + (cellW - dot.size.width) / 2f, 0f))
                continue
            }
            val frame = (p * 18f).toInt()
            val g = glyphs[(frame * 7 + i * 11 + frame * i * 3) % glyphs.size]
            val a = 0.45f + 0.55f * (0.5f + 0.5f * sin(p * 2f * PI.toFloat() * 2f - i * 0.9f))
            drawText(g, topLeft = Offset(i * cellW + (cellW - g.size.width) / 2f, 0f), alpha = a)
        }
    }
}

// =====================================================================================
// 5 · Onion layers — Tor connecting as three rotating rings that close as it bootstraps; green when online.
// =====================================================================================

/** "Connecting to Tor… 45% · …" → 45. "publishing your private address" → 95. Unknown → 10. */
fun torPercent(note: String): Int {
    Regex("(\\d{1,3})%").find(note)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.coerceIn(0, 100) }
    return if (note.contains("publishing", ignoreCase = true) || note.contains("local port", ignoreCase = true)) 95 else 10
}

@Composable
fun OnionRings(connecting: Boolean, online: Boolean, percent: Int, color: Color, size: Dp = 18.dp) {
    val reduced = reducedMotion()
    val target = when { online -> 1f; connecting -> 0.22f + 0.72f * (percent.coerceIn(0, 100) / 100f); else -> 1f }
    val close by animateFloatAsState(target, tween(if (reduced) 0 else 600), label = "onion")
    val spin = if (connecting && !reduced) rememberInfiniteTransition(label = "onionSpin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "os") else null
    Spacer(
        Modifier.size(size).drawWithCache {
            val sw = this.size.minDimension * 0.085f
            val stroke = Stroke(sw, cap = StrokeCap.Round)
            onDrawBehind {
                val s = spin?.value ?: 0f
                val sweep = 360f * close
                for (i in 0 until 3) {
                    val r = this.size.minDimension / 2f * (1f - i * 0.3f) - sw
                    if (r <= 0f) continue
                    val start = when (i) { 0 -> s; 1 -> -s * 1.5f; else -> s * 2f } - 90f
                    drawArc(color, start, sweep, false, topLeft = Offset(center.x - r, center.y - r), size = Size(2f * r, 2f * r),
                        alpha = 1f - i * 0.22f, style = stroke)
                }
                drawCircle(color, radius = sw * 1.1f, center = center)
            }
        }
    )
}

// =====================================================================================
// 6 · Sonar discovery — helpers for the Mesh radar's pings (drawn inside MeshScreen's own Canvas).
// =====================================================================================

/** 0..1 progress of a ping that started at [startedAt] (uptime ms), or -1 when it's over. */
fun pingProgress(startedAt: Long, now: Long, durationMs: Long = 1400L): Float {
    val age = now - startedAt
    return if (age < 0 || age >= durationMs) -1f else age.toFloat() / durationMs
}

// =====================================================================================
// 7 · Voice orb — a breathing orb behind the record button that swells with your voice; a glow follows playback.
// =====================================================================================

@Composable
fun voiceOrb(active: Boolean, level: () -> Float, color: Color): Modifier {
    if (!active) return Modifier
    val reduced = reducedMotion()
    val lite = liteMotion()
    val breath = if (reduced) null else rememberInfiniteTransition(label = "orb")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ob")
    return Modifier.drawWithCache {
        val ring = Stroke(1.5.dp.toPx())
        onDrawBehind {
            val b = breath?.value ?: 0.5f
            val lv = level().coerceIn(0f, 1f)
            val base = size.minDimension / 2f
            if (!lite) drawCircle(color, radius = base * (1.22f + 0.10f * b + 0.55f * lv), alpha = 0.13f + 0.12f * lv)
            drawCircle(color, radius = base * (1.06f + 0.06f * b + 0.25f * lv), alpha = 0.26f)
            if (breath != null) drawCircle(color, radius = base * (1.15f + 0.55f * b), alpha = 0.45f * (1f - b), style = ring)
        }
    }
}

/** Behind a voice note's waveform while it plays: a soft glow and a bright playhead at [progress]. */
@Composable
fun voiceGlow(active: Boolean, progress: () -> Float, color: Color): Modifier {
    if (!active) return Modifier
    val reduced = reducedMotion()
    val breath = if (reduced) null else rememberInfiniteTransition(label = "vglow")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "vg")
    return Modifier.drawWithCache {
        val rad = size.height * 1.1f
        val glow = Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)), center = Offset.Zero, radius = rad)
        val lineW = 2.dp.toPx()
        onDrawBehind {
            val b = breath?.value ?: 0.5f
            val x = size.width * progress().coerceIn(0f, 1f)
            translate(x, size.height / 2f) {
                scale(0.8f + 0.35f * b, pivot = Offset.Zero) { drawCircle(glow, radius = rad, center = Offset.Zero) }
            }
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = lineW, alpha = 0.55f + 0.45f * b, cap = StrokeCap.Round)
        }
    }
}

// =====================================================================================
// 8 · Aurora call ring — a flowing colour ring around the caller that swells with the voice level.
// =====================================================================================

@Composable
fun AuroraRing(active: Boolean, level: () -> Float, modifier: Modifier = Modifier) {
    val reduced = reducedMotion()
    val lite = liteMotion()
    val spin = if (reduced) null else rememberInfiniteTransition(label = "aurora")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "ar")
    val smooth = remember { FloatArray(1) }
    val amber = Mura.Amber; val mint = Mura.Mint; val blue = Mura.Blue
    Spacer(
        modifier.drawWithCache {
            val brush = Brush.sweepGradient(listOf(amber, mint, blue, VIOLET, amber))   // centred on the ring
            onDrawBehind {
                val target = if (active) level().coerceIn(0f, 1f) else 0f
                smooth[0] += (target - smooth[0]) * 0.2f
                val lv = smooth[0]
                val w = (3f + 9f * lv).dp.toPx()
                val r = size.minDimension / 2f - 12.dp.toPx()
                rotate(spin?.value ?: 0f) {
                    if (!lite) drawCircle(brush, radius = r, alpha = 0.18f + 0.22f * lv, style = Stroke(w * 2.6f))
                    drawCircle(brush, radius = r, alpha = 0.9f, style = Stroke(w))
                }
            }
        }
    )
}

// =====================================================================================
// 9 · Reaction burst — the reaction pops in with a ring of amber sparks.
// =====================================================================================

/** Put on the reaction chip (before its clip). Plays once when [freshKey] is new and [fresh] is true. */
@Composable
fun reactionBurst(fresh: Boolean, freshKey: Long): Modifier {
    if (reducedMotion()) return Modifier
    val t = remember { Animatable(1f) }
    LaunchedEffect(freshKey) { if (fresh) { t.snapTo(0f); t.animateTo(1f, tween(620, easing = LinearEasing)) } }
    val amber = Mura.Amber
    return Modifier
        .graphicsLayer {
            val p = t.value
            val s = if (p < 1f) 1f + 0.32f * sin(p * PI.toFloat()) * (1f - p * 0.5f) else 1f
            scaleX = s; scaleY = s
        }
        .drawWithContent {
            drawContent()
            val p = t.value
            if (p >= 1f) return@drawWithContent
            val e = easeOutCubic(p)
            val c = Offset(size.height / 2f + 6.dp.toPx(), size.height / 2f)    // the first emoji
            val d = (6f + 22f * e).dp.toPx()
            val r = (1.8f * (1f - p) + 0.4f).dp.toPx()
            for (i in 0 until 8) {
                val a = i / 8f * 2f * PI.toFloat() + 0.3f
                drawCircle(amber, radius = r, center = Offset(c.x + cos(a) * d, c.y + sin(a) * d), alpha = 1f - p)
            }
            drawCircle(amber, radius = (4f + 20f * e).dp.toPx(), center = c, alpha = 0.6f * (1f - p), style = Stroke(1.5.dp.toPx()))
        }
}

// =====================================================================================
// 10 · Timer ring — a disappearing message's border drains like a clock until it burns.
// =====================================================================================

/** Put on the bubble after its clip/background. Ticks rarely (≈240 steps over the whole timer), drawn only. */
@Composable
fun timerRing(expireAt: Long, created: Long, shape: Shape, color: Color): Modifier {
    if (expireAt <= 0L) return Modifier
    val total = (expireAt - created).coerceAtLeast(1_000L)
    fun frac(): Float {
        val left = expireAt - System.currentTimeMillis()
        return if (left <= 0) 0f else (left.toFloat() / max(total, left).toFloat()).coerceIn(0f, 1f)
    }
    val f = remember(expireAt) { mutableFloatStateOf(frac()) }
    LaunchedEffect(expireAt) {
        val step = (total / 240L).coerceIn(100L, 30_000L)
        while (true) {
            f.floatValue = frac()
            if (f.floatValue <= 0f) break
            delay(step)
        }
    }
    return Modifier.drawWithCache {
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val pm = PathMeasure().apply { setPath(outline, false) }
        val len = pm.length
        val seg = Path()
        val sw = 3.dp.toPx()                // half of it shows inside the bubble's clip
        val track = Stroke(sw)
        val stroke = Stroke(sw, cap = StrokeCap.Round)
        onDrawWithContent {
            drawContent()
            val v = f.floatValue
            drawPath(outline, color, alpha = 0.14f, style = track)
            if (v > 0f && len > 0f) {
                seg.reset()
                pm.getSegment(0f, len * v, seg, true)
                drawPath(seg, color, alpha = 0.9f, style = stroke)
            }
        }
    }
}

// =====================================================================================
// 11 · Vault unlock — after the app lock or the calculator, the lock rings rotate open and the app appears.
// =====================================================================================

@Composable
fun VaultUnlock(gated: Boolean) {
    var wasGated by remember { mutableStateOf(gated) }
    val t = remember { Animatable(1f) }
    val reduced = reducedMotion()
    val haptics = LocalHapticFeedback.current
    val pending = wasGated && !gated && !reduced          // first frame after unlocking, before the effect starts
    LaunchedEffect(gated) {
        if (wasGated && !gated && !reduced) {
            t.snapTo(0f)
            wasGated = false
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            t.animateTo(1f, tween(760, easing = LinearEasing))
        } else {
            if (gated) t.snapTo(1f)                         // locked again mid-animation: drop the overlay
            wasGated = gated
        }
    }
    val running by remember { derivedStateOf { t.value < 1f } }
    if (!running && !pending) return
    val ink = Mura.Ink; val amber = Mura.Amber; val mint = Mura.Mint
    Canvas(Modifier.fillMaxSize()) {
        val p = if (running) t.value else 0f
        val bgA = 1f - ((p - 0.3f) / 0.7f).coerceIn(0f, 1f).let { it * it }
        drawRect(ink, alpha = bgA)
        val e = easeOutCubic(p)
        val c = center
        val u = 1.dp.toPx() * (1f + 0.7f * ((p - 0.45f) / 0.55f).coerceIn(0f, 1f))
        val fade = 1f - ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (fade <= 0f) return@Canvas
        val col = if (p > 0.4f) mint else amber
        // Outer ring: two halves rotating apart.
        val r1 = 64f * u
        val rot = 150f * e
        drawArc(col, -90f + rot, 150f, false, Offset(c.x - r1, c.y - r1), Size(2 * r1, 2 * r1), alpha = fade, style = Stroke(4f * u, cap = StrokeCap.Round))
        drawArc(col, 90f - rot, 150f, false, Offset(c.x - r1, c.y - r1), Size(2 * r1, 2 * r1), alpha = fade, style = Stroke(4f * u, cap = StrokeCap.Round))
        // Inner ring: six teeth turning the other way.
        val r2 = 46f * u
        for (i in 0 until 6) drawArc(amber, i * 60f - 200f * e, 34f, false, Offset(c.x - r2, c.y - r2), Size(2 * r2, 2 * r2),
            alpha = fade * 0.8f, style = Stroke(3f * u, cap = StrokeCap.Round))
        // Padlock: the shackle lifts and swings open.
        val lift = 10f * u * e
        drawRoundRect(amber, topLeft = Offset(c.x - 15f * u, c.y - 4f * u), size = Size(30f * u, 24f * u), cornerRadius = CornerRadius(6f * u), alpha = fade)
        rotate(-35f * e, pivot = Offset(c.x + 9f * u, c.y - 4f * u - lift)) {
            drawArc(amber, 180f, 180f, false, topLeft = Offset(c.x - 9f * u, c.y - 15f * u - lift), size = Size(18f * u, 18f * u),
                alpha = fade, style = Stroke(4f * u))
        }
        drawCircle(ink, radius = 3.5f * u, center = Offset(c.x, c.y + 7f * u), alpha = fade)
    }
}

// =====================================================================================
// 12 · Mesh splash — helper for SplashScreen: where a node is while the mesh assembles.
// =====================================================================================

/** A node flies in from outside (spiralling a little) to [target] around [c] as [k] goes 0 → 1. */
fun assemble(target: Offset, c: Offset, k: Float, spread: Float, twist: Float): Offset {
    if (k >= 1f) return target
    val dx = target.x - c.x; val dy = target.y - c.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val out = (1f - k) * spread
    val a = (1f - k) * twist
    val ox = target.x + dx / len * out; val oy = target.y + dy / len * out
    // rotate the start point around the centre by a small angle so the paths curve in
    val rx = ox - c.x; val ry = oy - c.y
    val ca = cos(a); val sa = sin(a)
    return Offset(c.x + rx * ca - ry * sa, c.y + rx * sa + ry * ca)
}
