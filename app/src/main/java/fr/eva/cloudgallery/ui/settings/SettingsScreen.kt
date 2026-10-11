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

/** Ecran Paramètres One UI : grand titre puis cartes groupées sur fond gris clair. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
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
                        onShowCloudInPhotos = viewModel::setShowCloudInPhotos,
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
    onShowCloudInPhotos: (Boolean) -> Unit,
    onThemeClick: () -> Unit,
    onShowVideoDuration: (Boolean) -> Unit,
    onAutoPlay: (Boolean) -> Unit,
    onUseTrash: (Boolean) -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
    onReindex: () -> Unit,
) {
    val indent = OneUiTheme.dimens.cardPadding
    Column(Modifier.fillMaxWidth()) {
        // Compte OneDrive : absent des builds sans configuration MSAL (cloudState null).
        if (cloudState != null) {
            OneUiCategoryHeader(stringResource(R.string.settings_section_account))
            OneUiCard {
                OneUiListItem(
                    title = stringResource(R.string.common_onedrive),
                    subtitle = cloudAccountSummary(cloudState),
                    onClick = onOpenAccount,
                )
                // Etat de la synchronisation (information) : compte connecté ou session à renouveler.
                if (cloudState is CloudAccountState.SignedIn || cloudState is CloudAccountState.NeedsReauth) {
                    OneUiDivider(startIndent = indent)
                    OneUiListItem(
                        title = stringResource(R.string.settings_cloud_sync_state),
                        subtitle = cloudSyncStatusText(cloudSyncState),
                        trailing = if (cloudSyncState.running) RunningTrailing else null,
                    )
                }
                OneUiDivider(startIndent = indent)
                OneUiSwitchItem(
                    title = stringResource(R.string.settings_show_cloud_in_photos),
                    subtitle = stringResource(R.string.settings_show_cloud_in_photos_summary),
                    checked = settings.showCloudInPhotos,
                    onCheckedChange = onShowCloudInPhotos,
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

/** Choix du thème : un toucher sur une option l'applique et ferme la boîte. */
@Composable
private fun ThemeDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OneUiTheme.colors
    OneUiDialog(
        title = stringResource(R.string.settings_theme),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onSelect(mode) })
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = colors.accent,
                            unselectedColor = colors.textTertiary,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(themeLabel(mode), style = OneUiTheme.typography.listTitle, color = colors.textPrimary)
                }
            }
        }
    }
}
