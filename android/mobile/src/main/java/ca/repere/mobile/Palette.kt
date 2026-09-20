package ca.repere.mobile

import android.content.Context
import androidx.compose.ui.graphics.Color

/** Whether to use Android 12+ Material You wallpaper-derived colors instead of the Pine brand palette. */
object AppearancePrefs {
    private const val PREF_DYNAMIC_COLOR = "dynamic_color"
    private fun prefs(context: Context) = context.getSharedPreferences("repere", Context.MODE_PRIVATE)
    fun dynamicColorEnabled(context: Context) = prefs(context).getBoolean(PREF_DYNAMIC_COLOR, false)
    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_DYNAMIC_COLOR, enabled).apply()
    }
}

/** One theme's worth of brand colors. Values are chosen to match the web app's design-system tokens. */
private class RepereColors(
    val pine: Color, val pineDark: Color, val mint: Color, val amber: Color, val amberSoft: Color,
    val paper: Color, val cardSurface: Color, val danger: Color, val gridLine: Color, val accentOn: Color,
)

private val LightRepereColors = RepereColors(
    pine = Color(0xFF0F5946), pineDark = Color(0xFF093B30), mint = Color(0xFFDDF3E9),
    amber = Color(0xFFEAA33A), amberSoft = Color(0xFFFFE8C2),
    paper = Color(0xFFF7F9F5), cardSurface = Color.White,
    danger = Color(0xFFD9534F), gridLine = Color(0xFFE2E5E3), accentOn = Color.White,
)

private val DarkRepereColors = RepereColors(
    pine = Color(0xFF62CDA7), pineDark = Color(0xFFE8EEEB), mint = Color(0xFF173B30),
    amber = Color(0xFFF0BE72), amberSoft = Color(0xFF3A2E14),
    paper = Color(0xFF0D1210), cardSurface = Color(0xFF151B18),
    danger = Color(0xFFFF9690), gridLine = Color(0xFF242D29), accentOn = Color(0xFF092219),
)

/**
 * Brand palette, resolved for the active theme by [applyPalette] before any screen composes.
 * These are plain vars (not CompositionLocal-backed) because several chart primitives read
 * them from non-composable `DrawScope` extension functions; [applyPalette] must run first
 * in every composition pass (see MainActivity's `setContent`), which the app's activity
 * restart on system theme change (no `uiMode` in `android:configChanges`) guarantees.
 */
internal var Pine: Color = LightRepereColors.pine
/** Strong "ink" color for primary text/onSurface content, and the app's default text tint everywhere else. */
internal var PineDark: Color = LightRepereColors.pineDark
/** Soft accent tint for containers, chips, and secondary text — flips with theme. */
internal var Mint: Color = LightRepereColors.mint
internal var Amber: Color = LightRepereColors.amber
/** Light amber container background (e.g. pending-sync indicator). */
internal var AmberSoft: Color = LightRepereColors.amberSoft
/** Page/scaffold background. */
internal var Paper: Color = LightRepereColors.paper
/** Card/row surface, replaces the old hardcoded `Color.White`. */
internal var CardSurface: Color = LightRepereColors.cardSurface
internal var Danger: Color = LightRepereColors.danger
/** Borders, gridlines, dividers, and "not observed" placeholder cells. */
internal var GridLine: Color = LightRepereColors.gridLine
/** Readable text/icon color on top of a Pine- or Amber-colored surface (e.g. filled buttons). */
internal var AccentOn: Color = LightRepereColors.accentOn

/** Deep, deliberately dark hero-card surface (e.g. the day summary card) — stays dark in both themes. */
internal val HeroSurface = Color(0xFF093B30)
/** Text/icon tint for content on [HeroSurface] — stays pale in both themes so it never flips to low contrast. */
internal val HeroAccent = Color(0xFFDDF3E9)

internal fun applyPalette(dark: Boolean) {
    val p = if (dark) DarkRepereColors else LightRepereColors
    Pine = p.pine; PineDark = p.pineDark; Mint = p.mint; Amber = p.amber; AmberSoft = p.amberSoft
    Paper = p.paper; CardSurface = p.cardSurface; Danger = p.danger; GridLine = p.gridLine; AccentOn = p.accentOn
}
