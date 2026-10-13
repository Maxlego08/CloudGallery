package fr.eva.cloudgallery.ui.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.ui.components.ImmersiveSystemBars
import fr.eva.cloudgallery.ui.theme.ViewerColors
import fr.eva.cloudgallery.ui.util.openWithExternalApp
import kotlinx.coroutines.delay

private const val AUTO_HIDE_DELAY_MS = 3_000L
private const val SEEK_FEEDBACK_DURATION_MS = 700L

/** Retour visuel d'un double appui (côté -1 / +1, cumul en secondes). */
internal data class SeekFeedback(val side: Int, val seconds: Int, val key: Int)

/**
 * Lecteur vidéo plein écran façon Samsung Galerie : fond noir, mode immersif, commandes
 * superposées masquées automatiquement après 3 s de lecture, double appui gauche/droite = -/+10 s.
 */
@Composable
fun VideoPlayerScreen(onBack: () -> Unit, onOpenAccount: () -> Unit) {
    val viewModel: VideoPlayerViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var speedDialogVisible by remember { mutableStateOf(false) }
    var volumePanelVisible by remember { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }

    val hasError = state.error != null
    val barsVisible = controlsVisible || hasError

    val currentOnBack by rememberUpdatedState(onBack)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    // Pendant la transition d'entrée l'écran n'est pas encore RESUMED et la navigation ignore le
    // retour : on ne met pas en pause pour rien, et le retour système reste à la navigation.
    val exit: () -> Unit = {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.pause()
            currentOnBack()
        }
    }
    BackHandler(enabled = lifecycleState.isAtLeast(Lifecycle.State.RESUMED), onBack = exit)

    // L'appli passe en arrière-plan : pause (la position est sauvegardée par le ViewModel) et
    // commandes visibles au retour.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.pause()
        controlsVisible = true
    }

    // Retour de l'écran Compte après « Se connecter » : nouvel essai si la connexion était demandée.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.retryIfSignInPending()
    }

    // Pause non demandée depuis les commandes (perte du focus audio, fin de la vidéo, arrière-plan) :
    // on réaffiche les commandes plutôt que de laisser une image figée sans bouton.
    LaunchedEffect(state.loading, state.playWhenReady, state.ended) {
        if (!state.loading && (!state.playWhenReady || state.ended)) controlsVisible = true
    }

    // Commandes masquées ou erreur : aucun glissement de la barre de progression ne peut être en cours.
    LaunchedEffect(hasError, barsVisible) {
        if (hasError || !barsVisible) scrubbing = false
    }

    // Masquage automatique des commandes pendant la lecture.
    LaunchedEffect(controlsVisible, state.isPlaying, scrubbing, menuExpanded, speedDialogVisible, hasError, interactionTick) {
        if (controlsVisible && state.isPlaying && !scrubbing && !menuExpanded && !speedDialogVisible && !hasError) {
            delay(AUTO_HIDE_DELAY_MS)
            controlsVisible = false
            volumePanelVisible = false
        }
    }

    // Mode immersif : barres système masquées avec les commandes. Leur apparence (icônes claires)
    // et leur restauration en quittant sont gérées par la navigation (SystemBarsAppearance).
    ImmersiveSystemBars(visible = barsVisible)

    // Ecran allumé uniquement pendant la lecture.
    DisposableEffect(view, state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    // Orientation forcée par le bouton « Pivoter » : rendue au système en quittant.
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Disparition du retour visuel de double appui.
    LaunchedEffect(seekFeedback?.key) {
        if (seekFeedback != null) {
            delay(SEEK_FEEDBACK_DURATION_MS)
            seekFeedback = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ViewerColors.background),
    ) {
        VideoSurface(player = viewModel.player)

        // Couche de gestes : appui = afficher/masquer, double appui sur les tiers = -/+10 s.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            if (!controlsVisible) volumePanelVisible = false
                        },
                        onDoubleTap = { offset ->
                            val side = VideoPlayerLogic.doubleTapZone(offset.x, size.width.toFloat())
                            if (side == 0) {
                                controlsVisible = !controlsVisible
                            } else {
                                viewModel.seekBy(side * VideoPlayerLogic.SEEK_INCREMENT_MS)
                                val previous = seekFeedback
                                val step = (VideoPlayerLogic.SEEK_INCREMENT_MS / 1000).toInt()
                                seekFeedback = SeekFeedback(
                                    side = side,
                                    seconds = if (previous != null && previous.side == side) previous.seconds + step else step,
                                    key = (previous?.key ?: 0) + 1,
                                )
                                interactionTick++
                            }
                        },
                    )
                },
        )

        SeekFeedbackIndicator(feedback = seekFeedback)

        val showSpinner = !hasError && (state.loading || state.isBuffering)
        if (showSpinner && !controlsVisible) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = ViewerColors.content,
                strokeWidth = 3.dp,
            )
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            VideoControlsOverlay(
                player = viewModel.player,
                state = state,
                showCenterAndBottom = !hasError,
                buffering = showSpinner,
                menuExpanded = menuExpanded,
                onMenuExpandedChange = {
                    menuExpanded = it
                    interactionTick++
                },
                volumePanelVisible = volumePanelVisible,
                onVolumePanelVisibleChange = {
                    volumePanelVisible = it
                    interactionTick++
                },
                onInteraction = { interactionTick++ },
                onBack = exit,
                onSeekBy = { delta ->
                    viewModel.seekBy(delta)
                    interactionTick++
                },
                onScrubbingChange = { scrubbing = it },
                onSeekTo = viewModel::seekTo,
                onToggleMute = {
                    viewModel.toggleMute()
                    interactionTick++
                },
                onShowSpeedDialog = {
                    menuExpanded = false
                    speedDialogVisible = true
                },
                onOpenWith = {
                    menuExpanded = false
                    state.uri?.let { uri ->
                        viewModel.pause()
                        openWithExternalApp(context, uri, state.mimeType)
                    }
                },
                onRotate = {
                    interactionTick++
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
            )
        }

        state.error?.let { error ->
            PlaybackErrorPanel(
                error = error,
                canOpenExternally = state.uri != null && VideoPlayerLogic.canOpenExternally(error),
                onOpenWith = {
                    state.uri?.let { uri -> openWithExternalApp(context, uri, state.mimeType) }
                },
                onRetry = viewModel::retry,
                onSignIn = {
                    viewModel.pause()
                    onOpenAccount()
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    if (speedDialogVisible) {
        PlaybackSpeedDialog(
            currentSpeed = state.speed,
            onSpeedSelected = { speed ->
                viewModel.setSpeed(speed)
                speedDialogVisible = false
                interactionTick++
            },
            onDismiss = { speedDialogVisible = false },
        )
    }
}
