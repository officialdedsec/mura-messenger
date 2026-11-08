package com.mura.mesh.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mura.mesh.MuraApp
import com.mura.mesh.mesh.MeshEngine
import com.mura.mesh.mesh.Settings
import com.mura.mesh.ui.theme.Mura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// ------------------------------------------------------------------ navigation

sealed class Screen {
    data object Splash : Screen()
    data object Onboarding : Screen()
    data object Permissions : Screen()
    data object Chats : Screen()
    /** chat = contact key (1:1) or "g" + group id. */
    data class Chat(val chat: String, val focus: String? = null) : Screen()
    data object Mesh : Screen()
    data object AddContact : Screen()
    data object Settings : Screen()
    data object Privacy : Screen()
    data object Backup : Screen()
    data object Profile : Screen()
    data object Blocked : Screen()
    data class Viewer(val msgId: String) : Screen()
    data object Call : Screen()
    data object NewGroup : Screen()
    data class GroupInfo(val gid: String) : Screen()
    data class AddMembers(val gid: String) : Screen()
    data object Status : Screen()
    data class StatusView(val owner: String) : Screen()
    data object NewStatus : Screen()
    data object Starred : Screen()
    data class Checklist(val listId: String) : Screen()
    data class Doodle(val chat: String) : Screen()
    data object Translation : Screen()
    data object Calls : Screen()
    data object Contacts : Screen()
    /** Picking chats for something shared from another app. */
    data object ShareTo : Screen()
    /** Media, links, docs and voice notes shared in one chat. */
    data class ChatMedia(val chat: String) : Screen()
    /** Theme, colour and wallpaper. With [chat]: that chat's own wallpaper. */
    data class Appearance(val chat: String? = null) : Screen()
}

class Navigator(start: Screen) {
    var stack by mutableStateOf(listOf(start))
        private set
    val top: Screen get() = stack.last()
    /** Direction of the last move, so screens slide in (forward) or back out (back) like WhatsApp. */
    var forward by mutableStateOf(true)
        private set
    /** Which home tab is showing: 0 Chats, 1 Status, 2 Calls, 3 Mesh, 4 Me. */
    var homeTab by mutableIntStateOf(0)

    fun go(s: Screen) { if (top != s) { forward = true; stack = stack + s } }
    fun replace(s: Screen) { forward = true; stack = stack.dropLast(1) + s }
    fun reset(s: Screen) { forward = stack.size <= 1; stack = listOf(s); if (s == Screen.Chats) homeTab = 0 }
    fun back(): Boolean { if (stack.size <= 1) return false; forward = false; stack = stack.dropLast(1); return true }

    /** Switch home tab (swipes the pager); from deeper screens it returns home first. */
    fun tab(i: Int) {
        if (top != Screen.Chats) { forward = false; stack = listOf(Screen.Chats) }
        homeTab = i
    }
}

/** True inside the home pager: tab screens hide their back button. */
val LocalInTab = compositionLocalOf { false }

val LocalNav = staticCompositionLocalOf<Navigator> { error("Navigator missing") }

fun requireEngine(): MeshEngine = MuraApp.engine ?: error("engine not started")

// ------------------------------------------------------------------ data helpers

/** Re-runs [query] off the main thread whenever the database changes. */
@Composable
fun <T> rememberQuery(initial: T, vararg keys: Any?, query: () -> T): State<T> {
    val tick by (MuraApp.engine?.dbTick ?: NoTicks).collectAsState()
    return produceState(initial, tick, *keys) { value = withContext(Dispatchers.IO) { query() } }
}

private val NoTicks = kotlinx.coroutines.flow.MutableStateFlow(0L)

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

/** Blocks screenshots and screen recording while on screen (view-once content). */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            val keep = MuraApp.engine?.db?.getBool(Settings.SCREEN_SECURITY, true) == true
            if (!keep) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

fun timeLabel(t: Long): String {
    if (t <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = t }
    return if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR))
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
    else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))
}

/** Always the clock time ("14:05" / "2:05 PM"), for message bubbles under a day header. */
fun clockLabel(t: Long): String = if (t <= 0) "" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))

/** Day header in chats and call history: Today, Yesterday, a weekday this week, or the full date. */
fun dayLabel(t: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = t }
    val days = ((startOfDay(now.timeInMillis) - startOfDay(t)) / 86_400_000L).toInt()
    return when {
        days == 0 -> "Today"
        days == 1 -> "Yesterday"
        days in 2..6 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(Date(t))
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(Date(t))
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}

fun sameDay(a: Long, b: Long): Boolean = startOfDay(a) == startOfDay(b)

private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun agoLabel(t: Long): String {
    if (t <= 0) return "never seen nearby"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "seen just now"
        m < 60 -> "seen ${m}m ago"
        m < 24 * 60 -> "seen ${m / 60}h ago"
        else -> "seen ${m / (24 * 60)}d ago"
    }
}

fun displayName(name: String, pub: String) = name.ifBlank { "Unknown · ${pub.take(4).uppercase()}" }

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
fun Avatar(key: String, name: String, size: Dp = 52.dp, ring: Color? = null, photo: ByteArray? = null, group: Boolean = false) {
    val (bg, fg) = Mura.avatarColors(key)
    val shape = RoundedCornerShape(size * 0.33f)
    val bmp = remember(photo) {
        photo?.let { try { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } catch (e: Exception) { null } }
    }
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(bg)
            .then(if (ring != null) Modifier.border(2.dp, ring, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> androidx.compose.foundation.Image(bmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            group -> Icon(MIcons.Group, null, tint = fg, modifier = Modifier.size(size * 0.45f))
            else -> Text(initials(name), color = fg, fontFamily = Mura.Sans, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.32f).sp)
        }
    }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn(MIcons.Back, "Back", onClick = onBack)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Mura.Soft, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun IconBtn(icon: ImageVector, label: String, tint: Color = Mura.Text, bg: Color = Color.Transparent, size: Dp = 44.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(if (enabled) Mura.Amber else Mura.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Mura.OnAmber else Mura.Muted, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = Mura.Soft, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(Mura.Surface).border(1.dp, Mura.Line, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}

@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = Mura.Amber, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Mura.Amber, checkedThumbColor = Mura.OnAmber,
                uncheckedTrackColor = Mura.Surface2, uncheckedThumbColor = Mura.Muted, uncheckedBorderColor = Mura.Line),
        )
    }
}

@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = Mura.Amber, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (tint == Mura.Red) Mura.Red else Mura.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mura.Muted)
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Mura.Amber,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
}
