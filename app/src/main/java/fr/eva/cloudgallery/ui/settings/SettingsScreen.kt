package fr.eva.cloudgallery.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.BuildConfig
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.model.ThemeMode
import fr.eva.cloudgallery.ui.account.cloudAccountSummary
import fr.eva.cloudgallery.ui.account.cloudSyncStatusText
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiCategoryHeader
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiListItem
import fr.eva.cloudgallery.ui.components.OneUiSwitchItem
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/** One UI Settings screen: large title, then grouped cards on a light gray background. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenStorage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val indexState by viewModel.indexState.collectAsStateWithLifecycle()
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val cloudSyncState by viewModel.cloudSyncState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val titleAlpha by rememberCollapsedTitleAlpha(listState)
    val colors = OneUiTheme.colors
    val title = stringResource(R.string.common_settings)
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showSyncIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showReindexDialog by rememberSaveable { mutableStateOf(false) }

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
            val current = settings
            if (current != null) {
                item(key = "content") {
                    SettingsContent(
                        settings = current,
                        indexState = indexState,
                        cloudState = if (viewModel.cloudAvailable) cloudState else null,
                        cloudSyncState = cloudSyncState,
                        onOpenAccount = onOpenAccount,
                        onOpenStorage = onOpenStorage,
                        onSyncIntervalClick = { showSyncIntervalDialog = true },
                        onBackgroundSyncWifiOnly = viewModel::setBackgroundSyncWifiOnly,
                        onShowCloudInPhotos = viewModel::setShowCloudInPhotos,
                        onLoadOriginalsOnMobileData = viewModel::setLoadOriginalsOnMobileData,
                        onOfflineWifiOnly = viewModel::setOfflineWifiOnly,
                        onThemeClick = { showThemeDialog = true },
                        onShowVideoDuration = viewModel::setShowVideoDuration,
                        onAutoPlay = viewModel::setAutoPlayVideos,
                        onUseTrash = viewModel::setUseTrash,
                        onConfirmDelete = viewModel::setConfirmBeforeDelete,
                        onReindex = { showReindexDialog = true },
                    )
                }
            }
        }
    }

    val current = settings
    if (showThemeDialog && current != null) {
        ThemeDialog(
            selected = current.themeMode,
            onSelect = {
                viewModel.setThemeMode(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }
    if (showSyncIntervalDialog && current != null) {
        SyncIntervalDialog(
            selected = current.backgroundSyncInterval,
            onSelect = {
                viewModel.setBackgroundSyncInterval(it)
                showSyncIntervalDialog = false
            },
            onDismiss = { showSyncIntervalDialog = false },
        )
    }
    if (showReindexDialog) {
        OneUiDialog(
            title = stringResource(R.string.settings_reindex_title),
            message = stringResource(R.string.settings_reindex_message),
            onDismiss = { showReindexDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.settings_reindex_confirm),
            onConfirm = {
                showReindexDialog = false
                viewModel.reindexAll()
            },
        )
    }
}

@Composable
private fun SettingsContent(
    settings: AppSettings,
    indexState: IndexState,
    cloudState: CloudAccountState?,
    cloudSyncState: CloudSyncState,
    onOpenAccount: () -> Unit,
    onOpenStorage: () -> Unit,
    onSyncIntervalClick: () -> Unit,
    onBackgroundSyncWifiOnly: (Boolean) -> Unit,
    onShowCloudInPhotos: (Boolean) -> Unit,
    onLoadOriginalsOnMobileData: (Boolean) -> Unit,
    onOfflineWifiOnly: (Boolean) -> Unit,
    onThemeClick: () -> Unit,
    onShowVideoDuration: (Boolean) -> Unit,
    onAutoPlay: (Boolean) -> Unit,
    onUseTrash: (Boolean) -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
    onReindex: () -> Unit,
) {
    val indent = OneUiTheme.dimens.cardPadding
    Column(Modifier.fillMaxWidth()) {
        // OneDrive account: absent from builds without an MSAL configuration (cloudState null).
        if (cloudState != null) {
            OneUiCategoryHeader(stringResource(R.string.settings_section_account))
            OneUiCard {
                OneUiListItem(
                    title = stringResource(R.string.common_onedrive),
                    subtitle = cloudAccountSummary(cloudState),
                    onClick = onOpenAccount,
                )
                // Sync status (informational): account signed in or session to renew.
                if (cloudState is CloudAccountState.SignedIn || cloudState is CloudAccountState.NeedsReauth) {
                    OneUiDivider(startIndent = indent)
                    OneUiListItem(
                        title = stringResource(R.string.settings_cloud_sync_state),
                        subtitle = cloudSyncStatusText(cloudSyncState),
                        trailing = if (cloudSyncState.running) RunningTrailing else null,
                    )
                }
                OneUiDivider(startIndent = indent)
                OneUiListItem(
                    title = stringResource(R.string.settings_background_sync),
                    subtitle = syncIntervalLabel(settings.backgroundSyncInterval),
                    onClick = onSyncIntervalClick,
                )
                if (settings.backgroundSyncInterval != BackgroundSyncInterval.OFF) {
                    OneUiDivider(startIndent = indent)
                    OneUiSwitchItem(
                        title = stringResource(R.string.settings_background_sync_wifi_title),
                        subtitle = stringResource(R.string.settings_background_sync_wifi_summary),
                        checked = settings.backgroundSyncWifiOnly,
                        onCheckedChange = onBackgroundSyncWifiOnly,
                    )
                }
                OneUiDivider(startIndent = indent)
                OneUiSwitchItem(
                    title = stringResource(R.string.settings_show_cloud_in_photos),
                    subtitle = stringResource(R.string.settings_show_cloud_in_photos_summary),
                    checked = settings.showCloudInPhotos,
                    onCheckedChange = onShowCloudInPhotos,
                )
                OneUiDivider(startIndent = indent)
                OneUiSwitchItem(
                    title = stringResource(R.string.settings_originals_mobile_title),
                    subtitle = stringResource(R.string.settings_originals_mobile_summary),
                    checked = settings.loadOriginalsOnMobileData,
                    onCheckedChange = onLoadOriginalsOnMobileData,
                )
                OneUiDivider(startIndent = indent)
                OneUiSwitchItem(
                    title = stringResource(R.string.settings_offline_wifi_only_title),
                    subtitle = stringResource(R.string.settings_offline_wifi_only_summary),
                    checked = settings.offlineWifiOnly,
                    onCheckedChange = onOfflineWifiOnly,
                )
            }

            // Storage: caches and OneDrive copies (nothing to see there without OneDrive).
            OneUiCategoryHeader(stringResource(R.string.storage_title))
            OneUiCard {
                OneUiListItem(
                    title = stringResource(R.string.storage_title),
                    subtitle = stringResource(R.string.storage_settings_summary),
                    onClick = onOpenStorage,
                )
            }
        }

        OneUiCategoryHeader(stringResource(R.string.settings_section_appearance))
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.settings_theme),
                subtitle = themeLabel(settings.themeMode),
                onClick = onThemeClick,
            )
        }

        OneUiCategoryHeader(stringResource(R.string.settings_section_display))
        OneUiCard {
            OneUiSwitchItem(
                title = stringResource(R.string.settings_show_video_duration),
                checked = settings.showVideoDuration,
                onCheckedChange = onShowVideoDuration,
            )
            OneUiDivider(startIndent = indent)
            OneUiSwitchItem(
                title = stringResource(R.string.settings_autoplay_videos),
                subtitle = stringResource(R.string.settings_autoplay_videos_summary),
                checked = settings.autoPlayVideos,
                onCheckedChange = onAutoPlay,
            )
        }

        OneUiCategoryHeader(stringResource(R.string.settings_section_delete))
        OneUiCard {
            OneUiSwitchItem(
                title = stringResource(R.string.settings_use_trash),
                subtitle = stringResource(R.string.settings_use_trash_summary),
                checked = settings.useTrash,
                onCheckedChange = onUseTrash,
            )
            OneUiDivider(startIndent = indent)
            OneUiSwitchItem(
                title = stringResource(R.string.settings_confirm_delete),
                checked = settings.confirmBeforeDelete,
                onCheckedChange = onConfirmDelete,
            )
        }

        OneUiCategoryHeader(stringResource(R.string.settings_section_library))
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.settings_index_state),
                subtitle = indexStatusText(indexState),
                trailing = if (indexState.running) RunningTrailing else null,
            )
            OneUiDivider(startIndent = indent)
            OneUiListItem(
                title = stringResource(R.string.settings_reindex),
                subtitle = stringResource(R.string.settings_reindex_summary),
                onClick = if (indexState.running) null else onReindex,
            )
        }

        OneUiCategoryHeader(stringResource(R.string.settings_section_about))
        OneUiCard {
            OneUiListItem(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            )
        }
    }
}

private val RunningTrailing: @Composable () -> Unit = { IndexRunningIndicator() }

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
}

@Composable
private fun syncIntervalLabel(interval: BackgroundSyncInterval): String = when (interval) {
    BackgroundSyncInterval.OFF -> stringResource(R.string.settings_background_sync_off)
    BackgroundSyncInterval.HOURLY -> stringResource(R.string.settings_background_sync_hourly)
    BackgroundSyncInterval.EVERY_6_HOURS -> stringResource(R.string.settings_background_sync_6h)
    BackgroundSyncInterval.DAILY -> stringResource(R.string.settings_background_sync_daily)
}

/** Background sync frequency: tapping an option applies it and closes the dialog. */
@Composable
private fun SyncIntervalDialog(
    selected: BackgroundSyncInterval,
    onSelect: (BackgroundSyncInterval) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = stringResource(R.string.settings_background_sync),
        options = BackgroundSyncInterval.entries,
        selected = selected,
        label = { syncIntervalLabel(it) },
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** Theme choice: tapping an option applies it and closes the dialog. */
@Composable
private fun ThemeDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = stringResource(R.string.settings_theme),
        options = ThemeMode.entries,
        selected = selected,
        label = { themeLabel(it) },
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** List of radio-button options in a One UI dialog ("Cancel" as the only button). */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
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
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = option == selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = colors.accent,
                            unselectedColor = colors.textTertiary,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(label(option), style = OneUiTheme.typography.listTitle, color = colors.textPrimary)
                }
            }
        }
    }
}
