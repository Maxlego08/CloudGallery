package fr.eva.cloudgallery.ui.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiPillButton
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/**
 * Ecran d'accueil / d'autorisation affiché tant qu'aucun accès aux médias n'est accordé.
 * Explique pourquoi l'accès est nécessaire, puis lance la demande système ; en cas de refus
 * définitif, propose d'ouvrir les paramètres de l'appli.
 */
@Composable
fun PermissionScreen(
    controller: MediaPermissionController,
    modifier: Modifier = Modifier,
) {
    val colors = OneUiTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OneUiTheme.dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(64.dp))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(colors.accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.perm_title),
                style = OneUiTheme.typography.largeTitle,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.perm_message),
                style = OneUiTheme.typography.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            OneUiCard {
                ReasonRow(Icons.Rounded.Collections, stringResource(R.string.perm_reason_browse))
                ReasonRow(Icons.Rounded.PlayCircleOutline, stringResource(R.string.perm_reason_videos))
                ReasonRow(Icons.Rounded.Lock, stringResource(R.string.perm_reason_private))
            }
            if (controller.permanentlyDenied) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.perm_denied_message),
                    style = OneUiTheme.typography.body,
                    color = colors.danger,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = OneUiTheme.dimens.screenPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controller.permanentlyDenied) {
                OneUiPillButton(
                    text = stringResource(R.string.perm_open_settings),
                    onClick = controller::openAppSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Le système peut encore redemander si la boîte a seulement été fermée.
                OneUiPillButton(
                    text = stringResource(R.string.common_retry),
                    onClick = controller::request,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OneUiPillButton(
                    text = stringResource(R.string.perm_allow),
                    onClick = controller::request,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ReasonRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OneUiTheme.dimens.cardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = OneUiTheme.colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = OneUiTheme.typography.listTitle, color = OneUiTheme.colors.textPrimary)
    }
}

/**
 * Bandeau fin affiché au-dessus de la barre d'onglets en mode d'accès partiel :
 * « Accès limité à certaines photos — Gérer ». « Gérer » relance la demande système
 * (sélection d'autres éléments ou autorisation complète).
 */
@Composable
fun PartialAccessBanner(
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OneUiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .clickable(role = Role.Button, onClick = onManage)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.perm_partial_banner),
            style = OneUiTheme.typography.listSubtitle,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.perm_partial_manage),
            style = OneUiTheme.typography.button,
            color = colors.accent,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}
