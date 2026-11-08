package com.mura.mesh.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mura.mesh.core.Checklists
import com.mura.mesh.core.DoodleCodec
import com.mura.mesh.core.Payload
import com.mura.mesh.data.ChatSummary
import com.mura.mesh.data.ContactState
import com.mura.mesh.data.MessageRow
import com.mura.mesh.data.MsgKind
import com.mura.mesh.data.ReactionRow
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.EmojiPicker
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.timeLabel
import com.mura.mesh.ui.toast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// =====================================================================================
// Reactions
// =====================================================================================

val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")

/** The emoji strip that appears above a long-pressed message. */
@Composable
fun ReactionBar(mine: String?, onPick: (String) -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(26.dp)).background(Mura.Surface2).border(1.dp, Mura.Line, RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (e in QUICK_REACTIONS) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(if (e == mine) Mura.AmberDeep else Color.Transparent)
                    .clickable(role = Role.Button, onClickLabel = "React $e") { onPick(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 24.sp) }
        }
        Box(Modifier.size(42.dp).clip(CircleShape).background(Mura.Surface).clickable(role = Role.Button, onClickLabel = "More reactions", onClick = onMore),
            contentAlignment = Alignment.Center) { Icon(MIcons.Plus, null, tint = Mura.Soft, modifier = Modifier.size(20.dp)) }
    }
}

/** Little "❤️ 2" chips under a bubble. Tapping opens who reacted. */
@Composable
fun ReactionChips(list: List<ReactionRow>, me: String, onOpen: () -> Unit) {
    if (list.isEmpty()) return
    val groups = list.groupBy { it.emoji }.entries.sortedByDescending { it.value.size }
    val mineEmoji = list.firstOrNull { it.sender == me }?.emoji
    // Reaction burst: pops with sparks when a reaction was just added (yours, or one that arrived right now).
    val newest = list.maxOf { it.at }
    val fresh = remember(newest) { System.currentTimeMillis() - newest in 0..3_000 }
    Row(
        Modifier.padding(horizontal = 6.dp).then(com.mura.mesh.ui.reactionBurst(fresh, newest)).clip(RoundedCornerShape(14.dp)).background(Mura.Surface2)
            .border(1.dp, if (mineEmoji != null) Mura.Amber.copy(alpha = 0.6f) else Mura.Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "See reactions", onClick = onOpen).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((e, rows) in groups.take(4)) Text(e, fontSize = 15.sp)
        if (list.size > 1) Text(list.size.toString(), style = MaterialTheme.typography.labelMedium, color = Mura.Soft)
    }
}

@Composable
fun ReactionsDialog(list: List<ReactionRow>, me: String, nameOf: (String) -> String, onRemoveMine: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Mura.Surface,
        title = { Text("Reactions") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in list.sortedByDescending { it.at }) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = r.sender == me) { onRemoveMine(); onDismiss() }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(r.sender, nameOf(r.sender), 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(r.sender), style = MaterialTheme.typography.titleSmall)
                            if (r.sender == me) Text("Tap to remove", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                        }
                        Text(r.emoji, fontSize = 24.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Mura.Soft) } },
    )
}

@Composable
fun EmojiPickDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Mura.Surface).padding(top = 12.dp)) {
            Text("React with any emoji", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            EmojiPicker { onPick(it); onDismiss() }
        }
    }
}

// =====================================================================================
// Mentions, forwarded label, edit bar, pinned bar, in-chat search
// =====================================================================================

/** Message text with "@Name" mentions in amber. */
fun withMentions(text: String, names: List<String>, accent: Color): AnnotatedString = buildAnnotatedString {
    if (names.isEmpty()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (i < text.length) {
        val hit = names.filter { it.isNotEmpty() }.sortedByDescending { it.length }.firstOrNull { text.startsWith("@$it", i) }
        if (hit != null) {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append("@$hit") }
            i += hit.length + 1
        } else { append(text[i]); i++ }
    }
}

@Composable
fun ForwardedLabel(onAmber: Boolean) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MIcons.Forward, null, tint = if (onAmber) Mura.OnAmber.copy(alpha = 0.6f) else Mura.Muted, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text("Forwarded", style = MaterialTheme.typography.labelMedium.copy(fontStyle = FontStyle.Italic),
            color = if (onAmber) Mura.OnAmber.copy(alpha = 0.6f) else Mura.Muted)
    }
}

