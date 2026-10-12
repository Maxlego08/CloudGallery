package fr.eva.cloudgallery.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import fr.eva.cloudgallery.ui.components.fullscreenSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters
import java.util.Locale

/**
 * Panneau « Détails » façon One UI : date, fichier, caractéristiques, appareil photo (EXIF), lieu, provenance.
 * L'EXIF ([exif]) est lu hors du thread principal par le ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsSheet(
    media: MediaDetails,
    exif: ExifState?,
    onDismiss: () -> Unit,
    offlineStatus: String? = null,
) {
    val colors = OneUiTheme.colors
    val radius = OneUiTheme.dimens.sheetRadius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Ouvert au-dessus de la visionneuse (fond noir) : icônes de barre d'état claires.
        properties = fullscreenSheetProperties(),
        shape = RoundedCornerShape(topStart = radius, topEnd = radius),
        containerColor = colors.sheet,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textTertiary),
            )
        },
    ) {
        DetailsContent(media = media, exif = exif, offlineStatus = offlineStatus)
    }
}

@Composable
private fun DetailsContent(media: MediaDetails, exif: ExifState?, offlineStatus: String?) {
    val context = LocalContext.current
    val typography = OneUiTheme.typography
    val colors = OneUiTheme.colors
    val info = exif?.info?.takeIf { exif.mediaId == media.id }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OneUiTheme.dimens.screenPadding)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.common_details),
            style = typography.dialogTitle,
            color = colors.textPrimary,
            modifier = Modifier
                .padding(vertical = 12.dp)
                .semantics { heading() },
        )

        // Date
        if (media.dateTaken > 0) {
            DetailRow(icon = Icons.Outlined.CalendarToday, primary = Formatters.fullDateTime(media.dateTaken))
        }

        // Fichier
        // Chemin du fichier local, sinon de l'élément OneDrive (une copie hors ligne n'en a pas).
        val path = media.localSource?.relativePath
            ?: media.cloudSource?.relativePath
            ?: media.sources.firstNotNullOfOrNull { it.relativePath }
        DetailRow(
            icon = Icons.Outlined.InsertDriveFile,
            primary = media.displayName,
            secondary = path?.let { stringResource(R.string.viewer_details_path, it) },
        )

        // Caractéristiques
        val size = media.sizeBytes.takeIf { it > 0 }
            ?: media.localSource?.sizeBytes?.takeIf { it > 0 }
            ?: media.sources.maxOfOrNull { it.sizeBytes }?.takeIf { it > 0 }
        val specs = listOfNotNull(
            Formatters.resolution(media.width, media.height).takeIf { it.isNotEmpty() },
            size?.let { Formatters.fileSize(it) },
            media.durationMs.takeIf { media.type == MediaType.VIDEO && it > 0 }?.let { Formatters.duration(it) },
        ).joinToString("   ")
        DetailRow(
            icon = Icons.Outlined.Image,
            primary = specs.ifEmpty { stringResource(if (media.type == MediaType.VIDEO) R.string.common_video else R.string.common_image) },
            secondary = media.mimeType,
        )

        // Appareil photo (EXIF)
        if (exif != null && exif.mediaId == media.id && exif.loading) {
            DetailRow(icon = Icons.Outlined.PhotoCamera, primary = stringResource(R.string.common_loading))
        } else if (info != null && info.hasCameraInfo) {
            val iso = info.iso?.let { stringResource(R.string.viewer_details_iso, it) }
            val settings = listOfNotNull(
                info.fNumber?.let { ExifReader.formatAperture(it) },
                info.exposureSeconds?.let { ExifReader.formatExposure(it) },
                info.focalLengthMm?.let { ExifReader.formatFocalLength(it) },
                iso,
            ).joinToString("   ")
            val flash = when (info.flashFired) {
                true -> stringResource(R.string.viewer_details_flash_on)
                false -> stringResource(R.string.viewer_details_flash_off)
                null -> null
            }
            DetailRow(
                icon = Icons.Outlined.PhotoCamera,
                primary = info.camera ?: stringResource(R.string.common_unknown),
                secondary = listOfNotNull(settings.ifEmpty { null }, flash).joinToString("\n").ifEmpty { null },
            )
        }

        // Lieu
        if (info != null && info.latitude != null && info.longitude != null) {
            val lat = info.latitude
            val lon = info.longitude
            DetailRow(
                icon = Icons.Outlined.Place,
                primary = ExifReader.formatCoordinates(lat, lon),
                secondary = stringResource(R.string.viewer_details_open_map),
                onClick = {
                    val geo = String.format(Locale.ROOT, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lon, lat, lon)
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo)))
                    } catch (_: ActivityNotFoundException) {
                        // Aucune application de cartes installée : rien à faire.
                    }
                },
            )
        }

        // Provenance
        val provenance = buildList {
            if (media.hasLocal) add(stringResource(R.string.common_on_device))
            if (media.hasCloud) add(stringResource(R.string.common_onedrive))
            if (media.sources.any { it.kind == SourceKind.OFFLINE_COPY }) add(stringResource(R.string.common_offline))
        }
        if (provenance.isNotEmpty()) {
            DetailRow(
                icon = Icons.Outlined.Cloud,
                primary = provenance.joinToString(" · "),
                secondary = stringResource(R.string.viewer_details_source),
            )
        }

        // Copie hors ligne : état du téléchargement, copie présente (taille) ou dernière copie.
        if (offlineStatus != null) {
            DetailRow(icon = Icons.Outlined.OfflinePin, primary = offlineStatus)
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    primary: String,
    secondary: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = OneUiTheme.colors
    val typography = OneUiTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(text = primary, style = typography.listTitle, color = colors.textPrimary)
            if (!secondary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(text = secondary, style = typography.listSubtitle, color = colors.textSecondary)
            }
        }
    }
}
