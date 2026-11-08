package com.mura.mesh.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mura.mesh.media.Translate
import com.mura.mesh.ui.ActionRow
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.SectionLabel
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.Wallpapers
import com.mura.mesh.ui.chatWallpaper
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.theme.Themes
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// =====================================================================================
// Appearance: theme, accent colour, wallpaper (for all chats, or one chat)
// =====================================================================================

@Composable
fun AppearanceScreen(chat: String?) {
    val engine = requireEngine()
    val db = engine.db
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatName by rememberQuery("", chat) { chat?.let { engine.chatTitle(it) } ?: "" }
    val kind by rememberQuery(if (chat == null) "dots" else Wallpapers.DEFAULT, chat) {
        db.getSetting(Wallpapers.kindKey(chat), if (chat == null) "dots" else Wallpapers.DEFAULT)
    }
    val storedDim by rememberQuery(40, chat) { db.getSetting(Wallpapers.dimKey(chat), "40").toIntOrNull() ?: 40 }
    var dim by remember(storedDim) { mutableFloatStateOf(storedDim.toFloat()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { Wallpapers.savePhoto(ctx, db, chat, uri) } } catch (e: Exception) { ctx.toast("Couldn't use that photo") }
        }
    }

    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar(if (chat == null) "Appearance" else "Wallpaper", if (chat == null) "Theme, colour and wallpaper" else "For your chat with $chatName", onBack = { nav.back() })

        // Live preview
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(210.dp).clip(RoundedCornerShape(22.dp))
                .border(1.dp, Mura.Line, RoundedCornerShape(22.dp)).chatWallpaper(chat).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            PreviewBubble("Are you coming to the lake later? 🌊", mine = false)
            PreviewBubble("On my way — saving you a spot", mine = true)
            PreviewBubble("Perfect!", mine = false)
        }

        if (chat == null) {
            SectionLabel("Theme")
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((value, label) in Themes.MODES) {
                    val on = Themes.mode == value
                    val p = Themes.palette(value, Themes.accent, systemDark = Mura.isDark)
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(2.dp, if (on) Mura.Amber else Mura.Line, RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { Themes.set(ctx, mode = value) }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp)).background(p.ink).padding(6.dp)) {
                            if (value == "system") Box(Modifier.align(Alignment.CenterEnd).width(22.dp).height(42.dp).clip(RoundedCornerShape(6.dp))
                                .background(Themes.palette("light", Themes.accent, false).ink))
                            Box(Modifier.align(Alignment.TopStart).width(30.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.surface2))
                            Box(Modifier.align(Alignment.BottomEnd).padding(end = if (value == "system") 24.dp else 0.dp).width(26.dp).height(10.dp)
                                .clip(RoundedCornerShape(5.dp)).background(p.accent))
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Mura.Amber else Mura.Soft)
                    }
                }
            }
            Text("Black uses pure black — easiest on battery for phones with OLED screens.", style = MaterialTheme.typography.bodySmall,
                color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

            SectionLabel("Accent colour")
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((value, label) in Themes.ACCENTS) {
                    val on = Themes.accent == value
                    val c = Themes.palette(Themes.mode, value, Mura.isDark).accent
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(role = Role.RadioButton, onClickLabel = label) {
                        Themes.set(ctx, accent = value)
                    }) {
                        Box(Modifier.size(42.dp).clip(CircleShape).border(3.dp, if (on) Mura.Text else Color.Transparent, CircleShape).padding(5.dp)
                            .clip(CircleShape).background(c))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) Mura.Text else Mura.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        SectionLabel(if (chat == null) "Chat wallpaper" else "Wallpaper for this chat")
        val options = (if (chat != null) listOf(Wallpapers.DEFAULT to "Same as all chats") else emptyList()) + Wallpapers.KINDS
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((value, label) in options) {
                val on = kind == value
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val preview = when (value) {
                        Wallpapers.DEFAULT -> null
                        "photo" -> null
                        else -> Wallpapers.Spec(value, null, 0f)
                    }
                    Box(
                        Modifier.width(78.dp).height(120.dp).clip(RoundedCornerShape(14.dp))
                            .border(2.dp, if (on) Mura.Amber else Mura.Line, RoundedCornerShape(14.dp))
                            .then(if (preview != null) Modifier.chatWallpaper(null, preview) else Modifier.background(Mura.Surface))
                            .clickable(role = Role.RadioButton) {
                                if (value == "photo") picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                else Wallpapers.set(db, chat, value)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (value) {
                            "photo" -> Icon(MIcons.Image, null, tint = Mura.Amber)
                            Wallpapers.DEFAULT -> Icon(MIcons.Undo, null, tint = Mura.Soft)
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) Mura.Amber else Mura.Soft, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 78.dp).padding(top = 4.dp))
                }
            }
        }
        if (kind == "photo") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Dim the photo: ${dim.toInt()}%", style = MaterialTheme.typography.titleSmall)
                Slider(dim, { dim = it }, valueRange = 0f..85f,
                    onValueChangeFinished = { db.setSetting(Wallpapers.dimKey(chat), dim.toInt().toString()) },
                    colors = SliderDefaults.colors(thumbColor = Mura.Amber, activeTrackColor = Mura.Amber, inactiveTrackColor = Mura.Line))
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("Choose another photo", color = Mura.Amber)
                }
            }
        }
        Text("Your wallpaper photo is shrunk, stripped of location data and stored encrypted on this phone. It's never sent to anyone.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (chat == null) ActionRow(MIcons.Translate, "Translation", "Translate messages on this phone") { nav.go(com.mura.mesh.ui.Screen.Translation) }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PreviewBubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (mine) Mura.OnAmber else Mura.Text,
            modifier = Modifier.widthIn(max = 230.dp)
                .clip(if (mine) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                .background(if (mine) Mura.Amber else Mura.Surface).padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

// =====================================================================================
// Translation settings
// =====================================================================================

@Composable
fun TranslationScreen() {
    val engine = requireEngine()
    val db = engine.db
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val eng by rememberQuery("system") { Translate.engine(db) }
    val myLang by rememberQuery(Locale.getDefault().language) { Translate.myLanguage(db) }
    var langs by remember { mutableStateOf<List<Translate.Lang>?>(null) }
    var pickLang by remember { mutableStateOf(false) }
    LaunchedEffect(eng, myLang, refresh) {
        langs = null
        langs = try { Translate.languages(ctx, db) } catch (e: Exception) { emptyList() }
    }
    val systemOk = remember { Translate.systemAvailable(ctx) }

    Column(Modifier.fillMaxSize().background(Mura.Ink).verticalScroll(rememberScrollState())) {
        TopBar("Translation", "On this phone · never sent anywhere", onBack = { nav.back() })

        ActionRow(MIcons.Globe, "Translate into", Translate.displayName(myLang)) { pickLang = true }

        SectionLabel("Translator")
        Text(
            if (systemOk) "Uses your phone's own on-device translator (Android 12+; included on phones whose maker ships one, like Pixel). " +
                "Mura downloads nothing, contacts no server, and the text never leaves your phone."
            else "This phone has no built-in on-device translator, so translation isn't available here. Mura doesn't include a " +
                "third-party translator because those contact their maker over the normal internet, which Mura never uses.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).background(Mura.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
        )

        SectionLabel("Languages")
        if (systemOk) {
            ActionRow(MIcons.Download, "Download languages", "Opens Android's translation settings") {
                try { Translate.systemSettingsIntent(ctx)?.send() ?: ctx.toast("Not available on this phone") } catch (e: Exception) { ctx.toast("Not available on this phone") }
            }
        }
        val list = langs
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Mura.Amber, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            list.isEmpty() -> Text("No languages available yet.", style = MaterialTheme.typography.bodySmall, color = Mura.Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            else -> for (l in list) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.titleSmall)
                        Text(when (l.state) { Translate.READY -> "On this phone"; Translate.DOWNLOADING -> "Downloading…"; else -> "Not downloaded" },
                            style = MaterialTheme.typography.bodySmall, color = if (l.state == Translate.READY) Mura.Mint else Mura.Muted)
                    }
                }
            }
        }
        Text("Turn on auto-translate in any chat from its ⋮ menu. You can also translate one message by long-pressing it. " +
            "Translations are kept, encrypted, only on this phone.",
            style = MaterialTheme.typography.bodySmall, color = Mura.Muted, modifier = Modifier.padding(20.dp))
        Spacer(Modifier.height(40.dp))
    }

    if (pickLang) {
        val all = remember {
            Locale.getAvailableLocales().map { it.language }.filter { it.length in 2..3 }.distinct()
                .map { it to Translate.displayName(it) }.sortedBy { it.second.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { pickLang = false }, containerColor = Mura.Surface,
            title = { Text("Translate into") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(all, key = { it.first }) { (tag, name) ->
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = if (tag == myLang) Mura.Amber else Mura.Text,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                db.setSetting(Translate.MY_LANG, tag); engine.translateFailed.clear(); pickLang = false
                            }.padding(vertical = 10.dp, horizontal = 6.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickLang = false }) { Text("Close", color = Mura.Soft) } },
        )
    }
}
