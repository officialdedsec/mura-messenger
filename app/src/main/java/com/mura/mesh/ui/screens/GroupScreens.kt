package com.mura.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mura.mesh.core.Payload
import com.mura.mesh.data.ChatId
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.GroupRow
import com.mura.mesh.data.MemberRow
import com.mura.mesh.ui.ActionRow
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.PrimaryButton
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.SectionLabel
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura

/** Pick contacts, name the group, create. */
@Composable
fun NewGroupScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val all by rememberQuery(emptyList<ContactRow>()) { engine.db.contactsByState(com.mura.mesh.data.ContactState.CONTACT) }
    val selected = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        TopBar("New group", "${selected.size} of ${Payload.MAX_GROUP - 1} selected", onBack = { nav.back() })
        OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Group name") }, colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Text("Messages are encrypted once with a shared group key and signed by each sender. Up to 200 people.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (all.isEmpty()) item {
                Text("Add contacts first — scan each other's code in person.", color = Mura.Muted, modifier = Modifier.padding(20.dp))
            }
            items(all, key = { it.pub }) { c ->
                val ready = c.edpub != null
                val on = c.pub in selected
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = ready, role = Role.Checkbox) {
                        if (on) selected.remove(c.pub) else if (selected.size < Payload.MAX_GROUP - 1) selected.add(c.pub)
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(c.pub, c.display, 44.dp, photo = c.avatar)
                    Column(Modifier.weight(1f)) {
                        Text(c.display, style = MaterialTheme.typography.titleSmall)
                        if (!ready) Text("Needs to be in range once to exchange keys", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                    }
                    Checkbox(checked = on, onCheckedChange = null, enabled = ready,
                        colors = CheckboxDefaults.colors(checkedColor = Mura.Amber, checkmarkColor = Mura.OnAmber, uncheckedColor = Mura.Line))
                }
            }
        }
        PrimaryButton(if (creating) "Creating…" else "Create group", Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
            enabled = !creating && name.isNotBlank() && selected.isNotEmpty()) {
            creating = true
            engine.createGroup(name, selected.toList()) { gid -> nav.replace(Screen.Chat(ChatId.group(gid))) }
        }
    }
}

private data class GroupInfo(val group: GroupRow?, val members: List<MemberRow>, val iAmAdmin: Boolean)