/** "Editing message" above the composer. */
@Composable
fun EditBar(m: MessageRow, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp).height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp)).background(Mura.Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Mura.Blue))
        Icon(MIcons.Edit, null, tint = Mura.Blue, modifier = Modifier.padding(start = 10.dp).size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(if (m.sendAt > 0) "Editing scheduled message" else "Editing message", style = MaterialTheme.typography.labelMedium, color = Mura.Blue)
            Text(m.body, style = MaterialTheme.typography.bodySmall, color = Mura.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(MIcons.Close, "Cancel editing", size = 40.dp, onClick = onClose)
    }
}

/** Pinned messages under the chat header: shows one, tap jumps to it and moves to the next. */
@Composable
fun PinnedBar(pins: List<MessageRow>, index: Int, onTap: (MessageRow) -> Unit) {
    if (pins.isEmpty()) return
    val m = pins[index % pins.size]
    Row(
        Modifier.fillMaxWidth().background(Mura.Surface).clickable { onTap(m) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pins.size > 1) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            pins.indices.forEach { i -> Box(Modifier.size(3.dp, 10.dp).clip(RoundedCornerShape(2.dp)).background(if (i == index % pins.size) Mura.Amber else Mura.Line)) }
        }
        Icon(MIcons.Pinned, null, tint = Mura.Amber, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(if (pins.size > 1) "Pinned · ${index % pins.size + 1} of ${pins.size}" else "Pinned message", style = MaterialTheme.typography.labelMedium, color = Mura.Amber)
            Text(snippetOf(m), style = MaterialTheme.typography.bodySmall, color = Mura.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun snippetOf(m: MessageRow): String = when {
    m.deleted -> "This message was deleted"
    m.viewOnce -> "View-once message"
    m.kind == MsgKind.POLL -> "Poll: ${m.body}"
    m.kind == MsgKind.LIST -> "List: ${m.body}"
    m.kind == MsgKind.DOODLE -> "Doodle"
    m.kind == MsgKind.LOCATION -> "Location"
    else -> m.body.ifBlank { m.meta.optString("caption", "") }
}

@Composable
fun ChatSearchBar(query: String, onQuery: (String) -> Unit, count: Int, index: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(MIcons.Back, "Close search", onClick = onClose)
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Mura.Text), cursorBrush = SolidColor(Mura.Amber),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Mura.Surface).border(1.dp, Mura.Line, RoundedCornerShape(14.dp)).padding(12.dp),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search this chat", color = Mura.Muted); inner() } },
        )
        Text(if (query.length < 2) "" else if (count == 0) "0" else "${index + 1}/$count", style = MaterialTheme.typography.labelSmall, color = Mura.Soft,
            modifier = Modifier.padding(horizontal = 8.dp))
        IconBtn(MIcons.Up, "Older match", enabled = count > 0, size = 40.dp, onClick = onNext)
        IconBtn(MIcons.Down, "Newer match", enabled = count > 0, size = 40.dp, onClick = onPrev)
    }
}

