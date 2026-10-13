package fr.eva.cloudgallery.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.db.entity.PlaybackPositionEntity
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local / OneDrive merging on an in-memory database: only on identical hash and
 * size, transfer of favorites, albums, covers, caches, downloads and playback
 * position; unmerging when the content diverges; folder album membership.
 */
@RunWith(AndroidJUnit4::class)
class MediaMergerTest {

    private lateinit var db: AppDatabase
    private lateinit var merger: MediaMerger

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        merger = MediaMerger(db, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun media(name: String, size: Long, local: Boolean, dateTaken: Long = 10) = MediaEntity(
        mediaType = MediaType.IMAGE, displayName = name, mimeType = "image/jpeg", dateTaken = dateTaken, dateAdded = dateTaken,
        dateModified = dateTaken, width = 10, height = 10, durationMs = 0, sizeBytes = size, orientation = if (local) 90 else 0,
        primaryUri = if (local) "content://media/external/images/media/$name" else null,
        hasLocal = local, hasCloud = !local, hasOffline = false,
    )

    /** Local file, hash up to date by default ([hashCurrent] false: stale signature). */
    private suspend fun seedLocal(name: String, hash: String?, size: Long = 100, hashCurrent: Boolean = true, bucket: String = "local:1"): Long {
        val id = db.mediaDao().insert(media(name, size, local = true).copy(contentHash = contentHashOf(hash)))
        db.mediaSourceDao().insert(
            MediaSourceEntity(
                mediaId = id, kind = SourceKind.LOCAL, externalId = "img:$name", uri = "content://media/external/images/media/$name",
                bucketKey = bucket, bucketName = "Camera", relativePath = "DCIM/Camera/", sizeBytes = size, dateModified = 10,
                generation = 3, quickXorHash = hash,
                hashSignature = hash?.let { if (hashCurrent) localHashSignature(size, 10, 3) else "0:0:0" },
            ),
        )
        db.mediaDao().refreshSourceFlags(listOf(id))
        return id
    }

    private suspend fun seedCloud(itemId: String, hash: String?, size: Long = 100, bucket: String = "onedrive:PH"): Long {
        val id = db.mediaDao().insert(media("$itemId.jpg", size, local = false).copy(contentHash = contentHashOf(hash)))
        db.mediaSourceDao().insert(
            MediaSourceEntity(
                mediaId = id, kind = SourceKind.ONEDRIVE, externalId = itemId, uri = null, bucketKey = bucket,
                bucketName = "Photos", relativePath = null, sizeBytes = size, dateModified = 20, cTag = "c-$itemId", quickXorHash = hash,
            ),
        )
        db.mediaDao().refreshSourceFlags(listOf(id))
        return id
    }

    private suspend fun sourcesOf(mediaId: Long) = db.mediaSourceDao().getForMedia(mediaId).map { it.kind }.sorted()

    private suspend fun cloudMediaOf(itemId: String) = db.mediaSourceDao().find(SourceKind.ONEDRIVE, itemId)!!.mediaId

    @Test
    fun merge_sameHashAndSize_attachesTheCloudItemToTheLocalMedia() = runTest {
        val local = seedLocal("a", "H1")
        val cloud = seedCloud("A", "H1")
        assertEquals(1, merger.reconcile())

        assertNull(db.mediaDao().getById(cloud))
        assertEquals(listOf(SourceKind.LOCAL, SourceKind.ONEDRIVE), sourcesOf(local))
        val merged = db.mediaDao().getById(local)!!
        assertTrue(merged.hasLocal && merged.hasCloud)
        // The phone's metadata and file are kept.
        assertEquals("a", merged.displayName)
        assertEquals(90, merged.orientation)
        assertNotNull(merged.primaryUri)
        assertEquals("A", merged.cloudItemId)
        // A single media in Photos, even with OneDrive shown.
        assertEquals(listOf(local), db.mediaDao().observeTimelineIds(includeCloudOnly = true).first())
        // Idempotent.
        assertEquals(0, merger.reconcile())
    }

    @Test
    fun noMerge_withoutIdenticalHashAndSize_orWithAStaleLocalHash() = runTest {
        seedLocal("sameName", "H1")
        seedCloud("sameName", "H2")
        seedLocal("b", "H3", size = 100)
        seedCloud("B", "H3", size = 101)
        seedLocal("c", "H4", hashCurrent = false)
        seedCloud("C", "H4")
        seedLocal("d", null)
        seedCloud("D", null)
        assertEquals(0, merger.reconcile())
        assertEquals(8, db.mediaDao().observeTimelineIds(includeCloudOnly = true).first().size)
    }

    @Test
    fun merge_transfersFavoriteAlbumsCoverCachesTasksAndPlayback() = runTest {
        val local = seedLocal("a", "H1")
        val cloud = seedCloud("A", "H1")
        db.favoriteDao().insertAll(listOf(FavoriteEntity(cloud, addedAt = 5)))
        val album = db.albumDao().insert(AlbumEntity(name = "Vacances", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 1, updatedAt = 1, coverMediaId = cloud))
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(album, cloud, addedAt = 7)))
        db.mediaSourceDao().insert(
            MediaSourceEntity(mediaId = cloud, kind = SourceKind.OFFLINE_COPY, externalId = "A", uri = null, bucketKey = null, bucketName = null, relativePath = null, sizeBytes = 100, dateModified = 20, cTag = "c-A"),
        )
        db.cachedFileDao().upsert(CachedFileEntity(mediaId = cloud, sourceId = null, kind = CacheKind.OFFLINE, path = "A.jpg", sizeBytes = 100, createdAt = 1, lastAccessAt = 1, pinned = true, version = "c-A"))
        val task = db.downloadTaskDao().insert(DownloadTaskEntity(mediaId = cloud, sourceId = null, state = DownloadState.DONE, bytesDownloaded = 100, bytesTotal = 100, keepOffline = true, createdAt = 1, updatedAt = 1, cloudItemId = "A"))
        db.playbackPositionDao().upsert(PlaybackPositionEntity(cloud, positionMs = 3_000, durationMs = 9_000, updatedAt = 1))

