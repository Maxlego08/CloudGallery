package fr.eva.cloudgallery.ui.viewer

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.albums.AlbumPickerSheet
import fr.eva.cloudgallery.ui.components.ImmersiveSystemBars
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiIconButton
import fr.eva.cloudgallery.ui.components.OneUiMenuItem
import fr.eva.cloudgallery.ui.components.OneUiOverflowMenu
import fr.eva.cloudgallery.ui.components.rememberIntentSenderLauncher
import fr.eva.cloudgallery.ui.theme.OneUiFavoriteRed
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.theme.ViewerColors
import fr.eva.cloudgallery.ui.util.Formatters
import fr.eva.cloudgallery.ui.util.openWithExternalApp
import fr.eva.cloudgallery.ui.util.shareMedia
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

private val ScrimColor = Color(0x99000000)

/**
 * Visionneuse plein écran façon Samsung Galerie : pager d'images/vidéos du contexte, chrome (barres,
 * bande de miniatures) masquable d'un tap, panneau Détails, rotation, recadrage en copie, suppression.
 * Le ViewModel lit [fr.eva.cloudgallery.ui.navigation.ViewerRoute] via SavedStateHandle.
 */
@Composable
fun ViewerScreen(onBack: () -> Unit, onPlayVideo: (mediaId: Long) -> Unit) {
    val viewModel: ViewerViewModel = hiltViewModel()
    val ids by viewModel.ids.collectAsStateWithLifecycle()
    val media by viewModel.currentMedia.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val exif by viewModel.exif.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val folders by viewModel.localFolders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showAlbumPicker by rememberSaveable { mutableStateOf(false) }
    var showFolderPicker by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showCropEditor by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    var currentZoomed by remember { mutableStateOf(false) }

    val launchSender = rememberIntentSenderLauncher { granted -> viewModel.onIntentSenderResult(granted) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ViewerEvent.LaunchIntentSender -> launchSender(event.sender)
                is ViewerEvent.Toast -> {
                    val text = if (event.arg != null) resources.getString(event.message, event.arg) else resources.getString(event.message)
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
                ViewerEvent.CropSaved -> showCropEditor = false
            }
        }
    }

    // Liste vide (dernier élément supprimé, album vidé…) : on ferme la visionneuse.
    val idList = ids
    LaunchedEffect(idList) {
        if (idList != null && idList.isEmpty()) currentOnBack()
    }

    val current = media
    val isLocal = current != null && current.hasLocal && current.uri != null
    // Uniquement sur OneDrive : les actions sur fichier local sont masquées (pas de bouton inopérant).
    val cloudOnly = current != null && !isLocal
    val isImage = current?.type == MediaType.IMAGE
    val chromeShown = chromeVisible && dismissProgress < 0.02f && !showCropEditor
    ImmersiveSystemBars(visible = chromeShown || showCropEditor)

    Box(
        Modifier
            .fillMaxSize()
            .background(ViewerColors.background),
    ) {
        if (!idList.isNullOrEmpty()) {
            val currentIds by rememberUpdatedState(idList)
            val pagerState = rememberPagerState(
                initialPage = idList.indexOf(currentId).coerceAtLeast(0),
            ) { currentIds.size }
            val scope = rememberCoroutineScope()
            val velocityThreshold = with(LocalDensity.current) { 400.dp.toPx() }

            LaunchedEffect(pagerState) {
                snapshotFlow { currentIds.getOrNull(pagerState.currentPage) }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { viewModel.onPageSelected(it) }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                pageSpacing = 12.dp,
                userScrollEnabled = !currentZoomed,
                key = { index -> currentIds.getOrElse(index) { -1L - index } },
            ) { page ->
                val id = currentIds.getOrNull(page)
                val pageMediaFlow = remember(id) { id?.let { viewModel.observeMedia(it) } }
                val pageMedia = pageMediaFlow?.collectAsStateWithLifecycle(initialValue = null)?.value
                val isSettled = page == pagerState.settledPage
                val callbacks = remember(page, pagerState) {
                    PageGestureCallbacks(
                        onTap = { chromeVisible = !chromeVisible },
                        onDismiss = { currentOnBack() },
                        onSwipeUp = { showDetails = true },
                        onDragProgress = { progress -> if (page == pagerState.settledPage) dismissProgress = progress },
                        onPagerDrag = { dx -> pagerState.dispatchRawDelta(-dx) },
                        onPagerDragEnd = { vx -> scope.launch { settlePager(pagerState, page, vx, velocityThreshold) } },
                    )
                }
                ViewerPage(
                    media = pageMedia,
                    isCurrentPage = isSettled,
                    callbacks = callbacks,
                    onZoomChanged = { zoomed -> if (page == pagerState.settledPage) currentZoomed = zoomed },
                    onPlayVideo = onPlayVideo,
                )
            }

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect {
                    currentZoomed = false
                    dismissProgress = 0f
                }
            }

            AnimatedVisibility(
                visible = chromeShown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, ScrimColor))),
                ) {
                    if (idList.size > 1) {
                        Filmstrip(
                            ids = idList,
                            currentIndex = pagerState.currentPage,
                            summaries = summaries,
                            onRequestSummaries = viewModel::requestSummaries,
                            onSelect = { index ->
                                scope.launch {
                                    if (abs(index - pagerState.currentPage) > 3) {
                                        pagerState.scrollToPage(index)
                                    } else {
                                        pagerState.animateScrollToPage(index)
                                    }
                                }
                            },
                        )
                    }
                    ViewerActionBar(
                        media = current,
                        isLocal = isLocal,
                        cloudOnly = cloudOnly,
                        isImage = isImage,
                        onFavorite = viewModel::toggleFavorite,
                        onEdit = { showCropEditor = true },
                        onShare = {
                            val m = current
                            if (m?.uri != null && isLocal) shareMedia(context, listOf(m.uri), listOf(m.mimeType))
                        },
                        onDelete = {
                            if (settings.confirmBeforeDelete) showDeleteConfirm = true else viewModel.requestDelete()
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                media = current,
                onBack = onBack,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = { menuExpanded = it },
                menuItems = buildList {
                    add(OneUiMenuItem(stringResource(R.string.common_details)) { showDetails = true })
                    if (isImage && !cloudOnly) {
                        // Rotation sans perte : seulement pour les formats dont l'orientation EXIF est modifiable.
                        if (viewModel.canRotate(current)) {
                            add(OneUiMenuItem(stringResource(R.string.viewer_rotate_left), enabled = isLocal && !busy) { viewModel.requestRotate(270) })
                            add(OneUiMenuItem(stringResource(R.string.viewer_rotate_right), enabled = isLocal && !busy) { viewModel.requestRotate(90) })
                        }
                        add(OneUiMenuItem(stringResource(R.string.viewer_crop), enabled = isLocal) { showCropEditor = true })
                    }
                    add(OneUiMenuItem(stringResource(R.string.common_add_to_album), enabled = current != null) { showAlbumPicker = true })
                    if (!cloudOnly) {
                        add(OneUiMenuItem(stringResource(R.string.common_copy_to_album), enabled = isLocal && !busy) { showFolderPicker = true })
                        add(
                            OneUiMenuItem(stringResource(R.string.common_open_with), enabled = isLocal) {
                                val m = current
                                if (m?.uri != null) openWithExternalApp(context, m.uri, m.mimeType)
                            },
                        )
                    }
                },
            )
        }

        if (busy && !showCropEditor) {
            CircularProgressIndicator(
                color = ViewerColors.content,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp),
            )
        }

        if (showCropEditor && current != null && isImage && isLocal) {
            val uri = current.uri
            if (uri != null) {
                CropEditor(
                    media = current,
                    uri = uri,
                    saving = busy,
                    onCancel = { showCropEditor = false },
                    onSave = { rect, rotation -> viewModel.saveCrop(rect, rotation) },
                )
            }
        }
    }

    // --- Panneaux et dialogues ---------------------------------------------------------------

    if (showDetails && current != null) {
        LaunchedEffect(current.id, current.dateModified) { viewModel.loadExif(current) }
        DetailsSheet(media = current, exif = exif, onDismiss = { showDetails = false })
    }

    if (showAlbumPicker) {
        AlbumPickerSheet(
            overFullscreenMedia = true,
            onDismiss = { showAlbumPicker = false },
            onAlbumChosen = { albumId ->
                showAlbumPicker = false
                viewModel.addToAlbum(albumId)
            },
        )
    }

    if (showFolderPicker) {
        FolderPickerSheet(
            folders = folders,
            onDismiss = { showFolderPicker = false },
            onFolderChosen = { folder ->
                showFolderPicker = false
                viewModel.copyToFolder(folder)
            },
        )
    }

    if (showDeleteConfirm) {
        OneUiDialog(
            title = pluralStringResource(R.plurals.common_delete_confirm, 1, 1),
            message = stringResource(if (settings.useTrash) R.string.common_trash_info else R.string.viewer_delete_permanent_info),
            confirmLabel = stringResource(R.string.common_delete),
            dismissLabel = stringResource(R.string.common_cancel),
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.requestDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

private suspend fun settlePager(pagerState: PagerState, fromPage: Int, velocityX: Float, velocityThreshold: Float) {
    val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val target = ZoomMath.pagerSettleTarget(position, fromPage, velocityX, velocityThreshold, pagerState.pageCount)
    pagerState.animateScrollToPage(target)
}

@Composable
private fun ViewerTopBar(
    media: MediaDetails?,
    onBack: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    menuItems: List<OneUiMenuItem>,
) {
    val todayLabel = stringResource(R.string.common_today)
    val yesterdayLabel = stringResource(R.string.common_yesterday)
    val (title, subtitle) = remember(media?.dateTaken, media?.displayName, todayLabel, yesterdayLabel) {
        val taken = media?.dateTaken ?: 0L
        if (taken > 0L) {
            val zone = ZoneId.systemDefault()
            val locale = Locale.getDefault()
            val dayTitle = Formatters.dayHeader(
                date = Formatters.localDate(taken, zone),
                locale = locale,
                todayLabel = todayLabel,
                yesterdayLabel = yesterdayLabel,
            )
            val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
                .format(Instant.ofEpochMilli(taken).atZone(zone))
            dayTitle to time
        } else {
            (media?.displayName ?: "") to null
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ScrimColor, Color.Transparent))),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(OneUiTheme.dimens.appBarHeight + 8.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OneUiBackButton(onClick = onBack, tint = ViewerColors.content)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = OneUiTheme.typography.appBarTitle,
                        color = ViewerColors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .semantics { heading() },
                    )
                    if (media?.hasCloud == true) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.Cloud,
                            contentDescription = stringResource(R.string.common_onedrive),
                            tint = ViewerColors.contentSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = OneUiTheme.typography.listSubtitle,
                        color = ViewerColors.contentSecondary,
                        maxLines = 1,
                    )
                }
            }
            Box {
                OneUiIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.common_more),
                    onClick = { onMenuExpandedChange(true) },
                    enabled = media != null,
                    tint = ViewerColors.content,
                )
                OneUiOverflowMenu(
                    expanded = menuExpanded,
                    onDismiss = { onMenuExpandedChange(false) },
                    items = menuItems,
                )
            }
        }
    }
}

