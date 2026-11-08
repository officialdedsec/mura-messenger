package com.mura.mesh.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mura.mesh.MuraApp
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.toHex
import com.mura.mesh.identity.IdentityStore
import com.mura.mesh.identity.TinkCurve
import com.mura.mesh.mesh.MeshPermissions
import com.mura.mesh.service.MeshService
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.PrimaryButton
import com.mura.mesh.ui.GhostButton
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf(false) }
    var driveRestore by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Preview key so the user sees their identity pattern before committing.
    val previewKey = remember { TinkCurve.generatePrivate() }
    val previewPub = remember(previewKey) { TinkCurve.publicFrom(previewKey) }

    fun finish(restoredKey: ByteArray?) {
        busy = true
        scope.launch {
            val id = withContext(Dispatchers.IO) { IdentityStore.create(ctx, name.ifBlank { "Me" }, restoredKey ?: previewKey) }
            MuraApp.instance.initEngine(id)
            if (MeshPermissions.bluetoothGranted(ctx)) {
                MeshService.start(ctx)
                nav.reset(Screen.Chats)
            } else nav.reset(Screen.Permissions)
        }
    }

    if (driveRestore) {
        FileRestore(onCancel = { driveRestore = false }) {
            if (MeshPermissions.bluetoothGranted(ctx)) { MeshService.start(ctx); nav.reset(Screen.Chats) } else nav.reset(Screen.Permissions)
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(Mura.Ink).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (restoring) "RESTORE" else "STEP 1 OF 2", style = MaterialTheme.typography.labelSmall, color = Mura.Amber)
            Text(if (restoring) "Bring your\nidentity back." else "No number.\nNo email.\nJust a key.", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (restoring) "Type the backup code you saved. Your chats stay on the old phone; your identity and contacts' trust come with you."
                else "Your identity is a key pair made on this phone. It never leaves it, and nobody can sign you up with someone else's number.",
                style = MaterialTheme.typography.bodyMedium, color = Mura.Soft,
            )
        }

        if (!restoring) {
            Row(
                Modifier.fillMaxWidth().background(Mura.Surface, RoundedCornerShape(20.dp)).border(1.dp, Mura.Line, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IdentityPattern(previewPub)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your key fingerprint", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                    val fp = Crypto.sha256(previewPub).copyOf(8).toHex().uppercase().chunked(4)
                    Text(fp.take(2).joinToString(" ") + "\n" + fp.drop(2).joinToString(" "),
                        fontFamily = Mura.Mono, color = Mura.Text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            NameField(name) { name = it.take(32) }
            Hint(MIcons.Lock, "Every message, photo, voice note and call is end-to-end encrypted before it leaves your phone.")
            Hint(MIcons.Scan, "Add friends by scanning each other's QR code in person — the strongest way to verify it's really them.")
            Hint(MIcons.Mesh, "Phones nearby carry sealed messages for each other. They can never read what they carry.")
        } else {
            OutlinedTextField(
                value = code, onValueChange = { code = it.take(80) }, label = { Text("Backup code") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mura.Mono),
                colors = fieldColors(),
            )
            NameField(name) { name = it.take(32) }
        }

        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!restoring) {
                PrimaryButton("Create my identity", Modifier.fillMaxWidth(), enabled = !busy && name.isNotBlank()) { finish(null) }
                GhostButton("Restore from backup file", Modifier.fillMaxWidth(), color = Mura.Amber) { driveRestore = true }
                GhostButton("Restore identity from backup code", Modifier.fillMaxWidth()) { restoring = true }
            } else {
                PrimaryButton("Restore identity", Modifier.fillMaxWidth(), enabled = !busy && code.isNotBlank()) {
                    val key = IdentityStore.parseBackupCode(code)
                    if (key == null) ctx.toast("That code doesn't check out — please look for a typo.") else finish(key)
                }
                GhostButton("Create a new identity instead", Modifier.fillMaxWidth()) { restoring = false }
            }
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            label = { Text("Display name") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            colors = fieldColors(),
            shape = RoundedCornerShape(14.dp),
        )
        Text("Shown to people you add, and to phones nearby if you allow it in Settings.", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Mura.Amber, unfocusedBorderColor = Mura.Line, focusedLabelColor = Mura.Amber,
    unfocusedLabelColor = Mura.Muted, cursorColor = Mura.Amber, focusedContainerColor = Mura.Surface, unfocusedContainerColor = Mura.Surface,
    focusedTextColor = Mura.Text, unfocusedTextColor = Mura.Text,
)

@Composable
private fun Hint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = Mura.Mint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Mura.Soft)
    }
}

