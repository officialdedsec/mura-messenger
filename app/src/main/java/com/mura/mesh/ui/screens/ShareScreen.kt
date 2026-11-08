package com.mura.mesh.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.mura.mesh.data.ContactState
import com.mura.mesh.media.PhotoQuality
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.media.VideoQuality
import com.mura.mesh.mesh.Settings
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share to Mura" from other apps (file manager, gallery, browser…), like WhatsApp's share target.
 *
 * Safety:
 *  • Only content:// items another app actually handed over are accepted — never file:// paths, and never
 *    anything from Mura's own storage (so a malicious app can't trick Mura into sending its private files).
 *  • It opens behind the app lock / calculator disguise, and nothing is sent until you pick chats and tap Send.
 *  • Files are compressed and sealed exactly like files attached inside a chat; with "Remove hidden details" on,
 *    location, dates and phone model are stripped first.
 */
object ShareInbox {
    class Item(val uri: Uri, val mime: String)
    class Pending(val items: List<Item>, val text: String?)

    var pending by mutableStateOf<Pending?>(null)
    const val MAX_ITEMS = 10

    /** True if [intent] is a share; the share is kept until the user sends or cancels it. */
    fun accept(ctx: Context, intent: Intent): Boolean {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false
        val uris = ArrayList<Uri>()
        try {
            if (action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            else IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
        } catch (_: Exception) {}
        val items = uris.filter { safe(ctx, it) }.distinct().take(MAX_ITEMS).map { u ->
            val type = try { ctx.contentResolver.getType(u) } catch (_: Exception) { null }
            Item(u, type ?: intent.type?.takeIf { !it.contains('*') } ?: "application/octet-stream")
        }
        val text = try { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.take(4000) } catch (_: Exception) { null }
            ?.takeIf { it.isNotEmpty() }
        // Consume the intent, so a screen rotation or a later resume can't share it twice.
        intent.action = Intent.ACTION_MAIN
        intent.removeExtra(Intent.EXTRA_STREAM); intent.removeExtra(Intent.EXTRA_TEXT)
        if (items.isEmpty() && text == null) { ctx.toast("Nothing Mura can send was shared"); return true }
        pending = Pending(items, text)
        return true
    }

    private fun safe(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false                                    // never raw file paths
        val auth = uri.authority ?: return false
        if (auth == ctx.packageName || auth.startsWith(ctx.packageName + ".")) return false   // never Mura's own files
        return true
    }
}

private class Target(val chat: String, val title: String, val avatar: ByteArray?, val group: Boolean)

@Composable
fun ShareToScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val share = ShareInbox.pending
    if (share == null) { LaunchedEffect(Unit) { nav.back() }; return }

    // Chats you can send to (locked chats and requests are left out of the share sheet), plus contacts without a chat yet.
    val targets by rememberQuery(emptyList<Target>()) {
        val chats = engine.db.chats().filter { !it.locked && it.state != ContactState.REQUEST && it.state != ContactState.BLOCKED }
            .map { Target(it.chat, it.title, it.avatar, it.isGroup) }
        val seen = chats.map { it.chat }.toSet()
        chats + engine.db.contactsByState(ContactState.CONTACT).filter { it.pub !in seen && !engine.db.isLocked(it.pub) }
            .map { Target(it.pub, it.display, it.avatar, false) }
    }
    val selected = remember { mutableStateListOf<String>() }
    val stripSetting = remember { engine.db.getSetting(Settings.STRIP_META, "ask") }
    var strip by remember { mutableStateOf(stripSetting != "never") }
    var busy by remember { mutableStateOf<String?>(null) }

    fun cancel() { ShareInbox.pending = null; nav.back() }
    BackHandler(enabled = busy == null) { cancel() }

    fun send() {
        val chats = selected.toList()
        if (chats.isEmpty() || busy != null) return
        busy = "Encrypting…"
        scope.launch {
            var failed = 0
            withContext(Dispatchers.IO) {
                val hd = engine.db.getSetting(Settings.PHOTO_QUALITY, "STANDARD") == "HD"
                val vq = try { VideoQuality.valueOf(engine.db.getSetting(Settings.VIDEO_QUALITY, "STANDARD")) } catch (e: Exception) { VideoQuality.STANDARD }
                for (chat in chats) {
                    for (item in share.items) {
                        try {
                            val p = when {
                                item.mime.startsWith("image/") && !item.mime.contains("gif") ->
                                    SecureMedia.prepareImage(ctx, item.uri, if (hd) PhotoQuality.HD else PhotoQuality.STANDARD)
                                item.mime.startsWith("video/") -> SecureMedia.prepareVideo(ctx, item.uri, vq, strip)
                                else -> SecureMedia.prepareDocument(ctx, item.uri, strip)
                            }
                            engine.sendPrepared(chat, p, "", false)
                        } catch (e: Exception) { failed++ }
                    }
                    share.text?.let { engine.sendText(chat, it, false, null) }
                }
            }
            ShareInbox.pending = null
            busy = null
            if (failed > 0) ctx.toast("$failed item${if (failed == 1) "" else "s"} couldn't be read")
            if (chats.size == 1) nav.replace(Screen.Chat(chats[0])) else { ctx.toast("Sent to ${chats.size} chats"); nav.back() }
        }
    }

    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        val what = buildList {
            if (share.items.isNotEmpty()) add("${share.items.size} item${if (share.items.size == 1) "" else "s"}")
            if (share.text != null) add("text")
        }.joinToString(" + ")
        TopBar("Send to…", "$what · end-to-end encrypted", onBack = { if (busy == null) cancel() })
        if (share.text != null) {
            Text(share.text, style = MaterialTheme.typography.bodySmall, color = Mura.Soft, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(12.dp)).padding(12.dp))
        }
        if (share.items.any { !it.mime.startsWith("image/") } && stripSetting != "never") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remove hidden details", style = MaterialTheme.typography.titleSmall)
                    Text("Location, dates and phone model inside videos and files", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                }
                Switch(strip, { strip = it }, colors = SwitchDefaults.colors(checkedThumbColor = Mura.OnAmber, checkedTrackColor = Mura.Amber))
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (targets.isEmpty()) item {
                Text("No chats yet. Add a contact first.", style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(20.dp))
            }
            items(targets, key = { it.chat }) { t ->
                val on = t.chat in selected
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                        if (on) selected.remove(t.chat) else if (selected.size < 5) selected.add(t.chat) else ctx.toast("Up to 5 chats at a time")
                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(t.chat, t.title, 44.dp, photo = t.avatar, group = t.group)
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) Mura.Amber else Mura.Surface2), contentAlignment = Alignment.Center) {
                        if (on) Icon(MIcons.Check, "Selected", tint = Mura.OnAmber, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).clip(RoundedCornerShape(16.dp))
                .background(if (selected.isEmpty() || busy != null) Mura.Surface2 else Mura.Amber)
                .clickable(enabled = selected.isNotEmpty() && busy == null) { send() }.padding(16.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MIcons.Send, null, tint = if (selected.isEmpty()) Mura.Muted else Mura.OnAmber, modifier = Modifier.size(18.dp))
            Text(busy ?: if (selected.isEmpty()) "  Pick a chat" else "  Send to ${selected.size}",
                style = MaterialTheme.typography.titleSmall, color = if (selected.isEmpty()) Mura.Muted else Mura.OnAmber)
        }
    }
}
