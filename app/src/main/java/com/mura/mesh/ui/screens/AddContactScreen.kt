package com.mura.mesh.ui.screens

import kotlinx.coroutines.launch
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.ContactState
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.PrimaryButton
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import com.mura.mesh.core.Onion
import java.net.URLDecoder
import java.net.URLEncoder

private data class Candidate(val pubHex: String, val name: String, val scanned: Boolean, val edPub: ByteArray? = null, val onion: String? = null)

private fun isHex64(s: String) = s.length == 64 && s.all { it in "0123456789abcdef" }

/**
 * Contact codes:
 *   mura:3:<encryption key hex>:<signing key hex>:<onion address or empty>:<url-encoded name>
 *   mura:2:<encryption key hex>:<signing key hex>:<url-encoded name>      (older versions, still accepted)
 * The onion address lets you reach each other over the internet later. It's only in this QR code
 * (shown in person) and in your end-to-end encrypted profile — never published anywhere.
 */
private fun parseCode(s: String): Candidate? {
    val parts = s.trim().split(":")
    if (parts.size < 4 || parts[0] != "mura" || (parts[1] != "2" && parts[1] != "3")) return null
    val pub = parts[2].lowercase(); val ed = parts[3].lowercase()
    if (!isHex64(pub) || !isHex64(ed)) return null
    val v3 = parts[1] == "3"
    val onion = if (v3 && parts.size > 4) parts[4].lowercase().takeIf { Onion.valid(it) } else null
    val nameParts = parts.drop(if (v3) 5 else 4)
    val name = if (nameParts.isNotEmpty()) try { URLDecoder.decode(nameParts.joinToString(":"), "UTF-8") } catch (e: Exception) { "" } else ""
    return Candidate(pub, name.take(32), scanned = true, edPub = ed.hexToBytes(), onion = onion)
}

