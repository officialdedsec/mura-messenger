package com.mura.mesh.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Cipher seal — how a view-once message leaves the chat once it has been opened.
 *
 *   0.00–0.70 s  the bubble's contents scramble into encrypted symbols, left to right, behind a glowing edge
 *   0.70–1.00 s  a lock stamps the bubble shut (haptic tick)
 *   1.00–1.45 s  the bubble folds: flat to a line, then to a point of light
 *   1.45–1.75 s  the point implodes with a pulse ring and a few sparks (haptic pulse)
 *
 * It works on whatever the bubble looks like: everything is drawn on top of the real message (only where the
 * bubble's own pixels are, so its shape is kept). Nothing is decrypted or shown — the bubble it seals is the
 * "tap to open" card, never the content. Ember burn stays for deleted and disappearing messages.
 */

private const val SCRAMBLE_END = 0.70f
private const val FOLD_START = 1.00f
private const val FOLD_END = 1.45f
private const val TOTAL_S = 1.75f

private val AMBER = Color(0xFFFFB547)
private val INK = Color(0xFF090B10)
private val HOT = Color(0xFFFFF0D2)
private const val GLYPHS = "#\$%&*+<>=?@ABCDEFKMNPRXZ0123456789ΔΣΞΨΩλπ∑◆▲●"

private fun easeIn(t: Float) = t * t * t
private fun easeOut(t: Float) = 1f - (1f - t).pow(3)
private fun easeOutBack(t: Float): Float { val c = 1.7f; return 1f + (c + 1f) * (t - 1f).pow(3) + c * (t - 1f).pow(2) }

@Composable
fun CipherSeal(active: Boolean, seed: Int, onDone: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val layer = rememberGraphicsLayer()
    // Where the bubble actually is inside this row (found once from its pixels); null = the whole row.
    var bubble by remember { mutableStateOf<Rect?>(null) }
    val rows = remember(seed) {
        val r = java.util.Random(seed * 131L + 17)
        List(8) { buildString { repeat(48) { append(GLYPHS[r.nextInt(GLYPHS.length)]) } } }
    }
    val measured = remember(rows, measurer) {
        rows.map { measurer.measure(it, TextStyle(color = AMBER, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) }
    }

    LaunchedEffect(active) {
        if (!active) { t.snapTo(0f); bubble = null; return@LaunchedEffect }
        withFrameNanos { }; withFrameNanos { }                       // let the layer record one frame
        bubble = try {
            val img = layer.toImageBitmap()
            withContext(Dispatchers.Default) { opaqueBounds(img.asAndroidBitmap()) }
        } catch (e: Exception) { null }
        launch {
            delay((SCRAMBLE_END * 1000).toLong()); haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(((FOLD_END - SCRAMBLE_END) * 1000).toLong()); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        t.animateTo(TOTAL_S, tween((TOTAL_S * 1000).toInt(), easing = LinearEasing))
        done()
    }

    Box(
        modifier.drawWithContent {
            drawContent()
            // The implosion is drawn outside the folding layer, so its ring can spread past the bubble.
            if (!active) return@drawWithContent
            val time = t.value
            if (time < FOLD_END) return@drawWithContent
            val b = bubble ?: Rect(Offset.Zero, size)
            val c = b.center
            val p = ((time - FOLD_END) / (TOTAL_S - FOLD_END)).coerceIn(0f, 1f)
            val core = (6f * (1f - p) + 1.5f) * density
            drawCircle(Brush.radialGradient(listOf(HOT.copy(alpha = 1f - p), AMBER.copy(alpha = 0f)), center = c, radius = core * 3f), radius = core * 3f, center = c)
            drawCircle(HOT.copy(alpha = 1f - p), radius = core, center = c)
            drawCircle(AMBER.copy(alpha = 0.9f * (1f - p)), radius = (8f + 90f * easeOut(p)) * density, center = c, style = Stroke(2f * density))
            val d = (10f + 70f * easeOut(p)) * density
            for (i in 0 until 10) {
                val a = i / 10f * 6.2832f
                drawCircle(AMBER.copy(alpha = 1f - p), radius = 1.4f * density, center = Offset(c.x + cos(a) * d, c.y + sin(a) * d))
            }
        }
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    compositingStrategy = if (active) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    if (!active) return@graphicsLayer
                    val time = t.value
                    if (time > FOLD_START) {
                        val b = bubble ?: Rect(Offset.Zero, size)
                        val p = ((time - FOLD_START) / (FOLD_END - FOLD_START)).coerceIn(0f, 1f)
                        scaleY = 1f - easeIn(min(1f, p * 1.6f)) * 0.97f
                        scaleX = 1f - easeIn(((p - 0.35f) / 0.65f).coerceIn(0f, 1f)) * 0.99f
                        if (size.width > 0f && size.height > 0f) transformOrigin = TransformOrigin(b.center.x / size.width, b.center.y / size.height)
                        alpha = if (time >= FOLD_END) 0f else 1f
                    }
                }
                .drawWithContent {
                    if (!active) { drawContent(); return@drawWithContent }
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                    val time = t.value
                    val b = bubble ?: Rect(Offset.Zero, size)
                    if (time < FOLD_START) {
                        // 1. Scramble: cover the contents with cipher text, only on the bubble's own pixels (SrcAtop).
                        val p = easeOut((time / SCRAMBLE_END).coerceIn(0f, 1f))
                        val frontier = b.left + b.width * p
                        clipRect(b.left, b.top, frontier, b.bottom) {
                            drawRect(INK.copy(alpha = 0.92f), blendMode = BlendMode.SrcAtop)
                            val frame = (time * 16f).toInt()
                            var y = b.top + 6f * density
                            var i = 0
                            while (y < b.bottom - 4f * density) {
                                drawText(measured[(i + frame) % measured.size], topLeft = Offset(b.left + 10f * density, y), blendMode = BlendMode.SrcAtop)
                                y += 16f * density; i++
                            }
                        }
                        if (p < 1f) {
                            val w = 14f * density
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, AMBER.copy(alpha = 0.75f), Color.Transparent), startX = frontier - w, endX = frontier + w),
                                topLeft = Offset(frontier - w, b.top), size = Size(w * 2f, b.height), blendMode = BlendMode.SrcAtop,
                            )
                        }
                        // 2. Lock stamp.
                        if (time > SCRAMBLE_END) {
                            val s = easeOutBack(((time - SCRAMBLE_END) / 0.25f).coerceIn(0f, 1f))
                            drawRect(INK.copy(alpha = 0.45f), blendMode = BlendMode.SrcAtop)
                            val c = b.center
                            val u = density * s
                            drawCircle(AMBER.copy(alpha = 0.25f * s), radius = 26f * u, center = c)
                            drawRoundRect(AMBER, topLeft = Offset(c.x - 15f * u, c.y - 4f * u), size = Size(30f * u, 24f * u), cornerRadius = CornerRadius(6f * u))
                            drawArc(AMBER, 180f, 180f, false, topLeft = Offset(c.x - 9f * u, c.y - 15f * u), size = Size(18f * u, 18f * u), style = Stroke(4f * u))
                            drawCircle(INK, radius = 3.5f * u, center = Offset(c.x, c.y + 7f * u))
                        }
                    } else {
                        // 3. Fold: the bubble heats from amber to white as it collapses.
                        val p = ((time - FOLD_START) / (FOLD_END - FOLD_START)).coerceIn(0f, 1f)
                        drawRect(Color(1f, 0.71f + 0.24f * p, 0.28f + 0.55f * p, 0.35f + 0.65f * p), blendMode = BlendMode.SrcAtop)
                    }
                }
        ) { content() }
    }
}

/** Bounding box of the visible (non-transparent) pixels: the bubble inside a full-width message row. */
private fun opaqueBounds(src: android.graphics.Bitmap): Rect? {
    val bmp = if (src.config == android.graphics.Bitmap.Config.HARDWARE) src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else src
    val w = bmp.width; val h = bmp.height
    if (w == 0 || h == 0) return null
    var l = w; var t = h; var r = -1; var b = -1
    val row = IntArray(w)
    var y = 0
    while (y < h) {
        bmp.getPixels(row, 0, w, 0, y, w, 1)
        var x = 0
        while (x < w) {
            if ((row[x] ushr 24) > 24) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
            x += 2
        }
        y += 2
    }
    if (r < l || b < t) return null
    return Rect(l.toFloat(), t.toFloat(), (r + 1).toFloat(), (b + 1).toFloat())
}
