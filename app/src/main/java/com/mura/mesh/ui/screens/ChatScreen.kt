package com.mura.mesh.ui.screens

import com.mura.mesh.ui.sameDay
import com.mura.mesh.ui.dayLabel
import com.mura.mesh.ui.clockLabel
import androidx.compose.material3.LocalContentColor
import com.mura.mesh.mesh.OnlineTransport
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import com.mura.mesh.ui.EmberBurn
import com.mura.mesh.mesh.MeshEngine
import com.mura.mesh.ui.CipherSeal
import com.mura.mesh.ui.ChatGate
import com.mura.mesh.ui.withAuth
import com.mura.mesh.ui.findActivity
import com.mura.mesh.ui.MainActivity
import com.mura.mesh.mesh.timerLabel
import com.mura.mesh.core.Checklists
import com.mura.mesh.core.Mention
import com.mura.mesh.data.ReactionRow
import com.mura.mesh.media.Transcriber
import com.mura.mesh.media.Translate
import com.mura.mesh.ui.chatWallpaper
import androidx.compose.ui.window.Popup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.LinkPreview
import com.mura.mesh.core.Payload
import com.mura.mesh.core.hexToBytes
import com.mura.mesh.core.toHex
import com.mura.mesh.data.ChatId
import com.mura.mesh.data.ContactRow
import com.mura.mesh.data.ContactState
import com.mura.mesh.data.FileRow
import com.mura.mesh.data.FileState
import com.mura.mesh.data.GroupRow
import com.mura.mesh.data.MessageRow
import com.mura.mesh.data.MsgKind
import com.mura.mesh.data.MsgStatus
import com.mura.mesh.media.LinkPreviews
import com.mura.mesh.media.LocationHelper
import com.mura.mesh.media.PhotoQuality
import com.mura.mesh.media.Prepared
import com.mura.mesh.media.SecureFileProvider
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.media.VideoQuality
import com.mura.mesh.media.VoicePlayer
import com.mura.mesh.media.VoiceRecorder
import com.mura.mesh.mesh.MeshPermissions
import com.mura.mesh.mesh.Settings
import com.mura.mesh.mesh.formatDuration
import com.mura.mesh.ui.Avatar
import com.mura.mesh.ui.EmojiPicker
import com.mura.mesh.ui.IconBtn
import com.mura.mesh.ui.LocalNav
import com.mura.mesh.ui.MIcons
import com.mura.mesh.ui.Screen
import com.mura.mesh.ui.SecureWindow
import com.mura.mesh.ui.agoLabel
import com.mura.mesh.ui.rememberQuery
import com.mura.mesh.ui.requireEngine
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.timeLabel
import com.mura.mesh.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private data class ChatData(
    val contact: ContactRow?, val group: GroupRow?, val iAmAdmin: Boolean, val memberCount: Int,
    val messages: List<MessageRow>, val files: Map<String, FileRow>, val names: Map<String, String>, val muted: Boolean,
    val timer: Int = 0, val locked: Boolean = false, val loaded: Boolean = false,
    val reactions: Map<String, List<ReactionRow>> = emptyMap(),
    val votes: Map<String, Map<String, List<Int>>> = emptyMap(),
    val listOps: Map<String, List<Checklists.Op>> = emptyMap(),
    val pins: List<String> = emptyList(),
    /** (pub, name) of the other group members, for @mentions. */
    val members: List<Pair<String, String>> = emptyList(),
)

/** What a message bubble can do (built per message by the chat screen). */
private class MsgActions(
    val canEdit: Boolean, val canPin: Boolean,
    val react: (String) -> Unit, val moreReactions: () -> Unit, val showReactions: () -> Unit,
    val edit: () -> Unit, val forward: () -> Unit, val star: (Boolean) -> Unit, val pin: (Boolean) -> Unit,
    val vote: (List<Int>) -> Unit, val toggleItem: (String, Boolean) -> Unit, val openList: () -> Unit, val sendNow: () -> Unit,
    /** Auto-translate is on for this chat. */
    val autoTranslate: Boolean = false,
)

/** A message that just disappeared (or turned into a placeholder): it burns away in place first. */
private class Ghost(val m: MessageRow, val file: FileRow?, val replaced: Boolean, val at: Long, val cipher: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chat: String, focus: String? = null) {
    val engine = requireEngine()
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isGroup = ChatId.isGroup(chat)
    val gid = ChatId.groupIdOf(chat)

    val data by rememberQuery(ChatData(null, null, false, 0, emptyList(), emptyMap(), emptyMap(), false), chat) {
        val msgs = engine.db.messages(chat)
        val names = if (isGroup) msgs.map { it.sender }.distinct().filter { it.isNotEmpty() }.associateWith { engine.db.memberName(gid, it) } else emptyMap()
        ChatData(
            if (isGroup) null else engine.db.contact(chat),
            if (isGroup) engine.db.group(gid) else null,
            isGroup && engine.isAdmin(gid),
            if (isGroup) engine.db.memberCount(gid) else 0,
            msgs,
            engine.db.filesWhere("chat=?", arrayOf(chat)).associateBy { it.fileId },
            names,
            engine.db.isMuted(chat),
            engine.db.chatTimer(chat),
            engine.db.isLocked(chat),
            loaded = true,
            reactions = engine.db.reactionsForChat(chat),
            votes = engine.db.votesForChat(chat),
            listOps = engine.db.listOpsForChat(chat),
            pins = engine.db.pins(chat),
            members = if (isGroup) engine.db.members(gid).filter { it.pub != engine.identity.publicHex }.map { it.pub to engine.db.memberName(gid, it.pub) } else emptyList(),
        )
    }
    val connected by engine.connected.collectAsState()
    val sendProgress by engine.sendProgress.collectAsState()
    val linked = !isGroup && connected.any { it.pubHex == chat }
    val linkedOnline = linked && connected.filter { it.pubHex == chat }.all { it.remote }
    val onlineState by engine.online.state.collectAsState()
    val contact = data.contact
    val group = data.group
    val title = if (isGroup) group?.name ?: "Group" else contact?.display ?: "…"
    val readOnly = (isGroup && (group?.removed == true || group?.dissolved == true)) || contact?.state == ContactState.BLOCKED

    DisposableEffect(chat) {
        engine.openChat = chat
        engine.markChatRead(chat)
        onDispose { if (engine.openChat == chat) engine.openChat = null; VoicePlayer.stop() }
    }
    LaunchedEffect(data.messages.size) { engine.markChatRead(chat) }

