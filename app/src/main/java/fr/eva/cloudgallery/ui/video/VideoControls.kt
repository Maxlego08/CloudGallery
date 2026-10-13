package fr.eva.cloudgallery.ui.video

import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiIconButton
import fr.eva.cloudgallery.ui.components.OneUiMenuItem
import fr.eva.cloudgallery.ui.components.OneUiOverflowMenu
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.theme.ViewerColors
import fr.eva.cloudgallery.ui.util.Formatters
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ScrimColor = Color.Black.copy(alpha = 0.6f)

/** Commandes superposées : barre supérieure, commandes centrales, barre de progression et boutons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoControlsOverlay(
    player: Player,
    state: PlayerUiState,
    showCenterAndBottom: Boolean,
    buffering: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    volumePanelVisible: Boolean,
    onVolumePanelVisibleChange: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    onBack: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onShowSpeedDialog: () -> Unit,
    onOpenWith: () -> Unit,
    onRotate: () -> Unit,
) {
    // Insets « ignorant la visibilité » : les barres ne sautent pas quand le système les masque.
    val topInsets = WindowInsets.statusBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val bottomInsets = WindowInsets.navigationBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val currentOnScrubbingChange by rememberUpdatedState(onScrubbingChange)

    // Overlay retiré en plein glissement (commandes masquées) : la barre ne signale jamais la fin
    // du geste, on libère donc l'état de glissement du parent.
    DisposableEffect(Unit) {
        onDispose { currentOnScrubbingChange(false) }
    }
    // Barre de progression retirée en plein glissement (erreur de lecture) : même chose, et l'aperçu
    // du temps ne doit pas rester figé au retour des commandes.
    LaunchedEffect(showCenterAndBottom) {
        if (!showCenterAndBottom && scrubPositionMs != null) {
            scrubPositionMs = null
            currentOnScrubbingChange(false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Barre supérieure : retour, nom du fichier, menu.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(ScrimColor, Color.Transparent)))
                .windowInsetsPadding(topInsets)
                .padding(horizontal = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OneUiTheme.dimens.appBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OneUiBackButton(onClick = onBack, tint = ViewerColors.content)
                Text(
                    text = state.title,
                    style = OneUiTheme.typography.appBarTitle,
                    color = ViewerColors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                Box {
                    OneUiIconButton(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.common_more),
                        onClick = { onMenuExpandedChange(true) },
                        tint = ViewerColors.content,
                    )
                    OneUiOverflowMenu(
                        expanded = menuExpanded,
                        onDismiss = { onMenuExpandedChange(false) },
                        items = listOf(
                            OneUiMenuItem(
                                label = stringResource(R.string.video_speed),
                                enabled = state.error == null && !state.loading,
                                onClick = onShowSpeedDialog,
                            ),
                            OneUiMenuItem(
                                label = stringResource(R.string.common_open_with),
                                enabled = state.uri != null,
                                onClick = onOpenWith,
                            ),
                        ),
                    )
                }
            }
        }

        if (showCenterAndBottom) {
            // Commandes centrales, remplacées par l'aperçu du temps pendant le glissement.
            val scrub = scrubPositionMs
            if (scrub != null) {
                Text(
                    text = "${Formatters.duration(scrub)} / ${Formatters.duration(state.durationMs)}",
                    style = OneUiTheme.typography.largeTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = ViewerColors.content,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControlButton(
                        icon = Icons.Rounded.Replay10,
                        contentDescription = stringResource(R.string.video_rewind_10),
                        enabled = !state.loading,
                        onClick = { onSeekBy(-VideoPlayerLogic.SEEK_INCREMENT_MS) },
                    )
                    BigPlayPauseButton(
                        player = player,
                        ended = state.ended,
                        buffering = buffering,
                        onInteraction = onInteraction,
                    )
                    RoundControlButton(
                        icon = Icons.Rounded.Forward10,
                        contentDescription = stringResource(R.string.video_forward_10),
                        enabled = !state.loading,
                        onClick = { onSeekBy(VideoPlayerLogic.SEEK_INCREMENT_MS) },
                    )
                }
            }

            // Bas : volume (optionnel), progression, boutons.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, ScrimColor)))
                    .windowInsetsPadding(bottomInsets)
                    .padding(horizontal = 12.dp)
                    .padding(top = 24.dp, bottom = 4.dp),
            ) {
                AnimatedVisibility(visible = volumePanelVisible, enter = fadeIn(), exit = fadeOut()) {
                    VolumePanel(muted = state.muted, onToggleMute = onToggleMute, onInteraction = onInteraction)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeText(scrubPositionMs ?: state.positionMs)
                    SeekBar(
                        positionMs = scrubPositionMs ?: state.positionMs,
                        bufferedMs = state.bufferedMs,
                        durationMs = state.durationMs,
                        enabled = state.durationMs > 0 && !state.loading,
                        onScrub = { position ->
                            if (scrubPositionMs == null) onScrubbingChange(true)
                            scrubPositionMs = position
                        },
                        onScrubFinished = {
                            scrubPositionMs?.let(onSeekTo)
                            scrubPositionMs = null
                            onScrubbingChange(false)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    TimeText(state.durationMs)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OneUiIconButton(
                        icon = if (state.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = stringResource(R.string.video_volume),
                        onClick = { onVolumePanelVisibleChange(!volumePanelVisible) },
                        tint = ViewerColors.content,
                    )
                    SpeedIndicator(speed = state.speed, onClick = onShowSpeedDialog)
                    Spacer(Modifier.weight(1f))
                    OneUiIconButton(
                        icon = Icons.Rounded.ScreenRotation,
                        contentDescription = stringResource(R.string.video_rotate),
                        onClick = onRotate,
                        tint = ViewerColors.content,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) ViewerColors.content else ViewerColors.contentSecondary,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun TimeText(ms: Long) {
    Text(
        text = Formatters.duration(ms),
        style = OneUiTheme.typography.listSubtitle,
        color = ViewerColors.content,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.widthIn(min = 44.dp),
    )
}

/** Barre de progression : glissement fluide (aperçu local), seek au relâchement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val description = stringResource(
        R.string.video_position_desc,
        Formatters.duration(positionMs),
        Formatters.duration(durationMs),
    )
    val seekbarLabel = stringResource(R.string.video_seekbar)
    Slider(
        value = positionMs.toFloat().coerceIn(0f, max),
        onValueChange = { onScrub(it.toLong()) },
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = seekbarLabel
                stateDescription = description
            },
        enabled = enabled,
        onValueChangeFinished = onScrubFinished,
        colors = SliderDefaults.colors(
            thumbColor = ViewerColors.content,
            activeTrackColor = ViewerColors.content,
            inactiveTrackColor = ViewerColors.content.copy(alpha = 0.3f),
        ),
        thumb = {
            Box(
                Modifier
                    .size(16.dp)
                    .background(if (enabled) ViewerColors.content else ViewerColors.contentSecondary, CircleShape),
            )
        },
        track = { sliderState ->
            val range = sliderState.valueRange
            val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
            val played = ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
            val buffered = (bufferedMs / max).coerceIn(0f, 1f)
            SeekTrack(played = played, buffered = buffered)
        },
        valueRange = 0f..max,
    )
}

@Composable
private fun SeekTrack(played: Float, buffered: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(ViewerColors.content.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(buffered)
                .fillMaxHeight()
                .background(ViewerColors.content.copy(alpha = 0.4f)),
        )
        Box(
            Modifier
                .fillMaxWidth(played)
                .fillMaxHeight()
                .background(ViewerColors.content),
        )
    }
}

/** Indicateur de vitesse (« 1x », « 1.5x ») qui ouvre le choix de vitesse. */
@Composable
private fun SpeedIndicator(speed: Float, onClick: () -> Unit) {
    val label = VideoPlayerLogic.speedLabel(speed)
    val description = stringResource(R.string.video_speed)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$description : $label" }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = OneUiTheme.typography.listSubtitle.copy(fontWeight = FontWeight.Bold),
            color = if (speed == 1f) ViewerColors.content else OneUiTheme.colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Volume multimédia du système (STREAM_MUSIC) + coupure du son du lecteur. */
@Composable
private fun VolumePanel(muted: Boolean, onToggleMute: () -> Unit, onInteraction: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember(audioManager) { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }
    var volume by remember { mutableIntStateOf(audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) }
    var dragging by remember { mutableStateOf(false) }

    // Suit les touches de volume physiques pendant que le panneau est affiché.
    LaunchedEffect(audioManager) {
        while (audioManager != null) {
            if (!dragging) volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            delay(500)
        }
    }

    val volumeLabel = stringResource(R.string.video_volume)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneUiIconButton(
            icon = if (muted || volume == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(if (muted) R.string.video_unmute else R.string.video_mute),
            onClick = onToggleMute,
            tint = ViewerColors.content,
        )
        var sliderValue by remember { mutableFloatStateOf(volume.toFloat()) }
        Slider(
            value = if (dragging) sliderValue else volume.toFloat(),
            onValueChange = { value ->
                dragging = true
                sliderValue = value
                val target = value.roundToInt()
                if (target != volume && audioManager != null) {
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                    volume = target
                }
                onInteraction()
            },
            onValueChangeFinished = { dragging = false },
            enabled = maxVolume > 0,
            valueRange = 0f..maxVolume.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = ViewerColors.content,
                activeTrackColor = ViewerColors.content,
                inactiveTrackColor = ViewerColors.content.copy(alpha = 0.3f),
            ),
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .semantics { contentDescription = volumeLabel },
        )
    }
}