@Composable
fun AddContactScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val connected by engine.connected.collectAsState()
    val contacts by rememberQuery(emptyList<ContactRow>()) { engine.db.contacts() }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var idInput by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val online by engine.online.state.collectAsState()
    val myCode = remember(online.status) {
        "mura:3:${engine.identity.publicHex}:${engine.identity.edPub.toHex()}:${engine.online.onionAddress ?: ""}:" +
            URLEncoder.encode(engine.identity.name, "UTF-8")
    }
    val qr = remember(myCode) {
        try {
            BarcodeEncoder().encodeBitmap(myCode, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 1))
        } catch (e: Exception) { null as Bitmap? }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val c = parseCode(text)
        when {
            c == null -> ctx.toast("That isn't a Mura code")
            c.pubHex == engine.identity.publicHex -> ctx.toast("That's your own code")
            else -> candidate = c
        }
    }

    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar("Add a contact", "Meet in person · scan each other", onBack = { nav.back() })

        Column(
            Modifier.padding(20.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(24.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (qr != null) {
                Box(Modifier.size(240.dp).clip(RoundedCornerShape(18.dp)).background(androidx.compose.ui.graphics.Color.White).padding(10.dp)) {
                    Image(qr.asImageBitmap(), "Your contact code", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                }
            }
            Text(engine.identity.name, style = MaterialTheme.typography.titleLarge)
            Text(engine.identity.fingerprint, fontFamily = Mura.Mono, style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
            Text("Let your friend scan this from Mura › Add. Then scan theirs.", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            if (engine.online.onionAddress != null) {
                Text("Includes your private internet address, so you can also reach each other over Tor when you're apart.",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        PrimaryButton("Scan their code", Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan a Mura contact code")
                .setBeepEnabled(false).setOrientationLocked(false))
        }

        // ---------------- Not together? Mura ID: add someone from anywhere, through Tor — no server, no search directory.
        Text("NOT TOGETHER? USE A MURA ID", style = MaterialTheme.typography.labelSmall, color = Mura.Amber,
            modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp))
        val myId = online.let { engine.online.onionAddress }
        Column(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Your Mura ID", style = MaterialTheme.typography.titleSmall)
            Text(myId?.let { com.mura.mesh.core.MuraId.pretty(it) } ?: "Being created…", fontFamily = Mura.Mono,
                style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Copy", color = Mura.Amber, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    myId?.let { copySecurely(ctx, com.mura.mesh.core.MuraId.fromOnion(it), true); ctx.toast("Copied — the clipboard clears itself in a minute") }
                }.padding(6.dp))
                Text("Share", color = Mura.Amber, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    val id = myId ?: return@clickable
                    com.mura.mesh.ui.Exposure.request = com.mura.mesh.ui.Exposure.Request("Share your Mura ID?",
                        "Anyone who gets your Mura ID can send you contact requests through Tor (you can block them, or reset your ID). " +
                            "The app you share it with will see it. It reveals nothing else — not your name, number or location.") {
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,
                                "Add me on Mura (private, serverless messenger): mura:${com.mura.mesh.core.MuraId.fromOnion(id)}"), "Share Mura ID"))
                    }
                }.padding(6.dp))
                Text("Reset", color = Mura.Soft, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    confirmReset = true
                }.padding(6.dp))
            }
            Text("Give it to a friend any way you like. They add you from their Mura while you're both online; you'll see a request. " +
                "There's no way to search people by name or number — that would need a server that knows everyone.",
                style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(idInput, { idInput = it.take(300) }, label = { Text("Their Mura ID (or invite code)") }, minLines = 2,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(), enabled = !adding,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mura.Mono))
            PrimaryButton(if (adding) "Finding them through Tor…" else "Add by Mura ID", Modifier.fillMaxWidth(), enabled = !adding && idInput.isNotBlank()) {
                // A full invite code (same as the QR) works too.
                parseCode(idInput.trim())?.let { c ->
                    if (c.pubHex == engine.identity.publicHex) ctx.toast("That's your own code") else candidate = c.copy(scanned = false)
                    return@PrimaryButton
                }
                adding = true
                scope.launch {
                    try {
                        val pub = engine.addById(idInput)
                        idInput = ""
                        ctx.toast("Added. Compare safety codes in the chat when you can, to be sure it's really them.")
                        nav.replace(Screen.Chat(pub))
                    } catch (e: Exception) {
                        ctx.toast(e.message ?: "Couldn't add")
                    } finally { adding = false }
                }
            }
        }

        val strangers = connected.filter { p -> contacts.none { it.pub == p.pubHex && it.state == ContactState.CONTACT } }
        Text("NEARBY ON MURA", style = MaterialTheme.typography.labelSmall, color = Mura.Amber, modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp))
        if (strangers.isEmpty()) {
            Text("Nobody new in range. Phones appear here when Mura is open nearby and they allow their name to be seen.",
                style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        for (p in strangers) {
            Row(
                Modifier.fillMaxWidth().clickable { candidate = Candidate(p.pubHex, p.name, scanned = false) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(p.pubHex, p.name.ifBlank { "?" }, 44.dp, ring = Mura.Mint)
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { "Hidden name" }, style = MaterialTheme.typography.titleSmall)
                    Text("In range now · compare safety codes to add", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                }
                Text("Add", color = Mura.Amber, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false }, containerColor = Mura.Surface,
            title = { Text("Get a new Mura ID?") },
            text = { Text("Your current ID stops working right away, so anyone who only has it can't reach you any more. " +
                "Your contacts keep reaching you — they get the new one automatically.", style = MaterialTheme.typography.bodySmall, color = Mura.Soft) },
            confirmButton = { TextButton(onClick = { confirmReset = false; engine.online.resetIdentity(); ctx.toast("New Mura ID created") }) { Text("Reset", color = Mura.Red) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }

    candidate?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        val code = remember(c) { Crypto.safetyCode(engine.identity.publicKey, c.pubHex.hexToBytes()) }
        AlertDialog(
            onDismissRequest = { candidate = null }, containerColor = Mura.Surface,
            title = { Text(if (c.scanned) "Add ${c.name.ifBlank { "contact" }}?" else "Add ${c.name.ifBlank { "contact" }}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it.take(32) }, singleLine = true, label = { Text("Name") }, colors = fieldColors())
                    Text(if (c.scanned) "You scanned their code in person, so this key is verified."
                        else "Ask them to open your chat's safety code. These numbers must match exactly:",
                        style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                    Text(code.chunked(18).joinToString("\n"), fontFamily = Mura.Mono, style = MaterialTheme.typography.titleSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    engine.addContact(c.pubHex, name.ifBlank { "Friend" }, verified = c.scanned, edPub = c.edPub, onion = c.onion)
                    candidate = null
                    nav.replace(Screen.Chat(c.pubHex))
                }) { Text(if (c.scanned) "Add" else "Codes match — add", color = Mura.Amber) }
            },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}
