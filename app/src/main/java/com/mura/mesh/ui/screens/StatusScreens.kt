package com.mura.mesh.ui.screens

import androidx.compose.runtime.DisposableEffect
import com.mura.mesh.ui.findActivity
import com.mura.mesh.ui.LocalInTab
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.core.Payload
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.StatusRow
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.EmojiPicker
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.SecureWindow
import com.mura.mesh.ui.SectionLabel
import com.mura.mesh.ui.TopBar
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.timeLabel
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STATUS_COLORS = listOf(0xFF3A2A12, 0xFF14283A, 0xFF1F2A1E, 0xFF2A1B2E, 0xFF2E1B1B, 0xFF1B2A2A, 0xFF0B0E13, 0xFF5A3A0A)

@Composable
fun StatusListScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val statuses by rememberQuery(emptyList<StatusRow>()) { engine.db.statuses(System.currentTimeMillis()) }
    val contacts by rememberQuery(emptyMap<String, ContactRow>()) { engine.db.contacts().associateBy { it.pub } }
    val me = engine.identity.publicHex
    val mine = statuses.filter { it.owner == me }
    val others = statuses.filter { it.owner != me }.groupBy { it.owner }
    LazyColumn(Modifier.fillMaxSize().background(Mura.Ink), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TopBar("Status", "Disappears after 24 hours · only your contacts can see it", onBack = if (LocalInTab.current) null else ({ nav.back() })) }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { if (mine.isNotEmpty()) nav.go(Screen.StatusView(me)) else nav.go(Screen.NewStatus) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    Avatar(me, engine.identity.name, 56.dp, ring = if (mine.isNotEmpty()) Mura.Amber else null, photo = engine.myAvatar())
                    Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(Mura.Amber), contentAlignment = Alignment.Center) {
                        Icon(MIcons.Plus, null, tint = Mura.OnAmber, modifier = Modifier.size(14.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("My status", style = MaterialTheme.typography.titleMedium)
                    Text(if (mine.isEmpty()) "Tap to add a status update" else "${mine.size} update${if (mine.size == 1) "" else "s"} · ${timeLabel(mine.last().created)}",
                        style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                }
                IconBtn(MIcons.Edit, "New status", bg = Mura.Surface) { nav.go(Screen.NewStatus) }
            }
        }
        item { SectionLabel("Recent updates") }
        if (others.isEmpty()) item {
            Text("Status updates from your contacts appear here as phones carry them to you.", style = MaterialTheme.typography.bodySmall,
                color = Mura.Muted, modifier = Modifier.padding(horizontal = 20.dp))
        }
        items(others.entries.sortedByDescending { e -> e.value.maxOf { it.created } }.map { it.key }) { owner ->
            val list = others.getValue(owner)
            val c = contacts[owner]
            val unseen = list.any { !it.viewed }
            Row(
                Modifier.fillMaxWidth().clickable { nav.go(Screen.StatusView(owner)) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(owner, c?.display ?: "?", 56.dp, ring = if (unseen) Mura.Amber else Mura.Line, photo = c?.avatar)
                Column {
                    Text(c?.display ?: "Contact", style = MaterialTheme.typography.titleMedium)
                    Text(timeLabel(list.maxOf { it.created }), style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                }
            }
        }
    }
}

/** Full-screen story viewer: tap right/left to move, hold to pause. */
@Composable
fun StatusViewScreen(owner: String) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val list by rememberQuery(emptyList<StatusRow>(), owner) { engine.db.statuses(System.currentTimeMillis()).filter { it.owner == owner } }
    val contact by rememberQuery<ContactRow?>(null, owner) { engine.db.contact(owner) }
    var index by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }       // writing a reply pauses the story
    var reply by remember { mutableStateOf("") }
    var sentFlash by remember { mutableStateOf<String?>(null) }
    val mine = owner == engine.identity.publicHex
    val ctx = LocalContext.current

    if (list.isEmpty()) { SecureWindow(); Box(Modifier.fillMaxSize().background(Mura.Ink)); return }
    val s = list[index.coerceIn(0, list.size - 1)]
    // Screenshots / screen recording follow the status owner's choice (Privacy › Status screenshots).
    StatusCaptureWindow(allow = s.allowCapture)
    LaunchedEffect(s.id) {
        if (!mine && !s.viewed) withContext(Dispatchers.IO) { engine.db.markStatusViewed(s.id) }
        progress = 0f
        while (progress < 1f) { delay(50); if (!paused && !typing) progress += 50f / 5000f }
        if (index < list.size - 1) index++ else nav.back()
    }

    Box(Modifier.fillMaxSize().background(Color(s.background)).pointerInput(list.size) {
        detectTapGestures(
            onPress = { paused = true; tryAwaitRelease(); paused = false },
            onTap = { o -> if (o.x > size.width / 3) { if (index < list.size - 1) index++ else nav.back() } else if (index > 0) index-- },
        )
    }) {
        if (s.kind == Payload.STATUS_IMAGE && s.image != null) {
            val img = remember(s.id) { BitmapFactory.decodeByteArray(s.image, 0, s.image.size)?.asImageBitmap() }
            if (img != null) Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (s.text.isNotBlank()) {
            Text(s.text, color = Color.White, textAlign = TextAlign.Center,
                style = if (s.kind == Payload.STATUS_TEXT) TextStyle(fontFamily = Mura.Display, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
                else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(if (s.kind == Payload.STATUS_TEXT) Alignment.Center else Alignment.BottomCenter)
                    .navigationBarsPadding().padding(24.dp).padding(bottom = if (!mine && s.kind == Payload.STATUS_IMAGE) 150.dp else 0.dp)
                    .then(if (s.kind == Payload.STATUS_IMAGE) Modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(12.dp) else Modifier))
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEachIndexed { i, _ ->
                    val p = when { i < index -> 1f; i == index -> progress; else -> 0f }
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Color.White))
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBtn(MIcons.Back, "Close", tint = Color.White) { nav.back() }
                Avatar(owner, if (mine) "Me" else contact?.display ?: "?", 36.dp, photo = if (mine) engine.myAvatar() else contact?.avatar)
                Column(Modifier.weight(1f)) {
                    Text(if (mine) "My status" else contact?.display ?: "Contact", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(timeLabel(s.created), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall)
                }
                if (mine) IconBtn(MIcons.Trash, "Delete this status", tint = Color.White) { engine.deleteStatus(s.id); if (list.size <= 1) nav.back() }
            }
        }

        // Reply privately (like WhatsApp): quick reactions and a message box. Replies arrive in your 1:1 chat, sealed end to end.
        if (!mine && contact?.state == com.mura.mesh.data.ContactState.CONTACT) {
            fun send(text: String) {
                engine.replyToStatus(s, text)
                sentFlash = text.takeIf { it.length <= 4 } ?: "✓"
                reply = ""; typing = false
                ctx.toast("Reply sent to ${contact?.display ?: "them"} privately")
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000)).navigationBarsPadding().imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (e in listOf("❤️", "😂", "😮", "😢", "👏", "🔥")) {
                        Text(e, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "React $e") { send(e) }.padding(6.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        value = reply, onValueChange = { reply = it.take(2000); typing = it.isNotEmpty() },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White), maxLines = 4,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        decorationBox = { inner -> Box { if (reply.isEmpty()) Text("Reply privately…", color = Color(0xCCFFFFFF)); inner() } },
                    )
                    if (reply.isNotBlank()) Box(Modifier.size(46.dp).clip(CircleShape).background(Mura.Amber).clickable(role = Role.Button, onClickLabel = "Send reply") { send(reply) },
                        contentAlignment = Alignment.Center) { Icon(MIcons.Send, "Send", tint = Mura.OnAmber) }
                }
            }
        }
        // A quick pop of the reaction that was just sent.
        sentFlash?.let { e ->
            val scale = remember(e, s.id) { androidx.compose.animation.core.Animatable(0.4f) }
            LaunchedEffect(e, s.id) {
                scale.animateTo(1.4f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f))
                delay(450); sentFlash = null
            }
            Text(e, fontSize = 72.sp, modifier = Modifier.align(Alignment.Center).graphicsLayer { scaleX = scale.value; scaleY = scale.value })
        }
    }
}

