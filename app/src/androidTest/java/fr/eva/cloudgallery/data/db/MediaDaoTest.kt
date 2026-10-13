package fr.eva.cloudgallery.data.db

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaRow
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun media(dateTaken: Long, type: MediaType = MediaType.IMAGE) = MediaEntity(
        mediaType = type,
        displayName = "m$dateTaken",
        mimeType = if (type == MediaType.IMAGE) "image/jpeg" else "video/mp4",
        dateTaken = dateTaken,
        dateAdded = dateTaken,
        dateModified = dateTaken,
        width = 100,
        height = 100,
        durationMs = 0,
        sizeBytes = 10,
        orientation = 0,
        primaryUri = null,
        hasLocal = false,
        hasCloud = false,
        hasOffline = false,
    )

    private fun localSource(mediaId: Long, storeId: Long, bucket: Long) = MediaSourceEntity(
        mediaId = mediaId,
        kind = SourceKind.LOCAL,
        externalId = "image:$storeId",
        uri = "content://media/external/images/media/$storeId",
        bucketKey = "local:$bucket",
        bucketName = "Bucket$bucket",
        relativePath = "Pictures/Bucket$bucket/",
        sizeBytes = 10,
        dateModified = 0,
        generation = storeId,
    )

    /** Insère 3 médias : a (bucket 1), b (bucket 1, même date que a), c (bucket 2, plus récent). */
    private suspend fun seed(): Triple<Long, Long, Long> {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val a = mediaDao.insert(media(1_000))
        val b = mediaDao.insert(media(1_000))
        val c = mediaDao.insert(media(5_000, MediaType.VIDEO))
        sourceDao.insert(localSource(a, 11, 1))
        sourceDao.insert(localSource(b, 12, 1))
        sourceDao.insert(localSource(c, 13, 2).copy(externalId = "video:13"))
        mediaDao.refreshSourceFlags(listOf(a, b, c))
        return Triple(a, b, c)
    }

    private suspend fun loadAll(source: PagingSource<Int, MediaRow>): List<MediaRow> {
        val result = source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false))
        assertTrue("Chargement en échec : $result", result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun pagingTimeline_ordersByDateTakenThenIdDesc() = runTest {
        val (a, b, c) = seed()
        val rows = loadAll(db.mediaDao().pagingTimeline(includeCloudOnly = true))
        assertEquals(listOf(c, b, a), rows.map { it.id })
        assertTrue(rows.all { it.hasLocal && it.primaryUri != null })
        assertEquals(listOf(c, b, a), db.mediaDao().observeTimelineIds(includeCloudOnly = true).first())
        assertEquals(listOf(c), loadAll(db.mediaDao().pagingVideos(includeCloudOnly = true)).map { it.id })
    }

    @Test
    fun favoriteFlagIsProjected() = runTest {
        val (a, _, _) = seed()
        db.favoriteDao().insertAll(listOf(FavoriteEntity(a, 1)))
        val rows = loadAll(db.mediaDao().pagingFavorites())
        assertEquals(listOf(a), rows.map { it.id })
        assertTrue(rows.single().isFavorite)
        assertEquals(1, db.mediaDao().observeFavoriteCount().first())
    }

    @Test
    fun albumMembership_folderAndVirtual() = runTest {
        val (a, b, c) = seed()
        val albumDao = db.albumDao()
        val folder = albumDao.insert(
            AlbumEntity(name = "Bucket1", type = AlbumType.LOCAL_FOLDER, sourceKey = "local:1", relativePath = "Pictures/Bucket1/", createdAt = 0, updatedAt = 0),
        )
        val virtual = albumDao.insert(
            AlbumEntity(name = "Favoris vacances", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0),
        )
        albumDao.insertMembers(listOf(AlbumMediaEntity(virtual, c, 0), AlbumMediaEntity(virtual, a, 0)))

        assertEquals(listOf(b, a), loadAll(db.mediaDao().pagingAlbum(folder)).map { it.id })
        assertEquals(listOf(c, a), loadAll(db.mediaDao().pagingAlbum(virtual)).map { it.id })
        assertEquals(2, db.mediaDao().observeAlbumCount(folder).first())

        val rows = albumDao.observeAlbumRows().first().associateBy { it.id }
        assertEquals(2, rows.getValue(folder).mediaCount)
        assertEquals(2, rows.getValue(virtual).mediaCount)
        assertEquals(5_000L, rows.getValue(virtual).latestDate)
        assertNotNull(rows.getValue(folder).coverUri)
    }

    @Test
    fun deleteOrphans_removesMediaWithoutSource() = runTest {
        val (a, b, c) = seed()
        val virtual = db.albumDao().insert(
            AlbumEntity(name = "V", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0),
        )
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(virtual, c, 0)))

        val cSource = db.mediaSourceDao().find(SourceKind.LOCAL, "video:13")!!
        db.mediaSourceDao().deleteByIds(listOf(cSource.id))
        assertEquals(1, db.mediaDao().deleteOrphans())

        assertNull(db.mediaDao().getById(c))
        assertEquals(listOf(b, a), db.mediaDao().observeTimelineIds(includeCloudOnly = true).first())
        // La référence de l'album virtuel disparaît en cascade, l'album reste.
        assertEquals(0, db.mediaDao().observeAlbumCount(virtual).first())
        assertNotNull(db.albumDao().getById(virtual))
    }

    @Test
    fun refreshSourceFlags_recomputesDenormalizedColumns() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val id = mediaDao.insert(media(10))
        sourceDao.insert(
            MediaSourceEntity(
                mediaId = id, kind = SourceKind.ONEDRIVE, externalId = "od-1", uri = null,
                bucketKey = "onedrive:root", bucketName = null, relativePath = null, sizeBytes = 0, dateModified = 0,
            ),
        )
        mediaDao.refreshSourceFlags(listOf(id))
        mediaDao.getById(id)!!.let {
            assertFalse(it.hasLocal)
            assertTrue(it.hasCloud)
            assertNull(it.primaryUri)
        }

        sourceDao.insert(localSource(id, 99, 3))
        mediaDao.refreshSourceFlags(listOf(id))
        mediaDao.getById(id)!!.let {
            assertTrue(it.hasLocal)
            assertTrue(it.hasCloud)
            assertFalse(it.hasOffline)
            assertEquals("content://media/external/images/media/99", it.primaryUri)
        }

        val local = sourceDao.find(SourceKind.LOCAL, "image:99")!!
        sourceDao.deleteByIds(listOf(local.id))
        assertEquals(0, mediaDao.deleteOrphans())
        mediaDao.refreshSourceFlags(listOf(id))
        mediaDao.getById(id)!!.let {
            assertFalse(it.hasLocal)
            assertNull(it.primaryUri)
        }
    }

    @Test
    fun getByIdsAndObserveRow() = runTest {
        val (a, b, _) = seed()
        assertEquals(setOf(a, b), db.mediaDao().getByIds(listOf(a, b, 9_999)).map { it.id }.toSet())
        assertEquals(a, db.mediaDao().observeRow(a).first()?.id)
        assertNull(db.mediaDao().observeRow(9_999).first())
    }

    @Test
    fun hiddenSource_hidesMediaButKeepsFavoriteAndAlbum() = runTest {
        val (a, b, _) = seed()
        val virtual = db.albumDao().insert(
            AlbumEntity(name = "V", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0),
        )
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(virtual, a, 0)))
        db.favoriteDao().insertAll(listOf(FavoriteEntity(a, 1)))

        val source = db.mediaSourceDao().find(SourceKind.LOCAL, "image:11")!!
        db.mediaSourceDao().markMissing(listOf(source.id), now = 123)
        db.mediaDao().refreshSourceFlags(listOf(a))

        assertFalse(a in db.mediaDao().observeTimelineIds(includeCloudOnly = true).first())
        assertEquals(0, db.mediaDao().observeFavoriteCount().first())
        assertEquals(0, db.mediaDao().observeAlbumCount(virtual).first())
        // Données de l'utilisateur conservées.
        assertTrue(db.favoriteDao().isFavorite(a))
        assertNotNull(db.mediaDao().getById(a))
        // Le compteur d'éléments indexés ignore les sources masquées.
        assertEquals(2, db.mediaSourceDao().count(SourceKind.LOCAL))
        // markMissing n'écrase pas un horodatage existant.
        db.mediaSourceDao().markMissing(listOf(source.id), now = 999)
        assertEquals(123L, db.mediaSourceDao().getById(source.id)!!.missingSince)

        // Source revenue : média de nouveau visible avec son favori.
        db.mediaSourceDao().update(source.copy(missingSince = null))
        db.mediaDao().refreshSourceFlags(listOf(a))
        assertTrue(a in db.mediaDao().observeTimelineIds(includeCloudOnly = true).first())
        assertEquals(1, db.mediaDao().observeFavoriteCount().first())
        assertTrue(b in db.mediaDao().observeTimelineIds(includeCloudOnly = true).first())
    }

    @Test
    fun staleSources_areRekeyedAndListedWithFingerprintData() = runTest {
        seed()
        val sourceDao = db.mediaSourceDao()
        sourceDao.markLocalStale()
        sourceDao.markLocalStale() // idempotent
        val stale = sourceDao.staleLocal()
        assertEquals(3, stale.size)
        assertNotNull(sourceDao.find(SourceKind.LOCAL, "stale:image:11"))
        assertNull(sourceDao.find(SourceKind.LOCAL, "image:11"))
        assertTrue(stale.all { it.displayName.startsWith("m") && it.relativePath != null })
    }

    @Test
    fun idRanges_followGridOrder() = runTest {
        val (a, b, c) = seed()
        val dao = db.mediaDao()
        assertEquals(listOf(c, b, a), dao.timelineIdsBetween(true, 5_000, c, 1_000, a))
        assertEquals(listOf(b, a), dao.timelineIdsBetween(true, 1_000, b, 1_000, a))
        assertEquals(listOf(b, a), dao.timelineIdsInPeriod(true, 0, 2_000))
        assertEquals(listOf(c), dao.videoIdsInPeriod(true, 0, 10_000))
    }

    @Test
    fun dates_followGridOrderAndFilters() = runTest {
        val (a, _, c) = seed()
        val dao = db.mediaDao()
        val cloudImage = dao.insert(media(9_000))
        db.mediaSourceDao().insert(cloudSource(cloudImage, "od-img"))
        dao.refreshSourceFlags(listOf(cloudImage))
        db.favoriteDao().insertAll(listOf(FavoriteEntity(a, 1)))

        assertEquals(listOf(5_000L, 1_000L, 1_000L), dao.observeTimelineDates(includeCloudOnly = false).first())
        assertEquals(listOf(9_000L, 5_000L, 1_000L, 1_000L), dao.observeTimelineDates(includeCloudOnly = true).first())
        assertEquals(listOf(5_000L), dao.observeVideoDates(includeCloudOnly = false).first())
        assertEquals(listOf(1_000L), dao.observeFavoriteDates().first())
        assertEquals(c, dao.observeTimelineIds(includeCloudOnly = false).first().first())
    }

    @Test
    fun pagingTimeline_initialKey_startsAtRankAndPrependsBefore() = runTest {
        val (a, b, c) = seed()
        val source = db.mediaDao().pagingTimeline(includeCloudOnly = true)
        val result = source.load(PagingSource.LoadParams.Refresh(key = 1, loadSize = 10, placeholdersEnabled = false))
        val page = result as PagingSource.LoadResult.Page
        assertEquals(listOf(b, a), page.data.map { it.id })
        val prevKey = page.prevKey
        assertNotNull(prevKey)
        val before = source.load(PagingSource.LoadParams.Prepend(key = prevKey!!, loadSize = 10, placeholdersEnabled = false))
        assertEquals(listOf(c), (before as PagingSource.LoadResult.Page).data.map { it.id })
    }

    @Test
    fun customizedFolderAlbum_isNeverDeleted() = runTest {
        val albumDao = db.albumDao()
        val plain = albumDao.insert(
            AlbumEntity(name = "A", type = AlbumType.LOCAL_FOLDER, sourceKey = "local:7", relativePath = null, createdAt = 0, updatedAt = 0),
        )
        val renamed = albumDao.insert(
            AlbumEntity(name = "B", type = AlbumType.LOCAL_FOLDER, sourceKey = "local:8", relativePath = null, createdAt = 0, updatedAt = 0, customName = "Vacances"),
        )
        val pinned = albumDao.insert(
            AlbumEntity(name = "C", type = AlbumType.LOCAL_FOLDER, sourceKey = "local:9", relativePath = null, createdAt = 0, updatedAt = 0, isPinned = true),
        )
        albumDao.deleteUncustomizedFolders(listOf(plain, renamed, pinned), AlbumType.LOCAL_FOLDER)
        assertNull(albumDao.getById(plain))
        assertNotNull(albumDao.getById(renamed))
        assertNotNull(albumDao.getById(pinned))
    }

    private fun cloudSource(mediaId: Long, itemId: String, cTag: String = "c1", missingSince: Long? = null) = MediaSourceEntity(
        mediaId = mediaId, kind = SourceKind.ONEDRIVE, externalId = itemId, uri = null,
        bucketKey = "onedrive:root", bucketName = null, relativePath = null, sizeBytes = 0, dateModified = 0,
        cTag = cTag, missingSince = missingSince,
    )

    @Test
    fun refreshSourceFlags_ignoresHiddenCloudSourceAndKeepsFavorite() = runTest {
        val mediaDao = db.mediaDao()
        val id = mediaDao.insert(media(10))
        val sourceId = db.mediaSourceDao().insert(cloudSource(id, "od-1", cTag = "ct"))
        mediaDao.refreshSourceFlags(listOf(id))
        mediaDao.getById(id)!!.let {
            assertTrue(it.hasCloud)
            assertEquals("od-1", it.cloudItemId)
            assertEquals("ct", it.cloudCTag)
            assertNull(it.primaryUri)
        }
        db.favoriteDao().insertAll(listOf(FavoriteEntity(id, 1)))

        db.mediaSourceDao().markMissing(listOf(sourceId), now = 5)
        mediaDao.refreshSourceFlags(listOf(id))
        mediaDao.getById(id)!!.let {
            assertFalse(it.hasCloud)
            assertNull(it.cloudItemId)
            assertNull(it.cloudCTag)
        }
        assertFalse(id in mediaDao.observeTimelineIds(includeCloudOnly = true).first())
        assertEquals(0, mediaDao.observeFavoriteCount().first())
        assertTrue(db.favoriteDao().isFavorite(id))
    }

    @Test
    fun refreshSourceFlags_hiddenLocalSourceGivesNoUri() = runTest {
        val (a, _, _) = seed()
        val source = db.mediaSourceDao().find(SourceKind.LOCAL, "image:11")!!
        db.mediaSourceDao().markMissing(listOf(source.id), now = 5)
        db.mediaDao().refreshSourceFlags(listOf(a))
        db.mediaDao().getById(a)!!.let {
            assertFalse(it.hasLocal)
            assertNull(it.primaryUri)
        }
    }

    @Test
    fun cloudOnlyMedia_followPhotosSettingButStayInFavoritesAndAlbums() = runTest {
        val (a, _, _) = seed()
        val mediaDao = db.mediaDao()
        val cloudImage = mediaDao.insert(media(9_000))
        val cloudVideo = mediaDao.insert(media(9_500, MediaType.VIDEO))
        db.mediaSourceDao().insert(cloudSource(cloudImage, "od-img"))
        db.mediaSourceDao().insert(cloudSource(cloudVideo, "od-vid"))
        mediaDao.refreshSourceFlags(listOf(cloudImage, cloudVideo))
        db.favoriteDao().insertAll(listOf(FavoriteEntity(cloudImage, 1)))
        val virtual = db.albumDao().insert(
            AlbumEntity(name = "V", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0),
        )
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(virtual, cloudVideo, 0), AlbumMediaEntity(virtual, a, 0)))

        assertFalse(cloudImage in mediaDao.observeTimelineIds(includeCloudOnly = false).first())
        assertTrue(cloudImage in mediaDao.observeTimelineIds(includeCloudOnly = true).first())
        assertEquals(3, mediaDao.observeTimelineCount(includeCloudOnly = false).first())
        assertEquals(5, mediaDao.observeTimelineCount(includeCloudOnly = true).first())
        assertEquals(1, mediaDao.observeVideoCount(includeCloudOnly = false).first())
        assertEquals(2, mediaDao.observeVideoCount(includeCloudOnly = true).first())
        assertEquals(listOf(cloudImage), mediaDao.timelineIdsInPeriod(true, 8_000, 9_100))
        assertTrue(mediaDao.timelineIdsInPeriod(false, 8_000, 9_100).isEmpty())
        // Favoris et albums montrent tout ce qui est visible.
        assertEquals(1, mediaDao.observeFavoriteCount().first())
        assertEquals(2, mediaDao.observeAlbumCount(virtual).first())
    }

    @Test
    fun albumRows_exposeCoverCloudItem() = runTest {
        val mediaDao = db.mediaDao()
        val id = mediaDao.insert(media(10))
        db.mediaSourceDao().insert(cloudSource(id, "od-cover", cTag = "ct9"))
        mediaDao.refreshSourceFlags(listOf(id))
        val album = db.albumDao().insert(
            AlbumEntity(name = "OneDrive", type = AlbumType.CLOUD_FOLDER, sourceKey = "onedrive:root", relativePath = "OneDrive/", createdAt = 0, updatedAt = 0),
        )
        val row = db.albumDao().observeAlbumRow(album).first()!!
        assertEquals(1, row.mediaCount)
        assertEquals(id, row.coverMediaId)
        assertNull(row.coverUri)
        assertEquals("od-cover", row.coverCloudItemId)
        assertEquals("ct9", row.coverCloudCTag)
    }

    @Test
    fun deleteByKindOnedrive_leavesLocalMediaIntact() = runTest {
        val (a, b, c) = seed()
        val mediaDao = db.mediaDao()
        val cloud = mediaDao.insert(media(9_000))
        db.mediaSourceDao().insert(cloudSource(cloud, "od-x"))
        mediaDao.refreshSourceFlags(listOf(cloud))
        db.mediaSourceDao().deleteByKind(SourceKind.ONEDRIVE)
        assertEquals(1, mediaDao.deleteOrphans())
        assertEquals(listOf(c, b, a), mediaDao.observeTimelineIds(includeCloudOnly = true).first())
    }
}