    var text by remember { mutableStateOf(TextFieldValue("")) }
    var stripAsk by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val autoTr by rememberQuery(false, chat) { engine.autoTranslate(chat) }
    var viewOnce by remember { mutableStateOf(false) }
    var hd by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var onceText by remember { mutableStateOf<MessageRow?>(null) }
    var preview by remember { mutableStateOf<LinkPreview?>(null) }
    var dismissedUrl by remember { mutableStateOf<String?>(null) }
    var replyTo by remember(chat) { mutableStateOf<MessageRow?>(null) }
    var highlight by remember { mutableStateOf<String?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var editing by remember(chat) { mutableStateOf<MessageRow?>(null) }
    var forwardOf by remember { mutableStateOf<MessageRow?>(null) }
    var showPoll by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var reactionsOf by remember { mutableStateOf<MessageRow?>(null) }
    var emojiFor by remember { mutableStateOf<MessageRow?>(null) }
    var searching by remember(chat) { mutableStateOf(false) }
    var searchQuery by remember(chat) { mutableStateOf("") }
    var searchIndex by remember(chat) { mutableIntStateOf(0) }
    var pinIndex by remember(chat) { mutableIntStateOf(0) }
    val mentions = remember(chat) { mutableStateMapOf<String, String>() }   // name -> pub
    var focused by remember(chat) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // ---- Ember burn: anything that leaves this chat (delete for me / everyone, disappearing timer,
    // a view-once being opened, clear chat) burns away where it was instead of just vanishing.
    val ghosts = remember(chat) { mutableStateMapOf<String, Ghost>() }
    val previous = remember(chat) { arrayOfNulls<Pair<Map<String, MessageRow>, Map<String, FileRow>>>(1) }
    LaunchedEffect(data) {
        if (!data.loaded) return@LaunchedEffect
        val cur = data.messages.associateBy { it.id }
        val old = previous[0]
        // Cipher seal: a view-once opened while this chat wasn't on screen (photo/video viewer, or the other person
        // opened yours) still seals here when you come back — within 30 s.
        run {
            val t = System.currentTimeMillis()
            MeshEngine.recentlyOpened.entries.removeIf { t - it.value > 30_000 }
            if (old == null) for ((id, m) in cur) {
                if (!m.viewOnce || !m.opened || m.deleted) continue
                MeshEngine.recentlyOpened.remove(id) ?: continue
                val f = m.fileId?.let { data.files[it] }?.copy(state = FileState.COMPLETE)
                ghosts[id] = Ghost(m.copy(opened = false), f, replaced = true, at = t, cipher = true)
            }
        }
        if (old != null) {
            val t = System.currentTimeMillis()
            val gone = old.first.values.filter { it.id !in cur }.sortedByDescending { it.created }.take(30)
            for (m in gone) ghosts[m.id] = Ghost(m, m.fileId?.let { old.second[it] }, replaced = false, at = t)
            for ((id, now) in cur) {
                val was = old.first[id] ?: continue
                val turned = (now.deleted && !was.deleted) || (now.viewOnce && now.opened && !was.opened && !was.outgoing)
                // A view-once just opened (by you, or — new — by them on your sent one) seals with the Cipher effect.
                val sealed = now.viewOnce && now.opened && !was.opened && !now.deleted
                if (sealed) MeshEngine.recentlyOpened.remove(id)
                if (turned || sealed) ghosts[id] = Ghost(was, was.fileId?.let { old.second[it] }, replaced = true, at = t, cipher = sealed)
            }
        }
        previous[0] = cur to data.files
    }
    // With live status on, opening a chat connects to them right away, so typing/online and messages are instant.
    LaunchedEffect(chat, "warm") { if (!isGroup && engine.sharePresence) withContext(Dispatchers.IO) { engine.warmUp(chat) } }
    DisposableEffect(chat, "presence") { onDispose { engine.activityDone(chat) } }
    LaunchedEffect(chat) {
        // Safety net for burns that scrolled out of view before finishing.
        while (true) {
            delay(600)
            val t = System.currentTimeMillis()
            ghosts.filterValues { t - it.at > 1800 }.keys.forEach { ghosts.remove(it) }
        }
    }
    val display = remember(data.messages, ghosts.size) {
        val removed = ghosts.values.filter { !it.replaced && data.messages.none { m -> m.id == it.m.id } }.map { it.m }
        (data.messages + removed).sortedBy { it.created }.asReversed()
    }
    val byId = remember(data.messages) { data.messages.associateBy { it.id } }
    fun nameOf(pub: String): String = when {
        pub == engine.identity.publicHex -> "You"
        isGroup -> data.names[pub] ?: engine.db.memberName(gid, pub)
        else -> contact?.display ?: "Them"
    }
    fun jumpTo(id: String) {
        val idx = display.indexOfFirst { it.id == id }
        if (idx < 0) { ctx.toast("The original message isn't on this phone anymore"); return }
        scope.launch { listState.animateScrollToItem(idx); highlight = id; delay(1400); if (highlight == id) highlight = null }
    }
    val searchHits = remember(data.messages, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.length < 2) emptyList() else data.messages.filter {
            it.kind in MsgKind.CONTENT && !it.deleted && !it.viewOnce &&
                (it.body.lowercase().contains(q) || it.meta.optString("caption", "").lowercase().contains(q) || it.meta.optString("transcript", "").lowercase().contains(q))
        }.asReversed().map { it.id }   // newest first
    }
    LaunchedEffect(searchHits) { searchIndex = 0; searchHits.firstOrNull()?.let { jumpTo(it) } }
    LaunchedEffect(data.loaded, focus) {
        if (data.loaded && focus != null && !focused) { focused = true; delay(250); jumpTo(focus) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(data.messages.lastOrNull()?.id) { if (data.messages.isNotEmpty()) listState.animateScrollToItem(0) }

    // Link preview while typing (sender side only, if allowed in Privacy settings).
    val url = remember(text.text) { LinkPreviews.firstUrl(text.text) }
    LaunchedEffect(url) {
        if (url == null) { preview = null; return@LaunchedEffect }
        if (url == dismissedUrl || preview?.url == url) return@LaunchedEffect
        if (!withContext(Dispatchers.IO) { engine.db.getBool(Settings.LINK_PREVIEWS, true) }) return@LaunchedEffect
        delay(700)
        preview = LinkPreviews.fetch(url)
    }

    fun sendPrepared(label: String, block: suspend () -> Prepared) {
        val once = viewOnce
        busy = label
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { block() }
                engine.sendPrepared(chat, p, "", once, replyTo)
                viewOnce = false; replyTo = null
            } catch (e: Exception) {
                ctx.toast(e.message ?: "Couldn't prepare that file")
            } finally { busy = null }
        }
    }
    suspend fun photoQ() = withContext(Dispatchers.IO) { if (hd || engine.db.getSetting(Settings.PHOTO_QUALITY, "STANDARD") == "HD") PhotoQuality.HD else PhotoQuality.STANDARD }
    suspend fun videoQ() = withContext(Dispatchers.IO) { try { VideoQuality.valueOf(engine.db.getSetting(Settings.VIDEO_QUALITY, "STANDARD")) } catch (e: Exception) { VideoQuality.STANDARD } }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendPrepared("Compressing photo…") { SecureMedia.prepareImage(ctx, uri, photoQ()) }
    }
    // Videos and photos/videos sent as files can carry location, dates and the phone's model. Ask (or follow
    // the saved choice in Privacy) before sending. Photos sent as photos are always re-encoded: nothing survives.
    fun withStripChoice(applies: Boolean, go: (Boolean) -> Unit) {
        if (!applies) { go(false); return }
        scope.launch {
            when (withContext(Dispatchers.IO) { engine.db.getSetting(Settings.STRIP_META, "ask") }) {
                "always" -> go(true)
                "never" -> go(false)
                else -> stripAsk = go
            }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) withStripChoice(true) { strip ->
            sendPrepared("Compressing video… this can take a moment") { SecureMedia.prepareVideo(ctx, uri, videoQ(), strip) }
        }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) withStripChoice(SecureMedia.hasStrippableMetadata(ctx, uri)) { strip ->
            sendPrepared("Compressing and encrypting…") { SecureMedia.prepareDocument(ctx, uri, strip) }
        }
    }
    val captureFile = remember { File(ctx.cacheDir, "capture/cap.jpg").apply { parentFile?.mkdirs() } }
    val captureUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", captureFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        // The camera's plain photo never outlives this step: encrypted and deleted, or deleted if cancelled.
        if (ok) sendPrepared("Compressing photo…") { try { SecureMedia.prepareImage(ctx, captureUri, photoQ()) } finally { captureFile.delete() } }
        else captureFile.delete()
    }
    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) camera.launch(captureUri) else ctx.toast("Camera permission is needed to take a photo")
    }
    fun sendLocationNow() {
        val once = viewOnce
        busy = "Finding your location…"
        scope.launch {
            val loc = LocationHelper.current(ctx)
            busy = null
            if (loc == null) ctx.toast("Couldn't get a location. Turn on location and try outdoors for GPS.")
            else { engine.sendLocation(chat, loc.latitude, loc.longitude, loc.accuracy, once, replyTo); viewOnce = false; replyTo = null }
        }
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) sendLocationNow()
        else ctx.toast("Location permission is needed to share where you are")
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ctx.toast("Hold the mic button to record") else ctx.toast("Microphone permission is needed for voice messages")
    }

    Column(Modifier.fillMaxSize().background(Mura.Ink).imePadding()) {
        // ---------------- header
        if (searching) ChatSearchBar(searchQuery, { searchQuery = it }, searchHits.size, searchIndex,
            onPrev = { if (searchHits.isNotEmpty()) { searchIndex = (searchIndex - 1 + searchHits.size) % searchHits.size; jumpTo(searchHits[searchIndex]) } },
            onNext = { if (searchHits.isNotEmpty()) { searchIndex = (searchIndex + 1) % searchHits.size; jumpTo(searchHits[searchIndex]) } },
            onClose = { searching = false; searchQuery = "" })
        else Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(MIcons.Back, "Back") { nav.back() }
            Avatar(chat, title, 42.dp, photo = if (isGroup) group?.avatar else contact?.avatar, group = isGroup)
            // Tap the name: shared media, links & docs (safety code is in ⋮ and on that screen). Groups open Group info.
            Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable { if (isGroup) nav.go(Screen.GroupInfo(gid)) else nav.go(Screen.ChatMedia(chat)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (contact?.verified == true) { Spacer(Modifier.width(6.dp)); Icon(MIcons.Shield, "Verified", tint = Mura.Mint, modifier = Modifier.size(16.dp)) }
                    if (data.timer > 0) { Spacer(Modifier.width(6.dp)); Icon(MIcons.Timer, "Disappearing messages: ${timerLabel(data.timer)}", tint = Mura.Amber, modifier = Modifier.size(15.dp)) }
                }
                val livePresence by engine.presence.collectAsState()
                val live = if (isGroup) null else livePresence[chat]
                val tNow = System.currentTimeMillis()
                val liveTyping = live?.typing(tNow) == true
                val liveRecording = live?.recording(tNow) == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isGroup) { Box(Modifier.size(7.dp).clip(CircleShape).background(if (linked) Mura.Mint else Mura.Muted)); Spacer(Modifier.width(6.dp)) }
                    if (liveTyping && !isGroup) com.mura.mesh.ui.CipherTyping("typing", Mura.Mint, MaterialTheme.typography.labelSmall)
                    else Text(
                        when {
                            isGroup -> "${data.memberCount} members · tap for info"
                            liveTyping -> "typing…"
                            liveRecording -> "recording audio…"
                            live?.online == true -> "online" + (if (linkedOnline) " · via Tor" else if (linked) " · nearby" else "")
                            linkedOnline -> "Online · via Tor · end-to-end encrypted"
                            linked -> "In range · direct link"
                            else -> "Out of range · messages will hop · ${agoLabel(contact?.lastSeen ?: 0)}"
                        },
                        style = MaterialTheme.typography.labelSmall, color = if (liveTyping || liveRecording) Mura.Mint else Mura.Soft,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Why isn't it sending? Live Tor status for this contact.
                if (!isGroup && !linked) {
                    var tick by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
                    val info = remember(tick, onlineState) { engine.online.dialInfo(chat) }
                    val line = when {
                        contact?.state == ContactState.BLOCKED -> null
                        onlineState.status != OnlineTransport.Status.ON -> "Internet: ${onlineState.note.ifBlank { "off" }}"
                        contact?.onion == null -> "No internet address for them yet — scan their QR code again (or wait for their profile)"
                        info != null -> "Tor: ${info.text}"
                        else -> "Tor: waiting to reach them"
                    }
                    var showTor by remember { mutableStateOf(false) }
                    if (line != null) Text(line, style = MaterialTheme.typography.labelSmall, color = Mura.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showTor = true })
                    if (showTor && line != null) {
                        val me = engine.online.selfReachable
                        AlertDialog(
                            onDismissRequest = { showTor = false }, containerColor = Mura.Surface,
                            title = { Text("Internet delivery") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("This phone: " + (if (onlineState.status == OnlineTransport.Status.ON) "online through Tor" else onlineState.note.ifBlank { "offline" }),
                                        style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                                    Text("Your address: " + when (me) { true -> "reachable"; false -> "not reachable yet"; null -> "checking…" },
                                        style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                                    Text("To them: " + line.removePrefix("Tor: "), style = MaterialTheme.typography.bodySmall, color = Mura.Text)
                                    info?.lastError?.takeIf { it.isNotBlank() && !line.contains(it) }?.let {
                                        Text("Last problem: $it", style = MaterialTheme.typography.bodySmall, color = Mura.Amber)
                                    }
                                    Text("Both phones must have Mura open or running in the background, connected to Tor, with the same Mura version. " +
                                        "\"Address isn't published\" means their phone isn't online on Tor right now.",
                                        style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
                                }
                            },
                            confirmButton = { TextButton(onClick = { showTor = false; engine.online.dialNow(chat) }) { Text("Try now", color = Mura.Amber) } },
                            dismissButton = { TextButton(onClick = { showTor = false }) { Text("Close", color = Mura.Soft) } },
                        )
                    }
                }
            }
            if (!isGroup) IconBtn(MIcons.Phone, "Voice call", bg = Mura.Surface, enabled = !readOnly) {
                fun call() {
                    val err = engine.calls.startCall(chat, title)
                    if (err != null) { if (err.startsWith("Microphone")) micPerm.launch(Manifest.permission.RECORD_AUDIO) else ctx.toast(err) }
                    else nav.go(Screen.Call)
                }
                // Not in Bluetooth range, but both of us can be online: open a Tor link first (needs them online too).
                if (!linked && contact?.onion != null && onlineState.status == OnlineTransport.Status.ON) {
                    ctx.toast("Connecting over Tor…")
                    scope.launch {
                        if (engine.online.ensureLink(chat, 30_000)) call()
                        else ctx.toast("$title isn't online or in range. Send a voice message — it'll be delivered when possible.")
                    }
                } else call()
            }
            Box {
                IconBtn(MIcons.More, "More") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Mura.Surface2) {
                    DropdownMenuItem(text = { Text("Search") }, leadingIcon = { Icon(MIcons.Search, null) }, onClick = { menu = false; searching = true })
                    if (!isGroup && contact?.onion != null) DropdownMenuItem(text = { Text("Reconnect over Tor now") }, leadingIcon = { Icon(MIcons.Refresh, null) },
                        onClick = { menu = false; engine.online.dialNow(chat); ctx.toast("Trying to reach them through Tor…") })
                    DropdownMenuItem(text = { Text(if (autoTr) "Auto-translate: on" else "Auto-translate") },
                        leadingIcon = { Icon(MIcons.Translate, null, tint = if (autoTr) Mura.Amber else LocalContentColor.current) },
                        onClick = {
                            menu = false; engine.setAutoTranslate(chat, !autoTr)
                            ctx.toast(if (!autoTr) "New and recent messages will be translated on this phone" else "Auto-translate off")
                        })
                    DropdownMenuItem(text = { Text("Wallpaper") }, leadingIcon = { Icon(MIcons.Image, null) }, onClick = { menu = false; nav.go(Screen.Appearance(chat)) })
                    if (isGroup) {
                        DropdownMenuItem(text = { Text("Group info") }, onClick = { menu = false; nav.go(Screen.GroupInfo(gid)) })
                    } else {
                        DropdownMenuItem(text = { Text("Verify safety code") }, onClick = { menu = false; showSafety = true })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; showRename = true })
                    }
                    DropdownMenuItem(text = { Text(if (data.timer > 0) "Disappearing · ${timerLabel(data.timer)}" else "Disappearing messages") },
                        leadingIcon = { Icon(MIcons.Timer, null) }, onClick = { menu = false; showTimer = true }, enabled = !readOnly)
                    DropdownMenuItem(text = { Text(if (data.locked) "Unlock chat" else "Lock chat") }, leadingIcon = { Icon(MIcons.Fingerprint, null) }, onClick = {
                        menu = false
                        if (data.locked) ctx.withAuth("Unlock this chat") { engine.setLocked(chat, false) }
                        else if ((ctx.findActivity() as? MainActivity)?.canUseLock() == false) ctx.toast("Set up a fingerprint or screen lock in Android settings first")
                        else { engine.setLocked(chat, true); ChatGate.unlocked.add(chat) }
                    })
                    DropdownMenuItem(text = { Text(if (data.muted) "Unmute notifications" else "Mute notifications") },
                        onClick = { menu = false; scope.launch(Dispatchers.IO) { engine.db.setMuted(chat, !data.muted) } })
                    DropdownMenuItem(text = { Text("Clear chat") }, onClick = { menu = false; confirmClear = true })
                    if (!isGroup) {
                        if (contact?.state == ContactState.BLOCKED)
                            DropdownMenuItem(text = { Text("Unblock") }, onClick = { menu = false; engine.unblock(chat) })
                        else
                            DropdownMenuItem(text = { Text("Block", color = Mura.Red) }, onClick = { menu = false; engine.block(chat); nav.back() })
                    }
                }
            }
        }

        if (contact?.state == ContactState.REQUEST) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Mura.BlueDeep, RoundedCornerShape(16.dp)).padding(14.dp)) {
                Text("$title isn't in your contacts", style = MaterialTheme.typography.titleSmall)
                Text("Files from them won't download until you accept. Compare safety codes to be sure who it is.",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { showRename = true }) { Text("Accept", color = Mura.Amber) }
                    TextButton(onClick = { engine.block(chat); nav.back() }) { Text("Block", color = Mura.Red) }
                }
            }
        }

        val pinned = data.pins.mapNotNull { byId[it] }.filter { !it.deleted }
        PinnedBar(pinned, pinIndex) { m -> jumpTo(m.id); pinIndex = (pinIndex + 1) % pinned.size.coerceAtLeast(1) }

        // ---------------- messages
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().chatWallpaper(chat), state = listState, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val msgs = display
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                val olderSameSender = i + 1 < msgs.size && msgs[i + 1].sender == m.sender && msgs[i + 1].kind != MsgKind.SYSTEM
                val ghost = ghosts[m.id]
                // Day header ("Today", "Yesterday", "Mon 12 Oct") above the first message of each day.
                val newDay = i + 1 >= msgs.size || !sameDay(msgs[i + 1].created, m.created)
                Column(Modifier.animateItem()) {
                if (newDay) DayChip(m.created)
                Box {
                    val shown = ghost?.m ?: m
                    EmberBurn(active = ghost != null && !ghost.cipher, seed = m.id.hashCode(), onDone = { ghosts.remove(m.id) }) {
                    CipherSeal(active = ghost?.cipher == true, seed = m.id.hashCode(), onDone = { ghosts.remove(m.id) }) {
                        val qId = shown.meta.optString("r_id", "")
                        MessageItem(
                            shown, shown.fileId?.let { ghost?.file ?: data.files[it] }, if (ghost == null) m.fileId?.let { sendProgress[it] } else null,
                            senderName = if (isGroup && !shown.outgoing && !olderSameSender) data.names[shown.sender] else null,
                            canDeleteForAll = ghost == null && engine.canDeleteForEveryone(m),
                            onOpenOnceText = { onceText = it },
                            interactive = ghost == null,
                            quoted = if (qId.isNotEmpty()) byId[qId] else null,
                            nameOf = ::nameOf,
                            onReply = if (readOnly || ghost != null) null else ({ replyTo = m }),
                            onQuoteClick = ::jumpTo,
                            highlight = highlight == m.id,
                            reactions = data.reactions[m.id] ?: emptyList(),
                            votes = data.votes[m.id] ?: emptyMap(),
                            listOps = data.listOps[m.id] ?: emptyList(),
                            pinned = m.id in data.pins,
                            actions = if (ghost != null || readOnly) null else MsgActions(
                                canEdit = engine.canEdit(m), canPin = engine.canPin(chat),
                                react = { e -> engine.react(m.id, e) }, moreReactions = { emojiFor = m }, showReactions = { reactionsOf = m },
                                edit = { editing = m; replyTo = null; text = TextFieldValue(m.body, TextRange(m.body.length)) },
                                forward = { forwardOf = m }, star = { on -> engine.star(m.id, on) }, pin = { on -> engine.pin(m.id, on) },
                                vote = { c -> engine.vote(m.id, c) }, toggleItem = { item, on -> engine.listOp(m.id, if (on) Payload.OP_CHECK else Payload.OP_UNCHECK, item) },
                                openList = { nav.go(Screen.Checklist(m.id)) }, sendNow = { engine.sendScheduledNow(m.id) },
                                autoTranslate = autoTr,
                            ),
                        )
                    }
                    }
                }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                    Row(Modifier.background(Mura.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(MIcons.Lock, null, tint = Mura.Mint, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                isGroup -> "Group messages are end-to-end encrypted and signed by each sender"
                                contact?.verified == true -> "Sealed end to end · key verified"
                                else -> "Sealed end to end · tap the name to verify"
                            },
                            style = MaterialTheme.typography.bodySmall, color = Mura.Soft,
                        )
                    }
                }
            }
        }

        busy?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Mura.Amber, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
            }
        }

        if (readOnly) {
            Text(
                when {
                    group?.dissolved == true -> "This group was deleted by an admin."
                    group?.removed == true -> "You're no longer a member of this group."
                    else -> "You blocked $title. Unblock from the menu to message them."
                },
                style = MaterialTheme.typography.bodySmall, color = Mura.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
            )
        } else {
            editing?.let { e -> EditBar(e) { editing = null; text = TextFieldValue("") } }
            replyTo?.let { r -> ReplyBar(r, nameOf(if (r.outgoing) engine.identity.publicHex else r.sender)) { replyTo = null } }
            preview?.let { pv -> ComposerPreview(pv) { dismissedUrl = pv.url; preview = null } }
            if (autoTr && !isGroup && editing == null && text.text.isNotBlank()) {
                DraftTranslateBar(chat, text.text) { t -> text = TextFieldValue(t, TextRange(t.length)) }
            }
            // "@" in a group: suggest members whose name starts with what's typed after it.
            val mentionQuery = remember(text) {
                if (!isGroup) null else {
                    val before = text.text.substring(0, text.selection.start.coerceIn(0, text.text.length))
                    Regex("(?:^|\\s)@([\\p{L}\\p{N}_.]{0,24})$").find(before)?.groupValues?.get(1)
                }
            }
            if (mentionQuery != null) {
                MentionPopup(data.members.filter { it.second.startsWith(mentionQuery, ignoreCase = true) || it.second.split(" ").any { w -> w.startsWith(mentionQuery, ignoreCase = true) } }) { pub, name ->
                    val cur = text.selection.start.coerceIn(0, text.text.length)
                    val at = text.text.lastIndexOf('@', cur - 1)
                    if (at >= 0) {
                        val t = text.text.substring(0, at) + "@" + name + " " + text.text.substring(cur)
                        text = TextFieldValue(t, TextRange(at + name.length + 2))
                        mentions[name] = pub
                    }
                }
            }
            Composer(
                text = text, viewOnce = viewOnce,
                onText = {
                    // Live status (if shared): "typing…" on their screen while you type; cleared when the box is emptied.
                    if (it.text != text.text && !isGroup) { if (it.text.isNotEmpty()) engine.typing(chat) else engine.activityDone(chat) }
                    text = it
                },
                onRecording = { on -> if (!isGroup) { if (on) engine.recordingVoice(chat) else engine.activityDone(chat) } }, onToggleOnce = { viewOnce = !viewOnce },
                emojiOpen = showEmoji,
                onEmoji = {
                    showEmoji = !showEmoji
                    if (showEmoji) { keyboard?.hide(); focusManager.clearFocus() }
                },
                onAttach = { showAttach = true },
                onSend = {
                    val e = editing
                    if (e != null) {
                        engine.edit(e.id, text.text)
                        editing = null; text = TextFieldValue("")
                    } else {
                        val pv = preview?.takeIf { LinkPreviews.firstUrl(text.text) == it.url }
                        val ms = mentions.entries.map { (name, pub) -> Mention(pub.hexToBytes(), name) }
                        engine.sendText(chat, text.text, viewOnce, pv, replyTo, mentions = ms)
                        engine.activityDone(chat)
                        text = TextFieldValue(""); viewOnce = false; preview = null; dismissedUrl = null; replyTo = null; mentions.clear()
                    }
                },
                onLongSend = if (editing == null && !viewOnce) ({ showSchedule = true }) else null,
                // With the view-once (①) toggle on, the voice note can be played a single time, then it's erased.
                onVoice = { p -> engine.sendPrepared(chat, p, "", viewOnce, replyTo); viewOnce = false; replyTo = null },
                needMic = { micPerm.launch(Manifest.permission.RECORD_AUDIO) },
            )
            if (showEmoji) Box(Modifier.navigationBarsPadding()) {
                EmojiPicker { e ->
                    val t = text
                    val start = minOf(t.selection.start, t.selection.end).coerceIn(0, t.text.length)
                    val end = maxOf(t.selection.start, t.selection.end).coerceIn(0, t.text.length)
                    text = TextFieldValue(t.text.substring(0, start) + e + t.text.substring(end), TextRange(start + e.length))
                }
            }
        }
    }

    if (showAttach) {
        ModalBottomSheet(onDismissRequest = { showAttach = false }, containerColor = Mura.Surface) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Send", style = MaterialTheme.typography.titleLarge)
                Text("Everything is compressed on your phone, then encrypted, so it travels fast over Bluetooth.",
                    style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    AttachTile(MIcons.Image, "Photo") { showAttach = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    AttachTile(MIcons.Camera, "Camera") {
                        showAttach = false
                        if (MeshPermissions.granted(ctx, Manifest.permission.CAMERA)) camera.launch(captureUri) else cameraPerm.launch(Manifest.permission.CAMERA)
                    }
                    AttachTile(MIcons.Video, "Video") { showAttach = false; videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                    AttachTile(MIcons.Pdf, "Document") { showAttach = false; docPicker.launch(arrayOf("*/*")) }
                    AttachTile(MIcons.Pin, "Location") {
                        showAttach = false
                        if (MeshPermissions.granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) sendLocationNow()
                        else locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                    AttachTile(MIcons.Poll, "Poll") { showAttach = false; showPoll = true }
                    AttachTile(MIcons.ListIcon, "List") { showAttach = false; showList = true }
                    AttachTile(MIcons.Brush, "Doodle") { showAttach = false; nav.go(Screen.Doodle(chat)) }
                }
                ToggleRow(MIcons.Once, "View once", "They can open it a single time. Screenshots are blocked while it's open.", viewOnce) { viewOnce = !viewOnce }
                ToggleRow(MIcons.Image, "HD photo", "Sharper photos, larger files (2560 px instead of 1600 px).", hd) { hd = !hd }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showSafety && contact != null) {
        val code = remember(chat) { Crypto.safetyCode(engine.identity.publicKey, chat.hexToBytes()) }
        AlertDialog(
            onDismissRequest = { showSafety = false }, containerColor = Mura.Surface,
            title = { Text("Safety code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Compare these numbers with $title's screen, in person. If they match, nobody is in the middle.",
                        style = MaterialTheme.typography.bodyMedium, color = Mura.Soft)
                    Text(code.chunked(18).joinToString("\n"), fontFamily = Mura.Mono, style = MaterialTheme.typography.titleMedium, color = Mura.Text)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSafety = false
                    scope.launch(Dispatchers.IO) {
                        engine.db.upsertContact(chat, Crypto.nodeId(chat.hexToBytes()).toHex(), null, contact.state, System.currentTimeMillis(), verified = true)
                    }
                }) { Text("They match", color = Mura.Amber) }
            },
            dismissButton = { TextButton(onClick = { showSafety = false }) { Text("Close", color = Mura.Soft) } },
        )
    }

    if (showRename && contact != null) {
        var newName by remember { mutableStateOf(contact.name.ifBlank { contact.pname }) }
        AlertDialog(
            onDismissRequest = { showRename = false }, containerColor = Mura.Surface,
            title = { Text(if (contact.state == ContactState.REQUEST) "Add to contacts" else "Rename") },
            text = {
                OutlinedTextField(newName, { newName = it.take(32) }, singleLine = true, label = { Text("Name") }, colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            },
            confirmButton = {
                TextButton(onClick = {
                    showRename = false
                    if (contact.state == ContactState.REQUEST) engine.addContact(chat, newName, contact.verified) else engine.rename(chat, newName)
                }) { Text("Save", color = Mura.Amber) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false }, containerColor = Mura.Surface,
            title = { Text("Clear this chat?") },
            text = { Text("All messages and files in this chat will be erased from this phone. Others keep their copies.", color = Mura.Soft) },
            confirmButton = { TextButton(onClick = { confirmClear = false; engine.clearChat(chat) }) { Text("Clear", color = Mura.Red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }

    if (showPoll) PollCreatorDialog(onDismiss = { showPoll = false }) { q, opts, multi -> engine.sendPoll(chat, q, opts, multi, replyTo); replyTo = null }
    if (showList) ChecklistCreatorDialog(onDismiss = { showList = false }) { t, items -> engine.sendChecklist(chat, t, items, replyTo); replyTo = null }
    if (showSchedule) ScheduleDialog(onDismiss = { showSchedule = false }) { at ->
        val ms = mentions.entries.map { (name, pub) -> Mention(pub.hexToBytes(), name) }
        engine.scheduleText(chat, text.text, at, replyTo, ms)
        ctx.toast("Scheduled for ${scheduledLabel(at)}")
        text = TextFieldValue(""); replyTo = null; mentions.clear(); preview = null
    }
    forwardOf?.let { m -> ForwardDialog(chat, onDismiss = { forwardOf = null }) { chats -> engine.forward(m.id, chats); ctx.toast("Forwarded") } }
    reactionsOf?.let { m ->
        ReactionsDialog(data.reactions[m.id] ?: emptyList(), engine.identity.publicHex, ::nameOf,
            onRemoveMine = { (data.reactions[m.id] ?: emptyList()).firstOrNull { it.sender == engine.identity.publicHex }?.let { engine.react(m.id, it.emoji) } },
            onDismiss = { reactionsOf = null })
    }
    emojiFor?.let { m -> EmojiPickDialog(onPick = { e -> engine.react(m.id, e) }, onDismiss = { emojiFor = null }) }

    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false }, containerColor = Mura.Surface,
            icon = { Icon(MIcons.Timer, null, tint = Mura.Amber) },
            title = { Text("Disappearing messages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("New messages in this chat erase themselves from every phone after this time. " +
                        "It counts from when each message arrives, and applies to ${if (isGroup) "everyone in the group" else "both of you"}.",
                        style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                    Spacer(Modifier.height(6.dp))
                    for (secs in Payload.TIMERS) {
                        val on = data.timer == secs
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Mura.AmberDeep else Color.Transparent)
                                .clickable(role = Role.RadioButton) { showTimer = false; engine.setTimer(chat, secs) }.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (on) Mura.Amber else Mura.Line, CircleShape), contentAlignment = Alignment.Center) {
                                if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Mura.Amber))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(timerLabel(secs), style = MaterialTheme.typography.bodyLarge, color = if (on) Mura.Amber else Mura.Text)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTimer = false }) { Text("Close", color = Mura.Soft) } },
        )
    }

    stripAsk?.let { go ->
        var keepChoice by remember { mutableStateOf(false) }
        fun choose(strip: Boolean) {
            stripAsk = null
            if (keepChoice) engine.db.setSetting(Settings.STRIP_META, if (strip) "always" else "never")
            go(strip)
        }
        AlertDialog(
            onDismissRequest = { stripAsk = null }, containerColor = Mura.Surface,
            icon = { Icon(MIcons.Shield, null, tint = Mura.Mint) },
            title = { Text("Remove hidden details?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Videos and photos can secretly contain where they were taken, the date and time, and your phone's make and model. " +
                        "Mura can remove all of that before sending — the picture and sound stay exactly the same.",
                        style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { keepChoice = !keepChoice }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (keepChoice) Mura.Amber else Color.Transparent)
                            .border(2.dp, if (keepChoice) Mura.Amber else Mura.Line, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                            if (keepChoice) Icon(MIcons.Check, null, tint = Mura.OnAmber, modifier = Modifier.size(14.dp))
                        }
                        Text("Remember my choice (change it in Privacy)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose(true) }) { Text("Remove (recommended)", color = Mura.Amber) } },
            dismissButton = { TextButton(onClick = { choose(false) }) { Text("Send as is", color = Mura.Soft) } },
        )
    }

    onceText?.let { m ->
        SecureWindow()
        AlertDialog(
            onDismissRequest = { onceText = null; engine.consumeViewOnce(m.id) }, containerColor = Mura.Surface,
            title = { Text("View once") },
            text = { Text(m.body, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { onceText = null; engine.consumeViewOnce(m.id) }) { Text("Done — erase it", color = Mura.Amber) } },
        )
    }
}

@Composable
private fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Mura.AmberDeep else Mura.Surface2)
            .clickable(role = Role.Switch, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (on) Mura.Amber else Mura.Soft)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
        }
        Text(if (on) "ON" else "OFF", style = MaterialTheme.typography.labelSmall, color = if (on) Mura.Amber else Mura.Muted)
    }
}

