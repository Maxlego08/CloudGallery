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
import androidx.compose.material3.LinearProgressIndicator
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
import fr.eva.cloudgallery.domain.model.PrecacheOutcome
import fr.eva.cloudgallery.domain.model.PrecacheState
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.model.THUMBNAIL_CACHE_CHOICES
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiCategoryHeader
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiListItem
import fr.eva.cloudgallery.ui.components.OneUiProgressDialog
import fr.eva.cloudgallery.ui.components.OneUiSwitchItem
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters

/**
 * Storage page (Settings): caching all of OneDrive (estimate, confirmation,
 * progress, stop, "Keep up to date"), space used per tier (thumbnails and previews,
 * originals, offline copies), cache limits and clearing of the unpinned cache with confirmation.
 * Measured again on every return to the page.
 */
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSpaceSaver: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val clearing by viewModel.clearing.collectAsStateWithLifecycle()
    val precacheState by viewModel.precacheState.collectAsStateWithLifecycle()
    val precacheDialog by viewModel.precacheDialog.collectAsStateWithLifecycle()
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
                StorageEvent.ESTIMATE_FAILED -> resources.getString(R.string.precache_estimate_failed)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    // Pass finished: measure the space used again.
    LaunchedEffect(precacheState is PrecacheState.Finished) {
        if (precacheState is PrecacheState.Finished) viewModel.refresh()
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
                        precacheState = precacheState,
                        onPrecacheClick = viewModel::onPrecacheClick,
                        onKeepUpdatedChange = viewModel::setPrecacheKeepUpdated,
                        onThumbnailCapClick = { showThumbnailCapDialog = true },
                        onOriginalsCapClick = { showOriginalsCapDialog = true },
                        onClearClick = { showClearDialog = true },
                        onOfflineClick = onOpenAccount,
                        onSpaceSaverClick = onOpenSpaceSaver,
                    )
                }
            }
        }
    }

    val currentSettings = settings
    PrecacheDialogs(
        dialog = precacheDialog,
        wifiOnly = currentSettings?.offlineWifiOnly ?: true,
        onDismiss = viewModel::dismissPrecacheDialog,
        onConfirm = viewModel::confirmPrecache,
        onConfirmCancel = viewModel::confirmCancelPrecache,
    )
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
    precacheState: PrecacheState,
    onPrecacheClick: () -> Unit,
    onKeepUpdatedChange: (Boolean) -> Unit,
    onThumbnailCapClick: () -> Unit,
    onOriginalsCapClick: () -> Unit,
    onClearClick: () -> Unit,
    onOfflineClick: () -> Unit,
    onSpaceSaverClick: () -> Unit,
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

        // Caching all of OneDrive (thumbnails and previews outside the LRU limit).
        OneUiCategoryHeader(stringResource(R.string.precache_section))
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.precache_action),
                subtitle = precacheSubtitle(precacheState, usage),
                onClick = onPrecacheClick,
            )
            val running = precacheState as? PrecacheState.Running
            if (running != null && running.total > 0) {
                LinearProgressIndicator(
                    progress = { running.done.toFloat() / running.total },
                    color = OneUiTheme.colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent, end = indent, bottom = 12.dp),
                )
            }
            OneUiDivider(startIndent = indent)
            OneUiSwitchItem(
                title = stringResource(R.string.precache_keep_updated),
                subtitle = stringResource(R.string.precache_keep_updated_summary),
                checked = settings.precacheKeepUpdated,
                onCheckedChange = onKeepUpdatedChange,
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

        // Space saver: phone files (never started automatically).
        OneUiCategoryHeader(stringResource(R.string.saver_title))
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.saver_title),
                subtitle = stringResource(R.string.saver_entry_summary),
                onClick = onSpaceSaverClick,
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

/** Subtitle of "Cache all of OneDrive" based on the pass state. */
@Composable
private fun precacheSubtitle(state: PrecacheState, usage: StorageUsage?): String {
    val size = usage?.takeIf { it.precacheBytes > 0 }?.let {
        stringResource(R.string.precache_size_summary, sizeText(it.precacheBytes), pluralStringResource(R.plurals.common_items_count, it.precacheFiles, it.precacheFiles))
    }
    return when (state) {
        PrecacheState.Idle -> size ?: stringResource(R.string.precache_idle_summary)
        PrecacheState.Waiting -> stringResource(R.string.precache_waiting)
        is PrecacheState.Running -> if (state.total <= 0) {
            stringResource(R.string.precache_preparing)
        } else {
            stringResource(R.string.precache_progress, state.done.coerceAtMost(state.total), state.total, sizeText(state.bytes))
        } + "\n" + stringResource(R.string.precache_tap_to_cancel)
        is PrecacheState.Finished -> when (state.outcome) {
            PrecacheOutcome.COMPLETED -> stringResource(
                R.string.precache_done,
                pluralStringResource(R.plurals.common_items_count, state.total, state.total),
            )
            PrecacheOutcome.LOW_SPACE -> stringResource(R.string.precache_low_space)
            PrecacheOutcome.SIGNED_OUT -> stringResource(R.string.precache_signed_out)
        } + (size?.let { "\n$it" } ?: "")
    }
}

/** Estimate in progress, confirmation (or "everything is already cached"), stopping the pass. */
@Composable
private fun PrecacheDialogs(
    dialog: PrecacheDialog?,
    wifiOnly: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onConfirmCancel: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        PrecacheDialog.Estimating -> OneUiProgressDialog(
            title = stringResource(R.string.precache_action),
            message = stringResource(R.string.precache_estimating),
            progress = null,
            cancelLabel = stringResource(R.string.common_cancel),
            onCancel = onDismiss,
        )
        is PrecacheDialog.Confirm -> {
            val estimate = dialog.estimate
            if (estimate.missing == 0) {
                OneUiDialog(
                    title = stringResource(R.string.precache_nothing_title),
                    message = stringResource(
                        R.string.precache_nothing_message,
                        pluralStringResource(R.plurals.common_items_count, estimate.total, estimate.total),
                    ),
                    onDismiss = onDismiss,
                    confirmLabel = stringResource(R.string.common_ok),
                    onConfirm = onDismiss,
                )
            } else {
                val network = stringResource(if (wifiOnly) R.string.precache_network_wifi else R.string.precache_network_any)
                val message = stringResource(
                    R.string.precache_confirm_message,
                    pluralStringResource(R.plurals.common_items_count, estimate.missing, estimate.missing),
                    pluralStringResource(R.plurals.common_items_count, estimate.total, estimate.total),
                    sizeText(estimate.estimatedBytes),
                    sizeText(estimate.freeBytes),
                    network,
                ) + if (estimate.enoughSpace) "" else stringResource(R.string.precache_confirm_low_space)
                OneUiDialog(
                    title = stringResource(R.string.precache_confirm_title),
                    message = message,
                    onDismiss = onDismiss,
                    dismissLabel = stringResource(R.string.common_cancel),
                    confirmLabel = stringResource(R.string.precache_confirm),
                    onConfirm = onConfirm,
                )
            }
        }
        PrecacheDialog.ConfirmCancel -> OneUiDialog(
            title = stringResource(R.string.precache_cancel_title),
            message = stringResource(R.string.precache_cancel_message),
            onDismiss = onDismiss,
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.precache_cancel_confirm),
            onConfirm = onConfirmCancel,
        )
    }
}

/** Human-readable size, a dash until the measurement is done. */
private fun sizeText(bytes: Long?): String = bytes?.let { Formatters.fileSize(it) } ?: "–"

/** Limit picker: tapping an option applies it and closes the dialog. */
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
