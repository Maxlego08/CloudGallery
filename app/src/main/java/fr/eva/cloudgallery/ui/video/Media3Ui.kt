package fr.eva.cloudgallery.ui.video

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.theme.ViewerColors

/**
 * Surface vidéo letterboxée : [ContentFrame] (media3-ui-compose) combine PlayerSurface,
 * le redimensionnement selon le ratio (ContentScale.Fit) et un obturateur noir tant que la
 * première image n'est pas rendue.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(ViewerColors.background), contentAlignment = Alignment.Center) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Fit,
            keepContentOnReset = true,
        )
    }
}

/**
 * Grand bouton lecture/pause central. L'état vient de `rememberPlayPauseButtonState`, qui gère
 * aussi la relecture depuis le début quand la vidéo est terminée.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun BigPlayPauseButton(
    player: Player,
    ended: Boolean,
    buffering: Boolean,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberPlayPauseButtonState(player)
    val description = stringResource(
        when {
            ended -> R.string.video_replay
            state.showPlay -> R.string.video_play
            else -> R.string.video_pause
        },
    )
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = state.isEnabled, role = Role.Button, onClickLabel = description) {
                onInteraction()
                state.onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = ViewerColors.content,
                strokeWidth = 3.dp,
            )
        } else {
            Icon(
                imageVector = when {
                    ended -> Icons.Rounded.Replay
                    state.showPlay -> Icons.Rounded.PlayArrow
                    else -> Icons.Rounded.Pause
                },
                contentDescription = description,
                tint = ViewerColors.content,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}
