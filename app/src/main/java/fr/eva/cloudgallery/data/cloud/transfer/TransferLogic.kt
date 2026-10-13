package fr.eva.cloudgallery.data.cloud.transfer

/*
 * Pure logic of OneDrive transfers (tested on the JVM).
 */

/**
 * How long a `@microsoft.graph.downloadUrl` URL is reused. The Graph documentation states a
 * short validity ("might expire within minutes"): the URL is requested again after two
 * minutes, and immediately after a rejection by the download host ([isExpiredUrlStatus]).
 */
internal const val DOWNLOAD_URL_TTL_MS = 2 * 60_000L

/** A URL obtained at [fetchedAtMs] can still be used at [nowMs] (monotonic clock). */
internal fun isUrlFresh(fetchedAtMs: Long, nowMs: Long, ttlMs: Long = DOWNLOAD_URL_TTL_MS): Boolean {
    val age = nowMs - fetchedAtMs
    // Clock went backwards (should not happen with a monotonic clock): not reliable.
    return age in 0 until ttlMs
}

/**
 * Download host statuses that probably signal an expired or revoked URL (the exact status is
 * not documented): a new URL is requested from Graph before retrying.
 */
internal fun isExpiredUrlStatus(code: Int): Boolean = code == 401 || code == 403 || code == 404 || code == 410

/** `Content-Range` header of a 206/416 response: `bytes start-end/total` (or total alone for a 416). */
internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

/**
 * Parses `Content-Range` (RFC 9110). Unsatisfiable form (asterisk then `/2048`):
 * start = end = -1. Null if absent or unreadable.
 */
internal fun parseContentRange(header: String?): ContentRange? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    val spec = value.substring(6).trim()
    val slash = spec.indexOf('/')
    if (slash < 0) return null
    val totalPart = spec.substring(slash + 1).trim()
    val total = if (totalPart == "*") null else totalPart.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val rangePart = spec.substring(0, slash).trim()
    if (rangePart == "*") return if (total != null) ContentRange(-1, -1, total) else null
    val dash = rangePart.indexOf('-')
    if (dash <= 0) return null
    val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
    val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
    if (start < 0 || end < start) return null
    if (total != null && end >= total) return null
    return ContentRange(start, end, total)
}

/** What to do with the download host's response to a request starting at [offset]. */
internal sealed interface RangeOutcome {
    /** Consistent 206: the received bytes are appended to the partial file. */
    data object Append : RangeOutcome

    /** 200: full content from the start (Range ignored or absent); the partial file is emptied. */
    data object RestartFromZero : RangeOutcome

    /** 416 while the partial file already has the expected size: nothing to download. */
    data object AlreadyComplete : RangeOutcome

    /** Inconsistent response: the partial file cannot be safely completed. */
    data class Invalid(val reason: String) : RangeOutcome
}

/**
 * Classifies a 2xx/416 response from the download host for a request from [offset]
 * ([expectedSize] = size announced by Graph). Graph documents that a Range may be ignored
 * (200 with the whole content): the file then restarts from zero.
 */
internal fun classifyRangeResponse(code: Int, contentRange: ContentRange?, offset: Long, expectedSize: Long): RangeOutcome = when (code) {
    200 -> RangeOutcome.RestartFromZero
    206 -> when {
        contentRange == null -> RangeOutcome.Invalid("206 without Content-Range")
        contentRange.start != offset -> RangeOutcome.Invalid("206 at wrong position")
        contentRange.total != null && expectedSize > 0 && contentRange.total != expectedSize ->
            RangeOutcome.Invalid("206 with unexpected size")
        else -> RangeOutcome.Append
    }
    416 -> if (offset > 0 && offset == expectedSize && (contentRange?.total == null || contentRange.total == expectedSize)) {
        RangeOutcome.AlreadyComplete
    } else {
        RangeOutcome.Invalid("416")
    }
    else -> RangeOutcome.Invalid("HTTP $code")
}

/** Resumption of a partial file. */
internal sealed interface ResumePlan {
    data object FromZero : ResumePlan
    data class From(val offset: Long) : ResumePlan
    data object Complete : ResumePlan
}

/**
 * Resume plan for a partial file of [partLength] bytes started for version [partCTag], while
 * the item is now at version [currentCTag] with [expectedSize] bytes. An unknown (null)
 * version on either side is never considered identical: restart from zero, as after a version
 * change or a file that is too long.
 */
internal fun resumePlan(partLength: Long, expectedSize: Long, partCTag: String?, currentCTag: String?): ResumePlan = when {
    partLength <= 0 -> ResumePlan.FromZero
    partCTag == null || currentCTag == null || partCTag != currentCTag -> ResumePlan.FromZero
    expectedSize <= 0 || partLength > expectedSize -> ResumePlan.FromZero
    partLength == expectedSize -> ResumePlan.Complete
    else -> ResumePlan.From(partLength)
}