/** "@" in a group: pick who to mention. */
@Composable
fun MentionPopup(candidates: List<Pair<String, String>>, onPick: (String, String) -> Unit) {
    if (candidates.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp)).background(Mura.Surface2)
            .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) {
        for ((pub, name) in candidates.take(20)) {
            Row(
                Modifier.fillMaxWidth().clickable { onPick(pub, name) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(pub, name, 32.dp)
                Text(name, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

// =====================================================================================
// Polls
// =====================================================================================

@Composable
fun PollContent(m: MessageRow, votes: Map<String, List<Int>>, me: String, nameOf: (String) -> String, onVote: (List<Int>) -> Unit) {
    val opts = remember(m.id) { m.meta.optJSONArray("options")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList() }
    val multi = m.meta.optBoolean("multi")
    // A single-choice poll counts only the first choice even if a modified app sent more.
    val clean = votes.mapValues { (_, c) -> c.filter { it in opts.indices }.let { if (multi) it.distinct() else it.take(1) } }.filterValues { it.isNotEmpty() }
    val mine = clean[me] ?: emptyList()
    val counts = IntArray(opts.size).also { arr -> clean.values.forEach { c -> c.forEach { arr[it]++ } } }
    val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    var showVoters by remember { mutableStateOf(false) }
    Column(Modifier.width(260.dp).padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MIcons.Poll, null, tint = Mura.Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (multi) "Poll · choose any" else "Poll · choose one", style = MaterialTheme.typography.labelMedium, color = Mura.Muted)
        }
        Text(m.body, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp))
        opts.forEachIndexed { i, o ->
            val on = i in mine
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !m.deleted, role = if (multi) Role.Checkbox else Role.RadioButton) {
                    val next = if (multi) (if (on) mine - i else mine + i) else (if (on) emptyList() else listOf(i))
                    onVote(next)
                }.padding(vertical = 6.dp, horizontal = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(20.dp).clip(if (multi) RoundedCornerShape(5.dp) else CircleShape)
                            .border(2.dp, if (on) Mura.Amber else Mura.Line, if (multi) RoundedCornerShape(5.dp) else CircleShape)
                            .background(if (on) Mura.Amber else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { if (on) Icon(MIcons.Check, null, tint = Mura.OnAmber, modifier = Modifier.size(14.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Text(o, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(counts[i].toString(), style = MaterialTheme.typography.labelMedium, color = if (on) Mura.Amber else Mura.Soft)
                }
                Box(Modifier.padding(start = 30.dp, top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Mura.Line)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(counts[i].toFloat() / max).clip(RoundedCornerShape(3.dp)).background(if (on) Mura.Amber else Mura.Soft.copy(alpha = 0.6f)))
                }
            }
        }
        val voters = clean.size
        Text(
            if (voters == 0) "No votes yet" else "$voters ${if (voters == 1) "person" else "people"} voted · View votes",
            style = MaterialTheme.typography.labelLarge, color = if (voters == 0) Mura.Muted else Mura.Amber,
            modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp)).clickable(enabled = voters > 0) { showVoters = true }.padding(8.dp),
        )
    }
    if (showVoters) AlertDialog(
        onDismissRequest = { showVoters = false }, containerColor = Mura.Surface,
        title = { Text(m.body) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                opts.forEachIndexed { i, o ->
                    val who = clean.filterValues { i in it }.keys
                    Text("$o · ${who.size}", style = MaterialTheme.typography.titleSmall, color = Mura.Amber)
                    if (who.isEmpty()) Text("No votes", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                    for (p in who) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Avatar(p, nameOf(p), 28.dp); Text(nameOf(p), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showVoters = false }) { Text("Close", color = Mura.Soft) } },
    )
}

@Composable
fun PollCreatorDialog(onDismiss: () -> Unit, onCreate: (String, List<String>, Boolean) -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multi by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Mura.Surface,
        icon = { Icon(MIcons.Poll, null, tint = Mura.Amber) },
        title = { Text("New poll") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question, { question = it.take(300) }, label = { Text("Question") }, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
                options.forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(o, { v ->
                            options[i] = v.take(100)
                            if (i == options.lastIndex && v.isNotBlank() && options.size < Payload.MAX_POLL_OPTIONS) options.add("")
                        }, singleLine = true, label = { Text("Option ${i + 1}") }, colors = fieldColors(), modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                        if (options.size > 2) IconBtn(MIcons.Close, "Remove option", size = 36.dp) { options.removeAt(i) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch) { multi = !multi }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Allow more than one answer", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(multi, { multi = it }, colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = Mura.Amber, checkedThumbColor = Mura.OnAmber, uncheckedTrackColor = Mura.Surface2, uncheckedThumbColor = Mura.Muted, uncheckedBorderColor = Mura.Line))
                }
            }
        },
        confirmButton = {
            val valid = question.isNotBlank() && options.count { it.isNotBlank() } >= 2
            TextButton(onClick = { onCreate(question, options.toList(), multi); onDismiss() }, enabled = valid) { Text("Send", color = if (valid) Mura.Amber else Mura.Muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mura.Soft) } },
    )
}

// =====================================================================================
// Shared checklists
// =====================================================================================

@Composable
fun ChecklistContent(m: MessageRow, ops: List<Checklists.Op>, onToggle: (String, Boolean) -> Unit, onOpen: () -> Unit) {
    val items = remember(ops) { Checklists.fold(ops) }
    val done = items.count { it.checked }
    Column(Modifier.width(260.dp).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MIcons.ListIcon, null, tint = Mura.Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Shared list · $done of ${items.size} done", style = MaterialTheme.typography.labelMedium, color = Mura.Muted)
        }
        Text(m.body, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Mura.Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (items.isEmpty()) 0f else done.toFloat() / items.size).background(Mura.Mint))
        }
        for (it in items.take(6)) ChecklistRow(it.text, it.checked, compact = true) { onToggle(it.id, !it.checked) }
        Text(if (items.size > 6) "+${items.size - 6} more · Open list" else "Open list", style = MaterialTheme.typography.labelLarge, color = Mura.Amber,
            modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(8.dp))
    }
}

