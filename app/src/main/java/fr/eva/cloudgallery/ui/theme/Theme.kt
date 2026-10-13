package fr.eva.cloudgallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.domain.model.ThemeMode

@Immutable
data class OneUiDimens(
    val screenPadding: Dp = 24.dp,
    val cardPadding: Dp = 20.dp,
    val cardRadius: Dp = 26.dp,
    val sheetRadius: Dp = 26.dp,
    val dialogRadius: Dp = 26.dp,
    val thumbnailSpacing: Dp = 2.dp,
    val albumCoverRadius: Dp = 16.dp,
    val listItemMinHeight: Dp = 60.dp,
    val bottomBarHeight: Dp = 64.dp,
    val actionBarHeight: Dp = 64.dp,
    val appBarHeight: Dp = 56.dp,
    /** Height of the large title area (≈ 38% of the screen on One UI, bounded). */
    val largeTitleMinHeight: Dp = 180.dp,
    val largeTitleMaxHeight: Dp = 300.dp,
)

private val LocalOneUiColors = staticCompositionLocalOf { LightOneUiColors }
private val LocalOneUiTypography = staticCompositionLocalOf { OneUiTypography() }
private val LocalOneUiDimens = staticCompositionLocalOf { OneUiDimens() }
private val LocalOneUiIsDark = staticCompositionLocalOf { false }

/** Access to the theme tokens: `OneUiTheme.colors.accent`, etc. */
object OneUiTheme {
    val colors: OneUiColors
        @Composable @ReadOnlyComposable get() = LocalOneUiColors.current
    val typography: OneUiTypography
        @Composable @ReadOnlyComposable get() = LocalOneUiTypography.current
    val dimens: OneUiDimens
        @Composable @ReadOnlyComposable get() = LocalOneUiDimens.current

    /** The app's theme (Light / Dark / System setting), not necessarily the system's. */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalOneUiIsDark.current
}

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The project's own theme: One UI tokens exposed via [OneUiTheme], and a derived Material 3
 * ColorScheme (for the few reused Material components: TextField, Switch…). No Material You
 * dynamic colors, in order to keep the Samsung Gallery look.
 */
@Composable
fun CloudGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkOneUiColors else LightOneUiColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = androidx.compose.ui.graphics.Color.White,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.card,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainer = colors.sheet,
            surfaceContainerHigh = colors.sheet,
            surfaceContainerHighest = colors.card,
            surfaceContainerLow = colors.card,
            outline = colors.divider,
            outlineVariant = colors.divider,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = androidx.compose.ui.graphics.Color.White,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.groupedBackground,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainer = colors.sheet,
            surfaceContainerHigh = colors.sheet,
            surfaceContainerHighest = colors.card,
            surfaceContainerLow = colors.card,
            outline = colors.divider,
            outlineVariant = colors.divider,
            error = colors.danger,
        )
    }
    val dimens = OneUiDimens()
    CompositionLocalProvider(
        LocalOneUiColors provides colors,
        LocalOneUiTypography provides OneUiTypography(),
        LocalOneUiDimens provides dimens,
        LocalOneUiIsDark provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(dimens.cardRadius),
                extraLarge = RoundedCornerShape(dimens.dialogRadius),
            ),
            content = content,
        )
    }
}