@Composable
fun NewStatusScreen() {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(STATUS_COLORS[0]) }
    var emoji by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            image = withContext(Dispatchers.IO) { try { SecureMedia.smallImage(ctx, uri, 1080, 62, square = false) } catch (e: Exception) { null } }
            busy = false
            if (image == null) ctx.toast("Couldn't open that photo")
        }
    }

    Column(Modifier.fillMaxSize().background(Color(color or 0xFF000000L)).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(MIcons.Close, "Cancel", tint = Color.White) { nav.back() }
            Spacer(Modifier.weight(1f))
            IconBtn(MIcons.Image, "Add a photo", tint = Color.White) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            IconBtn(MIcons.Smile, "Emoji", tint = Color.White) { emoji = !emoji }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val img = remember(image) { image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (img != null) Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
            BasicTextField(
                value = text, onValueChange = { text = it.take(700) },
                textStyle = TextStyle(color = Color.White, fontFamily = Mura.Display, fontSize = if (img != null) 18.sp else 30.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Mura.Amber),
                modifier = Modifier.fillMaxWidth().then(if (img != null) Modifier.align(Alignment.BottomCenter).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(12.dp) else Modifier),
                decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (text.isEmpty()) Text(if (img != null) "Add a caption…" else "Type a status", color = Color(0x99FFFFFF), fontFamily = Mura.Display, fontSize = if (img != null) 18.sp else 30.sp); inner() } },
            )
        }
        if (image == null) LazyRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(STATUS_COLORS) { c ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c or 0xFF000000L))
                    .border(2.dp, if (c == color) Color.White else Color.Transparent, CircleShape).clickable(role = Role.Button) { color = c })
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Visible to your contacts for 24 hours", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Box(Modifier.size(56.dp).clip(CircleShape).background(Mura.Amber).clickable(enabled = !busy && (text.isNotBlank() || image != null), role = Role.Button) {
                val img = image
                engine.postStatus(if (img != null) Payload.STATUS_IMAGE else Payload.STATUS_TEXT, text.trim(), color.toInt(), img ?: ByteArray(0))
                nav.back()
            }, contentAlignment = Alignment.Center) { Icon(MIcons.Send, "Post status", tint = Mura.OnAmber) }
        }
        if (emoji) Box(Modifier.navigationBarsPadding()) { EmojiPicker { e -> text = (text + e).take(700) } }
        else Spacer(Modifier.navigationBarsPadding())
    }
}


/**
 * While a status is on screen: blocked from screenshots and screen recording unless its owner allowed it.
 * Afterwards the window goes back to this phone's own "Block screenshots" setting.
 */
@Composable
private fun StatusCaptureWindow(allow: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, allow) {
        val w = activity?.window
        if (allow) w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            val keep = com.mura.mesh.MuraApp.engine?.db?.getBool(com.mura.mesh.mesh.Settings.SCREEN_SECURITY, true) == true
            if (keep) w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            else w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