@Composable
private fun ChecklistRow(text: String, checked: Boolean, compact: Boolean, sub: String? = null, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = if (compact) 4.dp else 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, if (checked) Mura.Mint else Mura.Line, RoundedCornerShape(6.dp))
                .background(if (checked) Mura.Mint else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(MIcons.Check, null, tint = Mura.Ink, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (checked) Mura.Muted else Mura.Text,
                textDecoration = if (checked) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
        }
    }
}

@Composable
fun ChecklistCreatorDialog(onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var title by remember { mutableStateOf("") }
    var items by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Mura.Surface,
        icon = { Icon(MIcons.ListIcon, null, tint = Mura.Amber) },
        title = { Text("New shared list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Everyone in the chat can tick, add and edit items — even offline. Changes meet up when phones do.",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                OutlinedTextField(title, { title = it.take(100) }, singleLine = true, label = { Text("Title") }, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(items, { items = it }, label = { Text("Items, one per line") }, colors = fieldColors(), minLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title, items.lines()); onDismiss() }, enabled = title.isNotBlank()) {
                Text("Send", color = if (title.isNotBlank()) Mura.Amber else Mura.Muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mura.Soft) } },
    )
}

private data class ListData(val msg: MessageRow?, val ops: List<Checklists.Op>, val names: Map<String, String>)

