package com.mura.mesh.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.min
import kotlin.math.sin

/*
 * Ember burn — Mura's delete animation.
 *
 * A ragged, glowing burn line sweeps diagonally across the bubble. Just ahead of it the bubble
 * scorches brown; on the line itself it glows white-hot → amber → red; behind it nothing is left.
 * Sparks rise from the line and ash flakes flutter down and fade.
 *
 * Timeline (seconds):  0 ─ burn line crosses ─ 0.95   sparks/ash live until 1.45
 * Everything is drawn in one pass per frame: no bitmaps, no allocations per particle.
 */

private const val BURN_S = 0.95f
private const val TOTAL_S = 1.45f

private val Spark0 = Color(0xFFFFF4D6)   // white-hot
private val Spark1 = Color(0xFFFFB547)   // Mura amber
private val Spark2 = Color(0xFFE5484D)   // ember red
private val Char = Color(0xFF2A1405)

private class Particle(
    val yFrac: Float,     // where on the burn line it starts (0 top … 1 bottom)
    val born: Float,      // seconds
    val vx: Float, val vy: Float,   // dp per second
    val size: Float,      // dp
    val life: Float,      // seconds
    val ash: Boolean,
    val phase: Float,
)

/** Seeded so a bubble always burns the same way (it looks deliberate, not random noise). */
private fun particlesFor(seed: Int, count: Int): List<Particle> {
    val r = java.util.Random(seed.toLong())
    return List(count) {
        val ash = r.nextFloat() < 0.38f
        Particle(
            yFrac = r.nextFloat(),
            born = r.nextFloat() * BURN_S * 0.95f,
            vx = if (ash) -6f + r.nextFloat() * 22f else -10f + r.nextFloat() * 36f,
            vy = if (ash) 14f + r.nextFloat() * 26f else -(40f + r.nextFloat() * 90f),
            size = if (ash) 1.6f + r.nextFloat() * 2.4f else 0.9f + r.nextFloat() * 1.8f,
            life = if (ash) 0.45f + r.nextFloat() * 0.45f else 0.3f + r.nextFloat() * 0.4f,
            ash = ash,
            phase = r.nextFloat() * 6.28f,
        )
    }
}

/**
 * Wraps [content]; when [active] turns true the burn plays once, then [onDone] is called
 * (the caller then removes the item and the list closes the gap smoothly).
 * The content stays in the same place in the tree whether burning or not, so images and
 * other remembered state don't reload when the burn starts.
 */
