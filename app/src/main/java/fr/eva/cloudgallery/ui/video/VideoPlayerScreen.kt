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

/** Visual feedback of a double tap (side -1 / +1, accumulated seconds). */
internal data class SeekFeedback(val side: Int, val seconds: Int, val key: Int)

/**
 * Samsung Gallery-style full-screen video player: black background, immersive mode, overlaid
 * controls auto-hidden after 3 s of playback, double tap left/right = -/+10 s.
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
    // During the enter transition the screen is not RESUMED yet and navigation ignores back:
    // don't pause for nothing, and leave system back to navigation.
    val exit: () -> Unit = {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.pause()
            currentOnBack()
        }
    }
    BackHandler(enabled = lifecycleState.isAtLeast(Lifecycle.State.RESUMED), onBack = exit)

    // The app goes to the background: pause (the position is saved by the ViewModel) and show
    // the controls on return.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.pause()
        controlsVisible = true
    }

    // Back from the Account screen after "Sign in": retry if sign-in was requested.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.retryIfSignInPending()
    }

    // Pause not requested from the controls (audio focus loss, end of video, background): show
    // the controls again rather than leaving a frozen frame without any button.
    LaunchedEffect(state.loading, state.playWhenReady, state.ended) {
        if (!state.loading && (!state.playWhenReady || state.ended)) controlsVisible = true
    }

    // Controls hidden or error: no progress bar drag can be in progress.
    LaunchedEffect(hasError, barsVisible) {
        if (hasError || !barsVisible) scrubbing = false
    }

    // Auto-hide the controls during playback.
    LaunchedEffect(controlsVisible, state.isPlaying, scrubbing, menuExpanded, speedDialogVisible, hasError, interactionTick) {
        if (controlsVisible && state.isPlaying && !scrubbing && !menuExpanded && !speedDialogVisible && !hasError) {
            delay(AUTO_HIDE_DELAY_MS)
            controlsVisible = false
            volumePanelVisible = false
        }
    }

    // Immersive mode: system bars hidden along with the controls. Their appearance (light icons)
    // and their restoration on exit are handled by navigation (SystemBarsAppearance).
    ImmersiveSystemBars(visible = barsVisible)

    // Keep the screen on only during playback.
    DisposableEffect(view, state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    // Orientation forced by the "Rotate" button: handed back to the system on exit.
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Hide the double-tap visual feedback.
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

        // Gesture layer: tap = show/hide, double tap on the side thirds = -/+10 s.
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