/**
 * Barre d'actions de la visionneuse (icônes seules, comme One UI) : Favori, Modifier, Partager,
 * Supprimer. Elément uniquement sur OneDrive ([cloudOnly]) : Favori seul (lecture seule).
 */
@Composable
private fun ViewerActionBar(
    media: MediaDetails?,
    isLocal: Boolean,
    cloudOnly: Boolean,
    isImage: Boolean,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val favorite = media?.isFavorite == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .height(OneUiTheme.dimens.actionBarHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionSlot {
            OneUiIconButton(
                icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(if (favorite) R.string.common_favorite_remove else R.string.common_favorite_add),
                onClick = onFavorite,
                enabled = media != null,
                tint = if (favorite) OneUiFavoriteRed else ViewerColors.content,
            )
        }
        if (cloudOnly) return@Row
        ActionSlot {
            OneUiIconButton(
                icon = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.common_edit),
                onClick = onEdit,
                // TODO(viewer): édition vidéo (découpe) non prise en charge pour l'instant.
                enabled = isLocal && isImage,
                tint = ViewerColors.content,
            )
        }
        ActionSlot {
            OneUiIconButton(
                icon = Icons.Outlined.Share,
                contentDescription = stringResource(R.string.common_share),
                onClick = onShare,
                enabled = isLocal,
                tint = ViewerColors.content,
            )
        }
        ActionSlot {
            OneUiIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.common_delete),
                onClick = onDelete,
                enabled = isLocal,
                tint = ViewerColors.content,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionSlot(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}
