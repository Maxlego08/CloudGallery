package fr.eva.cloudgallery.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Palette inspired by One UI 8 (approximate values, no Samsung resources bundled).

/** One UI accent blue. */
val OneUiBlue = Color(0xFF3E91FF)
val OneUiBlueDark = Color(0xFF4E9BFF)
val OneUiRed = Color(0xFFF2453D)
val OneUiFavoriteRed = Color(0xFFFF4B4B)

@Immutable
data class OneUiColors(
    /** Background of content screens (grids). */
    val background: Color,
    /** Background of "settings list" screens (with cards laid on top). */
    val groupedBackground: Color,
    /** Rounded cards laid on [groupedBackground]. */
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val divider: Color,
    /** Background of bottom sheets and context menus. */
    val sheet: Color,
    /** Bottom navigation bar. */
    val bottomBar: Color,
    /** Pill of the selected tab. */
    val tabIndicator: Color,
    /** Cell background while loading. */
    val placeholder: Color,
    val selectionOverlay: Color,
    val checkboxBorder: Color,
    val danger: Color,
    val switchTrackOn: Color,
    val isDark: Boolean,
)

val LightOneUiColors = OneUiColors(
    background = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF2F2F2),
    card = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF6E6E6E),
    textTertiary = Color(0xFF9A9A9A),
    accent = OneUiBlue,
    divider = Color(0x1F000000),
    sheet = Color(0xFFFCFCFC),
    bottomBar = Color(0xF7FFFFFF),
    tabIndicator = Color(0x14000000),
    placeholder = Color(0xFFEDEDED),
    selectionOverlay = Color(0x33000000),
    checkboxBorder = Color(0xFFFFFFFF),
    danger = OneUiRed,
    switchTrackOn = OneUiBlue,
    isDark = false,
)

val DarkOneUiColors = OneUiColors(
    background = Color(0xFF000000),
    groupedBackground = Color(0xFF000000),
    card = Color(0xFF171717),
    textPrimary = Color(0xFFFAFAFA),
    textSecondary = Color(0xFF9B9B9B),
    textTertiary = Color(0xFF6F6F6F),
    accent = OneUiBlueDark,
    divider = Color(0x29FFFFFF),
    sheet = Color(0xFF1E1E1E),
    bottomBar = Color(0xF2000000),
    tabIndicator = Color(0x1FFFFFFF),
    placeholder = Color(0xFF1C1C1C),
    selectionOverlay = Color(0x40000000),
    checkboxBorder = Color(0xFFFFFFFF),
    danger = Color(0xFFFF5A52),
    switchTrackOn = OneUiBlueDark,
    isDark = true,
)

/** The viewer is always on a black background, as in Samsung Gallery. */
object ViewerColors {
    val background = Color.Black
    val barScrim = Color(0x66000000)
    val content = Color.White
    val contentSecondary = Color(0xB3FFFFFF)
}
