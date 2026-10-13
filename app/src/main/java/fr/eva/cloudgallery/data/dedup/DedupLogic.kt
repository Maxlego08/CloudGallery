package fr.eva.cloudgallery.data.dedup

/*
 * Pure logic of local / OneDrive deduplication (tested on the JVM).
 *
 * Core rule: a phone file and a OneDrive item are the same media item only if their
 * QuickXorHash AND their size are identical. Name, folder and date are never taken into
 * account. Merging only changes the index: no file is touched.
 */

/** Prefix of `media.contentHash` for a QuickXorHash (same value as OneDrive). */
internal const val QUICK_XOR_PREFIX = "qxh:"

/** `media.contentHash` of a QuickXorHash, null if unknown. */
internal fun contentHashOf(quickXorHash: String?): String? = quickXorHash?.let { QUICK_XOR_PREFIX + it }

/**
 * Signature of a local file when its hash was computed (`media_source.hashSignature`): size,
 * modification date and MediaStore generation. A different value for the source's current state
 * makes the hash stale. Same shape as the SQL expression [LOCAL_HASH_SIGNATURE_SQL].
 */
internal fun localHashSignature(sizeBytes: Long, dateModified: Long, generation: Long): String =
    "$sizeBytes:$dateModified:$generation"

/**
 * Current signature of a `media_source` row in SQL (table prefix included by the queries that
 * embed it); must stay identical to [localHashSignature].
 */
internal const val LOCAL_HASH_SIGNATURE_SQL = "(sizeBytes || ':' || dateModified || ':' || generation)"

/**
 * Merge candidate pair: local file (hash up to date, media without a OneDrive source) and
 * OneDrive item (media without a local file) with the same hash and the same size.
 */
data class MergeCandidateRow(
    val localSourceId: Long,
    val localMediaId: Long,
    val cloudSourceId: Long,
    val cloudMediaId: Long,
    val quickXorHash: String,
    val sizeBytes: Long,
)

/** Merge to perform: OneDrive media [cloudMediaId] joins local media [localMediaId]. */
data class MergePlan(val localMediaId: Long, val cloudMediaId: Long, val cloudSourceId: Long)

/**
 * Merges to perform among [candidates]. Each media item is merged only once. Several identical
 * local files, or several identical OneDrive items (copies of the same file): deterministic rule,
 * per hash and size, the i-th local file (source id order) gets the i-th OneDrive item; surplus
 * items stay separate.
 */
internal fun planMerges(candidates: List<MergeCandidateRow>): List<MergePlan> {
    val plans = ArrayList<MergePlan>()
    candidates.groupBy { it.quickXorHash to it.sizeBytes }.values.forEach { group ->
        val locals = group.map { it.localSourceId to it.localMediaId }.distinct().sortedBy { it.first }
        val clouds = group.map { it.cloudSourceId to it.cloudMediaId }.distinct().sortedBy { it.first }
        locals.zip(clouds).forEach { (local, cloud) ->
            plans += MergePlan(localMediaId = local.second, cloudMediaId = cloud.second, cloudSourceId = cloud.first)
        }
    }
    // A media item can only take part in one merge (two groups never share a source, but a local
    // or OneDrive media item could appear in two groups because of inconsistent data).
    val usedLocal = HashSet<Long>()
    val usedCloud = HashSet<Long>()
    return plans.sortedBy { it.cloudSourceId }.filter { usedLocal.add(it.localMediaId) && usedCloud.add(it.cloudMediaId) }
}

/** Merged media as seen by the check: its local source and its OneDrive source. */
data class MergedRow(
    val mediaId: Long,
    val localSizeBytes: Long,
    val localQuickXorHash: String?,
    /** Local hash computed for the file's current state (signature up to date). */
    val localHashCurrent: Boolean,
    val cloudSourceId: Long,
    val cloudItemId: String,
    val cloudSizeBytes: Long,
    val cloudQuickXorHash: String?,
)

/**
 * The merge must be undone: different sizes (file modified on one side, certain), or two known
 * and different hashes (local hash recomputed after a modification, or OneDrive content
 * modified). A stale local hash not yet recomputed (file touched without a size change, e.g.
 * metadata) is not enough: the merge is kept until the next computation (while charging), which
 * decides.
 */
internal fun shouldSplit(row: MergedRow): Boolean {
    if (row.localSizeBytes != row.cloudSizeBytes) return true
    val local = row.localQuickXorHash?.takeIf { row.localHashCurrent } ?: return false
    val cloud = row.cloudQuickXorHash ?: return false
    return local != cloud
}

/** Local file whose hash is to be computed. */
data class HashTarget(
    val sourceId: Long,
    val mediaId: Long,
    val uri: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val generation: Long,
) {
    val signature: String get() = localHashSignature(sizeBytes, dateModified, generation)
}

/**
 * Next batch of hashes: first the files whose size exists among visible OneDrive items
 * ([cloudSizes]: likely duplicates, useful for deduplication and the space saver), then the
 * others; within each group, id order. At most [maxCount] files and [maxBytes] bytes (at least
 * one file, even if larger than the budget).
 */
internal fun nextHashBatch(targets: List<HashTarget>, cloudSizes: Set<Long>, maxCount: Int, maxBytes: Long): List<HashTarget> {
    val ordered = targets.sortedWith(compareBy<HashTarget>({ it.sizeBytes !in cloudSizes }, { it.sourceId }))
    val batch = ArrayList<HashTarget>()
    var bytes = 0L
    for (t in ordered) {
        if (batch.size >= maxCount) break
        if (batch.isNotEmpty() && bytes + t.sizeBytes > maxBytes) break
        batch += t
        bytes += t.sizeBytes.coerceAtLeast(0L)
    }
    return batch
}
