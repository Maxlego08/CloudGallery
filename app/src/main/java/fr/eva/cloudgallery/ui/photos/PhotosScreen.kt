package fr.eva.cloudgallery.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.components.OneUiMenuItem
import fr.eva.cloudgallery.ui.components.OneUiPillButton
import fr.eva.cloudgallery.ui.grid.MediaGridPage

/**
 * Etat vide de Photos en usage « OneDrive seul » : sans compte connecté, invite à se connecter à
 * OneDrive ([onSignIn] : écran Compte) ou à autoriser les photos du téléphone ([onAllowPhotos]).
 */
data class NoLocalAccessEmpty(
    val cloudSignedIn: Boolean,
    val onSignIn: () -> Unit,
    val onAllowPhotos: () -> Unit,
)

/**
 * Onglet Photos : chronologie de tous les médias, groupée par jour ou par mois selon le zoom.
 * La position de défilement est conservée (état sauvegardable + pagination mise en cache dans le VM).
 * [noLocalAccess] : usage « OneDrive seul » (état vide adapté).
 */
@Composable
fun PhotosScreen(
    onOpenMedia: (MediaContext, Long) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    noLocalAccess: NoLocalAccessEmpty? = null,
) {
    val viewModel: PhotosViewModel = hiltViewModel()
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()

    val currentOnSelectionModeChange by rememberUpdatedState(onSelectionModeChange)
    LaunchedEffect(selectionMode) { currentOnSelectionModeChange(selectionMode) }
    DisposableEffect(Unit) { onDispose { currentOnSelectionModeChange(false) } }

    val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val selectLabel = stringResource(R.string.grid_select)
    val settingsLabel = stringResource(R.string.common_settings)
    val menuItems = remember(viewModel, selectLabel, settingsLabel) {
        listOf(
            OneUiMenuItem(selectLabel) { viewModel.enterSelectionMode() },
            OneUiMenuItem(settingsLabel) { currentOnOpenSettings() },
        )
    }

    val (emptyTitle, emptyMessage) = when {
        noLocalAccess == null ->
            stringResource(R.string.photos_empty_title) to stringResource(R.string.photos_empty_message)
        noLocalAccess.cloudSignedIn ->
            stringResource(R.string.perm_onedrive_only_synced_empty_title) to
                stringResource(R.string.perm_onedrive_only_synced_empty_message)
        else ->
            stringResource(R.string.perm_onedrive_only_empty_title) to stringResource(R.string.perm_onedrive_only_empty_message)
    }
    val emptyAction: (@Composable () -> Unit)? = noLocalAccess?.takeIf { !it.cloudSignedIn }?.let { access ->
        {
            Column(
                modifier = Modifier.widthIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OneUiPillButton(
                    text = stringResource(R.string.perm_onedrive_only_sign_in),
                    onClick = access.onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                )
                OneUiPillButton(
                    text = stringResource(R.string.perm_onedrive_only_allow_photos),
                    onClick = access.onAllowPhotos,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    MediaGridPage(
        viewModel = viewModel,
        title = stringResource(R.string.photos_title),
        gridState = gridState,
        emptyIcon = Icons.Outlined.PhotoLibrary,
        emptyTitle = emptyTitle,
        emptyMessage = emptyMessage,
        onOpenMedia = onOpenMedia,
        bottomPadding = contentPadding.calculateBottomPadding(),
        menuItems = menuItems,
        isIndexing = indexing,
        emptyAction = emptyAction,
        modifier = modifier,
    )
}
