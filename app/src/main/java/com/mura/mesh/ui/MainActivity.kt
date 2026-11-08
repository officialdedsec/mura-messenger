package com.mura.mesh.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.mura.mesh.MuraApp
import com.mura.mesh.call.CallStatus
import com.mura.mesh.data.ChatId
import com.mura.mesh.identity.IdentityStore
import com.mura.mesh.media.SecureFileProvider
import com.mura.mesh.mesh.MeshPermissions
import com.mura.mesh.mesh.Settings
import com.mura.mesh.service.MeshService
import com.mura.mesh.service.Notifier
import com.mura.mesh.ui.screens.AddContactScreen
import com.mura.mesh.ui.screens.AddMembersScreen
import com.mura.mesh.ui.screens.BackupScreen
import com.mura.mesh.ui.screens.BlockedScreen
import com.mura.mesh.ui.screens.CallScreen
import com.mura.mesh.ui.screens.ChatScreen
import com.mura.mesh.ui.screens.ChatsScreen
import com.mura.mesh.ui.screens.GroupInfoScreen
import com.mura.mesh.ui.screens.MeshScreen
import com.mura.mesh.ui.screens.NewGroupScreen
import com.mura.mesh.ui.screens.NewStatusScreen
import com.mura.mesh.ui.screens.OnboardingScreen
import com.mura.mesh.ui.screens.PermissionsScreen
import com.mura.mesh.ui.screens.PrivacyScreen
import com.mura.mesh.ui.screens.ProfileScreen
import com.mura.mesh.ui.screens.SettingsScreen
import com.mura.mesh.ui.screens.SplashScreen
import com.mura.mesh.ui.screens.StatusListScreen
import com.mura.mesh.ui.screens.StatusViewScreen
import com.mura.mesh.ui.screens.ViewerScreen
import com.mura.mesh.ui.theme.Mura
import com.mura.mesh.ui.theme.MuraTheme
import com.mura.mesh.ui.theme.Themes
import androidx.activity.SystemBarStyle

/** Survives rotation, so the user stays on the same screen. */
class NavModel : androidx.lifecycle.ViewModel() {
    val nav = Navigator(Screen.Splash)
}

/** App lock state: locked again after the chosen time in the background (Privacy › Lock after). */
object AppLock {
    var locked by mutableStateOf(false)
    var leftAt = 0L
}

/** Locked chats opened (and the hidden "Locked chats" folder shown) since the app was last in the background. */
object ChatGate {
    var folderOpen by mutableStateOf(false)
    val unlocked = androidx.compose.runtime.mutableStateListOf<String>()
    fun reset() { folderOpen = false; unlocked.clear() }
}

/** Ask for fingerprint / face / screen lock, then run [ok]. */
fun android.content.Context.withAuth(title: String, ok: () -> Unit) {
    val a = findActivity() as? MainActivity
    if (a == null) ok() else a.authenticate(title, ok)
}

class MainActivity : FragmentActivity() {

    private val navModel: NavModel by viewModels()
    private val nav: Navigator get() = navModel.nav
    private var pendingIntent: Intent? = null

