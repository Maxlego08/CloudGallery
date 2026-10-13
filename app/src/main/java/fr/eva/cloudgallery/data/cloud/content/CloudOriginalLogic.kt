package fr.eva.cloudgallery.data.cloud.content

/*
 * Pure logic of the temporary cache of OneDrive originals (tested on the JVM).
 */

/** Free space kept on the device in addition to a downloaded original (50 MiB). */
internal const val ORIGINAL_SPACE_MARGIN_BYTES = 50L * 1024 * 1024

/**
 * An original used more recently than this (displayed, shared, opened in another app) is not
 * evicted to respect the cap: the app reading it through the FileProvider may do so a few
 * minutes later. The cap can therefore be exceeded for a while.
 */
internal const val ORIGINAL_IN_USE_GRACE_MS = 30L * 60 * 1000

/**
 * `cached_file` row of an original, as seen by the cleanup ([fileExists]: file present;
 * [pinned]: never deleted by the cleanup nor counted towards the cap).
 */
internal data class TrimRow(
    val id: Long,
    val path: String,
    val sizeBytes: Long,
    val lastAccessAt: Long,
    val fileExists: Boolean,
    val pinned: Boolean = false,
)

/** Rows to delete ([drop], with their file) and to keep ([keep]) during the cleanup. */
internal data class TrimPlan(val drop: List<TrimRow>, val keep: List<TrimRow>)

/**
 * LRU cleanup of the originals cache: rows without a file, originals unused for more than
 * [maxAgeMs], then the least recently used ones while the total (unpinned rows) exceeds
 * [maxBytes]. Never removed: a file being downloaded ([busy]), a pinned row; no eviction for
 * the cap of an original used less than [inUseGraceMs] ago.
 */
internal fun planOriginalTrim(
    rows: List<TrimRow>,
    busy: Set<String>,
    now: Long,
    maxBytes: Long,
    maxAgeMs: Long,
    inUseGraceMs: Long = ORIGINAL_IN_USE_GRACE_MS,
): TrimPlan {
    val drop = ArrayList<TrimRow>()
    val keep = ArrayList<TrimRow>()
    for (row in rows) {
        when {
            row.path in busy || row.pinned -> keep += row
            !row.fileExists -> drop += row
            now - row.lastAccessAt > maxAgeMs -> drop += row
            else -> keep += row
        }
    }
    var total = keep.filter { !it.pinned }.sumOf { it.sizeBytes }
    val evicted = HashSet<Long>()
    for (row in keep.sortedWith(compareBy({ it.lastAccessAt }, { it.id }))) {
        if (total <= maxBytes) break
        if (row.path in busy || row.pinned || now - row.lastAccessAt < inUseGraceMs) continue
        evicted += row.id
        total -= row.sizeBytes
    }
    return TrimPlan(drop = drop + keep.filter { it.id in evicted }, keep = keep.filter { it.id !in evicted })
}

/**
 * Alternate name for an original whose usual name [name] is already taken by a file (unknown
 * version, or previous file not reusable): `<base>-<nonce>.<ext>`. The existing file is never
 * overwritten before the new one is saved (it may be shared with another app).
 */
internal fun alternateFileName(name: String, nonce: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "$name-$nonce" else "${name.substring(0, dot)}-$nonce${name.substring(dot)}"
}
