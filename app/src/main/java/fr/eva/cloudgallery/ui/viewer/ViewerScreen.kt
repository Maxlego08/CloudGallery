package fr.eva.cloudgallery.ui.viewer

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.image.offlineThumbnail
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.roundToInt
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
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
import fr.eva.cloudgallery.ui.components.OneUiProgressDialog
import fr.eva.cloudgallery.ui.components.rememberIntentSenderLauncher
import fr.eva.cloudgallery.ui.securefolder.SecureFolderDialogs
import fr.eva.cloudgallery.ui.securefolder.secureFolderActionVisible
import fr.eva.cloudgallery.ui.offline.OfflinePromptDialog
import fr.eva.cloudgallery.ui.offline.offlineStatusText
import fr.eva.cloudgallery.ui.offline.rememberNotificationPermissionGate
import fr.eva.cloudgallery.ui.theme.OneUiFavoriteRed
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.theme.ViewerColors
import fr.eva.cloudgallery.ui.util.Formatters
import fr.eva.cloudgallery.ui.util.openWithExternalApp
import fr.eva.cloudgallery.ui.util.progressPercent
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
fun ViewerScreen(
    onBack: () -> Unit,
    onPlayVideo: (mediaId: Long) -> Unit,
    transition: ViewerTransition = ViewerTransition.Static,
) {
    val viewModel: ViewerViewModel = hiltViewModel()
    val ids by viewModel.ids.collectAsStateWithLifecycle()
    val media by viewModel.currentMedia.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val exif by viewModel.exif.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val folders by viewModel.localFolders.collectAsStateWithLifecycle()
    val cloudOriginal by viewModel.cloudOriginal.collectAsStateWithLifecycle()
    val costlyNetwork by viewModel.costlyNetwork.collectAsStateWithLifecycle()
    val sharePreparation by viewModel.sharePreparation.collectAsStateWithLifecycle()
    val offlineState by viewModel.offlineState.collectAsStateWithLifecycle()
    val offlinePrompt by viewModel.offlinePrompt.collectAsStateWithLifecycle()
    val secureFolderAvailable by viewModel.secureFolderAvailable.collectAsStateWithLifecycle()
    val secureFolderPrompt by viewModel.secureFolderPrompt.collectAsStateWithLifecycle()
    // Autorisation des notifications demandée au premier « Conserver hors ligne » (facultative).
    val withNotificationPermission = rememberNotificationPermissionGate(
        alreadyAsked = settings.offlineNotificationsAsked,
        onAsked = viewModel::markNotificationsAsked,
    )
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
                is ViewerEvent.PluralToast -> Toast.makeText(
                    context,
                    resources.getQuantityString(event.message, event.count, event.count),
                    Toast.LENGTH_SHORT,
                ).show()
                ViewerEvent.CropSaved -> showCropEditor = false
                is ViewerEvent.Share -> runCatching { shareMedia(context, event.uris, event.mimeTypes) }
                    .onFailure { Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show() }
                is ViewerEvent.OpenWith -> runCatching { openWithExternalApp(context, event.uri, event.mimeType) }
                    .onFailure { Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show() }
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
    // Sans fichier local (OneDrive ou copie hors ligne) : les actions d'écriture (modifier,
    // supprimer, rogner, pivoter, copier) restent réservées aux fichiers de l'appareil ; partager
    // et « Ouvrir avec » utilisent la copie hors ligne ou téléchargent l'original (lecture seule).
    val cloudOnly = current != null && !isLocal
    val canRead = isLocal || current?.hasCloud == true || current?.hasOffline == true
    val isImage = current?.type == MediaType.IMAGE
    val chromeShown = chromeVisible && dismissProgress < 0.02f && !showCropEditor
    ImmersiveSystemBars(visible = chromeShown || showCropEditor)

    // Transition grille ↔ visionneuse (ViewerTransition.kt) : la photo part de sa vignette et y
    // revient quand la grille l'affiche ; sinon (ouverture depuis une autre appli, vignette hors
    // écran) fondu et léger zoom.
    val registry = LocalMediaBounds.current
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(Size.Zero) }
    val entry = registry?.entryOf(currentId)
    val cell = entry?.rect?.translate(-rootOffset)
    // Dimensions : détails chargés, sinon média de la grille (connu dès la première image).
    val dims = current?.takeIf { it.id == currentId }?.let { Triple(it.width, it.height, it.orientation) }
        ?: entry?.media?.let { Triple(it.width, it.height, it.orientation) }
    val fitted = dims?.let { (w, h, orientation) ->
        val swap = orientation % 180 != 0
        ViewerZoomGeometry.fittedRect(rootSize, if (swap) h else w, if (swap) w else h)
    }
    /** Vignette et rectangle ajusté de la photo courante (repère de la visionneuse), si connus. */
    val zoomRects: Pair<Rect, Rect>? = if (cell != null && fitted != null) cell to fitted else null
    val shared = zoomRects != null
    val closing = transition.closing
    // Barres : masquées aussitôt au retour, affichées en fondu passée la moitié de l'ouverture.
    val opening by remember(transition) { derivedStateOf { !transition.closing && transition.progress < 0.6f } }
    val chromeOnScreen = chromeShown && !closing && !opening
    SideEffect { registry?.hiddenId = if (closing && shared) currentId else null }
    DisposableEffect(registry) { onDispose { registry?.hiddenId = null } }

    // Miniature de la grille (déjà en cache mémoire) sous les pages : la photo est visible dès la
    // première image de l'ouverture, sans attendre les identifiants ni l'image de la visionneuse.
    // Retirée au premier geste (page suivante, zoom, glisser) et au retour.
    val openedId = remember { currentId }
    val openedEntry = remember { registry?.entryOf(currentId) }
    var pagerMoved by remember { mutableStateOf(false) }
    val showGridThumb = openedEntry != null && fitted != null && currentId == openedId &&
        !closing && !pagerMoved && !currentZoomed && dismissProgress == 0f
    // Pages (identifiants, images pleine taille) composées une fois l'ouverture terminée : leur
    // construction pendant l'animation la faisait saccader. La miniature tient lieu de photo.
    val openingDone by remember(transition) { derivedStateOf { transition.closing || transition.progress >= 1f } }
    val pagerReady = openedEntry == null || openingDone

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootOffset = it.positionInWindow()
                rootSize = it.size.toSize()
            }
            .graphicsLayer {
                if (!shared) {
                    val p = transition.progress
                    alpha = p
                    scaleX = lerp(0.92f, 1f, p)
                    scaleY = scaleX
                }
            }
            .drawBehind {
                // Fond noir dès l'ouverture, transparent dès le retour : la grille est visible tout
                // de suite (y compris la première image, avant que la grille ne donne ses positions).
                if (!closing) drawRect(ViewerColors.background)
            },
    ) {
        if (showGridThumb && openedEntry != null && fitted != null) {
            GridThumbnailLayer(
                media = openedEntry.media,
                fitted = fitted,
                requestSide = openedEntry.rect.width,
                modifier = Modifier.fillMaxSize().zoomFromCell(zoomRects) { transition.progress },
            )
        }
        if (!idList.isNullOrEmpty() && pagerReady) {
            val currentIds by rememberUpdatedState(idList)
            val pagerState = rememberPagerState(
                initialPage = idList.indexOf(currentId).coerceAtLeast(0),
            ) { currentIds.size }
            val scope = rememberCoroutineScope()
            val velocityThreshold = with(LocalDensity.current) { 400.dp.toPx() }

            LaunchedEffect(pagerState) {
                snapshotFlow { abs(pagerState.currentPageOffsetFraction) > 0.001f }.first { it }
                pagerMoved = true
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { currentIds.getOrNull(pagerState.currentPage) }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { viewModel.onPageSelected(it) }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomFromCell(zoomRects) { transition.progress },
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
                    cloudOriginal = cloudOriginal,
                    costlyNetwork = costlyNetwork,
                    onHiResWanted = viewModel::onHiResWanted,
                    onLoadOriginal = viewModel::loadOriginal,
                    onCancelOriginal = viewModel::cancelOriginal,
                )
            }

            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect {
                    currentZoomed = false
                    dismissProgress = 0f
                }
            }

            AnimatedVisibility(
                visible = chromeOnScreen,
                enter = fadeIn(),
                exit = if (closing) ExitTransition.None else fadeOut(),
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
                        canShare = canRead && sharePreparation == null,
                        isImage = isImage,
                        onFavorite = viewModel::toggleFavorite,
                        onEdit = { showCropEditor = true },
                        onShare = viewModel::shareCurrent,
                        onDelete = {
                            if (settings.confirmBeforeDelete) showDeleteConfirm = true else viewModel.requestDelete()
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeOnScreen,
            enter = fadeIn(),
            exit = if (closing) ExitTransition.None else fadeOut(),
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
                    }
                    // Fichier du téléphone seulement, appli Dossier sécurisé Samsung installée.
                    if (secureFolderActionVisible(secureFolderAvailable, if (isLocal) 1 else 0)) {
                        add(
                            OneUiMenuItem(stringResource(R.string.secure_folder_move), enabled = !busy) {
                                viewModel.moveCurrentToSecureFolder()
                            },
                        )
                    }
                    add(
                        OneUiMenuItem(stringResource(R.string.common_open_with), enabled = canRead && sharePreparation == null) {
                            viewModel.openCurrentWith()
                        },
                    )
                    // Copie hors ligne (éléments OneDrive, ou copie conservée d'un élément supprimé de OneDrive).
                    if (current != null && (current.hasCloud || current.hasOffline)) {
                        when {
                            offlineState.active -> add(
                                OneUiMenuItem(stringResource(R.string.offline_cancel_download)) { viewModel.cancelOfflineDownload() },
                            )
                            offlineState.stopped -> {
                                add(
                                    OneUiMenuItem(stringResource(R.string.offline_resume_download)) {
                                        withNotificationPermission { viewModel.keepOffline() }
                                    },
                                )
                                add(OneUiMenuItem(stringResource(R.string.offline_cancel_download)) { viewModel.cancelOfflineDownload() })
                            }
                            current.hasCloud && !current.hasOffline -> add(
                                OneUiMenuItem(stringResource(R.string.offline_keep)) {
                                    withNotificationPermission { viewModel.keepOffline() }
                                },
                            )
                        }
                        if (current.hasOffline) {
                            add(OneUiMenuItem(stringResource(R.string.offline_release)) { viewModel.releaseOffline() })
                        }
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
        DetailsSheet(
            media = current,
            exif = exif,
            onDismiss = { showDetails = false },
            offlineStatus = offlineStatusText(
                state = offlineState,
                deletedOnOneDrive = current.hasOffline && !current.hasCloud,
                offlineBytes = current.offlineCopy?.sizeBytes ?: current.offlineSource?.sizeBytes ?: 0L,
            ),
        )
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

    sharePreparation?.let { preparation ->
        SharePreparationDialog(preparation = preparation, onCancel = viewModel::cancelSharePreparation)
    }

    offlinePrompt?.let { prompt ->
        OfflinePromptDialog(
            prompt = prompt,
            onConfirmLargeBatch = viewModel::confirmLargeBatch,
            onDownloadNow = viewModel::downloadOfflineNow,
            onConfirmRelease = viewModel::confirmRelease,
            onDismiss = viewModel::dismissOfflinePrompt,
        )
    }

    SecureFolderDialogs(
        prompt = secureFolderPrompt,
        onShareLaunched = viewModel::onSecureFolderShareLaunched,
        onShareReturned = viewModel::onSecureFolderShareReturned,
        onTrash = viewModel::trashAfterSecureFolder,
        onCancel = viewModel::dismissSecureFolder,
        onKeep = viewModel::dismissSecureFolder,
    )

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
 * Supprimer. Elément uniquement sur OneDrive ([cloudOnly]) : Favori et Partager (original
 * téléchargé) ; modifier et supprimer restent réservés aux fichiers de l'appareil.
 */
@Composable
private fun ViewerActionBar(
    media: MediaDetails?,
    isLocal: Boolean,
    cloudOnly: Boolean,
    canShare: Boolean,
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
        if (cloudOnly) {
            ActionSlot {
                OneUiIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.common_share),
                    onClick = onShare,
                    enabled = canShare,
                    tint = ViewerColors.content,
                )
            }
            return@Row
        }
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
                enabled = isLocal && canShare,
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

/**
 * Téléchargement d'un original OneDrive avant un partage ou un « Ouvrir avec » : progression et
 * annulation (aussi par retour ou toucher hors de la boîte).
 */
@Composable
private fun SharePreparationDialog(preparation: SharePreparation, onCancel: () -> Unit) {
    val context = LocalContext.current
    val percent = progressPercent(preparation.done, preparation.total)
    OneUiProgressDialog(
        title = stringResource(R.string.share_preparing),
        message = if (preparation.total > 0) {
            stringResource(
                R.string.share_preparing_progress,
                Formatter.formatShortFileSize(context, preparation.done),
                Formatter.formatShortFileSize(context, preparation.total),
            )
        } else {
            null
        },
        progress = if (percent >= 0) percent / 100f else null,
        cancelLabel = stringResource(R.string.common_cancel),
        onCancel = onCancel,
    )
}

/**
 * Pendant la transition grille ↔ visionneuse : contenu agrandi depuis la vignette (repère du
 * conteneur, centre de [rects].second pour pivot) et découpé de la vignette au rectangle ajusté.
 */
private fun Modifier.zoomFromCell(rects: Pair<Rect, Rect>?, progress: () -> Float): Modifier {
    val (cellRect, fittedRect) = rects ?: return this
    return this
        .drawWithContent {
            val p = progress()
            if (p >= 1f) {
                drawContent()
            } else {
                val clip = ViewerZoomGeometry.frame(cellRect, fittedRect, p).clip
                clipRect(clip.left, clip.top, clip.right, clip.bottom) { this@drawWithContent.drawContent() }
            }
        }
        .graphicsLayer {
            val frame = ViewerZoomGeometry.frame(cellRect, fittedRect, progress())
            scaleX = frame.scale
            scaleY = frame.scale
            translationX = frame.translation.x
            translationY = frame.translation.y
        }
}

/**
 * Miniature de la grille dans le rectangle ajusté [fitted] : demandée à la taille des vignettes
 * ([requestSide] px pour le grand côté, même palier de cache Coil que la grille) puis agrandie.
 */
@Composable
private fun GridThumbnailLayer(media: MediaSummary, fitted: Rect, requestSide: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val k = (requestSide / max(fitted.width, fitted.height)).coerceAtMost(1f)
    Box(modifier) {
        Box(
            Modifier
                .offset { IntOffset(fitted.left.roundToInt(), fitted.top.roundToInt()) }
                .size(with(density) { (fitted.width * k).toDp() }, with(density) { (fitted.height * k).toDp() })
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f / k
                    scaleY = 1f / k
                },
        ) {
            ThumbnailImage(
                uri = media.uri,
                isVideo = media.type == MediaType.VIDEO,
                version = media.dateModified,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                cloud = media.cloudThumb,
                offline = remember(media) { media.offlineThumbnail() },
                placeholder = false,
            )
        }
    }
}
