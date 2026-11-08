package com.mura.mesh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.ContactState
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura

/**
 * Everyone you've added, A–Z, kept only on this phone (encrypted). Tap to chat, or call straight from the list.
 * People who added you but whom you haven't added back are listed under "Requests".
 */
@Composable
fun ContactsScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val contacts by rememberQuery(emptyList<ContactRow>()) { engine.db.contactsByState(ContactState.CONTACT) }
    val requests by rememberQuery(emptyList<ContactRow>()) { engine.db.contactsByState(ContactState.REQUEST) }
    var query by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<ContactRow?>(null) }
    val call = rememberCaller()
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) contacts else contacts.filter { it.display.lowercase().contains(q) }

    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        TopBar("Contacts", "${contacts.size} contact${if (contacts.size == 1) "" else "s"} · stored only on this phone", onBack = { nav.back() }) {
            IconBtn(MIcons.Plus, "Add contact", bg = Mura.Surface) { nav.go(Screen.AddContact) }
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (query.isEmpty()) Text("Search contacts", color = Mura.Muted, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(Mura.Amber),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Mura.Text), modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (contacts.isEmpty()) item {
                Text("No contacts yet. Tap + to add someone by QR code or Mura ID.", style = MaterialTheme.typography.bodySmall,
                    color = Mura.Muted, modifier = Modifier.padding(20.dp))
            }
            items(shown, key = { it.pub }) { c ->
                ContactItem(c, onOpen = { nav.go(Screen.Chat(c.pub)) }, onCall = { call(c.pub, c.display) }, onRemove = { removing = c })
            }
            if (requests.isNotEmpty() && q.isEmpty()) {
                item {
                    Text("REQUESTS", style = MaterialTheme.typography.labelSmall, color = Mura.Amber,
                        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
                }
                items(requests, key = { "r" + it.pub }) { c -> ContactItem(c, onOpen = { nav.go(Screen.Chat(c.pub)) }, onCall = null, onRemove = { removing = c }) }
            }
        }
    }
    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = Mura.Surface,
            title = { Text("Remove ${c.display}?") },
            text = {
                Text("Erases them and everything about them from this phone: the chat with all messages, photos and files, call history " +
                    "and recordings, their statuses and the encryption keys for this chat. Your messages they haven't received yet are " +
                    "cancelled, and they can't see your future statuses. Groups you share stay as they are.\n\nThis can't be undone. " +
                    "If they write to you again, they'll appear as a new request.",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
            },
            confirmButton = { TextButton(onClick = { removing = null; engine.removeContact(c.pub) }) { Text("Remove", color = Mura.Red) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContactItem(c: ContactRow, onOpen: () -> Unit, onCall: (() -> Unit)?, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(c.pub, c.display, 46.dp, photo = c.avatar)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.display, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.verified) Icon(MIcons.Shield, "Key verified", tint = Mura.Mint, modifier = Modifier.size(14.dp))
            }
            Text(
                when {
                    c.state == ContactState.REQUEST -> "Added you · tap to add back"
                    c.onion != null -> "Bluetooth & internet (Tor)"
                    else -> "Bluetooth"
                },
                style = MaterialTheme.typography.bodySmall, color = Mura.Muted,
            )
        }
        if (onCall != null) IconBtn(MIcons.Phone, "Call ${c.display}", bg = Mura.Surface, onClick = onCall)
        Box {
            IconBtn(MIcons.More, "More options for ${c.display}") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Mura.Surface2) {
                DropdownMenuItem(text = { Text("Remove contact", color = Mura.Red) }, leadingIcon = { Icon(MIcons.Trash, null, tint = Mura.Red) },
                    onClick = { menu = false; onRemove() })
            }
        }
    }
}
