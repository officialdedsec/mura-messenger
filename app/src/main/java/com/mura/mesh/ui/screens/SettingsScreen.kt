package com.mura.mesh.ui.screens

import com.mura.mesh.MuraApp
import com.mura.mesh.mesh.Bridges
import com.mura.mesh.ui.Disguise
import com.mura.mesh.ui.LocalInTab
import com.mura.mesh.mesh.OnlineTransport
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.horizontalScroll
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.mura.mesh.ui.withAuth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.ContactState
import com.mura.mesh.identity.IdentityStore
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.mesh.Settings
import com.mura.mesh.ui.ActionRow
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.MainActivity
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.SectionLabel
import com.mura.mesh.ui.SecureWindow
import com.mura.mesh.ui.SettingRow
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.findActivity
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val engine = requireEngine()
    val db = engine.db
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    var showBackupCode by remember { mutableStateOf(false) }
    val autoMb by rememberQuery("50") { db.getSetting(Settings.AUTO_DOWNLOAD_MB, "50") }
    val photoQ by rememberQuery("STANDARD") { db.getSetting(Settings.PHOTO_QUALITY, "STANDARD") }
    val videoQ by rememberQuery("STANDARD") { db.getSetting(Settings.VIDEO_QUALITY, "STANDARD") }
    val avatar by rememberQuery<ByteArray?>(null) { engine.myAvatar() }

    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar(if (LocalInTab.current) "Me" else "Settings", onBack = if (LocalInTab.current) null else ({ nav.back() }))

        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(20.dp)).clickable { nav.go(Screen.Profile) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(engine.identity.publicHex, engine.identity.name, 60.dp, photo = avatar)
            Column(Modifier.weight(1f)) {
                Text(engine.identity.name, style = MaterialTheme.typography.titleLarge)
                Text(engine.identity.fingerprint, fontFamily = Mura.Mono, style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                Text("Profile photo and name", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            }
        }

        ActionRow(MIcons.Star, "Starred messages", "Messages you starred · kept only on this phone") { nav.go(Screen.Starred) }
        ActionRow(MIcons.Palette, "Appearance", "Light, dark or black theme · accent colour · chat wallpaper") { nav.go(Screen.Appearance()) }
        ActionRow(MIcons.Translate, "Translation", "Translate messages on this phone — never sent anywhere") { nav.go(Screen.Translation) }

        SectionLabel("Account")
        ActionRow(MIcons.Lock, "Privacy", "App lock, screenshots, read receipts, who can reach you") { nav.go(Screen.Privacy) }
        ActionRow(MIcons.Cloud, "Chat backup", "Encrypted backup file in a folder you choose") { nav.go(Screen.Backup) }
        ActionRow(MIcons.Ban, "Blocked contacts") { nav.go(Screen.Blocked) }
        ActionRow(MIcons.Key, "Identity backup code", "Move your identity to a new phone without Google") { showBackupCode = true }

        SectionLabel("Internet (Tor)")
        InternetSection()

        SectionLabel("Compression and downloads")
        Chips("Photos", listOf("STANDARD" to "Standard · smallest", "HD" to "HD"), photoQ) { db.setSetting(Settings.PHOTO_QUALITY, it) }
        Chips("Videos", listOf("DATA_SAVER" to "480p · tiny", "STANDARD" to "720p", "HD" to "1080p"), videoQ) { db.setSetting(Settings.VIDEO_QUALITY, it) }
        Chips("Auto-download from contacts", listOf("10" to "10 MB", "50" to "50 MB", "200" to "200 MB", "1000" to "Any"), autoMb) { db.setSetting(Settings.AUTO_DOWNLOAD_MB, it) }
        Text("Documents are compressed without any loss; photos and videos are re-encoded smaller, so they travel faster over Bluetooth.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        SectionLabel("Always reachable")
        Column(Modifier.padding(horizontal = 20.dp)) { PermissionChecklist(tick) { tick++ } }
        ActionRow(MIcons.Battery, "Phone maker auto-start", "Xiaomi, Oppo, Realme, Vivo, OnePlus, Huawei and Samsung stop background apps unless Mura is allowed to auto-start. Opens that setting directly.") {
            com.mura.mesh.service.KeepAlive.openAutoStartSettings(ctx)
        }

        SectionLabel("About us")
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Mura.AmberDeep), contentAlignment = Alignment.Center) {
                    Icon(MIcons.Shield, null, tint = Mura.Amber)
                }
                Column {
                    Text("DedSec Cyber Security", style = MaterialTheme.typography.titleMedium)
                    Text("Research Institute", style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                }
            }
            Text("Mura is made by DedSec Cyber Security Research Institute — only for privacy lovers. " +
                "No accounts, no phone numbers, no servers, no ads and no tracking. Your messages belong to you.",
                style = MaterialTheme.typography.bodyMedium, color = Mura.Text)
            // Website: opens in the phone's own browser (Mura itself never connects to it).
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dedseec.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { ctx.toast("No browser on this phone") }
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(MIcons.Globe, null, tint = Mura.Amber, modifier = Modifier.size(18.dp))
                Text("dedseec.com", style = MaterialTheme.typography.titleSmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), color = Mura.Amber)
            }
        }
        SectionLabel("Disclaimer")
        Text(
            "Mura is a serverless messaging app. DedSec Cyber Security Research Institute runs no servers and does not receive, " +
                "store, hold, read or have any access to your messages, calls, files, contacts, keys or any other data — everything " +
                "stays encrypted on users' own phones and travels directly between them. Because we hold nothing, we cannot recover, " +
                "restore, monitor, remove or hand over anything.\n\n" +
                "You are solely responsible for how you use Mura, for what you send and receive, and for complying with the laws of " +
                "your country. Mura is provided \"as is\", without any warranty; DedSec Cyber Security Research Institute is not liable " +
                "for any loss, damage or consequence arising from its use, from content exchanged between users, or from third-party " +
                "networks (such as Tor or Bluetooth) it relies on.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).background(Mura.Surface, RoundedCornerShape(16.dp)).padding(16.dp),
        )

        SectionLabel("About")
        Text("Mura 2.6 · Bluetooth mesh messenger, plus serverless, Tor-only internet links. No Mura server exists. " +
            "Includes Tor (tor-android, BSD licence). Fonts: Unbounded and IBM Plex (SIL Open Font License).",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(48.dp))
    }

    if (showBackupCode) {
        SecureWindow()
        AlertDialog(
            onDismissRequest = { showBackupCode = false }, containerColor = Mura.Surface,
            title = { Text("Identity backup code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Anyone with this code can become you. Write it on paper and keep it private. Screenshots are blocked here.",
                        style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                    Text(IdentityStore.backupCode(engine.identity), fontFamily = Mura.Mono, style = MaterialTheme.typography.titleSmall)
                }
            },
            confirmButton = { TextButton(onClick = { showBackupCode = false }) { Text("Done", color = Mura.Amber) } },
        )
    }
}