@Composable
fun ChecklistScreen(listId: String) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val data by rememberQuery(ListData(null, emptyList(), emptyMap()), listId) {
        val m = engine.db.message(listId)
        val ops = if (m == null) emptyList() else engine.db.listOpsForChat(m.chat)[listId] ?: emptyList()
        val names = ops.map { it.author }.distinct().associateWith { p ->
            if (p == engine.identity.publicHex) "You"
            else if (m != null && com.mura.mesh.data.ChatId.isGroup(m.chat)) engine.db.memberName(com.mura.mesh.data.ChatId.groupIdOf(m.chat), p)
            else engine.db.contact(p)?.display ?: "Them"
        }
        ListData(m, ops, names)
    }
    val rows = remember(data.ops) { Checklists.fold(data.ops) }
    var newItem by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Checklists.Item?>(null) }
    Column(Modifier.fillMaxSize().background(Mura.Ink).imePadding()) {
        TopBar(data.msg?.body ?: "List", "${rows.count { it.checked }} of ${rows.size} done · shared with the chat", onBack = { nav.back() })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(rows, key = { it.id }) { it ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ChecklistRow(it.text, it.checked, compact = false,
                            sub = if (it.checked) "Ticked by ${data.names[it.checkedBy] ?: "someone"}" else "Added by ${data.names[it.addedBy] ?: "someone"}") {
                            engine.listOp(listId, if (it.checked) Payload.OP_UNCHECK else Payload.OP_CHECK, it.id)
                        }
                    }
                    IconBtn(MIcons.Edit, "Rename item", tint = Mura.Muted, size = 40.dp) { renaming = it }
                    IconBtn(MIcons.Trash, "Remove item", tint = Mura.Muted, size = 40.dp) { engine.listOp(listId, Payload.OP_REMOVE, it.id) }
                }
            }
            if (rows.isEmpty()) item { Text("Nothing on the list yet.", color = Mura.Muted, modifier = Modifier.padding(vertical = 20.dp)) }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = newItem, onValueChange = { newItem = it.take(200) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Mura.Text), cursorBrush = SolidColor(Mura.Amber),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (newItem.isNotBlank()) { engine.listOp(listId, Payload.OP_ADD, null, newItem); newItem = "" } }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Mura.Surface).border(1.dp, Mura.Line, RoundedCornerShape(22.dp)).padding(14.dp),
                decorationBox = { inner -> Box { if (newItem.isEmpty()) Text("Add an item", color = Mura.Muted); inner() } },
            )
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (newItem.isNotBlank()) Mura.Amber else Mura.Surface2)
                    .clickable(enabled = newItem.isNotBlank(), role = Role.Button, onClickLabel = "Add item") { engine.listOp(listId, Payload.OP_ADD, null, newItem); newItem = "" },
                contentAlignment = Alignment.Center,
            ) { Icon(MIcons.Plus, "Add item", tint = if (newItem.isNotBlank()) Mura.OnAmber else Mura.Muted) }
        }
    }
    renaming?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = Mura.Surface,
            title = { Text("Rename item") },
            text = { OutlinedTextField(text, { text = it.take(200) }, singleLine = true, colors = fieldColors()) },
            confirmButton = { TextButton(onClick = { renaming = null; if (text.isNotBlank() && text != item.text) engine.listOp(listId, Payload.OP_RENAME, item.id, text) }) { Text("Save", color = Mura.Amber) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

// =====================================================================================
// Doodles
// =====================================================================================

private fun DrawScope.drawStrokes(strokes: List<DoodleCodec.Stroke>) {
    for (s in strokes) {
        val pts = s.points
        if (pts.size < 2) continue
        val w = (s.width * size.width).coerceAtLeast(1f)
        val color = Color(s.color)
        if (pts.size == 2) { drawCircle(color, w / 2, Offset(pts[0] * size.width, pts[1] * size.height)); continue }
        val path = Path()
        path.moveTo(pts[0] * size.width, pts[1] * size.height)
        // Smooth: quadratic curves through the midpoints.
        for (i in 1 until pts.size / 2 - 1) {
            val x = pts[i * 2] * size.width; val y = pts[i * 2 + 1] * size.height
            val nx = pts[i * 2 + 2] * size.width; val ny = pts[i * 2 + 3] * size.height
            path.quadraticTo(x, y, (x + nx) / 2, (y + ny) / 2)
        }
        path.lineTo(pts[pts.size - 2] * size.width, pts[pts.size - 1] * size.height)
        drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun DoodleContent(m: MessageRow) {
    val strokes = remember(m.id) {
        try { DoodleCodec.decode(android.util.Base64.decode(m.meta.optString("strokes", ""), android.util.Base64.NO_WRAP)) } catch (e: Exception) { emptyList() }
    }
    val w = m.meta.optInt("w", 600).coerceAtLeast(1); val h = m.meta.optInt("h", 800).coerceAtLeast(1)
    val bg = Color(m.meta.optInt("bg", 0xFF0B0E13.toInt()))
    Canvas(Modifier.width(240.dp).aspectRatio((w.toFloat() / h).coerceIn(0.5f, 1.6f)).clip(RoundedCornerShape(12.dp))) {
        drawRect(bg)
        drawStrokes(strokes)
    }
}

private val DOODLE_COLORS = listOf(0xFFECEFF4, 0xFFFFB547, 0xFFE5484D, 0xFF8FE3B0, 0xFF7CC4FF, 0xFFE7A6F0, 0xFF1A1204).map { it.toInt() }
private val DOODLE_BGS = listOf(0xFF0B0E13, 0xFFF4EFE6, 0xFF14283A).map { it.toInt() }

@Composable
fun DoodleScreen(chat: String) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val strokes = remember { mutableStateListOf<DoodleCodec.Stroke>() }
    val current = remember { mutableStateListOf<Float>() }
    var color by remember { mutableStateOf(DOODLE_COLORS[1]) }
    var width by remember { mutableStateOf(0.012f) }
    var bg by remember { mutableStateOf(DOODLE_BGS[0]) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        TopBar("Doodle", "Sent as tiny vector strokes", onBack = { nav.back() }) {
            IconBtn(MIcons.Undo, "Undo", enabled = strokes.isNotEmpty()) { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
            IconBtn(MIcons.Trash, "Clear", enabled = strokes.isNotEmpty()) { strokes.clear() }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).background(Color(bg))
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> current.clear(); current.add(o.x / size.width); current.add(o.y / size.height) },
                        onDrag = { change, _ -> current.add(change.position.x / size.width); current.add(change.position.y / size.height) },
                        onDragEnd = {
                            if (current.size >= 2) strokes.add(DoodleCodec.Stroke(color, width, DoodleCodec.simplify(current.toFloatArray())))
                            current.clear()
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawStrokes(strokes)
                if (current.size >= 2) drawStrokes(listOf(DoodleCodec.Stroke(color, width, current.toFloatArray())))
            }
            if (strokes.isEmpty() && current.isEmpty()) Text("Draw with your finger", color = Color(if (bg == DOODLE_BGS[1]) 0x661A1204 else 0x66ECEFF4),
                modifier = Modifier.align(Alignment.Center))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in DOODLE_COLORS) Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (c == color) Mura.Amber else Mura.Line, CircleShape)
                    .clickable(role = Role.RadioButton, onClickLabel = "Pen colour") { color = c },
            )
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((wv, label) in listOf(0.006f to "Thin", 0.012f to "Pen", 0.028f to "Marker")) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (width == wv) Mura.OnAmber else Mura.Soft,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (width == wv) Mura.Amber else Mura.Surface).clickable { width = wv }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(bg)).border(1.dp, Mura.Line, RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Background") { bg = DOODLE_BGS[(DOODLE_BGS.indexOf(bg) + 1) % DOODLE_BGS.size] })
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (strokes.isNotEmpty()) Mura.Amber else Mura.Surface2)
                    .clickable(enabled = strokes.isNotEmpty(), role = Role.Button, onClickLabel = "Send doodle") {
                        try {
                            engine.sendDoodle(chat, size.width, size.height, bg, DoodleCodec.encode(strokes.toList()))
                            nav.back()
                        } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't send that doodle") }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(MIcons.Send, "Send doodle", tint = if (strokes.isNotEmpty()) Mura.OnAmber else Mura.Muted) }
        }
    }
}

