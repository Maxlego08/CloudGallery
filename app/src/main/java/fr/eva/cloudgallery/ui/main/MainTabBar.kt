package fr.eva.cloudgallery.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.theme.OneUiTheme

enum class MainTab { PHOTOS, ALBUMS, MENU }

/**
 * Barre d'onglets inférieure Samsung Galerie : libellés texte, onglet sélectionné en gras
 * sur une pastille arrondie ; « Menu » avec l'icône ≡. Gère l'inset de la barre de navigation.
 */
@Composable
fun MainTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OneUiTheme.colors.bottomBar)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .height(OneUiTheme.dimens.bottomBarHeight)
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabItem(
            label = stringResource(R.string.shell_tab_photos),
            selected = selected == MainTab.PHOTOS,
            onClick = { onSelect(MainTab.PHOTOS) },
            modifier = Modifier.weight(1f),
        )
        TabItem(
            label = stringResource(R.string.shell_tab_albums),
            selected = selected == MainTab.ALBUMS,
            onClick = { onSelect(MainTab.ALBUMS) },
            modifier = Modifier.weight(1f),
        )
        TabItem(
            label = stringResource(R.string.shell_tab_menu),
            selected = selected == MainTab.MENU,
            onClick = { onSelect(MainTab.MENU) },
            withMenuIcon = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    withMenuIcon: Boolean = false,
) {
    val colors = OneUiTheme.colors
    val pill by animateColorAsState(
        targetValue = if (selected) colors.tabIndicator else Color.Transparent,
        animationSpec = tween(150),
        label = "tabPill",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick, indication = null, interactionSource = null),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(pill)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textColor = if (selected) colors.textPrimary else colors.textSecondary
            if (withMenuIcon) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = if (selected) OneUiTheme.typography.tabLabelSelected else OneUiTheme.typography.tabLabel,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
