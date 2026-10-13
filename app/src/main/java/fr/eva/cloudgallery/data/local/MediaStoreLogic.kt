package fr.eva.cloudgallery.data.local

import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType

/*
 * Pure logic (no Android dependency) of the MediaStore indexing, tested on the JVM.
 */

/** Provider name in the sync_cursor table. */
internal const val MEDIASTORE_PROVIDER = "mediastore"

private const val PREFIX_IMAGE = "image"
private const val PREFIX_VIDEO = "video"

/** External id of a LOCAL source: "image:123" / "video:45" (image and video _IDs are not disjoint). */
internal fun buildExternalId(type: MediaType, mediaStoreId: Long): String = when (type) {
    MediaType.IMAGE -> "$PREFIX_IMAGE:$mediaStoreId"
    MediaType.VIDEO -> "$PREFIX_VIDEO:$mediaStoreId"
}

/** Inverse of [buildExternalId]. Returns null if the format is not recognized. */
internal fun parseExternalId(externalId: String): Pair<MediaType, Long>? {
    val sep = externalId.indexOf(':')
    if (sep <= 0) return null
    val type = when (externalId.substring(0, sep)) {
        PREFIX_IMAGE -> MediaType.IMAGE
        PREFIX_VIDEO -> MediaType.VIDEO
        else -> return null
    }
    val id = externalId.substring(sep + 1).toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return type to id
}

/** Folder key of a local source. */
internal fun localBucketKey(bucketId: Long): String = "local:$bucketId"

/**
 * Capture date in ms: DATE_TAKEN (ms) if set, otherwise DATE_ADDED then DATE_MODIFIED (in
 * seconds in MediaStore), otherwise 0.
 */
internal fun resolveDateTaken(dateTakenMs: Long?, dateAddedSec: Long?, dateModifiedSec: Long?): Long = when {
    dateTakenMs != null && dateTakenMs > 0 -> dateTakenMs
    dateAddedSec != null && dateAddedSec > 0 -> dateAddedSec * 1000
    dateModifiedSec != null && dateModifiedSec > 0 -> dateModifiedSec * 1000
    else -> 0L
}

/**
 * A full re-read is needed if it is requested, if there is no usable cursor yet, or if the
 * MediaStore version has changed (generations are then no longer comparable: MediaStore
 * database recreated, restore…).
 */
internal fun needsFullSync(
    forceFull: Boolean,
    storedCursor: String?,
    storedVersion: String?,
    currentVersion: String?,
): Boolean = forceFull ||
    storedCursor?.toLongOrNull() == null ||
    storedVersion == null ||
    currentVersion == null ||
    storedVersion != currentVersion

/** The MediaStore version has changed (and is not merely "unknown"): _IDs are no longer reliable. */
internal fun mediaStoreVersionChanged(storedVersion: String?, currentVersion: String?): Boolean =
    storedVersion != null && currentVersion != null && storedVersion != currentVersion

/**
 * Stable fingerprint of a file, independent of the MediaStore _ID: used to re-match a source
 * to its media (favorites, albums) after a MediaStore version change.
 */
internal fun localFingerprint(relativePath: String?, displayName: String, sizeBytes: Long): String =
    "${relativePath.orEmpty().trim('/')}|$displayName|$sizeBytes"

/** Delay after which a hidden source whose disappearance is confirmed gets purged. */
internal const val MISSING_GRACE_MS = 30L * 24 * 60 * 60 * 1000

/** Thresholds of the mass-deletion brake (fraction AND absolute count). */
internal const val MASS_REMOVAL_MIN_COUNT = 200
internal const val MASS_REMOVAL_FRACTION = 0.25

/** Observed state of a source that no longer appears in the standard MediaStore query. */
internal enum class MissingVerdict {
    /** Absent from MediaStore (including trash / pending) while its volume is mounted. */
    GONE,

    /** Still present but invisible (trash, pending), on an unmounted volume, or unverifiable. */
    HIDDEN,
}

internal data class MissingCandidate(
    val sourceId: Long,
    val mediaId: Long,
    val verdict: MissingVerdict,
    /** Already hidden since this instant, or null. */
    val missingSince: Long?,
)

internal data class RemovalPlan(
    /** Sources to delete from the database (along with their media that became orphans). */
    val delete: List<MissingCandidate>,
    /** Sources to hide (kept along with favorites and albums). */
    val hide: List<MissingCandidate>,
    /** True if the mass-deletion brake was triggered. */
    val massRemovalBlocked: Boolean,
)

/**
 * Decides the fate of sources missing from the MediaStore query. Nothing is permanently
 * deleted without full access (images AND videos): under partial access, MediaStore only
 * returns the user's selection. A confirmed disappearance is deleted immediately, unless it is
 * massive (brake: hidden, then purged after [MISSING_GRACE_MS]). A source that is already
 * hidden (trash, unmounted volume, partial access, brake) is only purged once confirmed gone
 * and after the grace period.
 */