// =====================================================================================
// Forward
// =====================================================================================

@Composable
fun ForwardDialog(fromChat: String, onDismiss: () -> Unit, onSend: (List<String>) -> Unit) {
    val engine = requireEngine()
    val chats by rememberQuery(emptyList<ChatSummary>()) { engine.db.chats().filter { !it.locked && it.state == ContactState.CONTACT } }
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Mura.Surface,
        icon = { Icon(MIcons.Forward, null, tint = Mura.Amber) },
        title = { Text("Forward to…") },
        text = {
            Column {
                Text("Up to 5 chats. Each gets its own freshly encrypted copy.", style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(chats, key = { it.chat }) { c ->
                        val on = c.chat in picked
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) {
                                if (on) picked.remove(c.chat) else if (picked.size < 5) picked.add(c.chat)
                            }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(c.chat, c.title, 36.dp, photo = c.avatar, group = c.isGroup)
                            Text(c.title + if (c.chat == fromChat) " (this chat)" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Mura.Amber else Mura.Surface2), contentAlignment = Alignment.Center) {
                                if (on) Icon(MIcons.Check, null, tint = Mura.OnAmber, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(picked.toList()); onDismiss() }, enabled = picked.isNotEmpty()) {
                Text(if (picked.isEmpty()) "Send" else "Send to ${picked.size}", color = if (picked.isNotEmpty()) Mura.Amber else Mura.Muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mura.Soft) } },
    )
}

// =====================================================================================
// Send later
// =====================================================================================

