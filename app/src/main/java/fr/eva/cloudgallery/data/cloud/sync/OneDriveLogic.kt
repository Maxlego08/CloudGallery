package fr.eva.cloudgallery.data.cloud.sync

import fr.eva.cloudgallery.data.cloud.graph.DriveItemDto
import fr.eva.cloudgallery.data.local.MISSING_GRACE_MS
import fr.eva.cloudgallery.domain.model.MediaType
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/*
 * Pure logic (no Android dependency) of the OneDrive sync, tested on the JVM.
 * Read-only: nothing here modifies OneDrive or the device's files.
 */

/** OneDrive provider in the sync_cursor table (Microsoft Graph deltaLink). */
internal const val ONEDRIVE_PROVIDER = "onedrive"

/**
 * Fields requested by the delta query. The `$select` is copied into the deltaLink: any change
 * must come with a new [DELTA_SELECT_VERSION] (full enumeration).
 */
internal const val DELTA_SELECT =
    "id,name,size,eTag,cTag,createdDateTime,lastModifiedDateTime,fileSystemInfo,file,folder,package," +
        "image,photo,video,parentReference,deleted,root,remoteItem"

/** Version of [DELTA_SELECT] saved with the cursor. */
internal const val DELTA_SELECT_VERSION = "s1"

/** Minimum interval between two syncs triggered by returning to the foreground. */
internal const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 5 * 60_000L

/**
 * Access to OneDrive content for the signed-in account [currentAccountId]: never after another
 * drive has been detected ([mismatch]); otherwise only for the account verified by the last
 * pass ([verifiedAccountId]). No verified account (empty index, or data from before this check):
 * access allowed, the next pass verifies the drive.
 */
internal fun cloudAccountAllowed(currentAccountId: String?, verifiedAccountId: String?, mismatch: Boolean): Boolean =
    !mismatch && (verifiedAccountId == null || verifiedAccountId == currentAccountId)

private const val CLOUD_BUCKET_PREFIX = "onedrive:"

/** Maximum depth of a folder path (protection against a parent cycle). */
private const val MAX_FOLDER_DEPTH = 64

/** Folder key of a OneDrive source: "onedrive:<parent folder id>". */
internal fun cloudBucketKey(parentId: String?): String? =
    parentId?.takeIf { it.isNotBlank() }?.let { CLOUD_BUCKET_PREFIX + it }

/** Inverse of [cloudBucketKey]. */
internal fun folderIdOfCloudBucket(bucketKey: String): String? =
    bucketKey.takeIf { it.startsWith(CLOUD_BUCKET_PREFIX) }?.substring(CLOUD_BUCKET_PREFIX.length)?.takeIf { it.isNotEmpty() }

/**
 * Graph ISO 8601 date in ms. Null if absent, unreadable or non-positive (Graph sometimes
 * returns the sentinel value 0001-01-01).
 */
internal fun parseGraphInstantMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli().takeIf { it > 0 }
    } catch (e: DateTimeParseException) {
        null
    } catch (e: ArithmeticException) {
        null
    }
}

/** Date taken: photo.takenDateTime, then client-side creation, then service-side creation; 0 otherwise. */
internal fun chooseDateTakenMs(photoTaken: String?, fileSystemCreated: String?, created: String?): Long =
    parseGraphInstantMs(photoTaken) ?: parseGraphInstantMs(fileSystemCreated) ?: parseGraphInstantMs(created) ?: 0L

/**
 * Media type of a OneDrive file. The MIME type decides (SVG excluded); if it is absent or
 * generic, the video / image / photo facets serve as a fallback. Null: not a media.
 */
internal fun resolveCloudMediaType(mimeType: String?, hasImage: Boolean, hasPhoto: Boolean, hasVideo: Boolean): MediaType? {
    val mime = mimeType?.trim()?.lowercase().orEmpty()
    return when {
        mime == "image/svg+xml" -> null
        mime.startsWith("image/") -> MediaType.IMAGE
        mime.startsWith("video/") -> MediaType.VIDEO
        mime.isEmpty() || mime == "application/octet-stream" -> when {
            hasVideo -> MediaType.VIDEO
            hasImage || hasPhoto -> MediaType.IMAGE
            else -> null
        }
        else -> null
    }
}

/** Fields of a OneDrive media (one item = one media, never merged with a local file). */
internal data class CloudMediaFields(
    val itemId: String,
    val type: MediaType,
    val name: String,
    val mimeType: String?,
    val dateTakenMs: Long,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val bucketKey: String?,
    val parentId: String?,
    val eTag: String?,
    val cTag: String?,
    val quickXorHash: String?,
    val sha1Hash: String?,
) {
    /** Content fingerprint (future deduplication): QuickXorHash, the only hash guaranteed on OneDrive. */
    val contentHash: String? get() = quickXorHash?.let { "qxh:$it" }
}

