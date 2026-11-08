package com.mura.mesh.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mura.mesh.core.Payload
import com.mura.mesh.data.FileRow
import com.mura.mesh.data.MessageRow
import com.mura.mesh.data.MsgKind
import com.mura.mesh.media.SealedDataSource
import com.mura.mesh.media.SecureFileProvider
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.mesh.formatDuration
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.SecureWindow
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen viewer for photos, videos and PDFs. Everything is decrypted in memory only. */
@Composable
fun ViewerScreen(msgId: String) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<Pair<MessageRow?, FileRow?>?>(null, msgId) {
        value = withContext(Dispatchers.IO) { val m = engine.db.message(msgId); m to m?.fileId?.let { engine.db.file(it) } }
    }
    val m = loaded?.first
    val f = loaded?.second
    val once = m != null && m.viewOnce && !m.outgoing
    var askDelete by remember { mutableStateOf(false) }

    if (once) {
        SecureWindow()
        DisposableEffect(msgId) { onDispose { engine.consumeViewOnce(msgId) } }
    }

    // "Save to phone": the user explicitly exports a readable copy to a place they choose.
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = f ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openOutputStream(uri)?.use { out -> out.write(SecureMedia.readBytes(file, 512L * 1024 * 1024)) }; true } catch (e: Exception) { false }
            }
            ctx.toast(if (ok) "Saved a readable copy" else "Couldn't save")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Mura.Amber)
            m == null -> Text("This item is gone", color = Mura.Muted, modifier = Modifier.align(Alignment.Center))
            m.kind == MsgKind.LOCATION -> Column(Modifier.align(Alignment.Center).padding(24.dp).background(Mura.Surface).padding(16.dp)) {
                Text("Location (view once)", style = MaterialTheme.typography.titleMedium)
                val lat = m.meta.optDouble("lat", Double.NaN); val lon = m.meta.optDouble("lon", Double.NaN)
                if (!lat.isNaN()) Text("%.5f, %.5f".format(java.util.Locale.US, lat, lon), fontFamily = Mura.Mono, color = Mura.Soft)
            }
            f == null || !File(f.path).exists() -> Text(if (m.opened) "Already opened" else "This file is no longer on your phone",
                color = Mura.Muted, modifier = Modifier.align(Alignment.Center))
            f.media == Payload.MEDIA_VIDEO -> SealedVideo(f)
            f.media == Payload.MEDIA_IMAGE -> ZoomableImage(f)
            f.media == Payload.MEDIA_VOICE -> SealedVoice(m.id, f, once)
            else -> PdfPages(f)
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBtn(MIcons.Close, "Close", tint = Color.White, bg = Color(0x66000000)) { nav.back() }
            Text(if (once) "View once · erased when you close" else (f?.name ?: ""), color = Color.White,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
            if (!once && f != null) {
                if (f.media == Payload.MEDIA_FILE) IconBtn(MIcons.Share, "Open in another app", tint = Color.White, bg = Color(0x66000000)) { com.mura.mesh.ui.Exposure.confirmOpenOutside { openExternally(ctx, f) } }
                IconBtn(MIcons.Download, "Save a readable copy", tint = Color.White, bg = Color(0x66000000)) { com.mura.mesh.ui.Exposure.confirmSave { saver.launch(f.name) } }
            }
            if (m != null && !once) IconBtn(MIcons.Trash, "Delete", tint = Color.White, bg = Color(0x66000000)) { askDelete = true }
        }
    }
    if (askDelete && m != null) {
        val forAll = engine.canDeleteForEveryone(m)
        AlertDialog(
            onDismissRequest = { askDelete = false }, containerColor = Mura.Surface,
            title = { Text("Delete this?") },
            text = {
                Text(if (forAll) "Delete for everyone removes it from their phone too (if they're on Mura and reachable). The encrypted file is erased from this phone."
                     else "Erases the encrypted file from this phone.", style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { askDelete = false; engine.deleteForMe(m.id); nav.back() }) { Text("Delete for me", color = Mura.Soft) }
                    if (forAll) TextButton(onClick = { askDelete = false; engine.deleteForEveryone(m.id); nav.back() }) { Text("Delete for everyone", color = Mura.Red) }
                }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

@Composable
private fun ZoomableImage(f: FileRow) {
    val bmp by produceState<ImageBitmap?>(null, f.fileId) {
        value = withContext(Dispatchers.IO) { SecureMedia.decodeImage(f, 2560)?.asImageBitmap() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().transformable(state), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator(color = Mura.Amber)
        else Image(b, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        })
    }
}

/** Plays a sealed video by decrypting on the fly — no plaintext copy on storage. */
@Composable
private fun SealedVideo(f: FileRow) {
    val player = remember { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(f.durationMs) }
    var ratio by remember { mutableFloatStateOf(if (f.width > 0 && f.height > 0) f.width.toFloat() / f.height else 16f / 9f) }

    DisposableEffect(f.fileId) {
        try {
            player.setDataSource(SealedDataSource(SecureMedia.reader(f)))
            player.setOnPreparedListener { mp ->
                prepared = true; duration = mp.duration
                if (mp.videoWidth > 0 && mp.videoHeight > 0) ratio = mp.videoWidth.toFloat() / mp.videoHeight
                mp.start(); playing = true
            }
            player.setOnCompletionListener { playing = false }
            player.prepareAsync()
        } catch (_: Exception) {}
        onDispose { try { player.stop() } catch (_: Exception) {}; player.release() }
    }
    LaunchedEffect(playing) { while (playing) { try { position = player.currentPosition } catch (_: Exception) {}; delay(200) } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { try { player.setDisplay(h) } catch (_: Exception) {} }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { try { player.setDisplay(null) } catch (_: Exception) {} }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.3f, 3f))
                .clickable { if (prepared) { if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true } } },
        )
        if (!prepared) CircularProgressIndicator(color = Mura.Amber)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { v -> if (prepared) { position = (v * duration).toInt(); player.seekTo(position) } },
                colors = SliderDefaults.colors(thumbColor = Mura.Amber, activeTrackColor = Mura.Amber, inactiveTrackColor = Color(0x66FFFFFF)),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Mura.Amber).clickable {
                    if (!prepared) return@clickable
                    if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true }
                }, contentAlignment = Alignment.Center) {
                    Icon(if (playing) MIcons.Pause else MIcons.Play, if (playing) "Pause" else "Play", tint = Mura.OnAmber, modifier = Modifier.size(20.dp))
                }
                Text("  ${formatDuration(position)} / ${formatDuration(duration)}", color = Color.White, fontFamily = Mura.Mono)
            }
        }
    }
}