@Composable
private fun AttachTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(6.dp)) {
        Box(Modifier.size(54.dp).background(Mura.AmberDeep, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Mura.Amber)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Mura.Soft)
    }
}

@Composable
private fun ComposerPreview(p: LinkPreview, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Mura.Surface, RoundedCornerShape(14.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val img = remember(p) { if (p.image.isNotEmpty()) BitmapFactory.decodeByteArray(p.image, 0, p.image.size)?.asImageBitmap() else null }
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        else Icon(MIcons.Link, null, tint = Mura.Amber)
        Column(Modifier.weight(1f)) {
            Text(p.title.ifBlank { p.url }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.description.ifBlank { Uri.parse(p.url).host ?: "" }, style = MaterialTheme.typography.bodySmall, color = Mura.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(MIcons.Close, "Remove preview", size = 36.dp, onClick = onClose)
    }
}

// =====================================================================================
// Composer: hold the mic to record, release to send, slide left to delete
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    text: TextFieldValue, onText: (TextFieldValue) -> Unit, viewOnce: Boolean, onToggleOnce: () -> Unit,
    emojiOpen: Boolean, onEmoji: () -> Unit,
    onAttach: () -> Unit, onSend: () -> Unit, onVoice: (Prepared) -> Unit, needMic: () -> Unit,
    onLongSend: (() -> Unit)? = null,
    onRecording: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val cancelPx = with(density) { 120.dp.toPx() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(recording) {
        if (!recording) onRecording(false)
        while (recording) { elapsed = recorder.elapsedMs(); onRecording(true); delay(100) }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    // Voice orb: how loud you are while recording (read only when drawing — no recomposition).
    val micLevel = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(recording) {
        micLevel.floatValue = 0f
        while (recording) { micLevel.floatValue = micLevel.floatValue * 0.5f + recorder.level() * 0.5f; delay(60) }
    }

    val start = rememberUpdatedState {
        if (!MeshPermissions.granted(ctx, Manifest.permission.RECORD_AUDIO)) { needMic(); false }
        else if (recorder.start()) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); recording = true; dragX = 0f; true }
        else { ctx.toast("Couldn't start the microphone"); false }
    }
    val finish = rememberUpdatedState { cancelled: Boolean ->
        recording = false
        if (cancelled) { recorder.cancel(); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        else scope.launch {
            val p = withContext(Dispatchers.IO) { recorder.stop() }
            if (p == null) ctx.toast("Hold to record, release to send") else onVoice(p)
        }
        dragX = 0f
    }

    Row(
        Modifier.fillMaxWidth().then(if (emojiOpen) Modifier else Modifier.navigationBarsPadding())
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recording) {
            Row(Modifier.weight(1f).height(48.dp).background(Mura.Surface, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Mura.Red))
                Spacer(Modifier.width(10.dp))
                Text(formatDuration(elapsed.toInt()), fontFamily = Mura.Mono, color = Mura.Text)
                Spacer(Modifier.weight(1f))
                val pastCancel = dragX < -cancelPx
                Row(Modifier.offset { IntOffset(dragX.coerceIn(-cancelPx, 0f).roundToInt() / 2, 0) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (pastCancel) MIcons.Trash else MIcons.Back, null, tint = if (pastCancel) Mura.Red else Mura.Muted, modifier = Modifier.size(16.dp))
                    Text(if (pastCancel) "Release to delete" else if (viewOnce) "One-time voice note · slide to cancel" else "Slide to cancel", style = MaterialTheme.typography.bodySmall, color = if (pastCancel) Mura.Red else Mura.Muted)
                }
            }
        } else {
            IconBtn(MIcons.Plus, "Attach photo, video, document or location", bg = Mura.Surface, size = 44.dp, onClick = onAttach)
            Row(
                Modifier.weight(1f).background(Mura.Surface, RoundedCornerShape(22.dp))
                    .border(1.dp, if (viewOnce) Mura.Amber else Mura.Line, RoundedCornerShape(22.dp)).padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(MIcons.Smile, if (emojiOpen) "Show keyboard" else "Emoji", tint = if (emojiOpen) Mura.Amber else Mura.Muted, size = 40.dp, onClick = onEmoji)
                BasicTextField(
                    value = text, onValueChange = onText,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Mura.Text),
                    cursorBrush = SolidColor(Mura.Amber), maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    decorationBox = { inner -> Box { if (text.text.isEmpty()) Text(if (viewOnce) "View-once message…" else "Sealed message…", color = Mura.Muted); inner() } },
                )
                IconBtn(MIcons.Once, if (viewOnce) "View once is on" else "Turn on view once", tint = if (viewOnce) Mura.Amber else Mura.Muted, size = 40.dp, onClick = onToggleOnce)
            }
        }

        val sendMode = text.text.isNotBlank() && !recording
        Box(com.mura.mesh.ui.voiceOrb(recording, { micLevel.floatValue }, Mura.Red), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(if (recording) 60.dp else 48.dp).clip(CircleShape).background(if (recording) Mura.Red else Mura.Amber)
                .then(
                    if (sendMode) Modifier.combinedClickable(role = Role.Button, onClickLabel = "Send", onClick = onSend,
                        onLongClickLabel = if (onLongSend != null) "Send later" else null, onLongClick = onLongSend)
                    else Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (!start.value()) return@awaitEachGesture
                            var cancelled = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                dragX = ch.position.x - down.position.x
                                cancelled = dragX < -cancelPx
                                if (!ch.pressed) break
                                ch.consume()
                            }
                            finish.value(cancelled)
                        }
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (sendMode) MIcons.Send else MIcons.Mic, if (sendMode) "Send" else "Hold to record a voice message",
                tint = if (recording) Color.White else Mura.OnAmber, modifier = Modifier.size(22.dp))
        }
        }
    }
}