/**
 * File name of a OneDrive copy: `<sha1(itemId)[0..16]>_<sha1(cTag)[0..8]>.<ext>`. No user name
 * or path character: the extension (lower case, 1 to 5 [a-z0-9] characters) comes from the
 * display name, else the MIME type, else `bin`. A new version yields a new name.
 */
internal fun cacheFileName(itemId: String, cTag: String?, displayName: String, mimeType: String?): String {
    val idHash = sha1Hex(itemId).take(16)
    val versionHash = sha1Hex(cTag ?: "").take(8)
    return "${idHash}_$versionHash.${fileExtension(displayName, mimeType)}"
}

private val EXTENSION = Regex("^[a-z0-9]{1,5}$")

/** Safe file extension: the name's, else derived from the MIME type, else `bin`. */
internal fun fileExtension(displayName: String, mimeType: String?): String {
    val fromName = displayName.substringAfterLast('.', "").lowercase()
    if (displayName.contains('.') && EXTENSION.matches(fromName)) return fromName
    val fromMime = when (mimeType?.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        "image/bmp" -> "bmp"
        "image/x-adobe-dng", "image/dng" -> "dng"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        else -> mimeType?.substringAfter('/', "")?.lowercase()?.takeIf { EXTENSION.matches(it) }
    }
    return fromMime ?: "bin"
}

/**
 * Safe display name for the app receiving a share: no path separator, reserved or control
 * character, no bare `.`/`..`, at most 120 characters, never empty.
 */
internal fun safeDisplayName(name: String): String {
    val cleaned = name
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .trim()
        .trimStart('.')
        .trim()
    val capped = if (cleaned.length <= MAX_DISPLAY_NAME) {
        cleaned
    } else {
        // Truncate while keeping the extension (and without splitting a surrogate pair).
        val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
        val keep = MAX_DISPLAY_NAME - (ext?.length?.plus(1) ?: 0)
        var base = cleaned.take(keep)
        if (base.isNotEmpty() && Character.isHighSurrogate(base.last())) base = base.dropLast(1)
        if (ext != null) "$base.$ext" else base
    }
    return capped.ifEmpty { "file" }
}

private const val MAX_DISPLAY_NAME = 120

/**
 * Progress to publish (Room, notification, UI): at most once per second or every 4 MiB, and
 * always at the end of the download ([bytes] reaches [totalBytes]).
 */
internal fun shouldEmitProgress(lastEmitMs: Long, nowMs: Long, lastBytes: Long, bytes: Long, totalBytes: Long = -1): Boolean =
    (totalBytes > 0 && bytes >= totalBytes) ||
        nowMs - lastEmitMs >= PROGRESS_MIN_INTERVAL_MS ||
        bytes - lastBytes >= PROGRESS_MIN_BYTES

private const val PROGRESS_MIN_INTERVAL_MS = 1_000L
private const val PROGRESS_MIN_BYTES = 4L * 1024 * 1024

/** Suffix of partial files (download in progress or to resume). */
internal const val PART_SUFFIX = ".part"

/** Abandoned partial files deleted during cleanup beyond this age (24 h). */
internal const val PART_MAX_AGE_MS = 24L * 60 * 60 * 1000

/**
 * Files without a database row deleted during cleanup beyond this age (1 h): a copy just
 * renamed but not yet recorded is always more recent.
 */
internal const val ORPHAN_MIN_AGE_MS = 60L * 60 * 1000

/** File of a copies folder as seen by cleanup: name, regular file, age (ms). */
internal data class SweepEntry(val name: String, val isFile: Boolean, val ageMs: Long)

/**
 * Files to delete when cleaning up one of the app's copies folders: never a recorded file
 * ([known]: names of the `cached_file` rows), nor a file (or its partial file) in use ([busy]:
 * base names of downloads in progress or to resume), nor a folder; partial files abandoned for
 * more than [partMaxAgeMs], other row-less files for more than [orphanMinAgeMs].
 */
internal fun filesToSweep(
    entries: List<SweepEntry>,
    known: Set<String>,
    busy: Set<String>,
    partMaxAgeMs: Long = PART_MAX_AGE_MS,
    orphanMinAgeMs: Long = ORPHAN_MIN_AGE_MS,
): List<String> = entries.filter { entry ->
    val base = entry.name.removeSuffix(PART_SUFFIX)
    if (!entry.isFile || entry.name in known || base in busy) return@filter false
    if (entry.name.endsWith(PART_SUFFIX)) entry.ageMs > partMaxAgeMs else entry.ageMs > orphanMinAgeMs
}.map { it.name }

private fun sha1Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
