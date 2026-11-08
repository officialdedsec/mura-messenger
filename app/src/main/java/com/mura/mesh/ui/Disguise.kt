package com.mura.mesh.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.toHex

/**
 * Device-level privacy switches kept in plain app preferences (they must work before the encrypted
 * database is unlocked): the disguised app icon and the incognito keyboard.
 *
 * Disguise: Mura's launcher icon is swapped for a working "Calculator". Typing your secret code and
 * pressing "=" opens Mura; anything else just calculates. Android 10+ doesn't let any app remove its
 * icon completely, so a convincing disguise is the strongest option.
 */
object Disguise {
    private const val PREFS = "mura_ui"
    private const val ON = "disguise_on"
    private const val SALT = "disguise_salt"
    private const val HASH = "disguise_hash"
    private const val INCOGNITO = "incognito_kb"
    /** The stored code is known to be plain 0–9 (set by 2.7.2+), so only that needs checking. */
    private const val ASCII_ONLY = "disguise_ascii"

    private const val LAUNCHER = "com.mura.mesh.Launcher"
    private const val CALCULATOR = "com.mura.mesh.Calculator"
    private const val SHARE = "com.mura.mesh.ShareTarget"

    /** The calculator is showing and Mura is hidden behind it. */
    var locked by mutableStateOf(false)
    var enabledState by mutableStateOf(false)
        private set
    var incognitoKeyboard by mutableStateOf(true)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        enabledState = p.getBoolean(ON, false)
        incognitoKeyboard = p.getBoolean(INCOGNITO, true)
        // Disguise set before "Share to Mura" existed: make sure the share entry is hidden too.
        if (enabledState) try {
            val pm = ctx.packageManager
            val c = ComponentName(ctx.packageName, SHARE)
            if (pm.getComponentEnabledSetting(c) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                pm.setComponentEnabledSetting(c, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}
    }

    fun setIncognito(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(INCOGNITO, on).apply(); incognitoKeyboard = on }

    private const val ITERATIONS = 60_000

    /** Slow, salted hash (PBKDF2-SHA256): someone who copies the phone's storage can't simply look the code up. */
    private fun hash(salt: String, code: String): String {
        val chars = code.toCharArray()
        try { return "p2:" + Crypto.passwordKey(chars, salt.toByteArray(), ITERATIONS).toHex() } finally { chars.fill('0') }
    }
    private fun legacyHash(salt: String, code: String) = Crypto.sha256(salt.toByteArray(), code.toByteArray()).toHex()

    /**
     * Any keyboard's digits (१२३, ١٢٣, ۱۲۳, ১২৩ …) become plain 0–9, so the code you set always matches what the
     * calculator types. (Earlier versions could save a code in, say, Hindi numerals that the calculator can't type.)
     */
    fun normalize(code: String): String = buildString {
        for (c in code) { val d = Character.digit(c, 10); if (d >= 0) append('0' + d) }
    }

    /** Digit sets a keyboard might have used when the code was set (for codes saved before [normalize] existed). */
    private val ZEROS = intArrayOf(0x30, 0x966, 0x660, 0x6F0, 0x9E6, 0xA66, 0xAE6, 0xB66, 0xBE6, 0xC66, 0xCE6, 0xD66, 0xE50, 0xFF10)

    @Volatile private var failures = 0
    @Volatile private var coolUntil = 0L

    /**
     * Slow on purpose (PBKDF2): call it off the main thread. After 5 wrong codes in a row, checks pause for 30 s
     * (the calculator keeps calculating normally, so nothing gives the disguise away).
     */
    fun codeMatches(ctx: Context, input: String): Boolean {
        val code = normalize(input)
        if (code.length !in 4..12) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < coolUntil) return false
        val p = prefs(ctx)
        val salt = p.getString(SALT, null) ?: return false
        val stored = p.getString(HASH, null) ?: return false
        for (zero in ZEROS) {
            val variant = if (zero == 0x30) code else code.map { (zero + (it - '0')).toChar() }.joinToString("")
            val candidate = if (stored.startsWith("p2:")) hash(salt, variant) else legacyHash(salt, variant)
            if (java.security.MessageDigest.isEqual(candidate.toByteArray(), stored.toByteArray())) {
                // Matched: store it the current way (plain digits, slow hash) from now on.
                if (zero != 0x30 || !stored.startsWith("p2:")) p.edit().putString(HASH, hash(salt, code)).apply()
                p.edit().putBoolean(ASCII_ONLY, true).apply()
                failures = 0
                return true
            }
            if (zero == 0x30 && stored.startsWith("p2:") && p.getBoolean(ASCII_ONLY, false)) break   // saved as 0–9: no need to try others
        }
        if (++failures >= 5) { failures = 0; coolUntil = now + 30_000 }
        return false
    }

    /** Turns the disguise on with a secret code (4–12 digits). */
    fun enable(ctx: Context, input: String) {
        val code = normalize(input)
        require(code.length in 4..12)
        val salt = Crypto.randomBytes(16).toHex()
        prefs(ctx).edit().putBoolean(ON, true).putString(SALT, salt).putString(HASH, hash(salt, code)).putBoolean(ASCII_ONLY, true).apply()
        failures = 0; coolUntil = 0
        enabledState = true
        setIcon(ctx, calculator = true)
    }

    fun disable(ctx: Context) {
        prefs(ctx).edit().putBoolean(ON, false).remove(SALT).remove(HASH).remove(ASCII_ONLY).apply()
        enabledState = false
        locked = false
        setIcon(ctx, calculator = false)
    }

    /** Back to the normal icon (used by panic erase, before the preferences are wiped). */
    fun reset(ctx: Context) { try { setIcon(ctx, calculator = false) } catch (_: Exception) {} }

    private fun setIcon(ctx: Context, calculator: Boolean) {
        val pm = ctx.packageManager
        val on = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        // Enable the new one first so there's never a moment without a launcher entry.
        pm.setComponentEnabledSetting(ComponentName(ctx.packageName, if (calculator) CALCULATOR else LAUNCHER), on, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(ComponentName(ctx.packageName, if (calculator) LAUNCHER else CALCULATOR), off, PackageManager.DONT_KILL_APP)
        // "Share to Mura" would reveal the app in every share sheet: hidden while disguised.
        try { pm.setComponentEnabledSetting(ComponentName(ctx.packageName, SHARE), if (calculator) off else on, PackageManager.DONT_KILL_APP) } catch (_: Exception) {}
    }
}