// =====================================================================================
// Message bubbles
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    m: MessageRow, file: FileRow?, sending: Float?, senderName: String?, canDeleteForAll: Boolean,
    onOpenOnceText: (MessageRow) -> Unit,
    interactive: Boolean = true,
    quoted: MessageRow? = null,
    nameOf: (String) -> String = { "" },
    onReply: (() -> Unit)? = null,
    onQuoteClick: ((String) -> Unit)? = null,
    highlight: Boolean = false,
    reactions: List<ReactionRow> = emptyList(),
    votes: Map<String, List<Int>> = emptyMap(),
    listOps: List<Checklists.Op> = emptyList(),
    pinned: Boolean = false,
    actions: MsgActions? = null,
) {
    val ctx = LocalContext.current
    val engine = requireEngine()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var trRequest by remember(m.id) { mutableIntStateOf(0) }

    if (m.kind == MsgKind.SYSTEM || m.kind == MsgKind.CALL) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
            Row(Modifier.background(Mura.Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    m.kind == MsgKind.CALL -> MIcons.Phone
                    m.body.contains("disappearing", ignoreCase = true) -> MIcons.Timer
                    else -> MIcons.Lock
                }
                Icon(icon, null, tint = if (m.kind == MsgKind.CALL) Mura.Blue else Mura.Amber, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text("${m.body} · ${clockLabel(m.created)}", style = MaterialTheme.typography.bodySmall, color = Mura.Soft, textAlign = TextAlign.Center)
            }
        }
        return
    }

    val mine = m.outgoing
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    val plainText = m.kind == MsgKind.TEXT && !m.viewOnce && !m.deleted
    val bg = if (mine && plainText) Mura.Amber else Mura.Surface
    val fg = if (mine && plainText) Mura.OnAmber else Mura.Text
    val scheduled = m.status == MsgStatus.SCHEDULED
    val isContent = m.kind in MsgKind.CONTENT && !m.deleted && !scheduled
    val me = engine.identity.publicHex
    // Seal & send: only a bubble you sent in the last moments (decided once per message, so scrolling never replays it).
    val justSent = remember(m.id) {
        mine && interactive && !scheduled && !m.deleted && !m.meta.optBoolean("local") && System.currentTimeMillis() - m.created in 0..2_500
    }

    // Swipe right to reply (like WhatsApp): the bubble follows the finger, a reply arrow fades in,
    // and past the threshold a tick of haptics confirms it.
    val drag = remember { Animatable(0f) }
    val maxPx = with(density) { 84.dp.toPx() }
    val triggerPx = with(density) { 58.dp.toPx() }
    val flash by animateColorAsState(if (highlight) Mura.Amber.copy(alpha = 0.16f) else Color.Transparent, label = "flash")
    val canSwipe = interactive && onReply != null && !m.deleted

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(flash)
            .then(if (!canSwipe) Modifier else Modifier.pointerInput(m.id) {
                var armed = false
                detectHorizontalDragGestures(
                    onDragStart = { armed = false },
                    onDragEnd = {
                        if (drag.value >= triggerPx) onReply?.invoke()
                        scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, spring()) } },
                    onHorizontalDrag = { change, amount ->
                        val next = (drag.value + amount * (if (drag.value > triggerPx) 0.35f else 0.8f)).coerceIn(0f, maxPx)
                        if (next >= triggerPx && !armed) { armed = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        if (next < triggerPx) armed = false
                        change.consume()
                        scope.launch { drag.snapTo(next) }
                    },
                )
            }),
    ) {
        if (canSwipe) {
            val p = (drag.value / triggerPx).coerceIn(0f, 1f)
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(34.dp)
                    .graphicsLayer { alpha = p; scaleX = 0.6f + 0.4f * p; scaleY = 0.6f + 0.4f * p }
                    .clip(CircleShape).background(Mura.Surface2),
                contentAlignment = Alignment.Center,
            ) { Icon(MIcons.Reply, null, tint = Mura.Amber, modifier = Modifier.size(18.dp)) }
        }
        Column(
            Modifier.fillMaxWidth().offset { IntOffset(drag.value.roundToInt(), 0) }.padding(top = if (senderName != null) 6.dp else 0.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (senderName != null) {
                val (_, nameColor) = Mura.avatarColors(m.sender)
                Text(senderName, style = MaterialTheme.typography.labelMedium, color = nameColor, modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
            }
            Box {
                Column(
                    Modifier.widthIn(max = 290.dp).then(com.mura.mesh.ui.sealSendEffect(justSent, fromEnd = mine)).clip(shape).background(bg)
                        .then(com.mura.mesh.ui.timerRing(if (interactive && !m.deleted) m.expireAt else 0L, m.created, shape,
                            if (mine && plainText) Mura.OnAmber else Mura.Amber))
                        .then(if (interactive) Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }) else Modifier)
                        .padding(if (m.kind == MsgKind.TEXT || m.deleted) 12.dp else 8.dp),
                ) {
                    if (m.meta.optBoolean("fwd") && !m.deleted) ForwardedLabel(onAmber = mine && plainText)
                    val rId = m.meta.optString("r_id", "")
                    if (rId.isNotEmpty() && !m.deleted) {
                        QuoteBlock(m, quoted, nameOf, onAmber = mine && plainText, onClick = { onQuoteClick?.invoke(rId) })
                    }
                    when {
                        m.deleted -> Text(if (mine) "You deleted this message" else "This message was deleted", color = Mura.Muted,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic))
                        m.viewOnce -> ViewOnceContent(m, file, onOpenOnceText, onLong = { if (interactive) menu = true })
                        m.kind == MsgKind.TEXT -> TranslatableText(m, fg, auto = actions?.autoTranslate == true && !mine, request = trRequest)
                        m.kind == MsgKind.LOCATION -> LocationContent(m)
                        m.kind == MsgKind.FILE && file != null -> FileContent(m, file, sending, onLong = { if (interactive) menu = true })
                        m.kind == MsgKind.POLL -> PollContent(m, votes, me, nameOf) { c -> actions?.vote?.invoke(c) }
                        m.kind == MsgKind.LIST -> ChecklistContent(m, listOps, onToggle = { item, on -> actions?.toggleItem?.invoke(item, on) },
                            onOpen = { actions?.openList?.invoke() })
                        m.kind == MsgKind.DOODLE -> DoodleContent(m)
                        else -> Text(m.body, color = Mura.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    val caption = m.meta.optString("caption", "")
                    if (caption.isNotBlank() && !m.viewOnce && !m.deleted) Text(caption, color = Mura.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(6.dp))
                }
                // Long-press: emoji strip above the bubble, actions below.
                if (menu && actions != null && isContent) {
                    val lift = with(density) { 58.dp.roundToPx() }
                    Popup(alignment = if (mine) Alignment.TopEnd else Alignment.TopStart, offset = IntOffset(0, -lift), onDismissRequest = { menu = false }) {
                        ReactionBar(reactions.firstOrNull { it.sender == me }?.emoji,
                            onPick = { e -> actions.react(e); menu = false }, onMore = { actions.moreReactions(); menu = false })
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Mura.Surface2) {
                    if (scheduled) DropdownMenuItem(text = { Text("Send now") }, leadingIcon = { Icon(MIcons.Send, null) },
                        onClick = { menu = false; actions?.sendNow?.invoke() })
                    if (onReply != null && isContent) DropdownMenuItem(text = { Text("Reply") }, leadingIcon = { Icon(MIcons.Reply, null) },
                        onClick = { menu = false; onReply() })
                    if (actions != null && actions.canEdit && m.kind == MsgKind.TEXT) DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(MIcons.Edit, null) },
                        onClick = { menu = false; actions.edit() })
                    if (actions != null && isContent && !m.viewOnce) DropdownMenuItem(text = { Text("Forward") }, leadingIcon = { Icon(MIcons.Forward, null) },
                        onClick = { menu = false; actions.forward() })
                    if (actions != null && !m.deleted && !scheduled) DropdownMenuItem(text = { Text(if (m.starred) "Unstar" else "Star") },
                        leadingIcon = { Icon(if (m.starred) MIcons.StarFilled else MIcons.Star, null) }, onClick = { menu = false; actions.star(!m.starred) })
                    if (actions != null && actions.canPin && (isContent || pinned)) DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") },
                        leadingIcon = { Icon(MIcons.Pinned, null) }, onClick = { menu = false; actions.pin(!pinned) })
                    if (plainText && !mine && engine.cachedTranslation(m) == null) DropdownMenuItem(text = { Text("Translate") },
                        leadingIcon = { Icon(MIcons.Translate, null) }, onClick = { menu = false; trRequest++ })
                    if (plainText) DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(MIcons.Copy, null) }, onClick = {
                        menu = false
                        copySecurely(ctx, m.body, engine.db.getBool(Settings.CLIPBOARD_CLEAR, true))
                    })
                    DropdownMenuItem(text = { Text(if (scheduled) "Delete scheduled message" else "Delete for me") }, leadingIcon = { Icon(MIcons.Trash, null) },
                        onClick = { menu = false; engine.deleteForMe(m.id) })
                    if (canDeleteForAll) DropdownMenuItem(text = { Text("Delete for everyone", color = Mura.Red) }, leadingIcon = { Icon(MIcons.Flame, null, tint = Mura.Red) },
                        onClick = { menu = false; confirmDelete = true })
                }
            }
            if (reactions.isNotEmpty() && !m.deleted) Box(Modifier.offset(y = (-6).dp)) { ReactionChips(reactions, me) { actions?.showReactions?.invoke() } }
            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pinned) Icon(MIcons.Pinned, "Pinned", tint = Mura.Amber, modifier = Modifier.size(12.dp))
                if (m.starred) Icon(MIcons.StarFilled, "Starred", tint = Mura.Amber, modifier = Modifier.size(12.dp))
                if (m.expireAt > 0) Icon(MIcons.Timer, "Disappears", tint = Mura.Muted, modifier = Modifier.size(12.dp))
                if (scheduled) {
                    Icon(MIcons.Clock, null, tint = Mura.Blue, modifier = Modifier.size(12.dp))
                    Text("Scheduled · ${scheduledLabel(m.sendAt)}", style = MaterialTheme.typography.labelSmall, color = Mura.Blue)
                } else Text(clockLabel(m.created), style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
                if (m.meta.has("edited") && !m.deleted) Text("· edited", style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
                if (mine && !m.meta.optBoolean("local") && !m.deleted && !scheduled) {
                    com.mura.mesh.ui.StatusGlow(m.status, if (m.status == MsgStatus.READ) Mura.Amber else Mura.Mint) { StatusIcon(m.status) }
                    com.mura.mesh.ui.HopTrail(m.status, if (m.status == MsgStatus.CARRIED) Mura.Blue else Mura.Muted)
                    val note = when (m.status) { MsgStatus.WAITING -> "waiting"; MsgStatus.CARRIED -> "carried"; MsgStatus.EXPIRED -> "not delivered"; else -> null }
                    if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = if (m.status == MsgStatus.CARRIED) Mura.Blue else Mura.Muted)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = Mura.Surface,
            title = { Text("Delete for everyone?") },
            text = { Text("It will burn away on every phone it reaches. Someone may already have seen it.", color = Mura.Soft) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; engine.deleteForEveryone(m.id) }) { Text("Delete", color = Mura.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

/** The quoted message shown at the top of a reply. Tapping it jumps to the original. */
@Composable
private fun QuoteBlock(m: MessageRow, original: MessageRow?, nameOf: (String) -> String, onAmber: Boolean, onClick: () -> Unit) {
    val senderHex = m.meta.optString("r_sender", "")
    val who = if (senderHex.isBlank() || senderHex.all { it == '0' }) "Message" else nameOf(senderHex)
    val accent = if (onAmber) Mura.OnAmber.copy(alpha = 0.7f) else Mura.avatarColors(senderHex).second
    val kind = original?.kind ?: m.meta.optInt("r_kind", MsgKind.TEXT)
    val text = when {
        original?.deleted == true -> "Deleted message"
        original != null && original.viewOnce -> "View-once message"
        original != null && original.kind == MsgKind.TEXT -> original.body
        original != null -> original.body
        else -> m.meta.optString("r_text", "")
    }
    val icon = when (kind) {
        MsgKind.FILE -> when (original?.media ?: 0) {
            Payload.MEDIA_IMAGE -> MIcons.Image; Payload.MEDIA_VIDEO -> MIcons.Video; Payload.MEDIA_VOICE -> MIcons.Mic; else -> MIcons.File
        }
        MsgKind.LOCATION -> MIcons.Pin
        MsgKind.STATUS -> MIcons.Status
        else -> null
    }
    val nav = LocalNav.current
    val engine = requireEngine()
    // A reply to a status: tapping the quote opens that status while it's still up (24 h).
    val open = if (kind != MsgKind.STATUS) onClick else ({
        val owner = senderHex
        val live = engine.db.statuses(System.currentTimeMillis()).any { it.id == m.meta.optString("r_id") }
        if (live) nav.go(Screen.StatusView(owner))
    })
    Row(
        Modifier.padding(bottom = 6.dp).widthIn(min = 160.dp, max = 266.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp)).background(if (onAmber) Color(0x24000000) else Mura.Surface2)
            .clickable(role = Role.Button, onClickLabel = if (kind == MsgKind.STATUS) "Open the status" else "Show the original message", onClick = open),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(who, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = if (onAmber) Mura.OnAmber.copy(alpha = 0.7f) else Mura.Muted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                Text(text, style = MaterialTheme.typography.bodySmall, color = if (onAmber) Mura.OnAmber.copy(alpha = 0.8f) else Mura.Soft,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontStyle = if (original?.deleted == true) FontStyle.Italic else FontStyle.Normal)
            }
        }
    }
}

/** "Replying to …" above the composer. */
@Composable
private fun ReplyBar(m: MessageRow, name: String, onClose: () -> Unit) {
    val label = when {
        m.viewOnce -> "View-once message"
        m.kind == MsgKind.LOCATION -> "Location"
        else -> m.body
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp)).background(Mura.Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Mura.Amber))
        Icon(MIcons.Reply, null, tint = Mura.Amber, modifier = Modifier.padding(start = 10.dp).size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("Replying to $name", style = MaterialTheme.typography.labelMedium, color = Mura.Amber)
            Text(label, style = MaterialTheme.typography.bodySmall, color = Mura.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(MIcons.Close, "Cancel reply", size = 40.dp, onClick = onClose)
    }
}