/** OneDrive folder. */
internal data class CloudFolderFields(
    val itemId: String,
    val parentId: String?,
    val name: String,
    val isRoot: Boolean,
)

/** Effect of a delta query item on the local index. */
internal sealed interface DeltaAction {
    data class UpsertMedia(val media: CloudMediaFields) : DeltaAction
    data class UpsertFolder(val folder: CloudFolderFields) : DeltaAction

    /**
     * Item deleted, moved out of the drive, no longer a media, or ignored (OneNote package,
     * shared item from another drive). Folder or file: decided by the local index, since the
     * `folder` facet is not always present on a deleted item.
     */
    data class Remove(val itemId: String) : DeltaAction
}

/** Classifies a delta query item (rules applied in this order). */
internal fun classifyDeltaItem(item: DriveItemDto): DeltaAction = when {
    item.deleted != null -> DeltaAction.Remove(item.id)
    item.root != null -> DeltaAction.UpsertFolder(
        CloudFolderFields(itemId = item.id, parentId = null, name = item.name.orEmpty(), isRoot = true),
    )
    item.remoteItem != null || item.pkg != null -> DeltaAction.Remove(item.id)
    item.folder != null -> DeltaAction.UpsertFolder(
        CloudFolderFields(itemId = item.id, parentId = item.parentReference?.id, name = item.name.orEmpty(), isRoot = false),
    )
    item.file == null -> DeltaAction.Remove(item.id)
    else -> toCloudMediaFields(item)?.let { DeltaAction.UpsertMedia(it) } ?: DeltaAction.Remove(item.id)
}

/** Fields of a media file, or null if the file is neither an image nor a video. */
internal fun toCloudMediaFields(item: DriveItemDto): CloudMediaFields? {
    val file = item.file ?: return null
    val type = resolveCloudMediaType(file.mimeType, item.image != null, item.photo != null, item.video != null) ?: return null
    val created = parseGraphInstantMs(item.createdDateTime)
    val modified = parseGraphInstantMs(item.fileSystemInfo?.lastModifiedDateTime)
        ?: parseGraphInstantMs(item.lastModifiedDateTime)
        ?: created
        ?: 0L
    val taken = chooseDateTakenMs(item.photo?.takenDateTime, item.fileSystemInfo?.createdDateTime, item.createdDateTime)
    val image = item.image?.takeIf { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }
    val video = item.video?.takeIf { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }
    val parentId = item.parentReference?.id?.takeIf { it.isNotBlank() }
    return CloudMediaFields(
        itemId = item.id,
        type = type,
        name = item.name?.takeIf { it.isNotBlank() } ?: item.id,
        mimeType = file.mimeType,
        dateTakenMs = if (taken > 0) taken else modified,
        dateAddedMs = created ?: modified,
        dateModifiedMs = modified,
        width = image?.width ?: video?.width ?: 0,
        height = image?.height ?: video?.height ?: 0,
        durationMs = if (type == MediaType.VIDEO) (item.video?.duration ?: 0L).coerceAtLeast(0L) else 0L,
        sizeBytes = (item.size ?: 0L).coerceAtLeast(0L),
        bucketKey = cloudBucketKey(parentId),
        parentId = parentId,
        eTag = item.eTag,
        cTag = item.cTag,
        quickXorHash = file.hashes?.quickXorHash?.takeIf { it.isNotBlank() },
        sha1Hash = file.hashes?.sha1Hash?.takeIf { it.isNotBlank() },
    )
}

/**
 * The same item may appear several times in a delta feed: only its last occurrence counts.
 * Order preserved (that of the last occurrences).
 */
internal fun lastOccurrenceById(items: List<DriveItemDto>): List<DriveItemDto> {
    val latest = LinkedHashMap<String, DriveItemDto>(items.size)
    for (item in items) {
        latest.remove(item.id)
        latest[item.id] = item
    }
    return latest.values.toList()
}

/** Album name of a OneDrive folder: [rootLabel] for the root, [unnamedLabel] without a known name. */
internal fun cloudFolderAlbumName(folder: CloudFolderFields?, rootLabel: String, unnamedLabel: String): String = when {
    folder == null -> unnamedLabel
    folder.isRoot -> rootLabel
    else -> folder.name.takeIf { it.isNotBlank() } ?: unnamedLabel
}

/**
 * Readable path of a folder ("OneDrive/Photos/2024/"), the root being [rootLabel]. An unknown
 * parent truncates the path (without the root prefix); a cycle is broken. Null if the folder is
 * unknown.
 */
