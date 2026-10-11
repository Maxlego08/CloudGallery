package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.components.ImmersiveSystemBars
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiIconButton
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.theme.ViewerColors
import fr.eva.cloudgallery.ui.util.openWithExternalApp
import fr.eva.cloudgallery.ui.util.shareMedia

private val ScrimColor = Color(0x99000000)

/**
 * Image reçue d'une autre appli et absente de l'index (URI FileProvider, sélecteur de photos,
 * accès partiel…) : affichage zoomable en lecture seule depuis l'URI accordée par l'appelant.
 * Aucune action d'écriture (favori, suppression, rotation) : le fichier n'appartient pas à la
 * bibliothèque indexée.
 */
@Composable
fun ExternalImageScreen(
    uri: Uri,
    title: String?,
    mimeType: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    var loadError by remember(uri) { mutableStateOf(false) }

    // Métadonnées minimales : dimensions et orientation inconnues, déterminées au décodage.
    val media = remember(uri, title, mimeType) {
        MediaDetails(
            id = -1L,
            type = MediaType.IMAGE,
            displayName = title.orEmpty(),
            mimeType = mimeType,
            dateTaken = 0L,
            dateAdded = 0L,
            dateModified = 0L,
            width = 0,
            height = 0,
            durationMs = 0L,
            sizeBytes = 0L,
            orientation = 0,
            uri = uri,
            isFavorite = false,
            sources = emptyList(),
        )
    }
    val callbacks = remember {
        PageGestureCallbacks(
            onTap = { chromeVisible = !chromeVisible },
            onDismiss = { currentOnBack() },
            onSwipeUp = {},
            onDragProgress = { dismissProgress = it },
            onPagerDrag = {},
            onPagerDragEnd = {},
        )
    }
    val chromeShown = chromeVisible && dismissProgress < 0.02f
    ImmersiveSystemBars(visible = chromeShown)

    Box(
        Modifier
            .fillMaxSize()
            .background(ViewerColors.background),
    ) {
        ZoomableImage(
            media = media,
            uri = uri,
            isCurrentPage = true,
            callbacks = callbacks,
            onZoomChanged = {},
            onLoadError = { loadError = it },
        )
        if (loadError) {
            Text(
                text = stringResource(R.string.viewer_load_error),
                style = OneUiTheme.typography.listSubtitle,
                color = ViewerColors.content,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            )
        }

        AnimatedVisibility(
            visible = chromeShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
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
                    Text(
                        text = title.orEmpty(),
                        style = OneUiTheme.typography.appBarTitle,
                        color = ViewerColors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                            .semantics { heading() },
                    )
                    OneUiIconButton(
                        icon = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.common_share),
                        onClick = {
                            runCatching { shareMedia(context, listOf(uri), listOf(mimeType)) }.onFailure {
                                Log.w(TAG, "Partage impossible : $uri", it)
                                Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show()
                            }
                        },
                        tint = ViewerColors.content,
                    )
                    OneUiIconButton(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.common_open_with),
                        onClick = {
                            runCatching { openWithExternalApp(context, uri, mimeType) }.onFailure {
                                Log.w(TAG, "Ouverture externe impossible : $uri", it)
                                Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show()
                            }
                        },
                        tint = ViewerColors.content,
                    )
                }
            }
        }
    }
}

private const val TAG = "ExternalImageScreen"