/**
 * Copies a message without leaking it: marked sensitive (Android 13+ hides it from the
 * clipboard preview and keyboards' clipboard history) and, if enabled, wiped after a minute.
 */
fun copySecurely(ctx: Context, text: String, autoClear: Boolean) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("mura", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
    if (!autoClear) return
    val stamp = cm.primaryClipDescription?.timestamp ?: 0L
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        try {
            val d = cm.primaryClipDescription
            // Only clear our own copy — never something the user copied afterwards.
            if (d != null && d.label == "mura" && d.timestamp == stamp) cm.clearPrimaryClip()
        } catch (_: Exception) {}
    }, 60_000)
}

/** "Today" / "Yesterday" / "Mon 12 Oct" between days in a chat. */
@Composable
private fun DayChip(t: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        Text(dayLabel(t), style = MaterialTheme.typography.labelMedium, color = Mura.Soft,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Mura.Surface.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun TextContent(m: MessageRow, fg: Color) {
    TextContentInner(m, fg)
}

/**
 * A text message, translated on this phone when asked (long-press › Translate) or when the chat has
 * auto-translate on. Shows "Translated from … · Show original"; the translation is cached encrypted.
 */
@Composable
private fun TranslatableText(m: MessageRow, fg: Color, auto: Boolean, request: Int) {
    val engine = requireEngine()
    val ctx = LocalContext.current
    val myLang = remember { Translate.myLanguage(engine.db) }
    val cached = remember(m.meta.toString(), m.body) { engine.cachedTranslation(m, myLang) }
    var showOriginal by remember(m.id) { mutableStateOf(false) }
    var busy by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.id, m.body, auto, request) {
        val asked = request > 0
        if (cached != null || busy || !(asked || (auto && m.id !in engine.translateFailed))) return@LaunchedEffect
        busy = true
        try {
            val (from, _) = engine.translateMessage(m.id, myLang)
            if (from == myLang) { engine.translateFailed.add(m.id); if (asked) ctx.toast("It's already in ${Translate.displayName(myLang)}") }
            showOriginal = false
        } catch (e: Exception) {
            if (asked) ctx.toast(e.message ?: "Couldn't translate")
        } finally { busy = false }
    }
    if (cached != null && !showOriginal) {
        Text(cached.second, color = fg, style = MaterialTheme.typography.bodyMedium)
    } else TextContentInner(m, fg)
    if (busy) Text("Translating on this phone…", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
    if (cached != null) {
        Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { showOriginal = !showOriginal }, verticalAlignment = Alignment.CenterVertically) {
            Icon(MIcons.Translate, null, tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (showOriginal) "Show translation" else "Translated from ${Translate.displayName(cached.first)} · Show original",
                style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
        }
    }
}

/** Above the composer when auto-translate is on: translate what I typed into their language, with undo. */
@Composable
private fun DraftTranslateBar(chat: String, draft: String, onReplace: (String) -> Unit) {
    val engine = requireEngine()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<Pair<String, String>?>(null) }      // (original, translated)
    val showUndo = undo?.second == draft
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(Mura.Surface)
            .clickable(enabled = !busy) {
                val u = undo
                if (showUndo && u != null) { onReplace(u.first); undo = null; return@clickable }
                busy = true
                scope.launch {
                    try {
                        val r = engine.translateDraft(chat, draft)
                        if (r == null) ctx.toast("Already in their language") else { undo = draft to r.second; onReplace(r.second) }
                    } catch (e: Exception) { ctx.toast(e.message ?: "Couldn't translate") } finally { busy = false }
                }
            }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (showUndo) MIcons.Undo else MIcons.Translate, null, tint = Mura.Amber, modifier = Modifier.size(16.dp))
        Text(when { busy -> "Translating on this phone…"; showUndo -> "Undo translation"; else -> "Translate before sending" },
            style = MaterialTheme.typography.labelMedium, color = Mura.Soft)
    }
}

