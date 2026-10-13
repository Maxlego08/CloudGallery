package fr.eva.cloudgallery.data.mapper

import androidx.core.net.toUri
import fr.eva.cloudgallery.data.db.entity.AlbumRow
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaRow
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumVisibility
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSourceInfo
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.OfflineFile
import fr.eva.cloudgallery.domain.model.SourceKind

fun MediaRow.toSummary(): MediaSummary = MediaSummary(
    id = id,
    type = mediaType,
    mimeType = mimeType,
    dateTaken = dateTaken,
    durationMs = durationMs,
    width = width,
    height = height,
    orientation = orientation,
    uri = primaryUri?.toUri(),
    dateModified = dateModified,
    isFavorite = isFavorite,
    hasLocal = hasLocal,
    hasCloud = hasCloud,
    hasOffline = hasOffline,
    cloudThumb = cloudThumbRef(cloudItemId, cloudCTag, dateModified),
)

fun MediaSourceEntity.toInfo(): MediaSourceInfo = MediaSourceInfo(
    id = id,
    kind = kind,
    externalId = externalId,
    uri = uri?.toUri(),
    bucketKey = bucketKey,
    bucketName = bucketName,
    relativePath = relativePath,
    sizeBytes = sizeBytes,
    dateModified = dateModified,
    // Pour une source locale, la colonne contient GENERATION_MODIFIED : sans intérêt hors OneDrive.
    cTag = cTag.takeIf { kind != SourceKind.LOCAL },
)

fun MediaEntity.toDetails(
    isFavorite: Boolean,
    sources: List<MediaSourceEntity>,
    offlineFile: OfflineFile? = null,
): MediaDetails = MediaDetails(
    id = id,
    type = mediaType,
    displayName = displayName,
    mimeType = mimeType,
    dateTaken = dateTaken,
    dateAdded = dateAdded,
    dateModified = dateModified,
    width = width,
    height = height,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    orientation = orientation,
    uri = primaryUri?.toUri(),
    isFavorite = isFavorite,
    // Une source masquée (corbeille, volume démonté…) n'est pas exploitable par l'UI.
    sources = sources.filter { it.missingSince == null }.map { it.toInfo() },
    cloudThumb = cloudThumbRef(cloudItemId, cloudCTag, dateModified),
    offlineFile = offlineFile,
)

fun AlbumRow.toSummary(): AlbumSummary = AlbumSummary(
    id = id,
    name = customName?.takeIf { it.isNotBlank() } ?: name,
    type = type,
    sourceKey = sourceKey,
    relativePath = relativePath,
    mediaCount = mediaCount,
    coverMediaId = coverMediaId,
    coverUri = coverUri?.toUri(),
    coverType = coverType,
    coverDateModified = coverDateModified ?: 0L,
    latestDate = latestDate ?: 0L,
    isPinned = isPinned,
    coverCloud = coverCloudItemId?.let { cloudThumbRef(it, coverCloudCTag, coverDateModified ?: 0L) },
    visibility = when {
        hideMedia -> AlbumVisibility.HIDDEN_EVERYWHERE
        isHidden -> AlbumVisibility.HIDDEN_IN_ALBUMS
        else -> AlbumVisibility.SHOWN
    },
)

/**
 * Miniature OneDrive : version = cTag (contenu) ; à défaut (cTag absent), la date de
 * modification, pour qu'un changement de contenu invalide quand même le cache.
 */
internal fun cloudThumbRef(itemId: String?, cTag: String?, dateModified: Long): CloudThumbRef? =
    itemId?.let { CloudThumbRef(it, cTag?.takeIf { tag -> tag.isNotBlank() } ?: "m$dateModified") }
