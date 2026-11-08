package com.mura.mesh.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import com.mura.mesh.data.RecordingRow
import com.mura.mesh.media.VoicePlayer
import com.mura.mesh.mesh.formatDuration
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.MessageRow
import com.mura.mesh.mesh.OnlineTransport
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalInTab
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.clockLabel
import com.mura.mesh.ui.dayLabel
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.sameDay
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.launch

/** Call history: every call, missed or not, with date and time. Kept only on this phone, encrypted. */
@Composable
fun CallsScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val calls by rememberQuery(emptyList<MessageRow>()) { engine.db.callLog() }
    val recordings by rememberQuery(emptyList<RecordingRow>()) { engine.db.recordings() }
    // Calls and recordings in one timeline, newest first. Recordings live only here — never in chats.
    val entries = remember(calls, recordings) {
        (calls.map { Entry(it.id, it.created, call = it) } + recordings.map { Entry("rec:" + it.id, it.created, rec = it) })
            .sortedByDescending { it.at }
    }
    var deleteRec by remember { mutableStateOf<RecordingRow?>(null) }
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop() } }
    val contacts by rememberQuery(emptyMap<String, ContactRow>()) { engine.db.contacts().associateBy { it.pub } }
    val call = rememberCaller()
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        TopBar("Calls", "End-to-end encrypted · history stays on this phone",
            onBack = if (LocalInTab.current) null else ({ nav.back() })) {
            if (calls.isNotEmpty()) IconBtn(MIcons.Trash, "Clear call history") { confirmClear = true }
        }
        if (entries.isEmpty()) {
            Text("No calls yet. Call a contact from their chat — over Bluetooth when you're near each other, or through Tor when you're both online.",
                style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(entries.size, key = { entries[it].key }) { i ->
                val e = entries[i]
                if (i == 0 || !sameDay(entries[i - 1].at, e.at)) {
                    Text(dayLabel(e.at).uppercase(), style = MaterialTheme.typography.labelSmall, color = Mura.Amber,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                }
                val rec = e.rec
                if (rec != null) {
                    RecordingItem(rec, contacts[rec.peer]?.display ?: "Unknown", onDelete = { deleteRec = rec })
                } else {
                val m = e.call!!
                val c = contacts[m.chat]
                val name = c?.display ?: "Unknown"
                val missed = !m.outgoing && m.body.startsWith("Missed")
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go(Screen.Chat(m.chat)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(m.chat, name, 46.dp, photo = c?.avatar)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = if (missed) Mura.Red else Mura.Text)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(if (m.outgoing) MIcons.CallOut else MIcons.CallIn, if (m.outgoing) "Outgoing" else "Incoming",
                                tint = if (missed) Mura.Red else if (m.outgoing) Mura.Blue else Mura.Mint, modifier = Modifier.size(14.dp))
                            Text("${m.body} · ${clockLabel(m.created)}", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                        }
                    }
                    IconBtn(MIcons.Phone, "Call $name", bg = Mura.Surface) { call(m.chat, name) }
                }
                }
            }
        }
    }
    deleteRec?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteRec = null }, containerColor = Mura.Surface,
            title = { Text("Delete recording?") },
            text = { Text("The encrypted recording is erased from this phone. This can't be undone.", style = MaterialTheme.typography.bodySmall, color = Mura.Soft) },
            confirmButton = { TextButton(onClick = { deleteRec = null; if (VoicePlayer.state.value.id == r.id) VoicePlayer.stop(); engine.deleteRecording(r.id) }) { Text("Delete", color = Mura.Red) } },
            dismissButton = { TextButton(onClick = { deleteRec = null }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false }, containerColor = Mura.Surface,
            title = { Text("Clear call history?") },
            text = { Text("Removes every call from this list and from the chats. Messages and call recordings stay (delete recordings one by one).", style = MaterialTheme.typography.bodySmall, color = Mura.Soft) },
            confirmButton = { TextButton(onClick = { confirmClear = false; engine.db.clearCallLog() }) { Text("Clear", color = Mura.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

private class Entry(val key: String, val at: Long, val call: MessageRow? = null, val rec: RecordingRow? = null)

/** A recorded call: plays straight from encrypted storage, inside Mura only. */
@Composable
private fun RecordingItem(r: RecordingRow, name: String, onDelete: () -> Unit) {
    val engine = requireEngine()
    val ctx = LocalContext.current
    val playing by VoicePlayer.state.collectAsState()
    val isThis = playing.id == r.id
    val dur = if (isThis && playing.durationMs > 0) playing.durationMs else r.durationMs
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Mura.Amber).clickable {
            val f = engine.db.file(r.fileId)
            if (f == null || !File(f.path).exists()) ctx.toast("This recording is no longer on your phone") else VoicePlayer.toggle(r.id, f)
        }, contentAlignment = Alignment.Center) {
            Icon(if (isThis && playing.playing) MIcons.Pause else MIcons.Play, if (isThis && playing.playing) "Pause" else "Play recording",
                tint = Mura.OnAmber, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(MIcons.Record, null, tint = Mura.Red, modifier = Modifier.size(14.dp))
                Text("Recording · $name", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            val pos = if (isThis) playing.positionMs else 0
            LinearProgressIndicator(progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp), color = Mura.Amber, trackColor = Mura.Line)
            Text((if (isThis) "${formatDuration(pos)} / " else "") + "${formatDuration(dur)} · ${clockLabel(r.created)} · encrypted, only on this phone",
                style = MaterialTheme.typography.bodySmall, color = Mura.Muted, maxLines = 1)
        }
        IconBtn(MIcons.Trash, "Delete recording") { onDelete() }
    }
}

/** Starts a call over Bluetooth if in range, otherwise through Tor if you're both online. */
@Composable
fun rememberCaller(): (String, String) -> Unit {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun start(pub: String, name: String) {
        val err = engine.calls.startCall(pub, name)
        if (err == null) nav.go(Screen.Call) else ctx.toast(err)
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (ok && p != null) start(p.first, p.second) else if (!ok) ctx.toast("Microphone permission is needed for calls")
    }
    return { pub, name ->
        if (!com.mura.mesh.mesh.MeshPermissions.granted(ctx, Manifest.permission.RECORD_AUDIO)) {
            pending = pub to name; micPerm.launch(Manifest.permission.RECORD_AUDIO)
        } else if (engine.isLinked(pub)) start(pub, name)
        else if (engine.online.state.value.status == OnlineTransport.Status.ON && engine.db.contact(pub)?.onion != null) {
            ctx.toast("Connecting over Tor…")
            scope.launch {
                if (engine.online.ensureLink(pub, 30_000)) start(pub, name)
                else ctx.toast("$name isn't online or in range right now.")
            }
        } else ctx.toast("$name isn't in Bluetooth range, and you're not both online through Tor.")
    }
}