internal fun planRemovals(
    candidates: List<MissingCandidate>,
    knownCount: Int,
    fullAccess: Boolean,
    now: Long,
): RemovalPlan {
    if (!fullAccess) return RemovalPlan(emptyList(), candidates, massRemovalBlocked = false)
    val gone = candidates.filter { it.verdict == MissingVerdict.GONE }
    val freshGone = gone.count { it.missingSince == null }
    val massive = freshGone > MASS_REMOVAL_MIN_COUNT && freshGone > knownCount * MASS_REMOVAL_FRACTION
    val delete = ArrayList<MissingCandidate>()
    val hide = ArrayList<MissingCandidate>()
    for (c in candidates) {
        when {
            c.verdict == MissingVerdict.HIDDEN -> hide += c
            // Already hidden (brake, partial access…): purged only after the grace period.
            c.missingSince != null -> if (now - c.missingSince >= MISSING_GRACE_MS) delete += c else hide += c
            massive -> hide += c
            else -> delete += c
        }
    }
    return RemovalPlan(delete, hide, massRemovalBlocked = massive)
}

/**
 * Parses the path segments of a MediaStore URI (`content://media/<volume>/images/media/<id>`,
 * `.../video/media/<id>`, `.../file/<id>`) and returns the candidate external ids.
 * For "file" URIs the type is unknown: both candidates are returned.
 */
internal fun externalIdCandidatesFromMediaStorePath(pathSegments: List<String>): List<String> {
    if (pathSegments.size < 3) return emptyList()
    val id = pathSegments.last().toLongOrNull() ?: return emptyList()
    return when (pathSegments[1]) {
        "images" -> listOf(buildExternalId(MediaType.IMAGE, id))
        "video" -> listOf(buildExternalId(MediaType.VIDEO, id))
        "file" -> listOf(buildExternalId(MediaType.IMAGE, id), buildExternalId(MediaType.VIDEO, id))
        else -> emptyList()
    }
}

/**
 * Document id of the MediaDocumentsProvider ("image:123", "video:45"): it happens to have
 * exactly the format of our external ids.
 */
internal fun externalIdFromMediaDocumentId(documentId: String): String? =
    parseExternalId(documentId)?.let { (type, id) -> buildExternalId(type, id) }

/** Normalizes an angle to 0, 90, 180 or 270; null if it is not a multiple of 90. */
internal fun normalizeRightAngle(degrees: Int): Int? {
    if (degrees % 90 != 0) return null
    return ((degrees % 360) + 360) % 360
}

/** Normalized rectangle (0..1). */
internal data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clamped(): NormRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        return NormRect(l, t, right.coerceIn(l, 1f), bottom.coerceIn(t, 1f))
    }
}

/**
 * Converts a crop rectangle expressed in the image **rotated** by [rotationDegrees]
 * (clockwise) into a rectangle in the unrotated source image. This allows cropping before
 * rotating, and thus allocating only a single bitmap the size of the crop.
 */
internal fun cropRectInSource(rotated: NormRect, rotationDegrees: Int): NormRect {
    val r = rotated.clamped()
    return when (normalizeRightAngle(rotationDegrees) ?: 0) {
        // 90° clockwise rotation: x' = 1 - y, y' = x  =>  x = y', y = 1 - x'
        90 -> NormRect(left = r.top, top = 1f - r.right, right = r.bottom, bottom = 1f - r.left)
        180 -> NormRect(left = 1f - r.right, top = 1f - r.bottom, right = 1f - r.left, bottom = 1f - r.top)
        // 270° clockwise rotation: x' = y, y' = 1 - x  =>  x = 1 - y', y = x'
        270 -> NormRect(left = 1f - r.bottom, top = r.left, right = 1f - r.top, bottom = r.right)
        else -> r
    }
}

/** Subsampling factor (>= 1) so that the longest side does not exceed [maxSide]. */
internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    val largest = maxOf(width, height)
    if (largest <= maxSide || maxSide <= 0) return 1
    return (largest + maxSide - 1) / maxSide
}

/** Normalizes a MediaStore relative path: no leading "/", with a trailing "/". */
internal fun normalizeRelativePath(path: String): String {
    val trimmed = path.trim().trim('/')
    return if (trimmed.isEmpty()) "" else "$trimmed/"
}

/** "IMG_001.jpg" -> "IMG_001_crop_<ts>.jpg". */
internal fun croppedCopyName(originalName: String?, timestamp: Long): String {
    val base = originalName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "IMG"
    return "${base}_crop_$timestamp.jpg"
}

/** A playback position is only remembered if it is neither at the very start nor at the very end. */
internal fun shouldKeepPlaybackPosition(positionMs: Long, durationMs: Long, marginMs: Long = 3_000L): Boolean {
    if (positionMs < marginMs) return false
    if (durationMs > 0 && positionMs > durationMs - marginMs) return false
    return true
}

/**
 * Orders two media by their position in the grids (dateTaken DESC, id DESC):
 * returns (the higher one in the list, the lower one).
 */
internal fun orderedByListPosition(a: MediaSummary, b: MediaSummary): Pair<MediaSummary, MediaSummary> {
    val aFirst = a.dateTaken > b.dateTaken || (a.dateTaken == b.dateTaken && a.id >= b.id)
    return if (aFirst) a to b else b to a
}
