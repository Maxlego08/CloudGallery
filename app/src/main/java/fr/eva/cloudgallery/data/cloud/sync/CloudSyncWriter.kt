package fr.eva.cloudgallery.data.cloud.sync

import android.util.Log
import androidx.room.withTransaction
import fr.eva.cloudgallery.data.cloud.graph.DriveItemDto
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.cloud.offline.planGraceExpiry
import fr.eva.cloudgallery.data.cloud.offline.requeueOnCTagChange
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CloudFolderEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.dedup.MediaMerger
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracking of a sync in progress (one instance per pass).
 *
 * During a full enumeration, received items and folders are remembered: those of the index that
 * were not seen are hidden at the end of the pass. Deleted folders are only processed at the end
 * of the pass (a folder may reappear further along in the delta feed).
 */
internal class CloudSyncRun(full: Boolean) {
    var full: Boolean = full
        private set

    /** Identifiers of received media (full enumeration only). */
    var seen: MutableSet<String>? = if (full) HashSet() else null
        private set

    /** Identifiers of received folders (full enumeration only). */
    var seenFolders: MutableSet<String>? = if (full) HashSet() else null
        private set

    /** Folders known to the index reported deleted and not reappeared since. */
    val removedFolders: MutableSet<String> = HashSet()

    /**
     * A folder marked deleted that contains hidden items, directly or in its subfolders (or, for
     * data from before v5, a new folder while hidden items no longer have a known folder), has
     * reappeared (restored from the recycle bin): its items are not necessarily returned one by
     * one, so a full enumeration is required. The saved deltaLink is cleared in the same
     * transaction as the page: even if interrupted, the next pass will be a full enumeration.
     */
    var restoredFolderWithHiddenItems: Boolean = false

    /**
     * Operations on [seen] / [seenFolders] for the current page (full enumeration), for the
     * resume journal: collected by [drainPageOps] after the page's transaction.
     */
    private val pageOps = ArrayList<SeenEntry>()

    fun markSeen(itemId: String) {
        val set = seen ?: return
        set += itemId
        pageOps += SeenEntry(SeenOp.MEDIA, itemId)
    }

    fun markSeenFolder(itemId: String) {
        val set = seenFolders ?: return
        set += itemId
        pageOps += SeenEntry(SeenOp.FOLDER, itemId)
    }

    /** Item reported deleted: removed from the seen items and folders. */
    fun markRemoved(itemId: String) {
        if (seen == null && seenFolders == null) return
        seen?.remove(itemId)
        seenFolders?.remove(itemId)
        pageOps += SeenEntry(SeenOp.REMOVE, itemId)
    }

    /** Operations of the applied page (drained). */
    fun drainPageOps(): List<SeenEntry> = pageOps.toList().also { pageOps.clear() }

    /** Switches to a full enumeration (expired token, restored folder). */
    fun restartFull() {
        full = true
        seen = HashSet()
        seenFolders = HashSet()
        pageOps.clear()
        restoredFolderWithHiddenItems = false
    }

    companion object {
        /** Resumed full enumeration: seen items and folders replayed from the journal. */
        fun resumed(seen: Set<String>, seenFolders: Set<String>): CloudSyncRun = CloudSyncRun(full = true).apply {
            this.seen = HashSet(seen)
            this.seenFolders = HashSet(seenFolders)
        }
    }
}

/** Labels of OneDrive albums (resolved by the caller in the current language). */
internal data class CloudFolderLabels(val root: String, val unnamed: String)

/**
 * Writes the OneDrive sync into the index (Room database only, no network or file access).
 * Each page is applied in one transaction: media, source and denormalized flags are always
 * consistent (orphan cleanup by the local indexing is global). A new OneDrive item creates its
 * own media; attaching it to an identical local file (same QuickXorHash and same size, never by
 * name) is done at the end of the pass by [MediaMerger]. A merged media keeps the metadata of
 * its local file. Nothing is deleted directly: a vanished source is hidden, then purged after
 * the grace period.
 *
 * Offline copies (OFFLINE_COPY): hidden and shown again along with their OneDrive item; a
 * deleted item that has a copy is not purged at the end of the grace period (awaiting the user's
 * decision); modified content queues the download of the new version (started the next time the
 * app comes to the foreground). No file is touched here.
 */