    private val screenCaptureCallback: Any? =
        if (Build.VERSION.SDK_INT >= 34) Activity.ScreenCaptureCallback { onScreenshot() } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyScreenSecurity()
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val gate = pendingAuth
                if (gate != null) { pendingAuth = null; gate(); return }
                AppLock.locked = false
                pendingIntent?.let { if (nav.top != Screen.Splash) { handleIntent(it); pendingIntent = null } }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { pendingAuth = null }
        })
        pendingIntent = intent
        if (savedInstanceState == null && lockEnabled()) AppLock.locked = true
        Themes.load(this)
        Disguise.load(this)
        if (Disguise.enabledState) {
            if (savedInstanceState == null) Disguise.locked = true
            @Suppress("DEPRECATION") setTaskDescription(android.app.ActivityManager.TaskDescription("Calculator"))
        }
        setContent {
            MuraTheme {
                // Status and navigation bar icons follow the theme (light icons on dark, dark icons on light).
                val dark = Mura.isDark
                LaunchedEffect(dark) {
                    val t = android.graphics.Color.TRANSPARENT
                    val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                    enableEdgeToEdge(style, style)
                }
                IncognitoKeyboard(Disguise.incognitoKeyboard) {
                    CompositionLocalProvider(LocalNav provides nav) {
                        when {
                            Disguise.locked -> com.mura.mesh.ui.screens.CalculatorScreen {
                                Disguise.locked = false
                                if (AppLock.locked) unlock()
                                else pendingIntent?.let { if (nav.top != Screen.Splash) { handleIntent(it); pendingIntent = null } }
                            }
                            AppLock.locked -> LockScreen { unlock() }
                            else -> Root(nav, onSplashDone = ::afterSplash)
                        }
                        // Vault unlock: the lock rings rotate open over the app for a moment (draws only, never takes touches).
                        com.mura.mesh.ui.VaultUnlock(Disguise.locked || AppLock.locked)
                        ExposureDialog()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (nav.top == Screen.Splash || AppLock.locked || Disguise.locked) pendingIntent = intent else handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 34) registerScreenCaptureCallback(mainExecutor, screenCaptureCallback as Activity.ScreenCaptureCallback)
        val away = if (AppLock.leftAt > 0) SystemClock.elapsedRealtime() - AppLock.leftAt else 0L
        // The disguise closes again a few seconds after you leave: the calculator is what anyone sees next.
        if (Disguise.enabledState && AppLock.leftAt > 0 && away >= 3_000) Disguise.locked = true
        if (AppLock.leftAt > 0 && away >= lockAfterMs()) {
            if (lockEnabled()) AppLock.locked = true
            // Locked chats close again too.
            ChatGate.reset()
            val top = nav.top
            if (top is Screen.Chat && MuraApp.engine?.db?.isLocked(top.chat) == true) nav.reset(Screen.Chats)
        }
        if (AppLock.locked && !Disguise.locked) unlock()
        MuraApp.engine?.transport?.foreground = true
        MuraApp.engine?.setForeground(true)
        if (MuraApp.engine != null && MeshPermissions.bluetoothGranted(this)) MeshService.start(this)
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= 34) unregisterScreenCaptureCallback(screenCaptureCallback as Activity.ScreenCaptureCallback)
        MuraApp.engine?.transport?.foreground = false
        MuraApp.engine?.setForeground(false)
        AppLock.leftAt = SystemClock.elapsedRealtime()
        SecureFileProvider.revokeAll()
        super.onStop()
    }

    private fun lockEnabled() = MuraApp.engine?.db?.getBool(Settings.APP_LOCK, false) == true
    private fun lockAfterMs(): Long = (MuraApp.engine?.db?.getSetting(Settings.LOCK_AFTER, "30")?.toLongOrNull() ?: 30L) * 1000

    private var pendingAuth: (() -> Unit)? = null

    /** Used for locked chats. Without a screen lock on the phone there's nothing to check against, so it just opens. */
    fun authenticate(title: String, ok: () -> Unit) {
        if (!canUseLock()) { ok(); return }
        pendingAuth = ok
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Fingerprint, face or screen lock")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canUseLock(): Boolean = BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    private lateinit var prompt: BiometricPrompt

    fun unlock() {
        if (!canUseLock()) { AppLock.locked = false; return }
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Mura")
                .setSubtitle("Your chats are protected")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    /** Privacy-first default: screenshots and screen recording are blocked, and recents shows a blank card. */
    fun applyScreenSecurity() {
        val on = MuraApp.engine?.db?.getBool(Settings.SCREEN_SECURITY, true) ?: true
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun onScreenshot() {
        val engine = MuraApp.engine ?: return
        val chat = when (val s = nav.top) {
            is Screen.Chat -> s.chat
            is Screen.Viewer -> engine.db.message(s.msgId)?.chat
            else -> null
        } ?: return
        engine.reportScreenshot(chat)
        toast(if (ChatId.isGroup(chat)) "The group will be told you took a screenshot" else "They'll be told you took a screenshot")
    }

    private fun afterSplash() {
        val next = when {
            !IdentityStore.exists(this) || MuraApp.engine == null -> Screen.Onboarding
            !MeshPermissions.bluetoothGranted(this) -> Screen.Permissions
            else -> Screen.Chats
        }
        nav.reset(next)
        if (next == Screen.Chats) {
            MeshService.start(this)
            pendingIntent?.let { handleIntent(it) }
        }
        pendingIntent = null
    }

    private fun handleIntent(intent: Intent) {
        val engine = MuraApp.engine ?: return
        // "Share to Mura" from another app: only pre-fills a chat picker (nothing is sent without the user).
        if (com.mura.mesh.ui.screens.ShareInbox.accept(this, intent)) {
            if (com.mura.mesh.ui.screens.ShareInbox.pending != null) nav.go(Screen.ShareTo)
            return
        }
        // Only our own notifications may steer the app. Anything else (another app firing an intent at the
        // launcher) just opens Mura normally — it can never answer a call or jump into a chat.
        if (!Notifier.isOurs(this, intent)) {
            intent.removeExtra(Notifier.EXTRA_CHAT); intent.removeExtra(Notifier.EXTRA_CALL); intent.removeExtra(Notifier.EXTRA_ANSWER)
            return
        }
        intent.getStringExtra(Notifier.EXTRA_CHAT)?.let { chat ->
            nav.reset(Screen.Chats)
            if (engine.db.isLocked(chat) && chat !in ChatGate.unlocked) authenticate("Open locked chat") { ChatGate.unlocked.add(chat); nav.go(Screen.Chat(chat)) }
            else nav.go(Screen.Chat(chat))
        }
        if (intent.hasExtra(Notifier.EXTRA_CALL) && engine.calls.state.value.status != CallStatus.IDLE) nav.go(Screen.Call)
        intent.getStringExtra(Notifier.EXTRA_ANSWER)?.let { peer ->
            // Answer only the call that is actually ringing from that person.
            if (engine.calls.state.value.status != CallStatus.IDLE && engine.calls.state.value.peerHex == peer) { nav.go(Screen.Call); engine.calls.answer() }
        }
        intent.removeExtra(Notifier.EXTRA_CHAT); intent.removeExtra(Notifier.EXTRA_CALL); intent.removeExtra(Notifier.EXTRA_ANSWER)
        intent.removeExtra(Notifier.EXTRA_TOKEN)
    }

    fun showOverLockScreen(on: Boolean) {
        setShowWhenLocked(on); setTurnScreenOn(on)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Mura.Ink).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(MIcons.Lock, null, tint = Mura.Amber, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Mura is locked", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Use your fingerprint, face or screen lock to open your chats.", style = MaterialTheme.typography.bodyMedium, color = Mura.Soft)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Unlock", Modifier.size(200.dp, 56.dp), onClick = onUnlock)
    }
}

@Composable
private fun Root(nav: Navigator, onSplashDone: () -> Unit) {
    val screen = nav.top
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    val engine = MuraApp.engine
    if (engine != null) {
        val call by engine.calls.state.collectAsState()
        val activity = androidx.compose.ui.platform.LocalContext.current.findActivity() as? MainActivity
        LaunchedEffect(call.status) {
            activity?.showOverLockScreen(call.status == CallStatus.RINGING || call.status == CallStatus.ACTIVE)
            if (call.status != CallStatus.IDLE && nav.top != Screen.Call && nav.top !is Screen.Splash) nav.go(Screen.Call)
            if (call.status == CallStatus.IDLE && nav.top == Screen.Call) nav.back()
        }
    }

    Box(Modifier.fillMaxSize().background(Mura.Ink)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // WhatsApp-style: a new screen slides in from the right over the old one (which drifts left
                // and dims); going back reverses it. Splash and calls simply fade.
                val ease = FastOutSlowInEasing
                when {
                    initialState == Screen.Splash || targetState == Screen.Splash ->
                        fadeIn(tween(260)) togetherWith fadeOut(tween(200))
                    targetState == Screen.Call || initialState == Screen.Call ->
                        (fadeIn(tween(220)) + scaleIn(tween(260, easing = ease), initialScale = 0.96f)) togetherWith fadeOut(tween(180))
                    nav.forward ->
                        ((slideInHorizontally(tween(320, easing = ease)) { it / 3 } + fadeIn(tween(220, delayMillis = 40))) togetherWith
                            (slideOutHorizontally(tween(320, easing = ease)) { -it / 8 } + fadeOut(tween(260)))).apply { targetContentZIndex = 1f }
                    else ->
                        ((slideInHorizontally(tween(300, easing = ease)) { -it / 8 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(300, easing = ease)) { it / 3 } + fadeOut(tween(200)))).apply { targetContentZIndex = -1f }
                }
            },
            label = "screens",
        ) { s ->
            when (s) {
                Screen.Splash -> SplashScreen(onDone = onSplashDone)
                Screen.Onboarding -> OnboardingScreen()
                Screen.Permissions -> PermissionsScreen()
                Screen.Chats -> com.mura.mesh.ui.screens.HomeScreen()
                is Screen.Chat -> ChatScreen(s.chat, s.focus)
                Screen.Starred -> com.mura.mesh.ui.screens.StarredScreen()
                is Screen.Checklist -> com.mura.mesh.ui.screens.ChecklistScreen(s.listId)
                is Screen.Doodle -> com.mura.mesh.ui.screens.DoodleScreen(s.chat)
                Screen.Translation -> com.mura.mesh.ui.screens.TranslationScreen()
                Screen.Calls -> com.mura.mesh.ui.screens.CallsScreen()
                Screen.Contacts -> com.mura.mesh.ui.screens.ContactsScreen()
                Screen.ShareTo -> com.mura.mesh.ui.screens.ShareToScreen()
                is Screen.ChatMedia -> com.mura.mesh.ui.screens.ChatMediaScreen(s.chat)
                is Screen.Appearance -> com.mura.mesh.ui.screens.AppearanceScreen(s.chat)
                Screen.Mesh -> MeshScreen()
                Screen.AddContact -> AddContactScreen()
                Screen.Settings -> SettingsScreen()
                Screen.Privacy -> PrivacyScreen()
                Screen.Backup -> BackupScreen()
                Screen.Profile -> ProfileScreen()
                Screen.Blocked -> BlockedScreen()
                is Screen.Viewer -> ViewerScreen(s.msgId)
                Screen.Call -> CallScreen()
                Screen.NewGroup -> NewGroupScreen()
                is Screen.GroupInfo -> GroupInfoScreen(s.gid)
                is Screen.AddMembers -> AddMembersScreen(s.gid)
                Screen.Status -> StatusListScreen()
                is Screen.StatusView -> StatusViewScreen(s.owner)
                Screen.NewStatus -> NewStatusScreen()
            }
        }
    }
}
