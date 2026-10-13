package fr.eva.cloudgallery.ui.storage

/*
 * Pure logic of the Storage page (JVM-tested).
 */

/**
 * Thumbnail limit changed since the Coil cache was opened: the new limit only
 * applies on the next launch. [effectiveBytes] null (cache not opened yet): the
 * setting will be read when it opens, nothing is pending.
 */
internal fun thumbnailCapPendingRestart(settingBytes: Long, effectiveBytes: Long?): Boolean =
    effectiveBytes != null && effectiveBytes != settingBytes

/** "Clear cache" offered only if there is something to clear and nothing in progress. */
internal fun canClearCache(cacheBytes: Long?, clearing: Boolean): Boolean =
    !clearing && cacheBytes != null && cacheBytes > 0