@Singleton
class CloudSyncWriter @Inject constructor(
    private val db: AppDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val merger: MediaMerger,
) {
    private val dedupDao = db.dedupDao()
    private val mediaDao = db.mediaDao()
    private val sourceDao = db.mediaSourceDao()
    private val albumDao = db.albumDao()
    private val folderDao = db.cloudFolderDao()
    private val cursorDao = db.syncCursorDao()
    private val taskDao = db.downloadTaskDao()
    private val cachedFileDao = db.cachedFileDao()

    /**
     * True if the index contains OneDrive data visible to the user or attached to their
     * favorites and albums: sources (even hidden) or OneDrive albums. Folder rows alone don't
     * count (they are recomputed by a full enumeration).
     */
    suspend fun hasIndexedData(): Boolean = withContext(io) {
        sourceDao.existsAny(SourceKind.ONEDRIVE) || albumDao.existsByType(AlbumType.CLOUD_FOLDER)
    }

    /** Visible OneDrive items. */
    suspend fun visibleCount(): Int = withContext(io) { sourceDao.count(SourceKind.ONEDRIVE) }

    /** Applies one page of the delta query. */
    internal suspend fun applyPage(items: List<DriveItemDto>, run: CloudSyncRun, now: Long) = withContext(io) {
        if (items.isEmpty()) return@withContext
        val actions = lastOccurrenceById(items).map(::classifyDeltaItem)
        db.withTransaction {
            val touched = HashSet<Long>()
            applyFolders(actions.filterIsInstance<DeltaAction.UpsertFolder>().map { it.folder }, run, now)
            applyRemovals(actions.filterIsInstance<DeltaAction.Remove>().map { it.itemId }, run, now, touched)
            applyMedia(actions.filterIsInstance<DeltaAction.UpsertMedia>().map { it.media }, run, now, touched)
            refreshTouched(touched)
        }
    }

    /**
     * Offline copies aligned with the visibility of their OneDrive item (hidden with it, visible
     * if it comes back), then denormalized flags recomputed.
     */
    private suspend fun refreshTouched(touched: Collection<Long>) {
        for (chunk in touched.chunked(CHUNK)) {
            sourceDao.syncOfflineCopyVisibility(chunk)
            mediaDao.refreshSourceFlags(chunk)
        }
    }

    private suspend fun applyFolders(folders: List<CloudFolderFields>, run: CloudSyncRun, now: Long) {
        if (folders.isEmpty()) return
        for (chunk in folders.chunked(CHUNK)) {
            val ids = chunk.map { it.itemId }
            if (!run.full && !run.restoredFolderWithHiddenItems) {
                // Folder marked deleted that reappears (restored from the recycle bin) with hidden
                // items in its subtree: its items are not necessarily returned one by one. A truly
                // new folder (no marker) triggers nothing, unless hidden items without a known
                // folder remain (folders deleted before v5, without marker: broad test, as
                // before).
                val markers = folderDao.markedIds(ids).toSet()
                val restoredWithHidden = markers.isNotEmpty() &&
                    subtreeHasHiddenItems(markers, folderDao.getAll().map { it.toFields() }, hiddenFolderIds())
                val known = folderDao.knownIds(ids).toSet()
                val newKeys = ids.filter { it !in known }.mapNotNull { cloudBucketKey(it) }
                if (restoredWithHidden || (newKeys.isNotEmpty() && sourceDao.existsHiddenCloudInBucketsOrOrphaned(newKeys))) {
                    run.restoredFolderWithHiddenItems = true
                    // Durable decision: saved with the folder, in the page's transaction.
                    cursorDao.clearLink(ONEDRIVE_PROVIDER)
                }
            }
            // deletedAt null: a marked folder that comes back is present again.
            folderDao.upsertAll(
                chunk.map { CloudFolderEntity(itemId = it.itemId, parentId = it.parentId, name = it.name, isRoot = it.isRoot, updatedAt = now) },
            )
        }
        for (f in folders) {
            run.removedFolders.remove(f.itemId)
            run.markSeenFolder(f.itemId)
        }
    }

    private suspend fun applyRemovals(ids: List<String>, run: CloudSyncRun, now: Long, touched: MutableSet<Long>) {
        if (ids.isEmpty()) return
        for (chunk in ids.chunked(CHUNK)) {
            // Folder or file: according to the index (a deleted item doesn't always have the folder facet).
            val folders = folderDao.knownIds(chunk).toSet()
            run.removedFolders += folders
            val sources = sourceDao.findAll(SourceKind.ONEDRIVE, chunk.filter { it !in folders })
            if (sources.isNotEmpty()) {
                sourceDao.markMissing(sources.map { it.id }, now)
                touched += sources.map { it.mediaId }
            }
        }
        for (id in ids) run.markRemoved(id)
    }

    private suspend fun applyMedia(items: List<CloudMediaFields>, run: CloudSyncRun, now: Long, touched: MutableSet<Long>) {
        if (items.isEmpty()) return
        for (chunk in items.chunked(CHUNK)) {
            val itemIds = chunk.map { it.itemId }
            val sources = sourceDao.findAll(SourceKind.ONEDRIVE, itemIds).associateBy { it.externalId }
            // Offline copies of these items (same OneDrive identifier).
            val offline = sourceDao.findAll(SourceKind.OFFLINE_COPY, itemIds).associateBy { it.externalId }
            val medias = mediaDao.getByIds((sources.values.map { it.mediaId } + offline.values.map { it.mediaId }).distinct())
                .associateBy { it.id }
            // Media merged with a local file: their metadata (EXIF date, dimensions, name) stay
            // those of the local file, never replaced by Graph's.
            val merged = if (medias.isEmpty()) emptySet() else dedupDao.withLocalSource(medias.keys.toList()).toSet()
            val redownload = ArrayList<DownloadTaskEntity>()
            for (fields in chunk) {
                val source = sources[fields.itemId]
                val media = source?.let { medias[it.mediaId] }
                val copy = offline[fields.itemId]
                if (source != null && media != null) {
                    val updatedMedia = fields.applyTo(media)
                    if (media.id !in merged && updatedMedia != media) mediaDao.update(updatedMedia)
                    val updatedSource = fields.applyTo(source)
                    if (updatedSource != source) {
                        sourceDao.update(updatedSource)
                        touched += media.id
                    }
                    if (source.cTag != fields.cTag) {
                        // Content modified: the temporary original is stale (its file, without a
                        // row, goes away at the next cleanup).
                        cachedFileDao.deleteForMedia(listOf(media.id), CacheKind.ORIGINAL)
                    }
                    val hasCopy = copy != null && copy.mediaId == media.id
                    if (requeueOnCTagChange(hasCopy, copy?.cTag, fields.cTag)) {
                        // Stale offline copy: new download (the old one keeps being served until
                        // the new one replaces it).
                        redownload += redownloadTask(media.id, fields, now)
                    }
                } else {
                    // Orphan source impossible (cascading foreign key); replaced as a precaution.
                    if (source != null) sourceDao.deleteByIds(listOf(source.id))
                    val copyMedia = copy?.let { medias[it.mediaId] }
                    val mediaId = if (copy != null && copyMedia != null) {
                        // Item came back on OneDrive while its offline copy was kept: attached to
                        // the same media (favorites, albums), never duplicated.
                        val updatedMedia = fields.applyTo(copyMedia)
                        if (copyMedia.id !in merged && updatedMedia != copyMedia) mediaDao.update(updatedMedia)
                        if (requeueOnCTagChange(true, copy.cTag, fields.cTag)) {
                            redownload += redownloadTask(copyMedia.id, fields, now)
                        }
                        copyMedia.id
                    } else {
                        mediaDao.insert(fields.toMediaEntity())
                    }
                    sourceDao.insert(fields.toSourceEntity(mediaId))
                    touched += mediaId
                }
                run.markSeen(fields.itemId)
            }
            if (redownload.isNotEmpty()) taskDao.enqueueIfAbsent(redownload)
        }
    }

    /**
     * End of pass (after the last page): hides items missing from a full enumeration and the
     * content of deleted folders, purges after the grace period, then OneDrive albums.
     */
    internal suspend fun finishSync(run: CloudSyncRun, now: Long, labels: CloudFolderLabels) = withContext(io) {
        applyFinish(run, now, labels)
        // Merges OneDrive items with identical local files (and unmerges those whose content
        // changed): outside the pass's transaction, which stays valid if this fails.
        try {
            merger.reconcile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Deduplication after OneDrive sync failed", e)
        }
    }

    private suspend fun applyFinish(run: CloudSyncRun, now: Long, labels: CloudFolderLabels) {
        db.withTransaction {
            val touched = HashSet<Long>()

            // 1. Items: hidden if missing from a full enumeration, purged after the grace period.
            val stamps = sourceDao.stamps(SourceKind.ONEDRIVE).map { CloudStamp(it.id, it.mediaId, it.externalId, it.missingSince) }
            val plan = planCloudRemovals(stamps, run.seen, now)
            // Decision D1: an item kept offline is never purged without the user's say; it stays
            // hidden, awaiting their decision (Account screen).
            val expiry = planGraceExpiry(plan.purge, sourceDao.mediaIdsByKind(SourceKind.OFFLINE_COPY).toSet())
            for (chunk in plan.hide.chunked(CHUNK)) sourceDao.markMissing(chunk.map { it.sourceId }, now)
            for (chunk in expiry.purge.chunked(CHUNK)) {
                sourceDao.deleteByIds(chunk.map { it.sourceId })
                taskDao.deleteForMedia(chunk.map { it.mediaId }.distinct())
            }
            plan.hide.mapTo(touched) { it.mediaId }
            expiry.purge.mapTo(touched) { it.mediaId }

            // 2. Deleted folders (still missing at the end of the pass): their content is hidden.
            val folderRows = folderDao.getAll()
            val folders = folderRows.map { it.toFields() }
            val knownIds = folders.mapTo(HashSet()) { it.itemId }
            val seenFolders = run.seenFolders
            val goneFolders: Set<String> = if (seenFolders != null) {
                // Full enumeration: unseen items are already hidden in step 1.
                knownIds - seenFolders
            } else {
                descendantFolderIds(run.removedFolders.filterTo(HashSet()) { it in knownIds }, folders)
            }
            if (seenFolders == null && goneFolders.isNotEmpty()) {
                for (chunk in goneFolders.mapNotNull { cloudBucketKey(it) }.chunked(CHUNK)) {
                    touched += sourceDao.visibleCloudMediaIdsInBuckets(chunk)
                    sourceDao.markMissingInBuckets(chunk, now)
                }
            }

            refreshTouched(touched)
            mediaDao.deleteOrphans()
            albumDao.clearDanglingCovers()

            // 3. Deletion markers: a deleted (or already marked) folder stays marked as long as
            // its subtree has hidden items, otherwise its row is removed. An unseen folder that
            // still has visible items (edge case) keeps its row as is.
            val keepVisible = goneFolders.filterTo(HashSet()) { id ->
                cloudBucketKey(id)?.let { sourceDao.existsVisibleCloudInBucket(it) } ?: false
            }
            val tombstones = planFolderTombstones(
                folders = folders,
                markers = folderRows.filter { it.deletedAt != null }.mapTo(HashSet()) { it.itemId },
                gone = goneFolders,
                keepVisible = keepVisible,
                hiddenFolders = hiddenFolderIds(),
            )
            for (chunk in tombstones.mark.chunked(CHUNK)) folderDao.markDeleted(chunk, now)
            for (chunk in tombstones.delete.chunked(CHUNK)) folderDao.deleteByIds(chunk)

            syncCloudFolderAlbums(labels, now)
        }
    }

    /** Identifiers of folders containing hidden OneDrive sources. */
    private suspend fun hiddenFolderIds(): Set<String> =
        sourceDao.hiddenCloudBuckets().mapNotNullTo(HashSet()) { folderIdOfCloudBucket(it) }

    /**
     * OneDrive albums: one per folder containing visible items (a folder marked deleted has none,
     * so it is never shown). Name and path follow renames; an album without user customization
     * disappears with its folder.
     */
    private suspend fun syncCloudFolderAlbums(labels: CloudFolderLabels, now: Long) {
        val buckets = sourceDao.visibleBuckets(SourceKind.ONEDRIVE)
        val folders = folderDao.getAll().associate { it.itemId to it.toFields() }
        val albums = albumDao.getByType(AlbumType.CLOUD_FOLDER)
        val keys = buckets.mapTo(HashSet()) { it.bucketKey }
        val stale = albums.filter { it.sourceKey == null || it.sourceKey !in keys }.map { it.id }
        for (chunk in stale.chunked(CHUNK)) albumDao.deleteUncustomizedFolders(chunk, AlbumType.CLOUD_FOLDER)
        val byKey = albums.filter { it.sourceKey != null }.associateBy { it.sourceKey!! }
        for (bucket in buckets) {
            val key = bucket.bucketKey
            val folderId = folderIdOfCloudBucket(key) ?: continue
            val name = cloudFolderAlbumName(folders[folderId], labels.root, labels.unnamed)
            val path = cloudFolderPath(folderId, folders, labels.root)
            sourceDao.updateCloudBucketInfo(key, name, path)
            val album = byKey[key]
            if (album == null) {
                // An album of another type may already hold this unique key.
                if (albumDao.getBySourceKey(key) != null) continue
                albumDao.insert(
                    AlbumEntity(
                        name = name,
                        type = AlbumType.CLOUD_FOLDER,
                        sourceKey = key,
                        relativePath = path,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else if (album.name != name || album.relativePath != path) {
                albumDao.updateFolderInfo(album.id, name, path, now)
            }
        }
    }

    private companion object {
        const val TAG = "CloudSyncWriter"
        const val CHUNK = 500

        fun CloudFolderEntity.toFields() = CloudFolderFields(itemId = itemId, parentId = parentId, name = name, isRoot = isRoot)

        fun CloudMediaFields.toMediaEntity() = MediaEntity(
            mediaType = type,
            displayName = name,
            mimeType = mimeType,
            dateTaken = dateTakenMs,
            dateAdded = dateAddedMs,
            dateModified = dateModifiedMs,
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            // Graph thumbnails are already oriented.
            orientation = 0,
            primaryUri = null,
            hasLocal = false,
            hasCloud = true,
            hasOffline = false,
            contentHash = contentHash,
        )

        fun CloudMediaFields.applyTo(media: MediaEntity) = media.copy(
            mediaType = type,
            displayName = name,
            mimeType = mimeType,
            dateTaken = dateTakenMs,
            dateAdded = dateAddedMs,
            dateModified = dateModifiedMs,
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            contentHash = contentHash,
        )

        fun CloudMediaFields.toSourceEntity(mediaId: Long) = MediaSourceEntity(
            mediaId = mediaId,
            kind = SourceKind.ONEDRIVE,
            externalId = itemId,
            uri = null,
            bucketKey = bucketKey,
            // Folder name and path filled in at the end of the pass (OneDrive albums).
            bucketName = null,
            relativePath = null,
            sizeBytes = sizeBytes,
            dateModified = dateModifiedMs,
            eTag = eTag,
            cTag = cTag,
            sha1Hash = sha1Hash,
            quickXorHash = quickXorHash,
            missingSince = null,
        )

        /** Download of a new version of an offline copy. */
        fun redownloadTask(mediaId: Long, fields: CloudMediaFields, now: Long) = DownloadTaskEntity(
            mediaId = mediaId,
            sourceId = null,
            state = DownloadState.QUEUED,
            bytesDownloaded = 0,
            bytesTotal = fields.sizeBytes.coerceAtLeast(0),
            keepOffline = true,
            createdAt = now,
            updatedAt = now,
            cloudItemId = fields.itemId,
            cTag = fields.cTag,
        )

        /** Update of an existing source; an item that came back (restored) becomes visible again. */
        fun CloudMediaFields.applyTo(source: MediaSourceEntity): MediaSourceEntity {
            val sameBucket = source.bucketKey == bucketKey
            return source.copy(
                bucketKey = bucketKey,
                bucketName = if (sameBucket) source.bucketName else null,
                relativePath = if (sameBucket) source.relativePath else null,
                sizeBytes = sizeBytes,
                dateModified = dateModifiedMs,
                eTag = eTag,
                cTag = cTag,
                sha1Hash = sha1Hash,
                quickXorHash = quickXorHash,
                missingSince = null,
            )
        }
    }
}
