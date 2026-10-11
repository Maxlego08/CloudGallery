package fr.eva.cloudgallery.data.mapper

import androidx.core.net.toUri
import fr.eva.cloudgallery.data.db.entity.AlbumRow
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaRow
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSourceInfo
import fr.eva.cloudgallery.domain.model.MediaSummary

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
)

fun MediaEntity.toDetails(isFavorite: Boolean, sources: List<MediaSourceEntity>): MediaDetails = MediaDetails(
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
)
