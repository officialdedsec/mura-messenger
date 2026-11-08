package com.mura.mesh.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mura.mesh.backup.BackupManager
import com.mura.mesh.backup.BackupScheduler
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.ui.ActionRow
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.PrimaryButton
import com.mura.mesh.ui.SectionLabel
import com.mura.mesh.ui.SettingRow
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupScreen() {
    val engine = requireEngine()
    val db = engine.db
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BackupManager.status.collectAsState()
    val folder by rememberQuery("") { db.getSetting(BackupManager.FOLDER, "") }
    val freq by rememberQuery("off") { db.getSetting(BackupManager.FREQUENCY, "off") }
    val videos by rememberQuery(false) { db.getBool(BackupManager.INCLUDE_VIDEOS, false) }
    val hasPw by rememberQuery(false) { BackupManager.hasPassword(db) }
    val last by rememberQuery(0L) { db.getSetting(BackupManager.LAST_TIME, "0").toLongOrNull() ?: 0L }
    val lastSize by rememberQuery(0L) { db.getSetting(BackupManager.LAST_SIZE, "0").toLongOrNull() ?: 0L }
    var pwDialog by remember { mutableStateOf(false) }

    // Pick the folder for backups (kept across restarts with a persistable permission — only to that folder).
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {}
        scope.launch(Dispatchers.IO) {
            // Release the previous folder, if any.
            val old = db.getSetting(BackupManager.FOLDER, "")
            if (old.isNotEmpty() && old != uri.toString()) try {
                ctx.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            db.setSetting(BackupManager.FOLDER, uri.toString())
            BackupScheduler.ensureScheduled(ctx)
        }
        ctx.toast("Backup folder chosen")
    }
    // One-off "Save a copy…" anywhere.
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            BackupManager.report(BackupManager.Status(true, "Encrypting chats", 0f))
            val err = withContext(Dispatchers.IO) {
                try {
                    val size = BackupManager.exportTo(ctx, uri) { BackupManager.report(BackupManager.Status(true, "Encrypting chats", it)) }
                    db.setSetting(BackupManager.LAST_TIME, System.currentTimeMillis().toString())
                    db.setSetting(BackupManager.LAST_SIZE, size.toString())
                    null
                } catch (e: Exception) { e.message ?: "Backup failed" }
            }
            BackupManager.report(if (err == null) BackupManager.Status(false, "Backed up") else BackupManager.Status(false, error = err))
            ctx.toast(err ?: "Encrypted backup saved")
        }
    }
    val folderName = remember(folder) {
        if (folder.isEmpty()) "" else try {
            android.net.Uri.decode(android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(folder))).substringAfter(':').ifBlank { "Selected folder" }
        } catch (e: Exception) { "Selected folder" }
    }

    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar("Chat backup", onBack = { nav.back() })
        Text(
            "Back up your chats, photos and documents to an encrypted file in a folder you choose — this phone, an SD card or USB drive, " +
                "or any cloud app's folder. It's encrypted on this phone with your backup password; without Mura and that password it's unreadable. " +
                "No Google account, no server.",
            style = MaterialTheme.typography.bodyMedium, color = Mura.Soft, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Column(Modifier.padding(20.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(18.dp)).padding(16.dp)) {
            Text("Last backup", style = MaterialTheme.typography.titleSmall)
            Text(if (last > 0) "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))} · ${SecureMedia.humanSize(lastSize)}" else "Never",
                style = MaterialTheme.typography.bodyMedium, color = Mura.Soft)
            if (status.running) {
                Spacer(Modifier.height(10.dp))
                Text(status.phase, style = MaterialTheme.typography.bodySmall, color = Mura.Amber)
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Mura.Amber, trackColor = Mura.Line)
            }
            status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mura.Red, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (status.running) "Backing up…" else "Back up now", Modifier.fillMaxWidth(), enabled = !status.running && hasPw && folder.isNotBlank()) {
                BackupScheduler.backupNow(ctx)
                BackupManager.report(BackupManager.Status(true, "Starting…"))
            }
            if (!hasPw || folder.isBlank()) Text("Set a backup password and choose a backup folder first.", style = MaterialTheme.typography.bodySmall,
                color = Mura.Muted, modifier = Modifier.padding(top = 6.dp))
        }

        SectionLabel("Where backups go")
        ActionRow(MIcons.File, if (folder.isBlank()) "Choose backup folder" else folderName,
            if (folder.isBlank()) "Automatic backups and \"Back up now\" are saved here. Mura keeps the newest 3." else "Tap to choose another folder · newest 3 backups are kept") {
            try { pickFolder.launch(null) } catch (e: Exception) { ctx.toast("No file manager on this phone") }
        }
        ActionRow(MIcons.Download, "Save a copy…", "Save one encrypted backup file anywhere you like") {
            if (!hasPw) { ctx.toast("Set a backup password first"); return@ActionRow }
            try { saveCopy.launch("Mura-backup.mura") } catch (e: Exception) { ctx.toast("No file manager on this phone") }
        }

        SectionLabel("Encryption")
        ActionRow(MIcons.Key, if (hasPw) "Change backup password" else "Set backup password", "At least 8 characters. Without it, the backup can't be opened — not even by us.") { pwDialog = true }

        SectionLabel("Automatic backup")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            val options = listOf("off" to "Off", "daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly", "quarterly" to "Every 3 months", "yearly" to "Yearly")
            for (row in options.chunked(3)) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((v, label) in row) {
                        val on = freq == v
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Mura.OnAmber else Mura.Soft,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Mura.Amber else Mura.Surface)
                                .clickable(role = Role.RadioButton) {
                                    if (v != "off" && !hasPw) { ctx.toast("Set a backup password first"); return@clickable }
                                    if (v != "off" && folder.isBlank()) { ctx.toast("Choose a backup folder first"); return@clickable }
                                    scope.launch(Dispatchers.IO) { db.setSetting(BackupManager.FREQUENCY, v); BackupScheduler.ensureScheduled(ctx) }
                                }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        SettingRow(MIcons.Video, "Include videos", "Videos can make backups much larger", videos) { db.setBool(BackupManager.INCLUDE_VIDEOS, it) }
        Text("To restore on a new phone: install Mura, tap \"Restore from backup file\" on the welcome screen, pick the file and enter your password.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        Spacer(Modifier.height(40.dp))
    }

    if (pwDialog) {
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwDialog = false }, containerColor = Mura.Surface,
            title = { Text("Backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("You'll need this to restore on a new phone. Write it down — it can't be recovered.", style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                    OutlinedTextField(a, { a = it }, singleLine = true, label = { Text("Password") }, colors = fieldColors(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(b, { b = it }, singleLine = true, label = { Text("Repeat password") }, colors = fieldColors(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        a.length < 8 -> ctx.toast("Use at least 8 characters")
                        a != b -> ctx.toast("The passwords don't match")
                        else -> {
                            pwDialog = false
                            val pw = a.toCharArray()
                            scope.launch(Dispatchers.IO) { BackupManager.setPassword(db, pw); BackupScheduler.ensureScheduled(ctx) }
                        }
                    }
                }) { Text("Save", color = Mura.Amber) }
            },
            dismissButton = { TextButton(onClick = { pwDialog = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }

}
