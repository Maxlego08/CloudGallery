package fr.eva.cloudgallery.domain.model

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

/** Provenance physique d'un média logique. Un même média peut en avoir plusieurs. */
enum class SourceKind { LOCAL, ONEDRIVE, OFFLINE_COPY }

/**
 * Projection légère utilisée par les grilles (une ligne par cellule).
 * Ne contient que ce qui est nécessaire pour dessiner une miniature et ses badges.
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
    /** URI `content://` locale si disponible, sinon null (média uniquement distant). */
    val uri: Uri?,
    val dateModified: Long,
    val isFavorite: Boolean,
    val hasLocal: Boolean,
    val hasCloud: Boolean,
    val hasOffline: Boolean,
)

/** Une source physique d'un média (fichier MediaStore, élément OneDrive, copie hors ligne). */
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
)

/** Détails complets d'un média, pour la visionneuse et le panneau d'informations. */
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
) {
    val hasLocal: Boolean get() = sources.any { it.kind == SourceKind.LOCAL }
    val hasCloud: Boolean get() = sources.any { it.kind == SourceKind.ONEDRIVE }
    val localSource: MediaSourceInfo? get() = sources.firstOrNull { it.kind == SourceKind.LOCAL }
}
