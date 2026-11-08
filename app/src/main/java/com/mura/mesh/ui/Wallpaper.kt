package com.mura.mesh.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mura.mesh.data.Db
import com.mura.mesh.ui.theme.Mura
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Chat wallpapers: a few patterns drawn in the theme's own colours (so they suit light, dark and
 * black themes and every accent), or your own photo. One for all chats, and optionally one per chat.
 * Photos are stored encrypted in Mura's database, like everything else.
 */
object Wallpapers {
    val KINDS = listOf("none" to "Plain", "dots" to "Dots", "topo" to "Contours", "aurora" to "Aurora", "photo" to "Photo")
    const val DEFAULT = "default"          // per-chat: use the global wallpaper

    fun kindKey(chat: String?) = if (chat == null) "wp" else "wp:$chat"
    fun imageKey(chat: String?) = if (chat == null) "wp_img" else "wp_img:$chat"
    fun dimKey(chat: String?) = if (chat == null) "wp_dim" else "wp_dim:$chat"

    data class Spec(val kind: String, val image: ByteArray?, val dim: Float)

    /** The wallpaper to show in [chat] (its own, or the global one). */
    fun resolve(db: Db, chat: String?): Spec {
        if (chat != null) {
            val k = db.getSetting(kindKey(chat), DEFAULT)
            if (k != DEFAULT) return Spec(k, if (k == "photo") db.getSecret(imageKey(chat)) else null, dim(db, chat))
        }
        val k = db.getSetting(kindKey(null), "dots")
        return Spec(k, if (k == "photo") db.getSecret(imageKey(null)) else null, dim(db, null))
    }

    private fun dim(db: Db, chat: String?) = (db.getSetting(dimKey(chat), "40").toIntOrNull() ?: 40).coerceIn(0, 85) / 100f

    fun set(db: Db, chat: String?, kind: String) = db.setSetting(kindKey(chat), kind)

    /** Saves a picked photo, scaled down to at most 1440 px and re-encoded (which also drops its location and other metadata). */
    fun savePhoto(ctx: android.content.Context, db: Db, chat: String?, uri: Uri) {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val s = max(info.size.width, info.size.height)
                if (s > 1440) d.setTargetSize(info.size.width * 1440 / s, info.size.height * 1440 / s)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        db.setSecret(imageKey(chat), out.toByteArray())
        db.setSetting(kindKey(chat), "photo")
    }
}

/** Draws the chat wallpaper behind the content. */
@Composable
fun Modifier.chatWallpaper(chat: String?, override: Wallpapers.Spec? = null): Modifier {
    val engine = MuraAppEngine() ?: return this
    val stored by rememberQuery<Wallpapers.Spec?>(null, chat) { Wallpapers.resolve(engine.db, chat) }
    val spec = override ?: stored ?: return this
    val img by rememberQuery<ImageBitmap?>(null, spec.image?.contentHashCode()) {
        spec.image?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
    }
    val ink = Mura.Ink; val line = Mura.Line; val accent = Mura.Amber; val blue = Mura.Blue; val dark = Mura.isDark
    return this.drawBehind {
        drawRect(ink)
        when (spec.kind) {
            "dots" -> dots(line)
            "topo" -> contours(line)
            "aurora" -> aurora(accent, blue, dark)
            "photo" -> img?.let { photo(it, ink, spec.dim) }
        }
    }
}

private fun MuraAppEngine() = com.mura.mesh.MuraApp.engine

private fun DrawScope.dots(c: androidx.compose.ui.graphics.Color) {
    val step = 22.dp2px(this)
    val r = 1.6f * density
    var row = 0
    var y = step / 2
    while (y < size.height) {
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width) { drawCircle(c, r, Offset(x, y)); x += step }
        y += step * 0.87f; row++
    }
}

private fun DrawScope.contours(c: androidx.compose.ui.graphics.Color) {
    val gap = 26.dp2px(this)
    val stroke = Stroke(1.2f * density)
    var i = 0
    var y = -gap
    while (y < size.height + gap) {
        val p = Path()
        val amp = gap * (0.35f + 0.25f * sin(i * 0.9f))
        val phase = i * 0.7f
        var x = 0f
        p.moveTo(0f, y)
        while (x <= size.width) {
            p.lineTo(x, y + amp * sin((x / size.width * 2 * PI + phase).toFloat()) + amp * 0.4f * sin((x / size.width * 5 * PI + phase * 2).toFloat()))
            x += 8f
        }
        drawPath(p, c.copy(alpha = 0.8f), style = stroke)
        y += gap; i++
    }
}

private fun DrawScope.aurora(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color, dark: Boolean) {
    val alpha = if (dark) 0.22f else 0.16f
    val r = max(size.width, size.height) * 0.75f
    drawRect(Brush.radialGradient(listOf(a.copy(alpha = alpha), a.copy(alpha = 0f)), Offset(size.width * 0.15f, size.height * 0.2f), r))
    drawRect(Brush.radialGradient(listOf(b.copy(alpha = alpha), b.copy(alpha = 0f)), Offset(size.width * 0.9f, size.height * 0.85f), r))
}

private fun DrawScope.photo(img: ImageBitmap, ink: androidx.compose.ui.graphics.Color, dim: Float) {
    // Centre-crop to fill.
    val scale = max(size.width / img.width, size.height / img.height)
    val sw = (size.width / scale).toInt().coerceAtMost(img.width)
    val sh = (size.height / scale).toInt().coerceAtMost(img.height)
    drawImage(img, IntOffset((img.width - sw) / 2, (img.height - sh) / 2), IntSize(sw, sh),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    drawRect(ink.copy(alpha = dim), size = Size(size.width, size.height))
}

private fun Int.dp2px(d: DrawScope) = this * d.density
