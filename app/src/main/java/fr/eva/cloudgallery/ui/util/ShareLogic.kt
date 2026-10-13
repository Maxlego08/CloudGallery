package fr.eva.cloudgallery.ui.util

/*
 * Pure logic for sharing (and "Open with") OneDrive items (tested on the JVM).
 */

/** Maximum number of OneDrive items to download for a single share (user decision). */
internal const val MAX_SHARE_CLOUD_ITEMS = 20

/** Maximum total size of OneDrive items to download for a single share (500 MB). */
internal const val MAX_SHARE_CLOUD_BYTES = 500L * 1000 * 1000

/**
 * Share candidate: local file ([hasLocalFile]), offline copy
 * ([offlinePath], slice 3), or OneDrive item to download ([cloudItemId], [sizeBytes]).
 */
internal data class ShareCandidate(
    val mediaId: Long,
    val hasLocalFile: Boolean,
    val offlinePath: String?,
    val cloudItemId: String?,
    val sizeBytes: Long,
)

/** Why a share of OneDrive items is refused. */
internal enum class ShareRefusal {
    /** More than [MAX_SHARE_CLOUD_ITEMS] items to download. */
    TOO_MANY,

    /** More than [MAX_SHARE_CLOUD_BYTES] to download. */
    TOO_LARGE,
}

/**
 * Breakdown of a share: local files, offline copies, OneDrive items to download
 * and items without a shareable source ([unavailable]). [refusal] non-null: the downloads
 * exceed the limits, the share is not started.
 */
internal data class SharePlan(
    val local: List<Long>,
    val offline: List<Long>,
    val toDownload: List<Long>,
    val unavailable: List<Long>,
    val downloadBytes: Long,
    val refusal: ShareRefusal?,
)

/**
 * Share plan for [candidates] by source priority: local file, offline copy, then
 * download from OneDrive if [cloudAllowed] (account signed in), within the limits
 * [maxCloudItems] / [maxCloudBytes]. An unknown size (≤ 0) does not count toward the total.
 */
internal fun planShare(
    candidates: List<ShareCandidate>,
    cloudAllowed: Boolean,
    maxCloudItems: Int = MAX_SHARE_CLOUD_ITEMS,
    maxCloudBytes: Long = MAX_SHARE_CLOUD_BYTES,
): SharePlan {
    val local = ArrayList<Long>()
    val offline = ArrayList<Long>()
    val toDownload = ArrayList<Long>()
    val unavailable = ArrayList<Long>()
    var bytes = 0L
    for (c in candidates) {
        when {
            c.hasLocalFile -> local += c.mediaId
            c.offlinePath != null -> offline += c.mediaId
            c.cloudItemId != null && cloudAllowed -> {
                toDownload += c.mediaId
                bytes += c.sizeBytes.coerceAtLeast(0)
            }
            else -> unavailable += c.mediaId
        }
    }
    val refusal = when {
        toDownload.size > maxCloudItems -> ShareRefusal.TOO_MANY
        bytes > maxCloudBytes -> ShareRefusal.TOO_LARGE
        else -> null
    }
    return SharePlan(local, offline, toDownload, unavailable, bytes, refusal)
}

/** Integer percentage (0..100) of a progress, -1 if the size is unknown. */
internal fun progressPercent(done: Long, total: Long): Int =
    if (total <= 0) -1 else (done.coerceIn(0, total) * 100 / total).toInt()
