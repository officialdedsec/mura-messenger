package com.mura.mesh.ui.screens

import androidx.compose.material3.TextButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mura.mesh.data.ChatSummary
import com.mura.mesh.data.ContactState
import com.mura.mesh.data.MsgStatus
import com.mura.mesh.data.StatusRow
import com.mura.mesh.mesh.BleTransport
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.ChatGate
import com.mura.mesh.ui.withAuth
import com.mura.mesh.ui.toast
import com.mura.mesh.mesh.Settings
import androidx.compose.ui.platform.LocalContext
import com.mura.mesh.ui.findActivity
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.timeLabel

@Composable
fun ChatsScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val chats by rememberQuery(emptyList<ChatSummary>()) { engine.db.chats() }
    val statuses by rememberQuery(emptyList<StatusRow>()) { engine.db.statuses(System.currentTimeMillis()) }
    val connected by engine.connected.collectAsState()
    val nearby by engine.nearby.collectAsState()
    val state by engine.state.collectAsState()
    val relayCount by rememberQuery(0) { engine.db.relayCount() }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var fabMenu by remember { mutableStateOf(false) }

    val hideLocked by rememberQuery(false) { engine.db.getBool(Settings.HIDE_LOCKED, false) }
    val ctx = LocalContext.current
    val linkedPubs = connected.map { it.pubHex }.toSet()
    // Locked chats can be tucked away in a "Locked chats" folder that opens with fingerprint/face.
    val lockedHidden = hideLocked && !ChatGate.folderOpen
    val hiddenCount = if (lockedHidden) chats.count { it.locked } else 0
    val hiddenUnread = if (lockedHidden) chats.filter { it.locked }.sumOf { it.unread } else 0
    var showArchived by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showArchived) { showArchived = false }
    val archived = chats.filter { it.archived && !(lockedHidden && it.locked) }
    val shown = chats.filter {
        (query.isBlank() || (it.title.contains(query, ignoreCase = true) && !it.locked)) && !(lockedHidden && it.locked) &&
            (if (showArchived) it.archived else (!it.archived || query.isNotBlank()))
    }
    val statusOwners = statuses.groupBy { it.owner }
    val messageHits by androidx.compose.runtime.produceState(emptyList<com.mura.mesh.data.MessageRow>(), query, chats) {
        if (query.trim().length < 2) { value = emptyList(); return@produceState }
        kotlinx.coroutines.delay(250)
        val locked = chats.filter { it.locked }.map { it.chat }.toSet()
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { engine.db.search(query, skipChats = locked, limit = 60) }
    }

    Box(Modifier.fillMaxSize().background(Mura.Ink)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("mura", style = MaterialTheme.typography.titleLarge.copy(fontFamily = Mura.Display), modifier = Modifier.weight(1f))
                        IconBtn(MIcons.Search, "Search", bg = Mura.Surface) { searching = !searching; if (!searching) query = "" }
                        Spacer(Modifier.width(8.dp))
                        IconBtn(MIcons.Group, "Contacts", bg = Mura.Surface) { nav.go(Screen.Contacts) }
                        Spacer(Modifier.width(8.dp))
                        IconBtn(MIcons.Settings, "Settings", bg = Mura.Surface) { nav.tab(4) }
                    }
                    if (searching) {
                        Spacer(Modifier.height(12.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Mura.Text),
                            cursorBrush = SolidColor(Mura.Amber),
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).background(Mura.Surface, RoundedCornerShape(14.dp))
                                .border(1.dp, Mura.Line, RoundedCornerShape(14.dp)).padding(14.dp),
                            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search chats and messages", color = Mura.Muted); inner() } },
                        )
                    }
                }
            }
            item { MeshStatusCard(state, connected.size, nearby.size, relayCount) { nav.tab(3) } }
            item {
                // Status strip: my status first, then contacts with fresh updates.
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(vertical = 8.dp)) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)
                            .clickable(role = Role.Button) { nav.tab(1) }) {
                            Box(Modifier.size(58.dp).border(2.dp, Mura.Amber, RoundedCornerShape(19.dp)), contentAlignment = Alignment.Center) {
                                Icon(MIcons.Status, "Status updates", tint = Mura.Amber)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("Status", style = MaterialTheme.typography.labelMedium, color = Mura.Soft)
                        }
                    }
                    items(statusOwners.keys.filter { it != engine.identity.publicHex }.toList()) { owner ->
                        val c = chats.firstOrNull { it.chat == owner }
                        val unseen = statusOwners[owner]?.any { !it.viewed } == true
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)
                            .clickable(role = Role.Button) { nav.go(Screen.StatusView(owner)) }) {
                            Avatar(owner, c?.title ?: "?", 58.dp, ring = if (unseen) Mura.Amber else Mura.Line, photo = c?.avatar)
                            Spacer(Modifier.height(6.dp))
                            Text((c?.title ?: "Contact").split(" ").first(), style = MaterialTheme.typography.labelMedium, color = Mura.Soft,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(connected.filter { p -> chats.none { it.chat == p.pubHex } }, key = { it.linkId }) { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)
                            .clickable(role = Role.Button) { nav.go(Screen.AddContact) }) {
                            Avatar(p.pubHex, p.name.ifBlank { "?" }, 58.dp, ring = Mura.Mint)
                            Spacer(Modifier.height(6.dp))
                            Text(p.name.ifBlank { "Nearby" }.split(" ").first(), style = MaterialTheme.typography.labelMedium, color = Mura.Soft, maxLines = 1)
                        }
                    }
                }
            }
            if (hiddenCount > 0 && query.isBlank()) item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
                        .clickable { ctx.withAuth("Show locked chats") { ChatGate.folderOpen = true } }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Mura.AmberDeep), contentAlignment = Alignment.Center) {
                        Icon(MIcons.Fingerprint, null, tint = Mura.Amber)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Locked chats", style = MaterialTheme.typography.titleMedium)
                        Text("$hiddenCount chat${if (hiddenCount == 1) "" else "s"} · opens with your fingerprint or face", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                    }
                    if (hiddenUnread > 0) Box(Modifier.size(10.dp).clip(CircleShape).background(Mura.Amber))
                }
            }
            if (shown.isEmpty() && hiddenCount == 0) item { EmptyChats { nav.go(Screen.AddContact) } }
            if (showArchived) item {
                Row(Modifier.fillMaxWidth().clickable { showArchived = false }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(MIcons.Back, "Back", tint = Mura.Text)
                    Text("Archived chats", style = MaterialTheme.typography.titleMedium)
                }
                Text("Archived chats stay here, quietly, even when new messages arrive.", style = MaterialTheme.typography.bodySmall,
                    color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            } else if (archived.isNotEmpty() && query.isBlank()) item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).clickable { showArchived = true }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Mura.Surface), contentAlignment = Alignment.Center) {
                        Icon(MIcons.Archive, null, tint = Mura.Soft)
                    }
                    Text("Archived", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    val unread = archived.sumOf { it.unread }
                    Text(if (unread > 0) "$unread new" else "${archived.size}", style = MaterialTheme.typography.labelMedium,
                        color = if (unread > 0) Mura.Amber else Mura.Muted)
                }
            }
            item(key = "background-warning") { BackgroundWarning() }
            items(shown, key = { it.chat }) { c -> ChatRow(c, c.chat in linkedPubs) }
            // Search also looks inside messages (decrypted on the phone; locked chats are left out).
            if (query.trim().length >= 2) {
                item {
                    Text("MESSAGES", style = MaterialTheme.typography.labelSmall, color = Mura.Amber,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                }
                if (messageHits.isEmpty()) item {
                    Text("No messages found", style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
                items(messageHits, key = { "m" + it.id }) { m ->
                    SearchResultRow(m, chats.firstOrNull { it.chat == m.chat }?.title ?: "Chat", com.mura.mesh.data.ChatId.isGroup(m.chat), query) {
                        nav.go(Screen.Chat(m.chat, focus = m.id))
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(Mura.Amber).clickable(role = Role.Button) { fabMenu = true },
                contentAlignment = Alignment.Center,
            ) { Icon(MIcons.Plus, "New chat or group", tint = Mura.OnAmber, modifier = Modifier.size(26.dp)) }
            DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }, containerColor = Mura.Surface2) {
                DropdownMenuItem(text = { Text("Add a contact") }, leadingIcon = { Icon(MIcons.Qr, null) }, onClick = { fabMenu = false; nav.go(Screen.AddContact) })
                DropdownMenuItem(text = { Text("New group") }, leadingIcon = { Icon(MIcons.Group, null) }, onClick = { fabMenu = false; nav.go(Screen.NewGroup) })
                DropdownMenuItem(text = { Text("New status") }, leadingIcon = { Icon(MIcons.Status, null) }, onClick = { fabMenu = false; nav.go(Screen.NewStatus) })
            }
        }

    }
}