@Composable
fun GroupInfoScreen(gid: String) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val info by rememberQuery(GroupInfo(null, emptyList(), false), gid) {
        GroupInfo(engine.db.group(gid), engine.db.members(gid), engine.isAdmin(gid))
    }
    val g = info.group
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val me = engine.identity.publicHex
    val active = g != null && !g.removed && !g.dissolved

    LazyColumn(Modifier.fillMaxSize().background(Mura.Ink), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TopBar("Group info", onBack = { nav.back() }) }
        item {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(ChatId.group(gid), g?.name ?: "", 96.dp, group = true, photo = g?.avatar)
                Spacer(Modifier.height(12.dp))
                Text(g?.name ?: "", style = MaterialTheme.typography.headlineMedium)
                Text("${info.members.size} members · end-to-end encrypted", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            }
        }
        item { ActionRow(MIcons.Image, "Media, links & docs", "Everything shared in this group") { nav.go(Screen.ChatMedia(com.mura.mesh.data.ChatId.group(gid))) } }
        if (active && info.iAmAdmin) {
            item { ActionRow(MIcons.Plus, "Add members", "Up to ${Payload.MAX_GROUP} people") { nav.go(Screen.AddMembers(gid)) } }
            item { ActionRow(MIcons.Edit, "Rename group") { rename = true } }
        }
        item { SectionLabel("Members") }
        items(info.members, key = { it.pub }) { m -> MemberRowView(gid, m, m.pub == me, info.iAmAdmin && active) { t, a -> confirm = t to a } }
        item { SectionLabel("Danger zone") }
        if (active) item {
            ActionRow(MIcons.Exit, "Leave group", "Admins switch to a new key you won't have", tint = Mura.Red) {
                confirm = "Leave “${g?.name}”?" to { engine.leaveGroup(gid) }
            }
        }
        if (active && info.iAmAdmin) item {
            ActionRow(MIcons.Trash, "Delete group for everyone", "Closes the group on every member's phone", tint = Mura.Red) {
                confirm = "Delete “${g?.name}” for everyone?" to { engine.dissolveGroup(gid) }
            }
        }
        if (!active) item {
            ActionRow(MIcons.Trash, "Remove from this phone", "Erase this group and its messages here", tint = Mura.Red) {
                confirm = "Erase this group from your phone?" to { engine.deleteGroupLocally(gid); nav.reset(Screen.Chats) }
            }
        }
    }

    if (rename && g != null) {
        var name by remember { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { rename = false }, containerColor = Mura.Surface,
            title = { Text("Rename group") },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, colors = fieldColors()) },
            confirmButton = { TextButton(onClick = { rename = false; engine.renameGroup(gid, name) }) { Text("Save", color = Mura.Amber) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
    confirm?.let { (title, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = Mura.Surface,
            title = { Text(title) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("Confirm", color = Mura.Red) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

@Composable
private fun MemberRowView(gid: String, m: MemberRow, isMe: Boolean, canManage: Boolean, ask: (String, () -> Unit) -> Unit) {
    val engine = requireEngine()
    val nav = LocalNav.current
    var menu by remember { mutableStateOf(false) }
    val name = if (isMe) "You" else engine.db.memberName(gid, m.pub)
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { if (!isMe) menu = true }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(m.pub, name, 44.dp)
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (m.admin) Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Mura.AmberDeep).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(MIcons.Crown, null, tint = Mura.Amber, modifier = Modifier.size(12.dp))
                Text(" Admin", style = MaterialTheme.typography.labelSmall, color = Mura.Amber)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Mura.Surface2) {
            val contact = engine.db.contact(m.pub)
            if (contact != null) DropdownMenuItem(text = { Text("Message $name") }, onClick = { menu = false; nav.go(Screen.Chat(m.pub)) })
            if (canManage) {
                DropdownMenuItem(text = { Text(if (m.admin) "Dismiss as admin" else "Make admin") }, onClick = { menu = false; engine.setAdmin(gid, m.pub, !m.admin) })
                DropdownMenuItem(text = { Text("Remove from group", color = Mura.Red) }, onClick = {
                    menu = false
                    ask("Remove $name? The group switches to a new key they won't receive.") { engine.removeMember(gid, m.pub) }
                })
            }
        }
    }
}

@Composable
fun AddMembersScreen(gid: String) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val data by rememberQuery(Pair(emptyList<ContactRow>(), emptySet<String>()), gid) {
        engine.groupEligible() to engine.db.members(gid).map { it.pub }.toSet()
    }
    val (eligible, existing) = data
    val selected = remember { mutableStateListOf<String>() }
    val room = Payload.MAX_GROUP - existing.size
    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        TopBar("Add members", "$room spot${if (room == 1) "" else "s"} left", onBack = { nav.back() })
        LazyColumn(Modifier.weight(1f)) {
            items(eligible.filter { it.pub !in existing }, key = { it.pub }) { c ->
                val on = c.pub in selected
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { if (on) selected.remove(c.pub) else if (selected.size < room) selected.add(c.pub) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(c.pub, c.display, 44.dp, photo = c.avatar)
                    Text(c.display, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Mura.Amber else Mura.Surface2), contentAlignment = Alignment.Center) {
                        if (on) Icon(MIcons.Check, null, tint = Mura.OnAmber, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        PrimaryButton("Add ${selected.size}", Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), enabled = selected.isNotEmpty()) {
            engine.addMembers(gid, selected.toList()); nav.back()
        }
    }
}