/** Demi-cercle translucide façon Samsung affiché lors d'un double appui (-/+ N s). */
@Composable
internal fun BoxScope.SeekFeedbackIndicator(feedback: SeekFeedback?) {
    // Garde le dernier contenu pour l'animation de disparition (pas un état observable).
    val last = remember { LastFeedbackHolder() }
    if (feedback != null) last.value = feedback
    val shown = last.value ?: return
    val forward = shown.side > 0
    AnimatedVisibility(
        visible = feedback != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.38f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(
                    if (forward) {
                        RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                    } else {
                        RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                    },
                )
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                    contentDescription = null,
                    tint = ViewerColors.content,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = stringResource(
                        if (forward) R.string.video_seek_forward_seconds else R.string.video_seek_back_seconds,
                        shown.seconds,
                    ),
                    style = OneUiTheme.typography.listTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = ViewerColors.content,
                )
            }
        }
    }
}

/**
 * Message d'erreur avec, selon le cas, les actions « Ouvrir avec une autre application »,
 * « Se connecter » (compte OneDrive) et « Réessayer ».
 */
@Composable
internal fun PlaybackErrorPanel(
    error: PlaybackErrorKind,
    canOpenExternally: Boolean,
    onOpenWith: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = stringResource(
        when (error) {
            PlaybackErrorKind.UNSUPPORTED_FORMAT -> R.string.video_error_unsupported
            PlaybackErrorKind.CORRUPTED_FILE -> R.string.video_error_corrupted
            PlaybackErrorKind.FILE_NOT_ACCESSIBLE -> R.string.video_error_not_accessible
            PlaybackErrorKind.IO -> R.string.video_error_io
            PlaybackErrorKind.NETWORK -> R.string.video_error_network
            PlaybackErrorKind.SERVER_OR_LINK -> R.string.video_error_server_link
            PlaybackErrorKind.AUTH_REQUIRED -> R.string.video_error_auth_required
            PlaybackErrorKind.CLOUD_THROTTLED -> R.string.video_error_cloud_throttled
            PlaybackErrorKind.CLOUD_NOT_FOUND -> R.string.video_error_cloud_not_found
            PlaybackErrorKind.MEDIA_NOT_FOUND -> R.string.video_error_not_found
            PlaybackErrorKind.CLOUD_ONLY -> R.string.video_error_cloud_only
            PlaybackErrorKind.OTHER -> R.string.video_error_other
        },
    )
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp)
            .widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = when {
                error == PlaybackErrorKind.NETWORK -> Icons.Rounded.WifiOff
                VideoPlayerLogic.isCloudError(error) -> Icons.Rounded.CloudOff
                else -> Icons.Rounded.ErrorOutline
            },
            contentDescription = null,
            tint = ViewerColors.contentSecondary,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = message,
            style = OneUiTheme.typography.body,
            color = ViewerColors.content,
            textAlign = TextAlign.Center,
        )
        if (canOpenExternally) {
            PillButton(text = stringResource(R.string.video_open_with_other_app), onClick = onOpenWith)
        }
        if (VideoPlayerLogic.needsSignIn(error)) {
            PillButton(text = stringResource(R.string.video_action_sign_in), onClick = onSignIn)
        }
        if (VideoPlayerLogic.canRetry(error)) {
            PillButton(text = stringResource(R.string.common_retry), onClick = onRetry)
        }
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = OneUiTheme.typography.button, color = ViewerColors.content, textAlign = TextAlign.Center)
    }
}

/** Choix de la vitesse de lecture dans une boîte de dialogue One UI. */
@Composable
internal fun PlaybackSpeedDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    OneUiDialog(
        title = stringResource(R.string.video_speed),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                VideoPlayerLogic.PLAYBACK_SPEEDS.forEach { speed ->
                    val selected = speed == currentSpeed
                    val label = VideoPlayerLogic.speedLabel(speed).let {
                        if (speed == 1f) stringResource(R.string.video_speed_normal, it) else it
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = selected, role = Role.RadioButton) { onSpeedSelected(speed) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = OneUiTheme.typography.listTitle.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            ),
                            color = if (selected) OneUiTheme.colors.accent else OneUiTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = OneUiTheme.colors.accent,
                            )
                        }
                    }
                }
            }
        },
    )
}

private class LastFeedbackHolder {
    var value: SeekFeedback? = null
}