@Composable
private fun MeshStatusCard(state: BleTransport.State, linked: Int, nearby: Int, carrying: Int, onClick: () -> Unit) {
    val online by requireEngine().online.state.collectAsState()
    val (title, sub0, color) = when (state) {
        BleTransport.State.RUNNING -> Triple(
            when { linked > 0 -> "Mesh active · $linked linked"; nearby > 0 -> "$nearby phone${if (nearby == 1) "" else "s"} nearby"; else -> "Listening for nearby phones" },
            if (carrying > 0) "Carrying $carrying sealed item${if (carrying == 1) "" else "s"} for others" else "Messages hop phone to phone until they arrive",
            Mura.Amber)
        BleTransport.State.OFF -> Triple("Bluetooth is off", "Your messages are saved and will send when it's on", Mura.Red)
        BleTransport.State.NO_PERMISSION -> Triple("Bluetooth access needed", "Tap to allow nearby devices", Mura.Red)
        BleTransport.State.UNSUPPORTED -> Triple("No Bluetooth LE", "This phone can't join a mesh", Mura.Red)
    }
    val sub = if (online.status == com.mura.mesh.mesh.OnlineTransport.Status.ON) "$sub0 · online via Tor" else sub0
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Mura.Surface)
            .border(1.dp, Mura.Line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(34.dp)) {
            drawCircle(Mura.Line, size.minDimension / 2 - 2, center, style = Stroke(3f))
            drawCircle(color, size.minDimension / 3.4f, center, style = Stroke(3f))
            drawCircle(color, size.minDimension / 9f, center)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
        }
        Icon(MIcons.Back, null, tint = Mura.Muted, modifier = Modifier.size(18.dp).rotate(180f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(c: ChatSummary, linked: Boolean) {
    val nav = LocalNav.current
    val engine = requireEngine()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // Live status from their phone (only if you both share it). Locked chats never show it.
    val livePresence by engine.presence.collectAsState()
    val live = if (c.isGroup || c.locked) null else livePresence[c.chat]
    val tNow = System.currentTimeMillis()
    val liveText = when {
        live == null -> null
        live.typing(tNow) -> "typing…"
        live.recording(tNow) -> "recording audio…"
        else -> null
    }
    fun open() {
        if (c.locked && c.chat !in ChatGate.unlocked) ctx.withAuth("Open locked chat") { ChatGate.unlocked.add(c.chat); nav.go(Screen.Chat(c.chat)) }
        else nav.go(Screen.Chat(c.chat))
    }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { open() }, onLongClick = { menu = true })
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                Avatar(c.chat, c.title, photo = c.avatar, group = c.isGroup)
                if (linked || live?.online == true) Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(Mura.Ink).padding(2.dp).clip(CircleShape).background(Mura.Mint))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (c.pinned) { Spacer(Modifier.width(6.dp)); Icon(MIcons.Pin2, "Pinned", tint = Mura.Muted, modifier = Modifier.size(14.dp)) }
                    if (c.muted) { Spacer(Modifier.width(4.dp)); Icon(MIcons.Mute, "Muted", tint = Mura.Muted, modifier = Modifier.size(14.dp)) }
                    if (c.locked) { Spacer(Modifier.width(4.dp)); Icon(MIcons.Lock, "Locked chat", tint = Mura.Amber, modifier = Modifier.size(14.dp)) }
                    Spacer(Modifier.weight(1f))
                    if (c.state == ContactState.REQUEST) {
                        Text("REQUEST", style = MaterialTheme.typography.labelSmall, color = Mura.Blue,
                            modifier = Modifier.border(1.dp, Mura.Blue, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(timeLabel(c.lastAt), style = MaterialTheme.typography.labelSmall, color = if (c.unread > 0) Mura.Amber else Mura.Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (liveText == null && c.lastOutgoing && c.lastText != null && !c.locked) StatusIcon(c.lastStatus, small = true)
                    if (liveText == "typing…") com.mura.mesh.ui.CipherTyping("typing", Mura.Mint, MaterialTheme.typography.bodyMedium, Modifier.weight(1f))
                    else Text(
                        liveText ?: if (c.locked) "Locked chat" else c.lastText ?: when { c.isGroup -> "${c.members} members"; linked -> "In range — say hi"; else -> "No messages yet" },
                        style = MaterialTheme.typography.bodyMedium, color = if (liveText != null) Mura.Mint else Mura.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (c.unread > 0) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(if (c.muted) Mura.Muted else Mura.Amber), contentAlignment = Alignment.Center) {
                            Text(if (c.unread > 99) "99" else c.unread.toString(), style = MaterialTheme.typography.labelMedium, color = Mura.OnAmber)
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Mura.Surface2) {
            DropdownMenuItem(text = { Text(if (c.pinned) "Unpin" else "Pin to top") }, onClick = { menu = false; engine.db.setPinned(c.chat, !c.pinned) })
            DropdownMenuItem(text = { Text(if (c.muted) "Unmute notifications" else "Mute notifications") }, onClick = { menu = false; engine.db.setMuted(c.chat, !c.muted) })
            DropdownMenuItem(text = { Text("Mark as read") }, onClick = { menu = false; engine.markChatRead(c.chat) })
            DropdownMenuItem(text = { Text(if (c.locked) "Unlock chat" else "Lock chat") }, onClick = {
                menu = false
                val activity = ctx.findActivity() as? com.mura.mesh.ui.MainActivity
                if (c.locked) ctx.withAuth("Unlock this chat") { engine.setLocked(c.chat, false) }
                else if (activity?.canUseLock() == false) ctx.toast("Set up a fingerprint or screen lock in Android settings first")
                else engine.setLocked(c.chat, true)
            })
            DropdownMenuItem(text = { Text(if (c.archived) "Unarchive" else "Archive") }, leadingIcon = { Icon(MIcons.Archive, null) },
                onClick = { menu = false; engine.archive(c.chat, !c.archived); ctx.toast(if (c.archived) "Moved back to chats" else "Archived") })
            DropdownMenuItem(text = { Text("Clear messages") }, onClick = { menu = false; engine.clearChat(c.chat) })
            DropdownMenuItem(text = { Text("Delete chat", color = Mura.Red) }, leadingIcon = { Icon(MIcons.Trash, null, tint = Mura.Red) },
                onClick = { menu = false; confirmDelete = true })
        }
        if (confirmDelete) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = false }, containerColor = Mura.Surface,
                title = { Text("Delete chat with ${c.title}?") },
                text = { Text("Messages, photos and files of this chat are erased from this phone and the chat leaves your list. " +
                    "It comes back if they write again. ${if (c.isGroup) "You stay in the group." else "They stay in your contacts."}",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Soft) },
                confirmButton = { TextButton(onClick = { confirmDelete = false; engine.deleteChatFromList(c.chat) }) { Text("Delete", color = Mura.Red) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Mura.Soft) } },
            )
        }
    }
}

