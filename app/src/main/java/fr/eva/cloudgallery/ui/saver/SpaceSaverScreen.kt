package fr.eva.cloudgallery.ui.saver

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.SaverAge
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import fr.eva.cloudgallery.ui.components.EmptyState
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiCheckCircle
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiListItem
import fr.eva.cloudgallery.ui.components.OneUiPillButton
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.components.rememberIntentSenderLauncher
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters

/**
 * Economiseur d'espace (page Stockage) : copies du téléphone déjà sur OneDrive, filtrées par
 * ancienneté et toutes cochées par défaut ; l'utilisateur décoche ce qu'il veut garder, puis
 * confirme deux fois (boîte de l'appli, puis boîte système Android). Corbeille uniquement.
 */
@Composable
fun SpaceSaverScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpaceSaverViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val gridState = rememberLazyGridState()
    val titleAlpha by rememberCollapsedTitleAlpha(gridState)
    val colors = OneUiTheme.colors
    val title = stringResource(R.string.saver_title)
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var showAgeDialog by rememberSaveable { mutableStateOf(false) }
    var showConfirmDialog by rememberSaveable { mutableStateOf(false) }

    // Instant de référence du filtre d'ancienneté, fixé à l'ouverture de l'écran.
    val now = remember { System.currentTimeMillis() }
    val filtered = remember(state.list, state.age) { state.filtered(now) }
    val selected = remember(filtered, state.deselected) { selectedIn(filtered, state.deselected) }
    val summary = remember(selected) { summaryOf(selected) }

    val launcher = rememberIntentSenderLauncher { granted -> viewModel.onTrashResult(granted) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is SpaceSaverEvent.LaunchTrash -> {
                    launcher(event.sender)
                    null
                }
                is SpaceSaverEvent.Freed ->
                    resources.getQuantityString(R.plurals.saver_freed, event.count, event.count, Formatters.fileSize(event.bytes))
                is SpaceSaverEvent.Changed -> resources.getQuantityString(R.plurals.saver_changed, event.count, event.count)
                SpaceSaverEvent.Failed -> resources.getString(R.string.common_error_generic)
            }
            if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = horizontalInsets.calculateStartPadding(layoutDirection) + 12.dp,
                end = horizontalInsets.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                OneUiLargeTitleHeader(title = title, subtitle = null)
            }
            val list = state.list
            if (list != null && list.candidates.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Rounded.CloudDone,
                        title = stringResource(R.string.saver_empty_title),
                        message = stringResource(R.string.saver_empty_message),
                    )
                }
            }
            if (list != null && list.candidates.isNotEmpty()) {
                item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                    SaverHeader(
                        selectedCount = summary.count,
                        selectedBytes = summary.bytes,
                        age = state.age,
                        allChecked = filtered.isNotEmpty() && selected.size == filtered.size,
                        hasFiltered = filtered.isNotEmpty(),
                        excluded = list.excludedUserData,
                        onAgeClick = { showAgeDialog = true },
                        onToggleAll = { viewModel.setAllSelected(selected.size != filtered.size, now) },
                    )
                }
                if (filtered.isEmpty()) {
                    item(key = "emptyFilter", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.saver_empty_filter),
                            style = OneUiTheme.typography.listSubtitle,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(filtered, key = { it.sourceId }) { candidate ->
                    SaverCell(
                        candidate = candidate,
                        checked = candidate.sourceId !in state.deselected,
                        enabled = !state.busy,
                        onToggle = { viewModel.toggle(candidate.sourceId) },
                    )
                }
            }
        }
        if (summary.count > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = bottomInset + 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                OneUiPillButton(
                    text = stringResource(R.string.saver_free_up, Formatters.fileSize(summary.bytes)),
                    onClick = { showConfirmDialog = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showAgeDialog) {
        AgeDialog(
            selected = state.age,
            onSelect = {
                viewModel.setAge(it)
                showAgeDialog = false
            },
            onDismiss = { showAgeDialog = false },
        )
    }
    if (showConfirmDialog && summary.count > 0) {
        OneUiDialog(
            title = stringResource(R.string.saver_confirm_title, Formatters.fileSize(summary.bytes)),
            message = pluralStringResource(R.plurals.saver_confirm_message, summary.count, summary.count),
            onDismiss = { showConfirmDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.saver_confirm),
            destructive = true,
            onConfirm = {
                showConfirmDialog = false
                viewModel.freeUp(now)
            },
        )
    }
}

private const val GRID_COLUMNS = 4

@Composable
private fun SaverHeader(
    selectedCount: Int,
    selectedBytes: Long,
    age: SaverAge,
    allChecked: Boolean,
    hasFiltered: Boolean,
    excluded: Int,
    onAgeClick: () -> Unit,
    onToggleAll: () -> Unit,
) {
    val colors = OneUiTheme.colors
    val indent = OneUiTheme.dimens.cardPadding
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        OneUiCard {
            OneUiListItem(
                title = stringResource(
                    R.string.saver_summary,
                    pluralStringResource(R.plurals.common_items_count, selectedCount, selectedCount),
                    Formatters.fileSize(selectedBytes),
                ),
                subtitle = stringResource(R.string.saver_info),
            )
            OneUiDivider(startIndent = indent)
            OneUiListItem(
                title = stringResource(R.string.saver_age),
                subtitle = ageLabel(age),
                onClick = onAgeClick,
            )
            if (hasFiltered) {
                OneUiDivider(startIndent = indent)
                OneUiListItem(
                    title = stringResource(if (allChecked) R.string.saver_select_none else R.string.saver_select_all),
                    onClick = onToggleAll,
                )
            }
        }
        if (excluded > 0) {
            Text(
                text = pluralStringResource(R.plurals.saver_excluded, excluded, excluded),
                style = OneUiTheme.typography.listSubtitle,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SaverCell(
    candidate: SpaceSaverCandidate,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val description = stringResource(
        if (checked) R.string.saver_item_checked else R.string.saver_item_unchecked,
        candidate.displayName,
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
    ) {
        ThumbnailImage(
            uri = remember(candidate.uri) { Uri.parse(candidate.uri) },
            isVideo = candidate.isVideo,
            version = candidate.dateModified,
            modifier = Modifier.fillMaxSize(),
        )
        OneUiCheckCircle(
            checked = checked,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        )
    }
}

@Composable
private fun ageLabel(age: SaverAge): String = when (age) {
    SaverAge.ALL -> stringResource(R.string.saver_age_all)
    SaverAge.DAYS_30 -> stringResource(R.string.saver_age_30)
    SaverAge.DAYS_90 -> stringResource(R.string.saver_age_90)
    SaverAge.YEAR -> stringResource(R.string.saver_age_year)
}

/** Choix du filtre d'ancienneté : un toucher sur une option l'applique et ferme la boîte. */
@Composable
private fun AgeDialog(
    selected: SaverAge,
    onSelect: (SaverAge) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OneUiTheme.colors
    OneUiDialog(
        title = stringResource(R.string.saver_age),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            SaverAge.entries.forEach { age ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = age == selected, role = Role.RadioButton, onClick = { onSelect(age) })
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = age == selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = colors.accent,
                            unselectedColor = colors.textTertiary,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(ageLabel(age), style = OneUiTheme.typography.listTitle, color = colors.textPrimary)
                }
            }
        }
    }
}