@Composable
fun EmberBurn(active: Boolean, seed: Int, onDone: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val particles = remember(seed) { particlesFor(seed, 46) }
    val wobble = remember(seed) { java.util.Random(seed * 31L + 7).let { r -> FloatArray(6) { r.nextFloat() * 6.28f } } }

    LaunchedEffect(active) {
        if (!active) { t.snapTo(0f); return@LaunchedEffect }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        t.animateTo(TOTAL_S, tween((TOTAL_S * 1000).toInt(), easing = LinearEasing))
        done()
    }

    Box(
        modifier.drawWithContent {
            drawContent()
            // Sparks and ash are drawn outside the bubble's layer so they can fly past its edges.
            if (active) drawParticles(t.value, particles, wobble, density)
        }
    ) {
        Box(
            Modifier
                .graphicsLayer { compositingStrategy = if (active) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                .drawWithContent {
                    drawContent()
                    if (!active) return@drawWithContent
                    val p = burnProgress(t.value)
                    if (p <= 0f) return@drawWithContent
                    val front = frontLine(p, wobble, t.value)
                    // 1. scorch just ahead of the line (only where the bubble has pixels)
                    drawPath(front.path, Char.copy(alpha = 0.85f), style = Stroke(width = 26f * density, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.SrcAtop)
                    drawPath(front.path, Color(0xFF7A2E0A).copy(alpha = 0.9f), style = Stroke(width = 14f * density, join = StrokeJoin.Round), blendMode = BlendMode.SrcAtop)
                    // 2. the glowing rim
                    drawPath(front.path, Spark2, style = Stroke(width = 8f * density, join = StrokeJoin.Round), blendMode = BlendMode.SrcAtop)
                    drawPath(front.path, Spark1, style = Stroke(width = 4.5f * density, join = StrokeJoin.Round), blendMode = BlendMode.SrcAtop)
                    drawPath(front.path, Spark0, style = Stroke(width = 1.6f * density, join = StrokeJoin.Round), blendMode = BlendMode.SrcAtop)
                    // 3. everything behind the line is gone
                    drawPath(front.burned, Color.Black, blendMode = BlendMode.Clear)
                }
        ) { content() }
    }
}

private fun burnProgress(t: Float): Float {
    val x = (t / BURN_S).coerceIn(0f, 1f)
    // ease-in-out: slow ignition, fast middle, lingering finish
    return (x * x * (3f - 2f * x))
}

private class Front(val path: Path, val burned: Path)

/** The ragged diagonal line at progress [p], from top to bottom, plus the burned area behind it. */
private fun DrawScope.frontLine(p: Float, w: FloatArray, t: Float): Front {
    val width = size.width
    val height = size.height
    val slant = height * 0.9f
    val amp = min(18f, width * 0.06f) * density
    val travel = width + slant + amp * 2
    val base = -slant - amp + travel * p
    val steps = 22
    val line = Path()
    val burned = Path()
    burned.moveTo(-amp * 3, -1f)
    for (i in 0..steps) {
        val yf = i / steps.toFloat()
        val y = yf * height
        val ragged = sin(yf * 9f + w[0] + t * 7f) * 0.5f + sin(yf * 23f + w[1]) * 0.3f + sin(yf * 41f + w[2] - t * 11f) * 0.2f
        val x = base + slant * yf + ragged * amp
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        burned.lineTo(x, y)
    }
    burned.lineTo(-amp * 3, height + 1f)
    burned.close()
    return Front(line, burned)
}

private fun frontXAt(p: Float, yFrac: Float, w: FloatArray, widthPx: Float, heightPx: Float, density: Float): Float {
    val slant = heightPx * 0.9f
    val amp = min(18f, widthPx * 0.06f) * density
    val travel = widthPx + slant + amp * 2
    return -slant - amp + travel * p + slant * yFrac + sin(yFrac * 9f + w[0]) * 0.5f * amp
}

private fun DrawScope.drawParticles(t: Float, ps: List<Particle>, w: FloatArray, density: Float) {
    val width = size.width
    val height = size.height
    // A soft halo along the line makes the rim look luminous against the dark chat.
    val p = burnProgress(t)
    if (p in 0.01f..0.99f) {
        for (k in 0..4) {
            val yf = (k + 0.5f) / 5f
            val x = frontXAt(p, yf, w, width, height, density)
            if (x < 0 || x > width) continue
            val c = Offset(x, yf * height)
            drawCircle(Brush.radialGradient(listOf(Spark1.copy(alpha = 0.22f), Color.Transparent), c, 26f * density), 26f * density, c)
        }
    }
    for (pt in ps) {
        val age = t - pt.born
        if (age < 0f || age > pt.life) continue
        val bornP = burnProgress(pt.born)
        val x0 = frontXAt(bornP, pt.yFrac, w, width, height, density)
        if (x0 < -4f || x0 > width + 4f) continue
        val life = age / pt.life
        val flutter = if (pt.ash) sin(age * 9f + pt.phase) * 5f * density else 0f
        val x = x0 + pt.vx * age * density + flutter
        val y = pt.yFrac * height + pt.vy * age * density + (if (pt.ash) 0f else 30f * age * age * density)
        val r = pt.size * density * (if (pt.ash) 1f - life * 0.3f else 1f - life * 0.6f)
        if (pt.ash) {
            val a = (1f - life) * 0.75f
            drawCircle(Color(0xFF5C5650).copy(alpha = a), r, Offset(x, y))
        } else {
            val col = when {
                life < 0.3f -> lerp(Spark0, Spark1, life / 0.3f)
                else -> lerp(Spark1, Spark2, (life - 0.3f) / 0.7f)
            }
            val a = (1f - life).coerceIn(0f, 1f)
            drawCircle(col.copy(alpha = a * 0.35f), r * 2.6f, Offset(x, y))
            drawCircle(col.copy(alpha = a), r, Offset(x, y))
        }
    }
}

private fun lerp(a: Color, b: Color, f: Float): Color {
    val x = f.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * x, a.green + (b.green - a.green) * x, a.blue + (b.blue - a.blue) * x, a.alpha + (b.alpha - a.alpha) * x)
}

