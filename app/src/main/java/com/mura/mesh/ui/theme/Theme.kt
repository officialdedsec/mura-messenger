package com.mura.mesh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mura.mesh.R

/** One complete set of colours. Mura's look is a palette chosen from theme × accent. */
data class Palette(
    val ink: Color, val surface: Color, val surface2: Color, val line: Color,
    val text: Color, val soft: Color, val muted: Color,
    val accent: Color, val accentDeep: Color, val onAccent: Color,
    val blue: Color, val blueDeep: Color, val mint: Color, val red: Color,
    val dark: Boolean,
)

/**
 * Themes and accent colours. Saved in plain app preferences (they aren't private, and the theme
 * must be known before the encrypted database is unlocked).
 */
object Themes {
    const val PREFS = "mura_ui"
    val MODES = listOf("system" to "System", "dark" to "Dark", "light" to "Light", "black" to "Black")
    val ACCENTS = listOf("amber" to "Amber", "mint" to "Mint", "sky" to "Sky", "rose" to "Rose", "violet" to "Violet")

    private val darkAccents = mapOf(
        "amber" to Triple(Color(0xFFFFB547), Color(0xFF3A2A12), Color(0xFF1A1204)),
        "mint" to Triple(Color(0xFF8FE3B0), Color(0xFF1F2A1E), Color(0xFF0E2A19)),
        "sky" to Triple(Color(0xFF7CC4FF), Color(0xFF14283A), Color(0xFF0B2033)),
        "rose" to Triple(Color(0xFFFF8FA3), Color(0xFF3A1820), Color(0xFF2B0A12)),
        "violet" to Triple(Color(0xFFC3A6FF), Color(0xFF2A1F3D), Color(0xFF1D1233)),
    )
    private val lightAccents = mapOf(
        "amber" to Triple(Color(0xFFB86E00), Color(0xFFFBE7C6), Color(0xFFFFFFFF)),
        "mint" to Triple(Color(0xFF157A43), Color(0xFFD5F0DF), Color(0xFFFFFFFF)),
        "sky" to Triple(Color(0xFF0B6BB8), Color(0xFFD6E8F8), Color(0xFFFFFFFF)),
        "rose" to Triple(Color(0xFFC2334F), Color(0xFFF8D9DF), Color(0xFFFFFFFF)),
        "violet" to Triple(Color(0xFF6B46C1), Color(0xFFE6DDF8), Color(0xFFFFFFFF)),
    )

    fun palette(mode: String, accent: String, systemDark: Boolean): Palette {
        val m = if (mode == "system") (if (systemDark) "dark" else "light") else mode
        return when (m) {
            "light" -> {
                val (a, d, on) = lightAccents[accent] ?: lightAccents.getValue("amber")
                Palette(Color(0xFFF6F4EF), Color(0xFFFFFFFF), Color(0xFFEEEAE2), Color(0xFFDCD6CB),
                    Color(0xFF16191F), Color(0xFF3E4552), Color(0xFF6B7280),
                    a, d, on, Color(0xFF1D6FB8), Color(0xFFD6E8F8), Color(0xFF1F8A4C), Color(0xFFD1343A), dark = false)
            }
            "black" -> {
                val (a, d, on) = darkAccents[accent] ?: darkAccents.getValue("amber")
                Palette(Color(0xFF000000), Color(0xFF0C0E12), Color(0xFF14171D), Color(0xFF1F242D),
                    Color(0xFFECEFF4), Color(0xFFB8C1D0), Color(0xFF9AA4B5),
                    a, d, on, Color(0xFF7CC4FF), Color(0xFF14283A), Color(0xFF8FE3B0), Color(0xFFE5484D), dark = true)
            }
            else -> {
                val (a, d, on) = darkAccents[accent] ?: darkAccents.getValue("amber")
                Palette(Color(0xFF0B0E13), Color(0xFF151A22), Color(0xFF1C2330), Color(0xFF263041),
                    Color(0xFFECEFF4), Color(0xFFB8C1D0), Color(0xFF9AA4B5),
                    a, d, on, Color(0xFF7CC4FF), Color(0xFF14283A), Color(0xFF8FE3B0), Color(0xFFE5484D), dark = true)
            }
        }
    }

