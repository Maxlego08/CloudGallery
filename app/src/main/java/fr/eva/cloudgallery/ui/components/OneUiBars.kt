package fr.eva.cloudgallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/** Insets à respecter par les barres du haut : barre d'état + encoches latérales. */
private val TopBarInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/**
 * Barre d'application One UI réduite. Applique elle-même le padding de la barre d'état ;
 * [titleAlpha] permet de faire apparaître le titre quand le grand titre sort de l'écran.
 */
@Composable
fun OneUiTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleAlpha: Float = 1f,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = OneUiTheme.colors.background,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .windowInsetsPadding(TopBarInsets)
            .height(OneUiTheme.dimens.appBarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null) {
            navigationIcon()
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(OneUiTheme.dimens.screenPadding - 4.dp))
        }
        Text(
            text = title,
            style = OneUiTheme.typography.appBarTitle,
            color = OneUiTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = titleAlpha.coerceIn(0f, 1f) }
                .semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * Hauteur du grand titre étendu One UI (≈ 30 % de la hauteur d'écran, bornée par les jetons).
 */
@Composable
fun rememberLargeTitleHeight(): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val dimens = OneUiTheme.dimens
    return remember(screenHeight, dimens) {
        (screenHeight * 0.30f).dp.coerceIn(dimens.largeTitleMinHeight, dimens.largeTitleMaxHeight)
    }
}

/**
 * Bloc de grand titre One UI, à placer comme premier élément pleine largeur d'une liste/grille,
 * sous un [OneUiTopBar] dont le titre réapparaît (titleAlpha) quand ce bloc défile hors champ
 * (voir [rememberCollapsedTitleAlpha]). N'inclut pas l'inset de la barre d'état.
 */
@Composable
fun OneUiLargeTitleHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rememberLargeTitleHeight())
            .padding(horizontal = OneUiTheme.dimens.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = OneUiTheme.typography.largeTitle,
                color = OneUiTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = OneUiTheme.typography.largeSubtitle,
                    color = OneUiTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Opacité du petit titre de barre en fonction du défilement d'une liste dont l'élément 0 est le grand titre. */
@Composable
fun rememberCollapsedTitleAlpha(state: LazyListState): State<Float> {
    val headerPx = with(LocalDensity.current) { rememberLargeTitleHeight().toPx() }
    return remember(state, headerPx) {
        derivedStateOf { collapsedTitleAlpha(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, headerPx) }
    }
}

/** Variante pour une grille dont l'élément 0 (pleine largeur) est le grand titre. */
@Composable
fun rememberCollapsedTitleAlpha(state: LazyGridState): State<Float> {
    val headerPx = with(LocalDensity.current) { rememberLargeTitleHeight().toPx() }
    return remember(state, headerPx) {
        derivedStateOf { collapsedTitleAlpha(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, headerPx) }
    }
}

/** Le petit titre apparaît sur les derniers 35 % du défilement du grand titre. */
fun collapsedTitleAlpha(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, headerHeightPx: Float): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (headerHeightPx <= 0f) return 1f
    val start = headerHeightPx * 0.55f
    val end = headerHeightPx * 0.9f
    return ((firstVisibleItemScrollOffset - start) / (end - start)).coerceIn(0f, 1f)
}

/** Bouton icône One UI : zone tactile 48 dp, ondulation circulaire. */
@Composable
fun OneUiIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = OneUiTheme.colors.textPrimary,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
fun OneUiBackButton(
    onClick: () -> Unit,
    tint: Color = OneUiTheme.colors.textPrimary,
) {
    OneUiIconButton(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = stringResource(R.string.common_back),
        onClick = onClick,
        tint = tint,
    )
}

/**
 * En-tête du mode sélection One UI : case ronde « Tout » à gauche, puis « N sélectionné(s) »
 * (ou « Sélectionner des éléments » quand rien n'est coché). Gère l'inset de la barre d'état.
 */
@Composable
fun OneUiSelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OneUiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(TopBarInsets)
            .heightIn(min = OneUiTheme.dimens.appBarHeight + 8.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .clip(ComponentShapes.small)
                .clickable(role = Role.Checkbox, onClick = onToggleAll)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            SelectAllCircle(checked = allSelected)
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.common_all),
                style = OneUiTheme.typography.actionLabel,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (selectedCount > 0) {
                pluralStringResource(R.plurals.common_selected_count, selectedCount, selectedCount)
            } else {
                stringResource(R.string.common_select_items)
            },
            style = OneUiTheme.typography.appBarTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
    }
}

/** Formes réutilisées par les composants One UI. */
internal object ComponentShapes {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(18.dp)
    val pill = RoundedCornerShape(percent = 50)
}