@Composable
private fun TextContentInner(m: MessageRow, fg: Color) {
    val ctx = LocalContext.current
    var confirmUrl by remember { mutableStateOf<String?>(null) }
    val pUrl = m.meta.optString("p_url", "")
    if (pUrl.isNotBlank()) {
        val img = remember(m.id) {
            val b64 = m.meta.optString("p_img", "")
            if (b64.isEmpty()) null else try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
        Column(Modifier.width(260.dp).padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).clickable { confirmUrl = pUrl }) {
            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(130.dp))
            Column(Modifier.padding(10.dp)) {
                Text(m.meta.optString("p_title", pUrl), style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val desc = m.meta.optString("p_desc", "")
                if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(Uri.parse(pUrl).host ?: "", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.6f))
            }
        }
    }
    val firstUrl = LinkPreviews.firstUrl(m.body)
    val names = remember(m.meta.toString()) { m.meta.optJSONArray("mentions")?.let { a -> List(a.length()) { a.optJSONObject(it)?.optString("name") ?: "" } } ?: emptyList() }
    Text(withMentions(m.body, names, if (fg == Mura.OnAmber) Mura.OnAmber.copy(alpha = 0.7f) else Mura.Amber), color = fg, style = MaterialTheme.typography.bodyMedium,
        modifier = if (firstUrl != null && pUrl.isBlank()) Modifier.clickable { confirmUrl = firstUrl } else Modifier)
    confirmUrl?.let { u ->
        AlertDialog(
            onDismissRequest = { confirmUrl = null }, containerColor = Mura.Surface,
            title = { Text("Open link?") },
            text = { Text("This opens $u in your browser, outside Mura and outside Tor. The website will see your IP address and " +
                "roughly where you are. Mura can't protect what happens there.", color = Mura.Soft) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUrl = null
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { ctx.toast("No browser installed") }
                }) { Text("Open", color = Mura.Amber) }
            },
            dismissButton = { TextButton(onClick = { confirmUrl = null }) { Text("Cancel", color = Mura.Soft) } },
        )
    }
}

