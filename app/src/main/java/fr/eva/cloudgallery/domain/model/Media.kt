package fr.eva.cloudgallery.domain.model

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

/** Physical origin of a logical media item. The same media item can have several. */
enum class SourceKind { LOCAL, ONEDRIVE, OFFLINE_COPY }

/**
 * Lightweight projection used by the grids (one row per cell).
 * Contains only what is needed to draw a thumbnail and its badges.
 */
data class MediaSummary(
    val id: Long,
    val type: MediaType,
    val mimeType: String?,
    val dateTaken: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    /** Local `content://` URI if available, null otherwise (remote-only media). */
    val uri: Uri?,
    val dateModified: Long,
    val isFavorite: Boolean,
    val hasLocal: Boolean,
    val hasCloud: Boolean,
    val hasOffline: Boolean,
    /** OneDrive thumbnail (visible OneDrive item), null without a OneDrive source. */
    val cloudThumb: CloudThumbRef? = null,
)

/** One physical source of a media item (MediaStore file, OneDrive item, offline copy). */
data class MediaSourceInfo(
    val id: Long,
    val kind: SourceKind,
    val externalId: String,
    val uri: Uri?,
    val bucketKey: String?,
    val bucketName: String?,
    val relativePath: String?,
    val sizeBytes: Long,
    val dateModified: Long,
    /** Content version: cTag of OneDrive or of the offline copy (null for a local or unknown source). */
    val cTag: String? = null,
)

/** Full details of a media item, for the viewer and the info panel. */
data class MediaDetails(
    val id: Long,
    val type: MediaType,
    val displayName: String,
    val mimeType: String?,
    val dateTaken: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val orientation: Int,
    val uri: Uri?,
    val isFavorite: Boolean,
    val sources: List<MediaSourceInfo>,
    /** OneDrive thumbnail (visible OneDrive item), null without a OneDrive source. */
    val cloudThumb: CloudThumbRef? = null,
    /**
     * File of the offline copy (visible saved copy), null otherwise. Read-only: never
     * modified or deleted by an action on a local file.
     */
    val offlineFile: OfflineFile? = null,
) {
    val hasLocal: Boolean get() = sources.any { it.kind == SourceKind.LOCAL }
    val hasCloud: Boolean get() = sources.any { it.kind == SourceKind.ONEDRIVE }
    val hasOffline: Boolean get() = sources.any { it.kind == SourceKind.OFFLINE_COPY }
    val localSource: MediaSourceInfo? get() = sources.firstOrNull { it.kind == SourceKind.LOCAL }
    val cloudSource: MediaSourceInfo? get() = sources.firstOrNull { it.kind == SourceKind.ONEDRIVE }
    val offlineSource: MediaSourceInfo? get() = sources.firstOrNull { it.kind == SourceKind.OFFLINE_COPY }

    /** Usable offline copy (visible source and saved file). */
    val offlineCopy: OfflineFile? get() = offlineFile?.takeIf { hasOffline }
}