        assertEquals(1, merger.reconcile())

        assertTrue(db.favoriteDao().isFavorite(local))
        assertEquals(listOf(local), db.mediaDao().observeAlbumIds(album).first())
        assertEquals(local, db.albumDao().getById(album)!!.coverMediaId)
        assertEquals("A.jpg", db.cachedFileDao().get(local, CacheKind.OFFLINE)?.path)
        assertEquals(listOf(SourceKind.LOCAL, SourceKind.OFFLINE_COPY, SourceKind.ONEDRIVE), sourcesOf(local))
        assertTrue(db.mediaDao().getById(local)!!.hasOffline)
        assertEquals(3_000L, db.playbackPositionDao().get(local))
        assertEquals(local, db.downloadTaskDao().getById(task)?.mediaId)
    }

    @Test
    fun merge_withARunningDownload_waits() = runTest {
        seedLocal("a", "H1")
        val cloud = seedCloud("A", "H1")
        db.downloadTaskDao().insert(DownloadTaskEntity(mediaId = cloud, sourceId = null, state = DownloadState.RUNNING, bytesDownloaded = 0, bytesTotal = 100, keepOffline = true, createdAt = 1, updatedAt = 1, cloudItemId = "A"))
        assertEquals(0, merger.reconcile())
        assertNotNull(db.mediaDao().getById(cloud))
    }

    @Test
    fun merge_duplicates_pairDeterministically() = runTest {
        val first = seedLocal("a1", "H1")
        val second = seedLocal("a2", "H1")
        seedCloud("A1", "H1")
        seedCloud("A2", "H1")
        seedCloud("A3", "H1")
        assertEquals(2, merger.reconcile())
        assertEquals(first, cloudMediaOf("A1"))
        assertEquals(second, cloudMediaOf("A2"))
        // Surplus item: stays a separate OneDrive media.
        val extra = cloudMediaOf("A3")
        assertNotEquals(first, extra)
        assertNotEquals(second, extra)
    }

    @Test
    fun split_whenTheCloudContentChanges_keepsFavoritesOnTheLocalMedia() = runTest {
        val local = seedLocal("a", "H1")
        seedCloud("A", "H1")
        merger.reconcile()
        db.favoriteDao().insertAll(listOf(FavoriteEntity(local, addedAt = 5)))
        db.cachedFileDao().upsert(CachedFileEntity(mediaId = local, sourceId = null, kind = CacheKind.ORIGINAL, path = "A.jpg", sizeBytes = 100, createdAt = 1, lastAccessAt = 1, pinned = false, version = "c-A"))
        // New version on OneDrive (sync): different hash, same size.
        val source = db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!
        db.mediaSourceDao().update(source.copy(quickXorHash = "H9", cTag = "c2"))

        merger.reconcile()

        val cloud = cloudMediaOf("A")
        assertNotEquals(local, cloud)
        assertEquals(listOf(SourceKind.LOCAL), sourcesOf(local))
        assertTrue(db.favoriteDao().isFavorite(local))
        assertFalse(db.favoriteDao().isFavorite(cloud))
        val cloudMedia = db.mediaDao().getById(cloud)!!
        assertTrue(cloudMedia.hasCloud && !cloudMedia.hasLocal)
        assertEquals(0, cloudMedia.orientation)
        assertEquals("qxh:H9", cloudMedia.contentHash)
        assertEquals("A.jpg", db.cachedFileDao().get(cloud, CacheKind.ORIGINAL)?.path)
        assertNull(db.cachedFileDao().get(local, CacheKind.ORIGINAL))
    }

    @Test
    fun split_whenTheLocalSizeChanges_butNotForAStaleHashOfTheSameSize() = runTest {
        val local = seedLocal("a", "H1")
        seedCloud("A", "H1")
        merger.reconcile()
        val source = db.mediaSourceDao().find(SourceKind.LOCAL, "img:a")!!
        // File touched without a size change (stale signature): merge kept.
        db.mediaSourceDao().update(source.copy(generation = 4))
        merger.reconcile()
        assertEquals(local, cloudMediaOf("A"))
        // Size changed: content necessarily different, merge undone right away.
        db.mediaSourceDao().update(source.copy(generation = 5, sizeBytes = 120))
        merger.reconcile()
        assertNotEquals(local, cloudMediaOf("A"))
    }

    @Test
    fun folderAlbums_mergedMediaBelongsOnlyToTheFoldersOfItsVisibleSources() = runTest {
        val local = seedLocal("a", "H1", bucket = "local:1")
        seedCloud("A", "H1", bucket = "onedrive:PH")
        merger.reconcile()
        val camera = db.albumDao().insert(AlbumEntity(name = "Camera", type = AlbumType.LOCAL_FOLDER, sourceKey = "local:1", relativePath = null, createdAt = 1, updatedAt = 1))
        val photos = db.albumDao().insert(AlbumEntity(name = "Photos", type = AlbumType.CLOUD_FOLDER, sourceKey = "onedrive:PH", relativePath = null, createdAt = 1, updatedAt = 1))
        assertEquals(listOf(local), db.mediaDao().observeAlbumIds(camera).first())
        assertEquals(listOf(local), db.mediaDao().observeAlbumIds(photos).first())

        // Item deleted from OneDrive: the media stays in its phone folder only.
        val cloudSource = db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!
        db.mediaSourceDao().markMissing(listOf(cloudSource.id), now = 50)
        db.mediaDao().refreshSourceFlags(listOf(local))
        assertEquals(listOf(local), db.mediaDao().observeAlbumIds(camera).first())
        assertTrue(db.mediaDao().observeAlbumIds(photos).first().isEmpty())
        assertEquals(0, db.albumDao().observeAlbumRow(photos).first()!!.mediaCount)
        assertEquals(1, db.albumDao().observeAlbumRow(camera).first()!!.mediaCount)
    }

    @Test
    fun virtualAlbum_keepsAMergedMediaWhileOneSourceRemains() = runTest {
        val local = seedLocal("a", "H1")
        seedCloud("A", "H1")
        merger.reconcile()
        val album = db.albumDao().insert(AlbumEntity(name = "Mix", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 1, updatedAt = 1))
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(album, local, addedAt = 1)))
        val localSource = db.mediaSourceDao().find(SourceKind.LOCAL, "img:a")!!
        // File sent to the phone's trash: the OneDrive item keeps it displayed.
        db.mediaSourceDao().markMissing(listOf(localSource.id), now = 50)
        db.mediaDao().refreshSourceFlags(listOf(local))
        assertEquals(listOf(local), db.mediaDao().observeAlbumIds(album).first())
        // No visible source left: hidden (existing behavior), nothing is deleted.
        val cloudSource = db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!
        db.mediaSourceDao().markMissing(listOf(cloudSource.id), now = 60)
        db.mediaDao().refreshSourceFlags(listOf(local))
        assertTrue(db.mediaDao().observeAlbumIds(album).first().isEmpty())
        assertNotNull(db.mediaDao().getById(local))
    }

    @Test
    fun localHash_isRecordedOnlyForAnUnchangedFile() = runTest {
        val local = seedLocal("a", null)
        val dedup = db.dedupDao()
        val target = dedup.staleLocalHashes().single()
        assertEquals(local, target.mediaId)
        assertEquals(1, dedup.setLocalHash(target.sourceId, "H1", target.signature))
        assertTrue(dedup.staleLocalHashes().isEmpty())
        // Signature read before a modification: nothing is written.
        val source = db.mediaSourceDao().getById(target.sourceId)!!
        db.mediaSourceDao().update(source.copy(generation = 9))
        assertEquals(0, dedup.setLocalHash(target.sourceId, "H2", target.signature))
        assertEquals(1, dedup.staleLocalHashes().size)
    }
}