/** In-app PDF reader: pages are rendered from a decrypt-on-read descriptor, never from a plaintext file. */
@Composable
private fun PdfPages(f: FileRow) {
    val ctx = LocalContext.current
    val isPdf = f.mime == "application/pdf" || f.name.endsWith(".pdf", true)
    if (!isPdf) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(MIcons.File, null, tint = Mura.Amber, modifier = Modifier.size(48.dp))
            Text(f.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Tap the share icon to open it in another app.", color = Mura.Muted)
        }
        return
    }
    val doc by produceState<PdfDoc?>(null, f.fileId) {
        value = withContext(Dispatchers.IO) {
            try { val pfd = SecureFileProvider.open(ctx, f); PdfDoc(pfd, PdfRenderer(pfd)) } catch (e: Exception) { null }
        }
    }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    val d = doc
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mura.Amber) }
        return
    }
    val width = remember { ctx.resources.displayMetrics.widthPixels.coerceAtMost(1440) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(d.pageCount) { i ->
            // Pages are rendered only when scrolled into view, so long PDFs stay light on memory.
            val page by produceState<ImageBitmap?>(null, i) { value = withContext(Dispatchers.IO) { d.render(i, width) } }
            val p = page
            if (p == null) Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Mura.Amber, strokeWidth = 2.dp)
            } else Image(p, "Page ${i + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        }
    }
}

private class PdfDoc(private val pfd: android.os.ParcelFileDescriptor, private val renderer: PdfRenderer) {
    val pageCount: Int = renderer.pageCount
    private var closed = false

    @Synchronized
    fun render(i: Int, width: Int): ImageBitmap? {
        if (closed) return null
        return try {
            renderer.openPage(i).use { page ->
                val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            }
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try { renderer.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}


/** Plays a voice note (or a one-time voice note) straight from encrypted storage. One-time: erased when you close. */
@Composable
private fun SealedVoice(id: String, f: FileRow, once: Boolean) {
    val playing by com.mura.mesh.media.VoicePlayer.state.collectAsState()
    DisposableEffect(id) {
        com.mura.mesh.media.VoicePlayer.toggle(id, f)        // start right away
        onDispose { com.mura.mesh.media.VoicePlayer.stop() }
    }
    val isThis = playing.id == id
    val dur = if (isThis && playing.durationMs > 0) playing.durationMs else f.durationMs
    val pos = if (isThis) playing.positionMs else 0
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Mura.Amber)
            .clickable { com.mura.mesh.media.VoicePlayer.toggle(id, f) }, contentAlignment = Alignment.Center) {
            Icon(if (isThis && playing.playing) MIcons.Pause else MIcons.Play, if (isThis && playing.playing) "Pause" else "Play",
                tint = Mura.OnAmber, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        androidx.compose.material3.LinearProgressIndicator(progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp), color = Mura.Amber, trackColor = Mura.Line)
        Spacer(Modifier.height(8.dp))
        Text("${com.mura.mesh.mesh.formatDuration(pos)} / ${com.mura.mesh.mesh.formatDuration(dur)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
        if (once) {
            Spacer(Modifier.height(12.dp))
            Text("One-time voice note · erased when you close", color = Mura.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
