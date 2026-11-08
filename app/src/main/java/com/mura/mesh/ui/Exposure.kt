package com.mura.mesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.mura.mesh.ui.theme.Mura

/**
 * Everything Mura stores stays encrypted inside the app. Whenever something would leave that protection —
 * saving a readable copy, opening a file in another app, opening a link or a map outside Tor — the user
 * is warned first and has to confirm.
 */
object Exposure {
    class Request(val title: String, val body: String, val action: () -> Unit)
    var request by mutableStateOf<Request?>(null)

    private const val FILE_RISK = "This makes a readable copy outside Mura's encrypted storage. Other apps, your gallery, cloud photo " +
        "backup and anyone who uses this phone may be able to see it, and the file itself can identify you or the sender. " +
        "Mura can't protect, track or delete it afterwards."

    fun confirmSave(action: () -> Unit) { request = Request("Save a readable copy?", FILE_RISK, action) }
    fun confirmOpenOutside(action: () -> Unit) {
        request = Request("Open in another app?", "$FILE_RISK The other app may also keep its own copy.", action)
    }
    fun confirmLink(action: () -> Unit) {
        request = Request("Open outside Mura?", "Your browser or maps app connects directly, not through Tor — the website or map " +
            "service will see your IP address and roughly where you are. Mura can't protect what happens there.", action)
    }
}

@Composable
fun ExposureDialog() {
    val r = Exposure.request ?: return
    AlertDialog(
        onDismissRequest = { Exposure.request = null }, containerColor = Mura.Surface,
        icon = { Icon(MIcons.Shield, null, tint = Mura.Red) },
        title = { Text(r.title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(r.body, style = MaterialTheme.typography.bodySmall, color = Mura.Soft) } },
        confirmButton = { TextButton(onClick = { Exposure.request = null; r.action() }) { Text("I understand, continue", color = Mura.Red) } },
        dismissButton = { TextButton(onClick = { Exposure.request = null }) { Text("Keep it in Mura", color = Mura.Amber) } },
    )
}
