package fr.eva.cloudgallery.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.ui.account.cloudAccountSummary
import fr.eva.cloudgallery.ui.account.cloudSyncStatusText
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiCategoryHeader
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiListItem
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.settings.IndexRunningIndicator
import fr.eva.cloudgallery.ui.settings.indexStatusText
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/**
 * Onglet « Menu » : accès aux collections (Favoris, Vidéos), au compte OneDrive (si ce build
 * le permet), aux paramètres et à l'état de l'indexation locale. Pas d'entrée Corbeille : la
 * corbeille système n'est pas consultable de façon fiable par une appli tierce.
 */
@Composable
fun MenuScreen(
    onOpenFavorites: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: MenuViewModel = hiltViewModel(),
) {
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val cloudSyncState by viewModel.cloudSyncState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val titleAlpha by rememberCollapsedTitleAlpha(listState)
    val colors = OneUiTheme.colors
    val dimens = OneUiTheme.dimens
    val title = stringResource(R.string.menu_title)
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        OneUiTopBar(
            title = title,
            titleAlpha = titleAlpha,
            containerColor = colors.groupedBackground,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalInsets.calculateStartPadding(layoutDirection) + 12.dp,
                end = horizontalInsets.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "header") {
                OneUiLargeTitleHeader(title = title, subtitle = null)
            }
            item(key = "collections") {
                OneUiCard {
                    OneUiListItem(
                        title = stringResource(R.string.menu_favorites),
                        icon = Icons.Rounded.FavoriteBorder,
                        onClick = onOpenFavorites,
                    )
                    OneUiDivider(startIndent = dimens.cardPadding + 40.dp)
                    OneUiListItem(
                        title = stringResource(R.string.menu_videos),
                        icon = Icons.Rounded.VideoLibrary,
                        onClick = onOpenVideos,
                    )
                }
            }
            item(key = "library") {
                Column {
                    OneUiCategoryHeader(stringResource(R.string.menu_library))
                    OneUiCard {
                        OneUiListItem(
                            title = stringResource(R.string.menu_sync_now),
                            subtitle = indexStatusText(indexState),
                            icon = Icons.Rounded.Sync,
                            onClick = if (indexState.running) null else viewModel::syncNow,
                            trailing = if (indexState.running) RunningTrailing else null,
                        )
                        // Masquée quand ce build n'embarque pas de configuration OneDrive.
                        if (viewModel.cloudAvailable) {
                            OneUiDivider(startIndent = dimens.cardPadding + 40.dp)
                            // Compte connecté : état de la synchronisation ; sinon, état du compte.
                            val signedIn = cloudState is CloudAccountState.SignedIn
                            OneUiListItem(
                                title = stringResource(R.string.common_onedrive),
                                subtitle = if (signedIn) cloudSyncStatusText(cloudSyncState) else cloudAccountSummary(cloudState),
                                icon = Icons.Rounded.Cloud,
                                onClick = onOpenAccount,
                                trailing = if (signedIn && cloudSyncState.running) RunningTrailing else null,
                            )
                        }
                    }
                }
            }
            item(key = "settings") {
                OneUiCard(Modifier.padding(top = 16.dp)) {
                    OneUiListItem(
                        title = stringResource(R.string.common_settings),
                        icon = Icons.Rounded.Settings,
                        onClick = onOpenSettings,
                    )
                }
            }
        }
    }
}

private val RunningTrailing: @Composable () -> Unit = { IndexRunningIndicator() }
