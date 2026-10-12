package fr.eva.cloudgallery.ui.storage

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.ORIGINALS_CACHE_CHOICES
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.model.THUMBNAIL_CACHE_CHOICES
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiCategoryHeader
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiListItem
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters

/**
 * Page Stockage (Paramètres) : espace occupé par niveau (miniatures et aperçus, originaux,
 * copies hors ligne), plafonds des caches et vidage du cache non épinglé avec confirmation.
 * Mesure refaite à chaque retour sur la page.
 */
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val clearing by viewModel.clearing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val listState = rememberLazyListState()
    val titleAlpha by rememberCollapsedTitleAlpha(listState)
    val colors = OneUiTheme.colors
    val title = stringResource(R.string.storage_title)
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var showOriginalsCapDialog by rememberSaveable { mutableStateOf(false) }
    var showThumbnailCapDialog by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                StorageEvent.CLEARED -> resources.getString(R.string.storage_cleared)
                StorageEvent.CLEAR_FAILED -> resources.getString(R.string.storage_clear_failed)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        OneUiTopBar(
            title = title,
            titleAlpha = titleAlpha,
            navigationIcon = { OneUiBackButton(onClick = onBack) },
            containerColor = colors.groupedBackground,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalInsets.calculateStartPadding(layoutDirection) + 12.dp,
                end = horizontalInsets.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = bottomInset + 24.dp,
            ),
        ) {
            item(key = "header") {
                OneUiLargeTitleHeader(title = title, subtitle = null)
            }
            val currentSettings = settings
            if (currentSettings != null) {
                item(key = "content") {
                    StorageContent(
                        settings = currentSettings,
                        usage = usage,
                        clearing = clearing,
                        onThumbnailCapClick = { showThumbnailCapDialog = true },
                        onOriginalsCapClick = { showOriginalsCapDialog = true },
                        onClearClick = { showClearDialog = true },
                        onOfflineClick = onOpenAccount,
                    )
                }
            }
        }
    }

    val currentSettings = settings
    if (showClearDialog) {
        OneUiDialog(
            title = stringResource(R.string.storage_clear_title),
            message = stringResource(R.string.storage_clear_message, sizeText(usage?.cacheBytes)),
            onDismiss = { showClearDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.storage_clear_confirm),
            onConfirm = {
                showClearDialog = false
                viewModel.clearCache()
            },
        )
    }
    if (showOriginalsCapDialog && currentSettings != null) {
        CapDialog(
            title = stringResource(R.string.storage_originals_cap),
            choices = ORIGINALS_CACHE_CHOICES,
            selected = currentSettings.originalsCacheMaxBytes,
            onSelect = {
                showOriginalsCapDialog = false
                viewModel.setOriginalsCap(it)
            },
            onDismiss = { showOriginalsCapDialog = false },
        )
    }
    if (showThumbnailCapDialog && currentSettings != null) {
        CapDialog(
            title = stringResource(R.string.storage_thumbnail_cap),
            choices = THUMBNAIL_CACHE_CHOICES,
            selected = currentSettings.thumbnailCacheMaxBytes,
            onSelect = {
                showThumbnailCapDialog = false
                viewModel.setThumbnailCap(it)
            },
            onDismiss = { showThumbnailCapDialog = false },
        )
    }
}

@Composable
private fun StorageContent(
    settings: AppSettings,
    usage: StorageUsage?,
    clearing: Boolean,
    onThumbnailCapClick: () -> Unit,
    onOriginalsCapClick: () -> Unit,
    onClearClick: () -> Unit,
    onOfflineClick: () -> Unit,
) {
    val indent = OneUiTheme.dimens.cardPadding
    Column(Modifier.fillMaxWidth()) {
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.storage_used_by_app),
                subtitle = usage?.let {
                    sizeText(it.cacheBytes + it.offlineBytes) + " · " +
                        stringResource(R.string.storage_free_on_device, sizeText(it.freeBytes))
                },
            )
        }

        OneUiCategoryHeader(stringResource(R.string.storage_section_cache))
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.storage_thumbnails),
                subtitle = usage?.let { u ->
                    u.thumbnailMaxBytes?.let { stringResource(R.string.storage_size_of_cap, sizeText(u.thumbnailBytes), sizeText(it)) }
                        ?: sizeText(u.thumbnailBytes)
                },
            )
            OneUiDivider(startIndent = indent)
            OneUiListItem(
                title = stringResource(R.string.storage_originals),
                subtitle = usage?.let {
                    stringResource(
                        R.string.storage_size_and_count,
                        sizeText(it.originalBytes),
                        pluralStringResource(R.plurals.common_items_count, it.originalCount, it.originalCount),
                    )
                },
            )
            OneUiDivider(startIndent = indent)
            OneUiListItem(
                title = stringResource(R.string.storage_thumbnail_cap),
                subtitle = if (thumbnailCapPendingRestart(settings.thumbnailCacheMaxBytes, usage?.thumbnailMaxBytes)) {
                    stringResource(R.string.storage_thumbnail_cap_pending, sizeText(settings.thumbnailCacheMaxBytes))
                } else {
                    sizeText(settings.thumbnailCacheMaxBytes)
                },
                onClick = onThumbnailCapClick,
            )
            OneUiDivider(startIndent = indent)
            OneUiListItem(
                title = stringResource(R.string.storage_originals_cap),
                subtitle = stringResource(R.string.storage_originals_cap_summary, sizeText(settings.originalsCacheMaxBytes)),
                onClick = onOriginalsCapClick,
            )
            OneUiDivider(startIndent = indent)
            OneUiListItem(
                title = stringResource(R.string.storage_clear_cache),
                subtitle = stringResource(R.string.storage_clear_cache_summary, sizeText(usage?.cacheBytes)),
                onClick = if (canClearCache(usage?.cacheBytes, clearing)) onClearClick else null,
            )
        }

        OneUiCategoryHeader(stringResource(R.string.storage_section_offline))
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.storage_offline_copies),
                subtitle = usage?.let {
                    stringResource(
                        R.string.storage_size_and_count,
                        sizeText(it.offlineBytes),
                        pluralStringResource(R.plurals.common_items_count, it.offlineCount, it.offlineCount),
                    ) + "\n" + stringResource(R.string.storage_offline_summary)
                } ?: stringResource(R.string.storage_offline_summary),
                onClick = onOfflineClick,
            )
        }
    }
}

/** Taille lisible, tiret tant que la mesure n'est pas faite. */
private fun sizeText(bytes: Long?): String = bytes?.let { Formatters.fileSize(it) } ?: "–"

/** Choix d'un plafond : un toucher sur une option l'applique et ferme la boîte. */
@Composable
private fun CapDialog(
    title: String,
    choices: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OneUiTheme.colors
    OneUiDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            choices.forEach { bytes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = bytes == selected, role = Role.RadioButton, onClick = { onSelect(bytes) })
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = bytes == selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = colors.accent,
                            unselectedColor = colors.textTertiary,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(Formatters.fileSize(bytes), style = OneUiTheme.typography.listTitle, color = colors.textPrimary)
                }
            }
        }
    }
}