/** Pick a date, then a time. Returns epoch millis in the phone's time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val soon = LocalTime.now(zone).plusMinutes(10)
    if (date == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isBefore(today) && !d.isAfter(today.plusYears(1))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } }) { Text("Next", color = Mura.Amber) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Mura.Soft) } },
            colors = DatePickerDefaults.colors(containerColor = Mura.Surface),
        ) { DatePicker(state = state, title = { Text("Send on…", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    } else {
        val tState = rememberTimePickerState(initialHour = soon.hour, initialMinute = soon.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        val chosen = LocalDateTime.of(date!!, LocalTime.of(tState.hour, tState.minute)).atZone(zone).toInstant().toEpochMilli()
        val valid = chosen > System.currentTimeMillis() + 30_000
        AlertDialog(
            onDismissRequest = onDismiss, containerColor = Mura.Surface,
            title = { Text("At what time?") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = tState, colors = TimePickerDefaults.colors(clockDialColor = Mura.Surface2, selectorColor = Mura.Amber,
                        timeSelectorSelectedContainerColor = Mura.AmberDeep, timeSelectorSelectedContentColor = Mura.Amber))
                    Text(
                        if (valid) "Sends ${DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").format(LocalDateTime.of(date!!, LocalTime.of(tState.hour, tState.minute)))} · waits on this phone until then"
                        else "Pick a time in the future",
                        style = MaterialTheme.typography.bodySmall, color = if (valid) Mura.Soft else Mura.Red,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onPick(chosen); onDismiss() }, enabled = valid) { Text("Schedule", color = if (valid) Mura.Amber else Mura.Muted) } },
            dismissButton = { TextButton(onClick = { date = null }) { Text("Back", color = Mura.Soft) } },
        )
    }
}

fun scheduledLabel(at: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    val time = DateTimeFormatter.ofPattern("HH:mm").format(d)
    return when (d.toLocalDate()) {
        today -> "today $time"
        today.plusDays(1) -> "tomorrow $time"
        else -> DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").format(d)
    }
}

// =====================================================================================
// Starred messages
// =====================================================================================

private class StarredItem(val m: MessageRow, val chatTitle: String, val from: String)

@Composable
fun StarredScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val starred by rememberQuery(emptyList<StarredItem>()) {
        val titles = engine.db.chats().associate { it.chat to it.title }
        val locked = engine.db.chats().filter { it.locked }.map { it.chat }.toSet()
        engine.db.starred().filter { it.chat !in locked }.map { m ->
            val from = when {
                m.outgoing -> "You"
                com.mura.mesh.data.ChatId.isGroup(m.chat) -> engine.db.memberName(com.mura.mesh.data.ChatId.groupIdOf(m.chat), m.sender)
                else -> engine.db.contact(m.sender)?.display ?: "Them"
            }
            StarredItem(m, titles[m.chat] ?: "Chat", from)
        }
    }
    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        TopBar("Starred messages", "${starred.size} · only on this phone", onBack = { nav.back() })
        if (starred.isEmpty()) Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(MIcons.Star, null, tint = Mura.Amber, modifier = Modifier.size(36.dp))
            Text("Nothing starred yet", style = MaterialTheme.typography.titleMedium)
            Text("Long-press any message and tap Star to keep it here.", style = MaterialTheme.typography.bodyMedium, color = Mura.Muted)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(starred, key = { it.m.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go(Screen.Chat(s.m.chat, focus = s.m.id)) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(s.m.chat, s.chatTitle, 40.dp, group = com.mura.mesh.data.ChatId.isGroup(s.m.chat))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.from} › ${s.chatTitle}", style = MaterialTheme.typography.labelMedium, color = Mura.Soft, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(timeLabel(s.m.created), style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
                        }
                        Text(snippetOf(s.m), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(MIcons.Star, "Unstar", tint = Mura.Amber, size = 40.dp) { engine.star(s.m.id, false) }
                }
            }
        }
    }
}

// =====================================================================================
// Global search results (used by the chat list)
// =====================================================================================

@Composable
fun SearchResultRow(m: MessageRow, chatTitle: String, isGroup: Boolean, query: String, onClick: () -> Unit) {
    val text = snippetOf(m)
    val i = text.lowercase().indexOf(query.trim().lowercase())
    // Show the words around the match, with the match in amber.
    val start = if (i > 40) i - 30 else 0
    val shown = (if (start > 0) "…" else "") + text.substring(start)
    val hl = if (i >= 0) buildAnnotatedString {
        val j = shown.lowercase().indexOf(query.trim().lowercase())
        if (j < 0) append(shown) else {
            append(shown.substring(0, j))
            withStyle(SpanStyle(color = Mura.Amber, fontWeight = FontWeight.SemiBold)) { append(shown.substring(j, j + query.trim().length)) }
            append(shown.substring(j + query.trim().length))
        }
    } else AnnotatedString(shown)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(m.chat, chatTitle, 44.dp, group = isGroup)
        Column(Modifier.weight(1f)) {
            Row {
                Text(chatTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timeLabel(m.created), style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
            }
            Text(hl, style = MaterialTheme.typography.bodyMedium, color = Mura.Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