@Composable
private fun ViewOnceContent(m: MessageRow, file: FileRow?, onOpenOnceText: (MessageRow) -> Unit, onLong: () -> Unit = {}) {
    val nav = LocalNav.current
    val what = when (m.kind) { MsgKind.TEXT -> "message"; MsgKind.LOCATION -> "location"; else -> m.body.substringBefore(" ·").lowercase() }
    val ready = m.kind != MsgKind.FILE || file?.state == FileState.COMPLETE
    val enabled = !m.outgoing && !m.opened && ready
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onLongClick = onLong, onClick = {
            if (enabled) { if (m.kind == MsgKind.TEXT) onOpenOnceText(m) else nav.go(Screen.Viewer(m.id)) }
        }).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MIcons.Once, null, tint = if (m.opened) Mura.Muted else Mura.Amber)
        Column {
            Text(
                when {
                    m.outgoing && m.opened -> "View-once $what · opened"
                    m.outgoing -> "View-once $what"
                    m.opened -> "Opened"
                    !ready -> "View-once $what · downloading ${((file?.progress ?: 0f) * 100).roundToInt()}%"
                    else -> "View-once $what · tap to open"
                },
                style = MaterialTheme.typography.titleSmall, color = if (m.opened) Mura.Muted else Mura.Text,
            )
            if (!m.outgoing && !m.opened && ready) Text("Screenshots are blocked while it's open", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
        }
    }
}