    var mode by mutableStateOf("dark")
        private set
    var accent by mutableStateOf("amber")
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = p.getString("mode", "dark") ?: "dark"
        accent = p.getString("accent", "amber") ?: "amber"
    }

    fun set(ctx: Context, mode: String = this.mode, accent: String = this.accent) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode).putString("accent", accent).apply()
        this.mode = mode; this.accent = accent
    }
}

object Mura {
    /** The live palette. Every colour below reads it, so changing it recolours the whole app at once. */
    var palette by mutableStateOf(Themes.palette("dark", "amber", true))

    val Ink get() = palette.ink
    val Surface get() = palette.surface
    val Surface2 get() = palette.surface2
    val Line get() = palette.line
    val Text get() = palette.text
    val Soft get() = palette.soft
    val Muted get() = palette.muted
    /** The accent colour (named Amber for history; it follows the chosen accent). */
    val Amber get() = palette.accent
    val AmberDeep get() = palette.accentDeep
    val OnAmber get() = palette.onAccent
    val Blue get() = palette.blue
    val BlueDeep get() = palette.blueDeep
    val Mint get() = palette.mint
    val Red get() = palette.red
    val isDark get() = palette.dark

    val Display = FontFamily(
        Font(R.font.unbounded_medium, FontWeight.Medium),
        Font(R.font.unbounded_bold, FontWeight.Bold),
    )
    val Sans = FontFamily(
        Font(R.font.plex_sans_regular, FontWeight.Normal),
        Font(R.font.plex_sans_medium, FontWeight.Medium),
        Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
        Font(R.font.plex_sans_semibold, FontWeight.Bold),
    )
    val Mono = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )

    /** Avatar tints derived from a key, so each person keeps the same colour everywhere. */
    private val avatarPairs = listOf(
        Color(0xFF3A2A12) to Color(0xFFFFB547),
        Color(0xFF14283A) to Color(0xFF7CC4FF),
        Color(0xFF1F2A1E) to Color(0xFF8FE3B0),
        Color(0xFF2A1B2E) to Color(0xFFE7A6F0),
        Color(0xFF2E1B1B) to Color(0xFFFF9C8A),
        Color(0xFF1B2A2A) to Color(0xFF7FE0D6),
    )

    private val avatarPairsLight = listOf(
        Color(0xFFFBE7C6) to Color(0xFF9A5B00),
        Color(0xFFD6E8F8) to Color(0xFF0B5C9E),
        Color(0xFFD5F0DF) to Color(0xFF136B3A),
        Color(0xFFF1DDF4) to Color(0xFF8A2E9A),
        Color(0xFFF9DED9) to Color(0xFFB23A26),
        Color(0xFFD3F1EE) to Color(0xFF0E6E66),
    )

    fun avatarColors(key: String): Pair<Color, Color> =
        (if (palette.dark) avatarPairs else avatarPairsLight)[(key.hashCode() and 0x7fffffff) % avatarPairs.size]
}

private fun scheme(p: Palette) =
    if (p.dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.blue, onSecondary = p.ink, background = p.ink, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.soft, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface,
        outline = p.line, outlineVariant = p.line, error = p.red,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.blue, onSecondary = Color.White, background = p.ink, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.soft, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface,
        outline = p.line, outlineVariant = p.line, error = p.red,
    )

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = Mura.Display, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Mura.Display, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Mura.Display, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Mura.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Mura.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Mura.Sans, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Mura.Sans, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Mura.Sans, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Mura.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Mura.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mura.Mono, fontSize = 11.sp, letterSpacing = 0.3.sp),
)

@Composable
fun MuraTheme(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val p = remember(Themes.mode, Themes.accent, systemDark) { Themes.palette(Themes.mode, Themes.accent, systemDark) }
    // Set before the content composes, so this very frame already uses the new colours.
    if (Mura.palette != p) Mura.palette = p
    MaterialTheme(colorScheme = scheme(p), typography = typography) {
        // Text without an explicit colour follows the theme (Compose's default would be black — invisible on dark themes).
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides p.text, content = content)
    }
}