@Composable
fun StatusIcon(status: Int, small: Boolean = false) {
    val s = if (small) 15.dp else 14.dp
    when (status) {
        MsgStatus.WAITING -> Icon(MIcons.Clock, "Waiting for a phone to carry it", tint = Mura.Muted, modifier = Modifier.size(s))
        MsgStatus.CARRIED -> Icon(MIcons.Mesh, "Carried by a nearby phone", tint = Mura.Blue, modifier = Modifier.size(s))
        MsgStatus.DELIVERED -> Icon(MIcons.Checks, "Delivered", tint = Mura.Muted, modifier = Modifier.size(s))
        MsgStatus.READ -> Icon(MIcons.Checks, "Read", tint = Mura.Amber, modifier = Modifier.size(s))
        else -> Icon(MIcons.Warning, "Not delivered", tint = Mura.Red, modifier = Modifier.size(s))
    }
}

@Composable
private fun EmptyChats(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(MIcons.Mesh, null, tint = Mura.Amber, modifier = Modifier.size(40.dp))
        Text("Your mesh starts with one person", style = MaterialTheme.typography.titleMedium)
        Text("Meet a friend, open Add, and scan each other's code. From then on you can message even when you're apart — nearby phones carry it.",
            style = MaterialTheme.typography.bodyMedium, color = Mura.Muted)
        Spacer(Modifier.height(4.dp))
        Text("Add your first contact", style = MaterialTheme.typography.labelLarge, color = Mura.Amber,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onAdd).padding(12.dp))
    }
}


/**
 * Shown only while Android may pause Mura in the background (battery optimisation on). Without a server or
 * Google push, Mura must stay running to receive messages, so this explains it and fixes it in one tap.
 */
@Composable
private fun BackgroundWarning() {
    val ctx = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var exempt by remember { mutableStateOf(com.mura.mesh.mesh.MeshPermissions.ignoringBatteryOptimizations(ctx)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) exempt = com.mura.mesh.mesh.MeshPermissions.ignoringBatteryOptimizations(ctx)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    if (exempt) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Mura.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(MIcons.Battery, null, tint = Mura.Amber, modifier = Modifier.size(18.dp))
            Text("Messages may stop when Mura is closed", style = MaterialTheme.typography.titleSmall)
        }
        Text("Mura has no server, so it must keep running to receive. Allow it to run in the background — and on Xiaomi, Oppo, Realme, Vivo, OnePlus or Huawei phones also turn on Auto-start.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { com.mura.mesh.service.KeepAlive.requestUnrestrictedBattery(ctx) }) { Text("Allow background", color = Mura.Amber) }
            TextButton(onClick = { com.mura.mesh.service.KeepAlive.openAutoStartSettings(ctx) }) { Text("Auto-start", color = Mura.Soft) }
        }
    }
}