/** Tor bridges: for networks that block Tor or where using Tor draws attention. */
@Composable
private fun BridgesSection() {
    val engine = requireEngine()
    val db = engine.db
    val ctx = LocalContext.current
    val mode by rememberQuery("off") { db.getSetting(Bridges.MODE, "off") }
    val saved by rememberQuery("") { db.getSetting(Bridges.LINES, "") }
    var lines by remember(saved) { mutableStateOf(saved) }
    Chips("Bridges (if Tor is blocked or watched)", listOf("off" to "Off", "snowflake" to "Snowflake", "custom" to "My bridges"), mode) {
        db.setSetting(Bridges.MODE, it); engine.online.restartTor()
    }
    Text(when (mode) {
        "snowflake" -> "Your connection looks like a video call. Your network can't see that you use Tor. Slower to connect."
        "custom" -> "Paste obfs4, webtunnel or snowflake bridge lines from bridges.torproject.org or the Telegram bot @GetBridgesBot."
        else -> "Not needed on most networks. Turn on if Tor won't connect, or if you don't want your network to see Tor."
    }, style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    if (mode == "custom") {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            OutlinedTextField(lines, { lines = it.take(8000) }, label = { Text("Bridge lines") }, minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mura.Mono))
            TextButton(enabled = lines != saved, onClick = {
                val n = Bridges.parse(lines).size
                db.setSetting(Bridges.LINES, lines)
                engine.online.restartTor()
                ctx.toast(if (n == 0) "No bridge lines found" else "Saved $n bridge${if (n == 1) "" else "s"} · reconnecting Tor")
            }) { Text("Save and reconnect", color = Mura.Amber) }
        }
    }
}