@Composable
private fun LocationContent(m: MessageRow) {
    val ctx = LocalContext.current
    val lat = m.meta.optDouble("lat", Double.NaN)
    val lon = m.meta.optDouble("lon", Double.NaN)
    val acc = m.meta.optDouble("acc", 0.0)
    Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Mura.Surface2), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val step = size.width / 8
                for (i in 1 until 8) drawLine(Mura.Line, androidx.compose.ui.geometry.Offset(i * step, 0f), androidx.compose.ui.geometry.Offset(i * step, size.height), 1f)
                for (i in 1 until 5) drawLine(Mura.Line, androidx.compose.ui.geometry.Offset(0f, i * size.height / 5), androidx.compose.ui.geometry.Offset(size.width, i * size.height / 5), 1f)
                drawCircle(Mura.Amber.copy(alpha = 0.18f), radius = 36f, center = center)
            }
            Icon(MIcons.Pin, null, tint = Mura.Amber, modifier = Modifier.size(34.dp))
        }
        Text("Shared location", style = MaterialTheme.typography.titleSmall)
        if (!lat.isNaN()) {
            Text("%.5f, %.5f".format(java.util.Locale.US, lat, lon), fontFamily = Mura.Mono, style = MaterialTheme.typography.bodySmall, color = Mura.Soft)
            if (acc > 0) Text("Accurate to about ${acc.roundToInt()} m", style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
            Text("Open in maps", color = Mura.Amber, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                com.mura.mesh.ui.Exposure.confirmLink { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))) } catch (e: Exception) { ctx.toast("No maps app installed") } }
            }.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun rememberBitmap(key: Any?, load: () -> Bitmap?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, key) {
        value = withContext(Dispatchers.IO) { try { load()?.asImageBitmap() } catch (e: Exception) { null } }
    }
    return state.value
}

@Composable
private fun FileContent(m: MessageRow, f: FileRow, sending: Float?, onLong: () -> Unit = {}) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val engine = requireEngine()
    val haveLocal = (f.outgoing || f.state == FileState.COMPLETE) && f.state != FileState.GONE
    val pct = if (f.outgoing) sending else f.progress
    val origSize = m.meta.optLong("size", f.originalSize)

    when (f.media) {
        Payload.MEDIA_IMAGE, Payload.MEDIA_VIDEO -> {
            val ratio = if (f.width > 0 && f.height > 0) (f.width.toFloat() / f.height).coerceIn(0.6f, 1.8f) else 1.3f
            val full = rememberBitmap(f.fileId + haveLocal) {
                if (!haveLocal || !File(f.path).exists()) null
                else if (f.media == Payload.MEDIA_IMAGE) SecureMedia.decodeImage(f, 720) else SecureMedia.videoFrame(f)
            }
            val thumb = rememberBitmap(f.fileId + "t") { f.thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            Box(
                Modifier.width(250.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Mura.Surface2)
                    // Long-press opens the message menu (delete for everyone, forward…); a tap opens the photo.
                    .combinedClickable(onClick = { if (haveLocal) nav.go(Screen.Viewer(m.id)) }, onLongClick = onLong),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    full != null -> Image(full, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    // Not fully here yet: the tiny preview, blurred, until every piece has arrived and been verified.
                    thumb != null -> Image(thumb, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier))
                }
                if (f.media == Payload.MEDIA_VIDEO && haveLocal) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        Icon(MIcons.Play, "Play video", tint = Color.White)
                    }
                }
                if (!f.outgoing && f.state == FileState.NEEDS_APPROVAL) DownloadChip(f.size) { engine.acceptFile(f.fileId) }
                else if (!haveLocal) ProgressBadge(pct ?: 0f, f.size)
                else if (f.outgoing && m.status < MsgStatus.DELIVERED && pct != null && pct < 1f) {
                    Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { ProgressBadge(pct, f.size, small = true) }
                }
                if (f.media == Payload.MEDIA_VIDEO) {
                    Text("${formatDuration(f.durationMs)} · ${SecureMedia.humanSize(f.size)}", color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Payload.MEDIA_VOICE -> VoiceContent(m, f, haveLocal, pct)
        else -> {
            val isPdf = f.mime == "application/pdf" || f.name.endsWith(".pdf", ignoreCase = true)
            Row(
                Modifier.width(260.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onLongClick = onLong, onClick = {
                    if (haveLocal) { if (isPdf) nav.go(Screen.Viewer(m.id)) else com.mura.mesh.ui.Exposure.confirmOpenOutside { openExternally(ctx, f) } }
                }).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(44.dp).background(Mura.AmberDeep, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (isPdf) MIcons.Pdf else MIcons.File, null, tint = Mura.Amber)
                }
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        !f.outgoing && f.state == FileState.NEEDS_APPROVAL -> "${SecureMedia.humanSize(origSize)} · tap Download"
                        !haveLocal -> "${((pct ?: 0f) * 100).roundToInt()}% of ${SecureMedia.humanSize(origSize)}"
                        f.compressed -> "${SecureMedia.humanSize(origSize)} · sent as ${SecureMedia.humanSize(f.size)}"
                        else -> SecureMedia.humanSize(origSize)
                    }
                    Text(status, style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
                    if (!haveLocal && f.state != FileState.NEEDS_APPROVAL) ProgressLine(pct ?: 0f)
                }
                if (!f.outgoing && f.state == FileState.NEEDS_APPROVAL) IconBtn(MIcons.Download, "Download", tint = Mura.Amber) { engine.acceptFile(f.fileId) }
            }
        }
    }
}

/** Opens a document in another app the user picks, through a temporary decrypt-on-read link (no plaintext file). */
fun openExternally(ctx: Context, f: FileRow) {
    if (!File(f.path).exists()) { ctx.toast("This file is no longer on your phone"); return }
    val uri = SecureFileProvider.uriFor(ctx, f)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(Intent.createChooser(i, "Open ${f.name} with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { ctx.toast("No app on this phone can open this file") }
}

@Composable
private fun VoiceContent(m: MessageRow, f: FileRow, haveLocal: Boolean, pct: Float?) {
    val engine = requireEngine()
    val playing by VoicePlayer.state.collectAsState()
    val isThis = playing.id == m.id
    val progress = if (isThis && playing.durationMs > 0) playing.positionMs.toFloat() / playing.durationMs else 0f
    val bars = remember(m.id) {
        val h = Crypto.sha256(m.id.toByteArray())
        List(30) { 0.25f + ((h[it].toInt() and 0xff) / 255f) * 0.75f }
    }
    val isRecording = m.meta.optBoolean("local")
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var transcribing by remember { mutableStateOf(false) }
    val transcript = m.meta.optString("transcript", "")
    val canTranscribe = remember { Transcriber.available(ctx) }
    Column {
    Row(Modifier.width(260.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(if (haveLocal) Mura.Amber else Mura.Surface2)
                .clickable(enabled = haveLocal || f.state == FileState.NEEDS_APPROVAL) {
                    if (haveLocal) VoicePlayer.toggle(m.id, f) else engine.acceptFile(f.fileId)
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                haveLocal -> Icon(if (isThis && playing.playing) MIcons.Pause else MIcons.Play, "Play", tint = Mura.OnAmber, modifier = Modifier.size(18.dp))
                f.state == FileState.NEEDS_APPROVAL -> Icon(MIcons.Download, "Download", tint = Mura.Amber, modifier = Modifier.size(18.dp))
                else -> CircularProgressIndicator(progress = { pct ?: 0f }, modifier = Modifier.size(28.dp), color = Mura.Amber, strokeWidth = 3.dp, trackColor = Mura.Line)
            }
        }
        Column(Modifier.weight(1f)) {
            if (isRecording) Text("Call recording", style = MaterialTheme.typography.labelSmall, color = Mura.Blue)
            Row(Modifier.fillMaxWidth().height(28.dp).then(com.mura.mesh.ui.voiceGlow(isThis && playing.playing, { progress }, Mura.Amber)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                bars.forEachIndexed { i, b ->
                    Box(Modifier.weight(1f).fillMaxHeight(b).clip(RoundedCornerShape(2.dp))
                        .background(if (i.toFloat() / bars.size < progress) Mura.Amber else Mura.Line))
                }
            }
        }
        Text(formatDuration(if (isThis) playing.positionMs else f.durationMs), fontFamily = Mura.Mono, style = MaterialTheme.typography.labelSmall, color = Mura.Soft)
    }
    // Transcript: made on this phone only (on-device speech recognition), kept encrypted, never sent.
    when {
        transcript.isNotEmpty() -> Text(transcript, style = MaterialTheme.typography.bodySmall, color = Mura.Soft,
            modifier = Modifier.width(260.dp).padding(horizontal = 6.dp, vertical = 4.dp))
        transcribing -> Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Mura.Amber, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp)); Text("Transcribing on this phone…", style = MaterialTheme.typography.labelSmall, color = Mura.Muted)
        }
        haveLocal && !isRecording && canTranscribe -> Text("Transcribe", style = MaterialTheme.typography.labelLarge, color = Mura.Amber,
            modifier = Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp)).clickable {
                transcribing = true
                scope.launch {
                    try { engine.saveTranscript(m.id, Transcriber.transcribe(ctx, f).ifBlank { "(no words found)" }) }
                    catch (e: Exception) { ctx.toast(e.message ?: "Couldn't transcribe") }
                    finally { transcribing = false }
                }
            }.padding(4.dp))
    }
    }
}

@Composable
private fun DownloadChip(size: Long, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Mura.Ink.copy(alpha = 0.8f)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MIcons.Download, null, tint = Mura.Amber, modifier = Modifier.size(18.dp))
        Text("Download · ${SecureMedia.humanSize(size)}", color = Mura.Text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ProgressBadge(p: Float, size: Long, small: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Mura.Ink.copy(alpha = 0.8f)).padding(horizontal = if (small) 8.dp else 12.dp, vertical = if (small) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(progress = { p }, modifier = Modifier.size(if (small) 14.dp else 20.dp), color = Mura.Amber, strokeWidth = 2.5.dp, trackColor = Mura.Line)
        Text("${(p * 100).roundToInt()}%" + if (!small) " · ${SecureMedia.humanSize(size)}" else "", color = Mura.Text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ProgressLine(p: Float) {
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Mura.Line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).clip(RoundedCornerShape(2.dp)).background(Mura.Amber))
    }
}
