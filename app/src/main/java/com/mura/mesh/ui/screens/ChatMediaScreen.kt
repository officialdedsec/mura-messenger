package com.mura.mesh.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.core.Payload
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.data.FileRow
import com.mura.mesh.data.FileState
import com.mura.mesh.data.MessageRow
import com.mura.mesh.data.MsgKind
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.media.VoicePlayer
import com.mura.mesh.mesh.formatDuration
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Media, links & docs for one chat — everything shared, gathered and sorted.
 * Opens from the name at the top of a chat (and from Group info). Everything is read from the encrypted
 * database and decrypted only in memory for display; nothing is copied out. View-once and deleted items are
 * never listed.
 */

private class SharedFile(val m: MessageRow, val f: FileRow) {
    val local: Boolean get() = (f.outgoing || f.state == FileState.COMPLETE) && f.state != FileState.GONE
}
private class SharedLink(val m: MessageRow, val url: String, val title: String, val image: ByteArray?)
private class Gathered(val media: List<SharedFile>, val docs: List<SharedFile>, val links: List<SharedLink>, val voice: List<SharedFile>)

private val URL_RE = Regex("""\bhttps?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

private fun gather(engine: com.mura.mesh.mesh.MeshEngine, chat: String): Gathered {
    val msgs = engine.db.messages(chat, 100_000).filter { !it.deleted && !it.viewOnce }
    val files = engine.db.filesWhere("chat=?", arrayOf(chat)).associateBy { it.fileId }
    val media = ArrayList<SharedFile>(); val docs = ArrayList<SharedFile>(); val voice = ArrayList<SharedFile>(); val links = ArrayList<SharedLink>()
    for (m in msgs) {
        if (m.kind == MsgKind.FILE) {
            val f = m.fileId?.let { files[it] } ?: continue
            if (f.state == FileState.GONE) continue
            when (f.media) {
                Payload.MEDIA_IMAGE, Payload.MEDIA_VIDEO -> media.add(SharedFile(m, f))
                Payload.MEDIA_VOICE -> voice.add(SharedFile(m, f))
                else -> docs.add(SharedFile(m, f))
            }
        } else if (m.kind == MsgKind.TEXT) {
            val pUrl = m.meta.optString("p_url", "")
            val found = URL_RE.findAll(m.body).map { it.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':') }.toMutableList()
            if (pUrl.isNotEmpty() && pUrl !in found) found.add(0, pUrl)
            for (u in found.distinct()) {
                val img = if (u == pUrl) m.meta.optString("p_img", "").takeIf { it.isNotEmpty() }
                    ?.let { try { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) } catch (e: Exception) { null } } else null
                links.add(SharedLink(m, u, if (u == pUrl) m.meta.optString("p_title", "") else "", img))
            }
        }
    }
    val byNewest = compareByDescending<SharedFile> { it.m.created }
    return Gathered(media.sortedWith(byNewest), docs.sortedWith(byNewest), links.sortedByDescending { it.m.created }, voice.sortedWith(byNewest))
}

/** Small in-memory cache so the grid doesn't decrypt the same picture again while scrolling. Cleared with the process. */
private object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun get(k: String): Bitmap? = cache.get(k)
    fun put(k: String, b: Bitmap) { cache.put(k, b) }
    /** Decrypted thumbnails don't outlive the screen. */
    fun clear() { cache.evictAll() }
}

@Composable
fun ChatMediaScreen(chat: String) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val isGroup = com.mura.mesh.data.ChatId.isGroup(chat)
    val data by rememberQuery<Gathered?>(null, chat) { gather(engine, chat) }
    val contact by rememberQuery<com.mura.mesh.data.ContactRow?>(null, chat) { if (isGroup) null else engine.db.contact(chat) }
    val groupName by rememberQuery("", chat) { if (isGroup) engine.db.group(com.mura.mesh.data.ChatId.groupIdOf(chat))?.name ?: "Group" else "" }
    val call = rememberCaller()
    var tab by remember { mutableIntStateOf(0) }
    DisposableEffect(chat) { onDispose { VoicePlayer.stop(); ThumbCache.clear() } }

    val title = if (isGroup) groupName else contact?.display ?: "Unknown"
    val d = data
    val counts = listOf(d?.media?.size ?: 0, d?.docs?.size ?: 0, d?.links?.size ?: 0, d?.voice?.size ?: 0)

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().background(Mura.Ink),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- hero
        item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(MIcons.Back, "Back") { nav.back() }
                    Spacer(Modifier.weight(1f))
                    Text("SHARED", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp), color = Mura.Muted, fontFamily = Mura.Mono)
                    Spacer(Modifier.width(12.dp))
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(28.dp))
                        .background(Brush.verticalGradient(listOf(Mura.AmberDeep, Mura.Surface)))
                        .border(1.dp, Mura.Line, RoundedCornerShape(28.dp)).padding(20.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(chat, title, 92.dp, photo = if (isGroup) null else contact?.avatar, group = isGroup)
                        Spacer(Modifier.height(12.dp))
                        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Mura.Display), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (contact?.verified == true) Pill(MIcons.Shield, "Verified", Mura.Mint)
                            Pill(MIcons.Lock, "End-to-end encrypted", Mura.Soft)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QuickAction(MIcons.Send, "Message", Modifier.weight(1f)) { nav.back() }
                            if (!isGroup) {
                                QuickAction(MIcons.Phone, "Call", Modifier.weight(1f)) { call(chat, title) }
                                QuickAction(MIcons.Shield, "Safety code", Modifier.weight(1f)) { safetyDialogFor = chat }
                            } else {
                                QuickAction(MIcons.Group, "Group info", Modifier.weight(1f)) {
                                    nav.go(Screen.GroupInfo(com.mura.mesh.data.ChatId.groupIdOf(chat)))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Counter tiles double as tabs.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tabs = listOf(MIcons.Image to "Media", MIcons.File to "Docs", MIcons.Link to "Links", MIcons.Mic to "Voice")
                    tabs.forEachIndexed { i, (icon, label) ->
                        CounterTile(icon, label, counts[i], selected = tab == i, modifier = Modifier.weight(1f)) { tab = i }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        if (d == null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                Text("Gathering…", color = Mura.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp))
            }
        } else when (tab) {
            0 -> {
                if (d.media.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "e0") { EmptyState(MIcons.Image, "No photos or videos yet") }
                var lastMonth = ""
                val groups = d.media.groupBy { monthOf(it.m.created) }
                for ((month, list) in groups) {
                    if (month != lastMonth) {
                        lastMonth = month
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h$month") { MonthHeader(month, list.size) }
                    }
                    items(list, key = { "m" + it.m.id }) { sf -> MediaTile(sf) {
                        when {
                            sf.local && File(sf.f.path).exists() -> nav.go(Screen.Viewer(sf.m.id))
                            sf.f.state == FileState.NEEDS_APPROVAL -> { engine.acceptFile(sf.f.fileId); ctx.toast("Downloading…") }
                            else -> ctx.toast("Still arriving")
                        }
                    } }
                }
            }
            1 -> {
                if (d.docs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "e1") { EmptyState(MIcons.File, "No documents yet") }
                items(d.docs, key = { "d" + it.m.id }, span = { GridItemSpan(maxLineSpan) }) { sf -> DocCard(sf) {
                    val isPdf = sf.f.mime == "application/pdf" || sf.f.name.endsWith(".pdf", ignoreCase = true)
                    when {
                        !sf.local || !File(sf.f.path).exists() ->
                            if (sf.f.state == FileState.NEEDS_APPROVAL) { engine.acceptFile(sf.f.fileId); ctx.toast("Downloading…") } else ctx.toast("Still arriving")
                        isPdf -> nav.go(Screen.Viewer(sf.m.id))
                        else -> com.mura.mesh.ui.Exposure.confirmOpenOutside { openExternally(ctx, sf.f) }
                    }
                } }
            }
            2 -> {
                if (d.links.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "e2") { EmptyState(MIcons.Link, "No links yet") }
                items(d.links, key = { "l" + it.m.id + it.url }, span = { GridItemSpan(maxLineSpan) }) { l -> LinkCard(l) {
                    com.mura.mesh.ui.Exposure.confirmLink {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (e: Exception) { ctx.toast("No browser on this phone") }
                    }
                } }
            }
            else -> {
                if (d.voice.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "e3") { EmptyState(MIcons.Mic, "No voice notes yet") }
                items(d.voice, key = { "v" + it.m.id }, span = { GridItemSpan(maxLineSpan) }) { sf -> VoiceCard(sf, if (sf.m.outgoing) "You" else if (isGroup) "Member" else title) }
            }
        }
    }
    safetyDialogFor?.let { SafetyCodeDialog(it) { safetyDialogFor = null } }
}

/** Which chat's safety code dialog is open (shared by the quick action). */
private var safetyDialogFor by androidx.compose.runtime.mutableStateOf<String?>(null)

@Composable
private fun SafetyCodeDialog(chat: String, onClose: () -> Unit) {
    val engine = requireEngine()
    val code = remember(chat) { com.mura.mesh.core.Crypto.safetyCode(engine.identity.publicKey, chat.hexToBytes()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = Mura.Surface,
        title = { Text("Safety code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Compare these numbers with your contact in person or on a call. If they match, nobody is in the middle.",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                Text(code, fontFamily = Mura.Mono, style = MaterialTheme.typography.titleMedium)
                Text("To mark them as verified, use ⋮ › Verify safety code in the chat.", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close", color = Mura.Amber) } },
    )
}

private fun monthOf(t: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(t))
private fun shortDate(t: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t))

@Composable
private fun Pill(icon: ImageVector, text: String, tint: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Mura.Ink.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Mura.Ink.copy(alpha = 0.45f)).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = Mura.Amber, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mura.Text)
    }
}

@Composable
private fun CounterTile(icon: ImageVector, label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) Mura.Amber else Mura.Surface)
            .border(1.dp, if (selected) Mura.Amber else Mura.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, null, tint = if (selected) Mura.OnAmber else Mura.Soft, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(4.dp))
        Text(if (count > 999) "999+" else count.toString(), style = MaterialTheme.typography.titleLarge.copy(fontFamily = Mura.Display),
            color = if (selected) Mura.OnAmber else Mura.Text)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) Mura.OnAmber else Mura.Muted)
    }
}

@Composable
private fun MonthHeader(month: String, n: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(month.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Mura.Amber, fontFamily = Mura.Mono)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Mura.Line))
        Spacer(Modifier.width(8.dp))
        Text("$n", style = MaterialTheme.typography.labelSmall, color = Mura.Muted, fontFamily = Mura.Mono)
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Mura.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Mura.Muted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Mura.Muted)
    }
}

@Composable
private fun rememberThumb(sf: SharedFile): ImageBitmap? {
    val key = sf.f.fileId + (if (sf.local) "L" else "T")
    val state = produceState<ImageBitmap?>(ThumbCache.get(key)?.asImageBitmap(), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val bmp: Bitmap? = try {
                if (sf.local && File(sf.f.path).exists()) {
                    if (sf.f.media == Payload.MEDIA_IMAGE) SecureMedia.decodeImage(sf.f, 360)
                    else SecureMedia.videoFrame(sf.f)?.let { b -> if (b.width > 480) Bitmap.createScaledBitmap(b, 480, (480f * b.height / b.width).toInt().coerceAtLeast(1), true) else b }
                } else null
            } catch (e: Exception) { null } ?: sf.f.thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            bmp?.also { ThumbCache.put(key, it) }?.asImageBitmap()
        }
    }
    return state.value
}

@Composable
private fun MediaTile(sf: SharedFile, onClick: () -> Unit) {
    val img = rememberThumb(sf)
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Mura.Surface2).clickable(onClick = onClick)) {
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (sf.f.media == Payload.MEDIA_VIDEO) MIcons.Video else MIcons.Image, null, tint = Mura.Muted, modifier = Modifier.align(Alignment.Center))
        if (sf.f.media == Payload.MEDIA_VIDEO) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(50)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(MIcons.Play, null, tint = Color.White, modifier = Modifier.size(10.dp))
                Text(formatDuration(sf.f.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!sf.local) {
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Icon(MIcons.Download, "Not downloaded", tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

private fun docBadge(f: FileRow): Pair<String, Color> {
    val ext = f.name.substringAfterLast('.', "").lowercase()
    return when {
        f.mime == "application/pdf" || ext == "pdf" -> "PDF" to Color(0xFFE5484D)
        ext in setOf("doc", "docx", "odt", "rtf", "txt", "md") -> ext.uppercase().take(4) to Color(0xFF3E7BFA)
        ext in setOf("xls", "xlsx", "ods", "csv") -> ext.uppercase().take(4) to Color(0xFF2FB36E)
        ext in setOf("ppt", "pptx", "odp", "key") -> ext.uppercase().take(4) to Color(0xFFF08A24)
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> ext.uppercase().take(4) to Color(0xFF9B6BFF)
        ext in setOf("apk") -> "APK" to Color(0xFF6BC4C4)
        ext.isNotEmpty() -> ext.uppercase().take(4) to Color(0xFF7C8594)
        else -> "FILE" to Color(0xFF7C8594)
    }
}

@Composable
private fun DocCard(sf: SharedFile, onClick: () -> Unit) {
    val (label, color) = docBadge(sf.f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mura.Surface).border(1.dp, Mura.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 46.dp, height = 54.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, fontFamily = Mura.Mono)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sf.f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${SecureMedia.humanSize(sf.f.originalSize.takeIf { it > 0 } ?: sf.f.size)} · ${shortDate(sf.m.created)} · ${if (sf.m.outgoing) "Sent" else "Received"}",
                style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
        }
        Icon(if (sf.local) (if (sf.m.outgoing) MIcons.Up else MIcons.Down) else MIcons.Download, null, tint = Mura.Soft, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LinkCard(l: SharedLink, onClick: () -> Unit) {
    val host = remember(l.url) { try { Uri.parse(l.url).host?.removePrefix("www.") ?: l.url } catch (e: Exception) { l.url } }
    val img = remember(l.image) { l.image?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } catch (e: Exception) { null } } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mura.Surface).border(1.dp, Mura.Line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Mura.AmberDeep), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(host.firstOrNull()?.uppercase() ?: "#", color = Mura.Amber, style = MaterialTheme.typography.titleLarge.copy(fontFamily = Mura.Display))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(l.title.ifBlank { host }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(host, style = MaterialTheme.typography.labelSmall, color = Mura.Amber, fontFamily = Mura.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${shortDate(l.m.created)} · ${if (l.m.outgoing) "Sent" else "Received"}", style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
        }
    }
}

@Composable
private fun VoiceCard(sf: SharedFile, who: String) {
    val engine = requireEngine()
    val ctx = LocalContext.current
    val playing by VoicePlayer.state.collectAsState()
    val isThis = playing.id == sf.m.id
    val dur = if (isThis && playing.durationMs > 0) playing.durationMs else sf.f.durationMs
    val pos = if (isThis) playing.positionMs else 0
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mura.Surface).border(1.dp, Mura.Line, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (sf.local) Mura.Amber else Mura.Surface2).clickable {
            when {
                sf.local && File(sf.f.path).exists() -> VoicePlayer.toggle(sf.m.id, sf.f)
                sf.f.state == FileState.NEEDS_APPROVAL -> { engine.acceptFile(sf.f.fileId); ctx.toast("Downloading…") }
                else -> ctx.toast("Still arriving")
            }
        }, contentAlignment = Alignment.Center) {
            Icon(if (!sf.local) MIcons.Download else if (isThis && playing.playing) MIcons.Pause else MIcons.Play, "Play",
                tint = if (sf.local) Mura.OnAmber else Mura.Amber, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(who, style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp), color = Mura.Amber, trackColor = Mura.Line)
            Text("${if (isThis) formatDuration(pos) + " / " else ""}${formatDuration(dur)} · ${shortDate(sf.m.created)}",
                style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
        }
    }
}
