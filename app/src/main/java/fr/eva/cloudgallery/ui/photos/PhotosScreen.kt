package fr.eva.cloudgallery.ui.photos

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.components.OneUiMenuItem
import fr.eva.cloudgallery.ui.grid.MediaGridPage

/**
 * Onglet Photos : chronologie de tous les médias, groupée par jour ou par mois selon le zoom.
 * La position de défilement est conservée (état sauvegardable + pagination mise en cache dans le VM).
 */
@Composable
fun PhotosScreen(
    onOpenMedia: (MediaContext, Long) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
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

    MediaGridPage(
        viewModel = viewModel,
        title = stringResource(R.string.photos_title),
        gridState = gridState,
        emptyIcon = Icons.Outlined.PhotoLibrary,
        emptyTitle = stringResource(R.string.photos_empty_title),
        emptyMessage = stringResource(R.string.photos_empty_message),
        onOpenMedia = onOpenMedia,
        bottomPadding = contentPadding.calculateBottomPadding(),
        menuItems = menuItems,
        isIndexing = indexing,
        modifier = modifier,
    )
}