internal fun cloudFolderPath(folderId: String, folders: Map<String, CloudFolderFields>, rootLabel: String): String? {
    var current = folders[folderId] ?: return null
    val names = ArrayList<String>()
    val visited = HashSet<String>()
    var reachedRoot = false
    while (names.size < MAX_FOLDER_DEPTH && visited.add(current.itemId)) {
        if (current.isRoot) {
            reachedRoot = true
            break
        }
        names += current.name
        current = current.parentId?.let { folders[it] } ?: break
    }
    val parts = if (reachedRoot) listOf(rootLabel) + names.asReversed() else names.asReversed()
    return parts.joinToString(separator = "/", postfix = "/")
}

/** Folders [roots] and all their known descendants. */
internal fun descendantFolderIds(roots: Set<String>, folders: Collection<CloudFolderFields>): Set<String> {
    if (roots.isEmpty()) return emptySet()
    val children = HashMap<String, MutableList<String>>()
    for (f in folders) f.parentId?.let { children.getOrPut(it) { ArrayList() }.add(f.itemId) }
    val result = LinkedHashSet<String>(roots)
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        for (child in children[queue.removeFirst()].orEmpty()) {
            if (result.add(child)) queue.addLast(child)
        }
    }
    return result
}

/**
 * True if the subtree of one of the [roots] folders (themselves included, traversal by `parentId`
 * over [folders], marked folders included) contains a folder of [hiddenFolders] (folders that
 * have hidden OneDrive sources).
 */
internal fun subtreeHasHiddenItems(
    roots: Set<String>,
    folders: Collection<CloudFolderFields>,
    hiddenFolders: Set<String>,
): Boolean = roots.isNotEmpty() && roots.any { it in selfAndAncestorIds(hiddenFolders, folders) }

/** Folders to mark as deleted ([mark]) and rows to remove from the index ([delete]) at the end of a pass. */
internal data class FolderTombstonePlan(val mark: Set<String>, val delete: Set<String>)

/**
 * Deletion markers (tombstones) of OneDrive folders (end of pass). Candidates: folders missing
 * at the end of this pass ([gone]) and markers already present ([markers]), except those that
 * still have visible items ([keepVisible], edge case: row left as is).
 *
 * A candidate stays (or becomes) a marker as long as its subtree contains hidden sources
 * ([hiddenFolders]): if it is restored from the recycle bin, its hidden items may not be
 * returned one by one by the delta, and the marker is what signals it (full enumeration only in
 * that case). Intermediate folders without items are kept too: without them, a hidden subfolder
 * would no longer be attached to the restored folder. Otherwise the row is removed (nothing to
 * restore: a folder that comes back is then treated as a new folder).
 */
internal fun planFolderTombstones(
    folders: Collection<CloudFolderFields>,
    markers: Set<String>,
    gone: Set<String>,
    keepVisible: Set<String>,
    hiddenFolders: Set<String>,
): FolderTombstonePlan {
    val candidates = (gone + markers) - keepVisible
    if (candidates.isEmpty()) return FolderTombstonePlan(emptySet(), emptySet())
    val hiddenOrAncestors = selfAndAncestorIds(hiddenFolders, folders)
    val kept = candidates.filterTo(HashSet()) { it in hiddenOrAncestors }
    return FolderTombstonePlan(mark = kept - markers, delete = candidates - kept)
}

/** [ids] and all their known ancestors (walking up by `parentId`, cycles ignored). */
private fun selfAndAncestorIds(ids: Set<String>, folders: Collection<CloudFolderFields>): Set<String> {
    val parentOf = folders.associate { it.itemId to it.parentId }
    val result = HashSet<String>()
    for (id in ids) {
        var current: String? = id
        while (current != null && result.add(current)) current = parentOf[current]
    }
    return result
}

/** OneDrive source of the index (for the removal plan). */
internal data class CloudStamp(
    val sourceId: Long,
    val mediaId: Long,
    val externalId: String,
    val missingSince: Long?,
)

internal data class CloudRemovalPlan(
    /** Sources to hide (kept with favorites and albums). */
    val hide: List<CloudStamp>,
    /** Sources hidden for more than [MISSING_GRACE_MS]: removed from the index. */
    val purge: List<CloudStamp>,
)

/**
 * End of sync. [seen] (media items received) is only provided after a full enumeration: a
 * missing visible source is then hidden. A source is never removed immediately: only a source
 * hidden for more than [MISSING_GRACE_MS] is purged.
 */