/** A 3x3 pattern derived from the public key: a quick visual way to recognise an identity. */
@Composable
fun IdentityPattern(pub: ByteArray, sizeDp: Int = 72) {
    val h = Crypto.sha256(pub)
    Canvas(Modifier.size(sizeDp.dp)) {
        drawRoundRect(Mura.Surface2, cornerRadius = CornerRadius(size.width * 0.25f))
        val cell = size.width / 6f
        for (row in 0 until 3) for (col in 0 until 3) {
            val bits = h[row * 3 + col].toInt() and 0xff
            if (bits and 1 == 0 && !(row == 1 && col == 1)) continue
            val color = if (bits and 2 == 0) Mura.Amber else Mura.Blue
            val round = if (bits and 4 == 0) cell * 0.25f else cell * 0.5f
            drawRoundRect(color, topLeft = Offset(cell + col * cell * 1.5f, cell + row * cell * 1.5f), size = Size(cell, cell), cornerRadius = CornerRadius(round))
        }
    }
}


/** Restore an encrypted backup file: browse to it, then enter the backup password. Only Mura can open it. */
@Composable
private fun FileRestore(onCancel: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("Pick your Mura backup file (it ends in .mura) — from this phone, an SD card, a USB drive or a cloud app.") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var file by remember { mutableStateOf<java.io.File?>(null) }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            phase = "Reading the backup…"
            val result = withContext(Dispatchers.IO) {
                try { com.mura.mesh.backup.BackupManager.importFrom(ctx, uri) to null } catch (e: Exception) { null to (e.message ?: "Couldn't read that file") }
            }
            file = result.first
            phase = result.second ?: "Enter your backup password to unlock it."
            working = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Mura.Ink).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("RESTORE FROM BACKUP FILE", style = MaterialTheme.typography.labelSmall, color = Mura.Amber)
        Text("Welcome back.", style = MaterialTheme.typography.headlineMedium)
        Text(phase, style = MaterialTheme.typography.bodyMedium, color = Mura.Soft)
        progress?.let { androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Mura.Amber, trackColor = Mura.Line) }
        if (file == null) {
            PrimaryButton(if (working) "Working…" else "Choose backup file", Modifier.fillMaxWidth(), enabled = !working) {
                try { pick.launch(arrayOf("*/*")) } catch (e: Exception) { ctx.toast("No file manager on this phone") }
            }
        } else {
            OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("Backup password") }, colors = fieldColors(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            PrimaryButton(if (working) "Restoring…" else "Restore", Modifier.fillMaxWidth(), enabled = !working && password.isNotEmpty()) {
                val f = file ?: return@PrimaryButton
                val pw = password.toCharArray()
                working = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            com.mura.mesh.backup.BackupManager.peekName(f, pw.copyOf())
                            com.mura.mesh.backup.BackupManager.restore(ctx, f, pw) { progress = it }
                            true
                        } catch (e: Exception) { phase = e.message ?: "Couldn't restore"; false }
                    }
                    working = false; progress = null
                    if (ok) onDone()
                }
            }
        }
        GhostButton("Back", Modifier.fillMaxWidth()) { file?.delete(); onCancel() }
    }
}
