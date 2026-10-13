package fr.eva.cloudgallery.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.components.EmptyState
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiCategoryHeader
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiIconButton
import fr.eva.cloudgallery.ui.components.OneUiListItem
import fr.eva.cloudgallery.ui.grid.MediaGridPage
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/**
 * Full-text search (name, folder, album, date, type): input field at the top, then
 * suggestions while typing ("Search for “…”", matching albums, recent
 * searches), and results in a grid once the search is submitted (keyboard, suggestion). Tapping
 * the field while results are shown goes back to typing.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenMedia: (MediaContext, Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val input by viewModel.input.collectAsStateWithLifecycle()
    val submitted by viewModel.submitted.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val submit: (String) -> Unit = { text ->
        if (canSubmitSearch(text)) {
            focusManager.clearFocus()
            viewModel.submit(text)
        }
    }
    val bar: @Composable (Modifier) -> Unit = { barModifier ->
        SearchBar(
            value = input,
            onValueChange = viewModel::onInputChange,
            onSubmit = { submit(input) },
            onBack = onBack,
            focusRequester = focusRequester,
            onFocused = { if (viewModel.submitted.value != null) viewModel.edit() },
            modifier = barModifier,
        )
    }

    val query = submitted
    if (query == null) {
        // Typing: the field takes focus (keyboard open) on arrival and when coming back from results.
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        SuggestionsContent(
            input = input,
            albums = albums,
            recent = recent,
            onSearch = submit,
            onOpenAlbum = onOpenAlbum,
            onRemoveRecent = viewModel::removeRecent,
            onClearRecent = viewModel::clearRecent,
            bar = bar,
            modifier = modifier,
        )
    } else {
        val resultsViewModel = hiltViewModel<SearchResultsViewModel, SearchResultsViewModel.Factory>(
            key = "search:$query",
            creationCallback = { factory -> factory.create(MediaContext.Search(query)) },
        )
        val gridState = rememberSaveable(query, saver = LazyGridState.Saver) { LazyGridState() }
        MediaGridPage(
            viewModel = resultsViewModel,
            title = query,
            gridState = gridState,
            emptyIcon = Icons.Outlined.SearchOff,
            emptyTitle = stringResource(R.string.search_no_results_title),
            emptyMessage = stringResource(R.string.search_no_results_message, query),
            onOpenMedia = onOpenMedia,
            bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            topBar = bar,
            largeTitle = false,
            modifier = modifier,
        )
    }
}

/** One UI search field in the top bar (status bar inset included). */
@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OneUiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(OneUiTheme.dimens.appBarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneUiBackButton(onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.placeholder)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = OneUiTheme.typography.listTitle.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFocused() },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_hint),
                                style = OneUiTheme.typography.listTitle,
                                color = colors.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                OneUiIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.search_clear),
                    onClick = {
                        onValueChange("")
                        onFocused()
                        runCatching { focusRequester.requestFocus() }
                    },
                    tint = colors.textSecondary,
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** Suggestions while typing, or a prompt to search if there is nothing to suggest. */
@Composable
private fun SuggestionsContent(
    input: String,
    albums: List<AlbumSummary>,
    recent: List<String>,
    onSearch: (String) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    bar: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OneUiTheme.colors
    val indent = OneUiTheme.dimens.cardPadding
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + OneUiTheme.dimens.appBarHeight
    val canSearch = canSubmitSearch(input)
    Box(
        modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        if (!canSearch && recent.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(top = topPadding).imePadding()) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.search_empty_title),
                    message = stringResource(R.string.search_empty_message),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = topPadding + 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                ),
            ) {
                if (canSearch) {
                    item(key = "search_for") {
                        OneUiCard {
                            OneUiListItem(
                                title = stringResource(R.string.search_for, input.trim()),
                                icon = Icons.Outlined.Search,
                                onClick = { onSearch(input) },
                            )
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item(key = "albums") {
                        Column {
                            OneUiCategoryHeader(stringResource(R.string.search_section_albums))
                            OneUiCard {
                                albums.forEachIndexed { index, album ->
                                    if (index > 0) OneUiDivider(startIndent = indent + 40.dp)
                                    OneUiListItem(
                                        title = album.name,
                                        subtitle = pluralStringResource(R.plurals.common_items_count, album.mediaCount, album.mediaCount),
                                        icon = Icons.Outlined.PhotoAlbum,
                                        onClick = { onOpenAlbum(album.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent") {
                        Column {
                            OneUiCategoryHeader(stringResource(R.string.search_section_recent))
                            OneUiCard {
                                recent.forEachIndexed { index, text ->
                                    if (index > 0) OneUiDivider(startIndent = indent + 40.dp)
                                    OneUiListItem(
                                        title = text,
                                        icon = Icons.Outlined.History,
                                        onClick = { onSearch(text) },
                                        trailing = {
                                            OneUiIconButton(
                                                icon = Icons.Outlined.Close,
                                                contentDescription = stringResource(R.string.search_recent_remove),
                                                onClick = { onRemoveRecent(text) },
                                                tint = colors.textSecondary,
                                            )
                                        },
                                    )
                                }
                                if (input.isBlank()) {
                                    OneUiDivider(startIndent = indent)
                                    OneUiListItem(
                                        title = stringResource(R.string.search_recent_clear),
                                        onClick = onClearRecent,
                                        destructive = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        bar(Modifier.align(Alignment.TopCenter))
    }
}