/** Calculator disguise: on/off with a secret code. */
@Composable
private fun DisguiseSection() {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var code2 by remember { mutableStateOf("") }
    SettingRow(MIcons.Lock, "Disguise app icon", if (Disguise.enabledState) "Mura looks like a Calculator. Type your code and press = to open it."
        else "Replace Mura's icon with a working Calculator. Your secret code + \"=\" opens Mura.", Disguise.enabledState) { on ->
        if (on) { code = ""; code2 = ""; ask = true } else { Disguise.disable(ctx); ctx.toast("Mura's normal icon is back. It can take a few seconds to appear.") }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false }, containerColor = Mura.Surface,
            title = { Text("Choose a secret code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("4–12 digits. To open Mura, launch the Calculator, type this code and press \"=\". Anything else just calculates. " +
                        "Notifications will say \"New message\" with a neutral icon. If you forget the code, uninstalling is the only way back.",
                        style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                    OutlinedTextField(code, { code = Disguise.normalize(it).take(12) }, singleLine = true, label = { Text("Code") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), colors = fieldColors())
                    OutlinedTextField(code2, { code2 = Disguise.normalize(it).take(12) }, singleLine = true, label = { Text("Repeat code") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), colors = fieldColors())
                }
            },
            confirmButton = {
                TextButton(enabled = code.length >= 4 && code == code2, onClick = {
                    Disguise.enable(ctx, code)
                    MuraApp.engine?.db?.setSetting(Settings.NOTIF_PREVIEW, "none")
                    @Suppress("DEPRECATION") ctx.findActivity()?.setTaskDescription(android.app.ActivityManager.TaskDescription("Calculator"))
                    ask = false
                    ctx.toast("Done. The Calculator icon replaces Mura's in a few seconds; notification previews are now hidden.")
                }) { Text("Disguise", color = Mura.Amber) }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

/** Off / Wi-Fi only / any network, with live Tor status. */
@Composable
private fun InternetSection() {
    val engine = requireEngine()
    val db = engine.db
    val state by engine.online.state.collectAsState()
    val mode by rememberQuery("any") { db.getSetting(OnlineTransport.MODE, "any") }
    Chips("Send and receive over the internet", listOf("off" to "Off", "wifi" to "Wi-Fi only", "any" to "Wi-Fi + mobile data"), mode) {
        db.setSetting(OnlineTransport.MODE, it); engine.online.applyMode()
    }
    val (dot, line) = when (state.status) {
        OnlineTransport.Status.ON -> Mura.Mint to (if (state.linked > 0) "Online · ${state.linked} contact${if (state.linked == 1) "" else "s"} linked now" else "Online · ready")
        OnlineTransport.Status.STARTING -> Mura.Amber to state.note.ifBlank { "Starting Tor…" }
        OnlineTransport.Status.NO_NETWORK -> Mura.Muted to state.note
        OnlineTransport.Status.ERROR -> Mura.Red to state.note
        OnlineTransport.Status.OFF -> Mura.Muted to "Off · Bluetooth only"
    }
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Onion layers: three rings close as Tor connects, and turn green when it's online.
        com.mura.mesh.ui.OnionRings(connecting = state.status == OnlineTransport.Status.STARTING, online = state.status == OnlineTransport.Status.ON,
            percent = com.mura.mesh.ui.torPercent(state.note), color = dot)
        Text(line, style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
    }
    if (state.status == OnlineTransport.Status.ON) {
        // The real test: can Tor reach *my* address? If not, nobody can message me over the internet.
        val reach = engine.online.selfReachable
        val (rc, rt) = when (reach) {
            true -> Mura.Mint to "Your address is reachable on Tor — contacts can message you"
            false -> Mura.Amber to "Your address isn't reachable yet" + (engine.online.selfError?.let { " ($it)" } ?: "") +
                " — usually it takes 1–3 minutes after connecting"
            null -> Mura.Muted to "Checking whether your address is reachable…"
        }
        Text(rt, style = MaterialTheme.typography.bodySmall, color = rc, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        if (state.note.isNotBlank()) Text(state.note, style = MaterialTheme.typography.labelSmall, color = Mura.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        val ctx = LocalContext.current
        ActionRow(MIcons.Refresh, "Test my address", "Checks through Tor that other phones can reach you. Takes up to 2 minutes.") {
            ctx.toast(if (engine.online.testNow()) "Testing… the result appears above" else "Tor isn't ready yet")
        }
    }
    val rotate by rememberQuery("10") { db.getSetting(OnlineTransport.ROTATE, "10") }
    Chips("New Tor circuits", listOf("10" to "Every 10 min", "60" to "Every hour", "0" to "Only when I ask"), rotate) {
        db.setSetting(OnlineTransport.ROTATE, it)
    }
    if (state.status == OnlineTransport.Status.ON) {
        val ctx = LocalContext.current
        ActionRow(MIcons.Refresh, "New circuits now", "Like Tor Browser's \"New Tor circuit\". Each contact already gets a separate circuit.") {
            ctx.toast(if (engine.online.newCircuits()) "New Tor circuits for new connections" else "Tor isn't ready yet")
        }
    }
    val mailbox by rememberQuery(true) { db.getBool(Settings.FRIEND_MAILBOX, true) }
    SettingRow(MIcons.Group, "Friend mailboxes", "Deliver even when you and your contact are never online at the same time: a friend you both have " +
        "holds the sealed message and passes it on. They can't read it, but they can tell that you wrote to that person.", mailbox) {
        db.setBool(Settings.FRIEND_MAILBOX, it); engine.online.poke()
    }
    val anyone by rememberQuery(true) { db.getBool(Settings.INTERNET_RELAY, true) }
    SettingRow(MIcons.Mesh, "Carry anyone's messages online", "Like Bluetooth relaying, but over Tor: sealed messages hop from phone to phone " +
        "through everyone's connections until one reaches the person — even if you've never met the carriers. Nobody along the way can read " +
        "them or see who sent them. Uses some storage (Mesh › space lent) and data.", anyone) {
        db.setBool(Settings.INTERNET_RELAY, it); engine.online.poke()
    }
    BridgesSection()
    Text("Tor-only: Mura never uses the normal internet. Link previews also go through Tor, and wait " +
        "until Tor is connected — nothing is sent before that, not even a DNS lookup. The only direct connection is Tor " +
        "reaching the Tor network itself.",
        style = MaterialTheme.typography.bodySmall, color = Mura.Soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    Text("No server, ever. Your phone runs Tor and becomes its own hidden \"onion\" address, which only your contacts know. " +
        "Messages go phone-to-phone through Tor, still end-to-end encrypted with forward secrecy; Tor hides both phones' IP addresses, " +
        "so nobody — not your mobile network, not Tor relays — sees who you talk to. Both phones need to be online at the same time; " +
        "until then messages wait and still travel over Bluetooth when you meet. Contacts can tell when you're online. Uses some battery and data.",
        style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

@Composable
fun Chips(label: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                val active = current == value
                Text(text, style = MaterialTheme.typography.labelMedium, color = if (active) Mura.OnAmber else Mura.Soft,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (active) Mura.Amber else Mura.Surface)
                        .clickable(role = Role.RadioButton) { onPick(value) }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun PrivacyScreen() {
    val engine = requireEngine()
    val db = engine.db
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val readReceipts by rememberQuery(true) { db.getBool(Settings.READ_RECEIPTS, true) }
    val livePresence by rememberQuery(true) { db.getBool(Settings.SHARE_PRESENCE, true) }
    val statusCapture by rememberQuery(false) { db.getBool(Settings.STATUS_CAPTURE, false) }
    val blockMsgs by rememberQuery(false) { db.getBool(Settings.BLOCK_UNKNOWN_MESSAGES, false) }
    val blockCalls by rememberQuery(true) { db.getBool(Settings.BLOCK_UNKNOWN_CALLS, true) }
    val blockFiles by rememberQuery(true) { db.getBool(Settings.BLOCK_UNKNOWN_FILES, true) }
    val blockGroups by rememberQuery(true) { db.getBool(Settings.BLOCK_UNKNOWN_GROUPS, true) }
    val secure by rememberQuery(true) { db.getBool(Settings.SCREEN_SECURITY, true) }
    val visible by rememberQuery(true) { db.getBool(Settings.VISIBLE_NAME, true) }
    val previews by rememberQuery(true) { db.getBool(Settings.LINK_PREVIEWS, true) }
    val lock by rememberQuery(false) { db.getBool(Settings.APP_LOCK, false) }
    val notif by rememberQuery("full") { db.getSetting(Settings.NOTIF_PREVIEW, "full") }
    val lockAfter by rememberQuery("30") { db.getSetting(Settings.LOCK_AFTER, "30") }
    val hideLocked by rememberQuery(false) { db.getBool(Settings.HIDE_LOCKED, false) }
    val clipClear by rememberQuery(true) { db.getBool(Settings.CLIPBOARD_CLEAR, true) }
    val defTimer by rememberQuery("0") { db.getSetting(Settings.DEFAULT_TIMER, "0") }
    var confirmErase by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar("Privacy", onBack = { nav.back() })
        SectionLabel("This phone")
        SettingRow(MIcons.Lock, "App lock", "Fingerprint, face or screen lock to open Mura (re-locks after 30 s away)", lock) { on ->
            val a = ctx.findActivity() as? MainActivity
            if (on && a?.canUseLock() != true) ctx.toast("Set up a screen lock or fingerprint in Android settings first")
            else db.setBool(Settings.APP_LOCK, on)
        }
        Chips("Lock again after", listOf("0" to "Immediately", "30" to "30 s", "60" to "1 min", "300" to "5 min"), lockAfter) {
            db.setSetting(Settings.LOCK_AFTER, it)
        }
        SettingRow(MIcons.Fingerprint, "Hide locked chats", "Locked chats move into a \"Locked chats\" folder that opens with your fingerprint or face", hideLocked) {
            db.setBool(Settings.HIDE_LOCKED, it)
        }
        SettingRow(MIcons.Eye, "Block screenshots", "Blocks screenshots and screen recording in Mura, and hides chats in the recent-apps view", secure) {
            db.setBool(Settings.SCREEN_SECURITY, it)
            (ctx.findActivity() as? MainActivity)?.applyScreenSecurity()
        }
        Chips("Notifications show", listOf("full" to "Name + message", "name" to "Name only", "none" to "Nothing"), notif) { db.setSetting(Settings.NOTIF_PREVIEW, it) }

        DisguiseSection()
        SettingRow(MIcons.Keyboard, "Incognito keyboard", "Asks your keyboard not to learn or remember anything you type in Mura (like a browser's private tab)", Disguise.incognitoKeyboard) {
            Disguise.setIncognito(ctx, it)
        }

        SectionLabel("Hidden details")
        val stripMeta by rememberQuery("ask") { db.getSetting(Settings.STRIP_META, "ask") }
        Chips("Remove location, date and phone model from videos and files", listOf("ask" to "Ask me", "always" to "Always", "never" to "Never"), stripMeta) {
            db.setSetting(Settings.STRIP_META, it)
        }
        Text("Photos sent as photos are always re-encoded, which removes everything. Voice notes never carry your phone's details.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val pad by rememberQuery(true) { db.getBool(Settings.PAD_TRAFFIC, true) }
        SettingRow(MIcons.Shield, "Traffic padding", "Pads every message to a fixed size step and adds random cover traffic on internet links, so message length and rhythm reveal nothing. Uses a little more data.", pad) {
            engine.setPadding(it)
        }

        SectionLabel("Who can reach you")
        SettingRow(MIcons.Ban, "Block messages from strangers", "Only people you've added can message you", blockMsgs) { db.setBool(Settings.BLOCK_UNKNOWN_MESSAGES, it) }
        SettingRow(MIcons.Phone, "Silence calls from strangers", "Unknown callers are declined automatically", blockCalls) { db.setBool(Settings.BLOCK_UNKNOWN_CALLS, it) }
        SettingRow(MIcons.File, "Ask before files from strangers", "Photos, videos and documents from unknown people wait for your OK", blockFiles) { db.setBool(Settings.BLOCK_UNKNOWN_FILES, it) }
        SettingRow(MIcons.Group, "Block group invites from strangers", "Only contacts can add you to groups", blockGroups) { db.setBool(Settings.BLOCK_UNKNOWN_GROUPS, it) }
        SettingRow(MIcons.User, "Show my name to nearby phones", "Off: you appear as \"Hidden name\" until someone scans your code", visible) { db.setBool(Settings.VISIBLE_NAME, it) }

        SettingRow(MIcons.Clipboard, "Clear copied messages", "Copied text is hidden from clipboard previews and wiped after 1 minute", clipClear) {
            db.setBool(Settings.CLIPBOARD_CLEAR, it)
        }

        SectionLabel("Messages")
        Chips("Disappearing messages for new chats", listOf("0" to "Off", "3600" to "1 h", "86400" to "24 h", "604800" to "7 days", "2592000" to "30 days"), defTimer) {
            db.setSetting(Settings.DEFAULT_TIMER, it)
        }
        SettingRow(MIcons.Checks, "Read receipts", "If off, you won't send or see read ticks", readReceipts) { db.setBool(Settings.READ_RECEIPTS, it) }
        SettingRow(MIcons.Eye, "Online, typing & recording status",
            "Contacts you've added see when you're in Mura, typing or recording a voice note — sent live and encrypted over a direct link, never stored. If off, you won't share or see it.",
            livePresence) { engine.setSharePresence(it) }
        SettingRow(MIcons.Status, "Status screenshots",
            "Let contacts screenshot or screen-record your status updates. Off: Mura blocks it on their phones. Applies to statuses you post from now on.",
            statusCapture) { db.setBool(Settings.STATUS_CAPTURE, it) }
        SettingRow(MIcons.Link, "Link previews", "Your phone fetches the preview before sending (uses internet). People receiving it never contact the site.", previews) { db.setBool(Settings.LINK_PREVIEWS, it) }

        SectionLabel("Encryption")
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().background(Mura.Surface, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(MIcons.Shield, null, tint = Mura.Mint)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Forward secrecy is on", style = MaterialTheme.typography.titleSmall)
                Text("Every message gets its own key, which is deleted right after use. Even someone who later steals your phone " +
                    "can't unlock messages they recorded earlier. Chats use the Double Ratchet (as in Signal), groups use per-sender " +
                    "key chains, and calls use a fresh key exchange that's erased when the call starts.",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            }
        }

        SectionLabel("How your data is protected")
        Text(
            "• Every message, file, call and status is end-to-end encrypted. Phones that carry it can't read it.\n" +
                "• Over the internet, Mura talks only through Tor onion services — no server, no phone number, no IP address revealed. Only contacts can connect to you.\n" +
                "• Mura never pairs with other phones. Its Bluetooth signal shows a random id that changes every 15 minutes, so your phone can't be tracked by it.\n" +
                "• On this phone, chats are encrypted in the database and every file is stored encrypted, with keys locked in Android's hardware-backed Keystore. Copying the app's storage gives nothing readable.\n" +
                "• Received files are never written in readable form; they're decrypted in memory when you open them.\n" +
                "• Screenshot alerts work when the phone taking the screenshot runs Android 14 or newer. Nothing can stop a photo of the screen taken with another camera.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted,
            modifier = Modifier.padding(20.dp).background(Mura.Surface, RoundedCornerShape(16.dp)).padding(16.dp),
        )

        SectionLabel("Emergency")
        Text("Panic erase wipes every chat, file, key and setting from this phone and deletes the hardware keys, so nothing left in storage can be read. It can't be undone.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        HoldToErase(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth()) { confirmErase = true }
        Spacer(Modifier.height(40.dp))
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false }, containerColor = Mura.Surface,
            icon = { Icon(MIcons.Flame, null, tint = Mura.Red) },
            title = { Text("Erase everything now?") },
            text = { Text("Your identity, contacts, chats, files and settings will be gone from this phone. Backup files you saved (if any) stay where you put them, and only open in Mura with your backup password.", color = Mura.Soft) },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    val a = ctx.findActivity()
                    com.mura.mesh.MuraApp.instance.panicErase()
                    a?.finishAndRemoveTask()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("Erase", color = Mura.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

/** A button that must be held for 2.5 seconds; the fill shows progress, letting go cancels. */
@Composable
private fun HoldToErase(modifier: Modifier, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(Mura.Red.copy(alpha = 0.14f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.animateTo(1f, tween(2500, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDone()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) { job.cancel(); scope.launch { progress.animateTo(0f, tween(250)) } }
                    else scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Mura.Red.copy(alpha = 0.45f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(MIcons.Flame, null, tint = Mura.Red, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (progress.value > 0f) "Keep holding…" else "Hold to erase everything", style = MaterialTheme.typography.labelLarge, color = Mura.Text)
        }
    }
}

@Composable
fun ProfileScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatar by rememberQuery<ByteArray?>(null) { engine.myAvatar() }
    var name by remember { mutableStateOf(engine.identity.name) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { try { SecureMedia.smallImage(ctx, uri, 256, 72, square = true) } catch (e: Exception) { null } }
            if (bytes == null) ctx.toast("Couldn't open that photo") else engine.updateProfile(null, bytes)
        }
    }
    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar("Profile", onBack = { nav.back() })
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Avatar(engine.identity.publicHex, name, 120.dp, photo = avatar)
                Box(Modifier.align(Alignment.BottomEnd).size(40.dp).clip(CircleShape).background(Mura.Amber)
                    .clickable(role = Role.Button) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center) { Icon(MIcons.Camera, "Change photo", tint = Mura.OnAmber) }
            }
            if (avatar != null) TextButton(onClick = { engine.updateProfile(null, null, removeAvatar = true) }) { Text("Remove photo", color = Mura.Red) }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it.take(32) }, singleLine = true, label = { Text("Your name") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Your photo and name are sent only to your contacts, end-to-end encrypted (about 10 KB).", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            Spacer(Modifier.height(20.dp))
            com.mura.mesh.ui.PrimaryButton("Save", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
                engine.updateProfile(name, null); nav.back()
            }
        }
    }
}

@Composable
fun BlockedScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val blocked by rememberQuery(emptyList<ContactRow>()) { engine.db.contactsByState(ContactState.BLOCKED) }
    Column(Modifier.fillMaxSize().background(Mura.Ink)) {
        TopBar("Blocked", "${blocked.size} blocked", onBack = { nav.back() })
        if (blocked.isEmpty()) Text("Nobody is blocked.", color = Mura.Muted, modifier = Modifier.padding(20.dp))
        for (c in blocked) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(c.pub, c.display, 44.dp, photo = c.avatar)
                Text(c.display, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { engine.unblock(c.pub) }) { Text("Unblock", color = Mura.Amber) }
            }
        }
    }
}
