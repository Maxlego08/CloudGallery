package fr.eva.cloudgallery.data.cloud.precache

import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_PREVIEW_PX
import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_PREVIEW_TIERS
import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_THUMB_BUCKETS
import fr.eva.cloudgallery.data.cloud.thumb.cloudThumbBucket
import fr.eva.cloudgallery.data.cloud.thumb.previewSideFor
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.PrecacheOutcome
import fr.eva.cloudgallery.domain.model.PrecacheState
import java.security.MessageDigest

/*
 * Pure logic for caching all of OneDrive (tested on the JVM): file names, files to look up for a
 * thumbnail request, downloaded tiers, estimate, space.
 */

/** Number of columns of the Photos grid at the default zoom, in portrait (GridZoomLevels.DEFAULT_LEVEL). */
internal const val PRECACHE_GRID_COLUMNS = 3

/** Side of video previews in the viewer (DEFAULT_CLOUD_PREVIEW_SIDE, no adaptive tier). */
internal const val PRECACHE_VIDEO_PREVIEW_PX = CLOUD_PREVIEW_PX

/** Estimated average size of a grid thumbnail and of a preview (bytes), for the displayed estimate. */
internal const val ESTIMATED_GRID_BYTES = 35_000L
internal const val ESTIMATED_PREVIEW_BYTES = 350_000L

/** Minimum free space kept on the device: 2 GB, or 5 % of the volume if that is larger. */
internal const val MIN_FREE_BYTES = 2_000_000_000L
private const val MIN_FREE_PERCENT = 5L

/** Cached file: grid thumbnail (tier) or viewer preview (side). */
enum class PrecacheKind(internal val suffix: Char) { GRID('g'), PREVIEW('p') }

/**
 * Fingerprint (SHA-1, hexadecimal) of an item in a given version: a file name with no special
 * characters (OneDrive cTags contain quotes, braces, commas) and without the id in clear.
 */
internal fun precacheHash(ref: CloudThumbRef): String {
    val digest = MessageDigest.getInstance("SHA-1").digest("${ref.itemId}\n${ref.version}".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Relative path of a file: subdirectory named after the first two characters (≈ 400 files per directory for 100,000 items). */
internal fun precacheRelativePath(hash: String, kind: PrecacheKind, side: Int): String =
    "${hash.substring(0, 2)}/$hash.${kind.suffix}$side"

/** Fingerprint of a cache file name, null for any other file (temporary…). */
internal fun precacheHashOf(fileName: String): String? {
    val dot = fileName.indexOf('.')
    if (dot != HASH_LENGTH || dot + 2 > fileName.length) return null
    val hash = fileName.substring(0, dot)
    val suffix = fileName[dot + 1]
    if (PrecacheKind.entries.none { it.suffix == suffix }) return null
    if (fileName.substring(dot + 2).toIntOrNull() == null) return null
    return hash.takeIf { h -> h.all { it in '0'..'9' || it in 'a'..'f' } }
}

private const val HASH_LENGTH = 40

/**
 * Sizes to look up, in order, for a request of [requested] px: first the sizes at least as large
 * (closest first: no loss of sharpness), then, if [allowLower] (offline, download impossible),
 * the smaller ones (closest first).
 */
internal fun precacheLookupSides(requested: Int, kind: PrecacheKind, allowLower: Boolean): List<Int> {
    val sides = when (kind) {
        PrecacheKind.GRID -> CLOUD_THUMB_BUCKETS.toList()
        PrecacheKind.PREVIEW -> CLOUD_PREVIEW_TIERS.toList()
    }
    val larger = sides.filter { it >= requested }.sorted()
    val smaller = if (allowLower) sides.filter { it < requested }.sortedDescending() else emptyList()
    return larger + smaller
}

/** Thumbnail tier requested by the grid at the default zoom, for a [shortSidePx] screen (portrait). */
internal fun precacheGridBucket(shortSidePx: Int, columns: Int = PRECACHE_GRID_COLUMNS): Int =
    cloudThumbBucket(shortSidePx / columns.coerceAtLeast(1))

/**
 * Preview side requested by the viewer on an unmetered network (same computation as
 * `previewSideFor`), capped at the largest tier accepted by Graph ([maxAcceptedSide]). Video:
 * fixed preview ([PRECACHE_VIDEO_PREVIEW_PX]).
 */
internal fun precachePreviewSide(viewportLongPx: Int, imageLongPx: Int?, isVideo: Boolean, maxAcceptedSide: Int): Int {
    val side = if (isVideo) PRECACHE_VIDEO_PREVIEW_PX else previewSideFor(viewportLongPx, costlyNetwork = false, imageLongPx = imageLongPx)
    return minOf(side, maxAcceptedSide)
}

/** Estimated size of caching [missingItems] items (thumbnail and preview). */
internal fun precacheEstimatedBytes(missingItems: Int): Long =
    missingItems.toLong() * (ESTIMATED_GRID_BYTES + ESTIMATED_PREVIEW_BYTES)

/** Minimum free space to keep on a volume of [totalBytes]. */
internal fun precacheMinFreeBytes(totalBytes: Long): Long = maxOf(MIN_FREE_BYTES, totalBytes / 100 * MIN_FREE_PERCENT)

/** Enough space to continue: [usableBytes] above the minimum to keep. */
internal fun precacheHasRoom(usableBytes: Long, totalBytes: Long): Boolean = usableBytes > precacheMinFreeBytes(totalBytes)

/** Progress percentage (0..100), 0 when no total is known. */
internal fun precachePercent(done: Int, total: Int): Int =
    if (total <= 0) 0 else (done.coerceIn(0, total).toLong() * 100 / total).toInt()

/**
 * Too many consecutive failures (network down, service unavailable): the pass stops and will be
 * resumed later by WorkManager, without downloading again what is already saved.
 */
internal const val MAX_CONSECUTIVE_FAILURES = 20

internal fun precacheShouldPause(consecutiveFailures: Int): Boolean = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES

/** State of a caching WorkManager work, reduced to what the UI displays. */
internal enum class PrecacheWorkStatus { WAITING, RUNNING, SUCCEEDED, STOPPED }

/** Snapshot of a work (WorkInfo): state, progress or result ([outcome] null: none). */
internal data class PrecacheWorkSnapshot(
    val status: PrecacheWorkStatus,
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0L,
    val failed: Int = 0,
    val outcome: PrecacheOutcome? = null,
)

/**
 * Displayed state derived from the queued works (one pass, possibly followed by a pass added by
 * "Keep up to date"): a running pass first, then a waiting pass, then the last summary.
 * Cancelled, failed, or cleared before the end (no summary): nothing in progress.
 */
internal fun precacheStateOf(works: List<PrecacheWorkSnapshot>): PrecacheState {
    works.firstOrNull { it.status == PrecacheWorkStatus.RUNNING }?.let {
        return PrecacheState.Running(it.done, it.total, it.bytes, it.failed)
    }
    if (works.any { it.status == PrecacheWorkStatus.WAITING }) return PrecacheState.Waiting
    works.lastOrNull { it.status == PrecacheWorkStatus.SUCCEEDED && it.outcome != null }?.let {
        return PrecacheState.Finished(it.outcome!!, it.done, it.total, it.failed)
    }
    return PrecacheState.Idle
}