internal fun planCloudRemovals(all: List<CloudStamp>, seen: Set<String>?, now: Long): CloudRemovalPlan {
    val hide = ArrayList<CloudStamp>()
    val purge = ArrayList<CloudStamp>()
    for (stamp in all) {
        val since = stamp.missingSince
        when {
            since != null -> if (now - since >= MISSING_GRACE_MS) purge += stamp
            seen != null && stamp.externalId !in seen -> hide += stamp
        }
    }
    return CloudRemovalPlan(hide, purge)
}

/**
 * Sync on return to the foreground: not during Graph throttling, nor less than [minIntervalMs]
 * after the previous one (a clock moved back does not prevent the sync).
 */
internal fun shouldRunForegroundSync(
    lastSyncAt: Long,
    retryNotBefore: Long,
    now: Long,
    minIntervalMs: Long = FOREGROUND_SYNC_MIN_INTERVAL_MS,
): Boolean = now >= retryNotBefore && (lastSyncAt <= 0L || now < lastSyncAt || now - lastSyncAt >= minIntervalMs)

/** Saved Graph pause further away than this: clock out of whack, value ignored. */
internal const val MAX_STORED_THROTTLE_MS = 24 * 60 * 60_000L

/**
 * Sync pause read back at startup (saved Retry-After): kept if it has not elapsed yet, ignored
 * (0) if it is over or abnormally far away (clock moved back).
 */
internal fun restoredRetryNotBefore(stored: Long, now: Long, maxMs: Long = MAX_STORED_THROTTLE_MS): Long =
    if (stored > now && stored - now <= maxMs) stored else 0L

/** What a pass does next after applying a delta page. */
internal sealed interface DeltaPageStep {
    /**
     * A folder containing hidden items has reappeared (incremental pass): the deltaLink is
     * cleared right away (an interrupted pass will resume with a full enumeration) and the full
     * enumeration starts without waiting for the end of the delta feed.
     */
    data object RestartFull : DeltaPageStep

    data class NextPage(val url: String) : DeltaPageStep

    /** Last page: end of pass, then the deltaLink is saved. */
    data class Finish(val deltaLink: String) : DeltaPageStep

    /** Page without a continuation link: invalid response, the cursor is not modified. */
    data object MissingLink : DeltaPageStep
}

internal fun deltaPageStep(
    full: Boolean,
    restoredFolderWithHiddenItems: Boolean,
    nextLink: String?,
    deltaLink: String?,
): DeltaPageStep = when {
    !full && restoredFolderWithHiddenItems -> DeltaPageStep.RestartFull
    !nextLink.isNullOrBlank() -> DeltaPageStep.NextPage(nextLink)
    !deltaLink.isNullOrBlank() -> DeltaPageStep.Finish(deltaLink)
    else -> DeltaPageStep.MissingLink
}

/** Value of `sync_cursor.version`: bound drive and `$select` version. */
internal fun cloudCursorVersion(driveId: String, selectVersion: String): String = "$driveId|$selectVersion"

internal data class CloudCursorBinding(val driveId: String, val selectVersion: String?)

/** Inverse of [cloudCursorVersion] (an old value without a `$select` version is accepted). */
internal fun parseCloudCursorVersion(version: String?): CloudCursorBinding? {
    if (version.isNullOrBlank()) return null
    val sep = version.lastIndexOf('|')
    return if (sep < 0) {
        CloudCursorBinding(version, null)
    } else {
        version.substring(0, sep).takeIf { it.isNotBlank() }
            ?.let { CloudCursorBinding(it, version.substring(sep + 1).takeIf { v -> v.isNotBlank() }) }
    }
}

/** Starting point of a sync. */
internal sealed interface SyncStart {
    /** Resume from the saved deltaLink (changes only). */
    data class Incremental(val deltaLink: String) : SyncStart

    /** Full enumeration: first pass, new `$select`, deltaLink absent. */
    data object Full : SyncStart

    /**
     * The signed-in drive is not the one of the index data: nothing is synced or deleted
     * without the user's explicit confirmation.
     */
    data object AccountMismatch : SyncStart
}

/**
 * Chooses the starting point of a sync from the saved cursor. A cursor without a drive (first
 * pass interrupted before binding, old version) adopts the current drive; no data is ever
 * purged here.
 */
internal fun planSyncStart(
    storedLink: String?,
    storedVersion: String?,
    driveId: String,
    selectVersion: String,
    hasIndexedData: Boolean,
): SyncStart {
    val binding = parseCloudCursorVersion(storedVersion)
    return when {
        binding != null && binding.driveId != driveId -> if (hasIndexedData) SyncStart.AccountMismatch else SyncStart.Full
        binding == null -> SyncStart.Full
        binding.selectVersion != selectVersion -> SyncStart.Full
        storedLink.isNullOrBlank() -> SyncStart.Full
        else -> SyncStart.Incremental(storedLink)
    }
}
