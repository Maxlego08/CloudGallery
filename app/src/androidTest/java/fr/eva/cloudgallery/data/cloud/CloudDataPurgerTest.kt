package fr.eva.cloudgallery.data.cloud

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.cloud.sync.ONEDRIVE_PROVIDER
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.cloud.sync.CloudSyncThrottleStore
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.CloudFolderEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.Dispatchers
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
class CloudDataPurgerTest {

    private lateinit var db: AppDatabase
    private lateinit var purger: CloudDataPurger

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        purger = CloudDataPurger(
            db = db,
            mediaDao = db.mediaDao(),
            sourceDao = db.mediaSourceDao(),
            albumDao = db.albumDao(),
            syncCursorDao = db.syncCursorDao(),
            cloudFolderDao = db.cloudFolderDao(),
            thumbnails = thumbnails,
            throttle = CloudSyncThrottleStore(context, Dispatchers.IO),
            io = Dispatchers.IO,
        )
    }

    /** Cache de miniatures factice : le test ne touche pas au cache Coil de l'appareil. */
    private class FakeThumbnailCache : CloudThumbnailCache {
        var clears = 0
        override val generation: Long get() = clears.toLong()
        override suspend fun clear() {
            clears++
        }
    }

    private val thumbnails = FakeThumbnailCache()

    @After
    fun tearDown() {
        db.close()
    }

    private fun media(name: String) = MediaEntity(
        mediaType = MediaType.IMAGE, displayName = name, mimeType = "image/jpeg", dateTaken = 1, dateAdded = 1,
        dateModified = 1, width = 1, height = 1, durationMs = 0, sizeBytes = 1, orientation = 0,
        primaryUri = null, hasLocal = false, hasCloud = false, hasOffline = false,
    )

    private fun source(mediaId: Long, kind: SourceKind, externalId: String, bucket: String, missingSince: Long? = null) =
        MediaSourceEntity(
            mediaId = mediaId, kind = kind, externalId = externalId,
            uri = if (kind == SourceKind.LOCAL) "content://media/external/images/media/$externalId" else null,
            bucketKey = bucket, bucketName = null, relativePath = null, sizeBytes = 1, dateModified = 1,
            missingSince = missingSince,
        )

    @Test
    fun purgeAll_removesEveryCloudTrace_andKeepsLocalData() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val albumDao = db.albumDao()
        val local = mediaDao.insert(media("local"))
        sourceDao.insert(source(local, SourceKind.LOCAL, "image:1", "local:1"))
        val cloud = mediaDao.insert(media("cloud"))
        sourceDao.insert(source(cloud, SourceKind.ONEDRIVE, "A", "onedrive:ROOT"))
        // Elément supprimé sur OneDrive, encore masqué pendant le délai de grâce.
        val hidden = mediaDao.insert(media("hidden"))
        sourceDao.insert(source(hidden, SourceKind.ONEDRIVE, "B", "onedrive:ROOT", missingSince = 5))
        mediaDao.refreshSourceFlags(listOf(local, cloud, hidden))

        db.cloudFolderDao().upsertAll(listOf(CloudFolderEntity("ROOT", null, "root", isRoot = true, updatedAt = 0)))
        val cloudAlbum = albumDao.insert(
            AlbumEntity(name = "OneDrive", type = AlbumType.CLOUD_FOLDER, sourceKey = "onedrive:ROOT", relativePath = "OneDrive/",
                createdAt = 0, updatedAt = 0, customName = "Renommé", isPinned = true),
        )
        val virtual = albumDao.insert(
            AlbumEntity(name = "Mix", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0),
        )
        albumDao.insertMembers(listOf(AlbumMediaEntity(virtual, local, 0), AlbumMediaEntity(virtual, cloud, 0)))
        albumDao.setCover(virtual, cloud, 0)
        db.favoriteDao().insertAll(listOf(FavoriteEntity(local, 0), FavoriteEntity(cloud, 0)))
        db.syncCursorDao().upsert(SyncCursorEntity(ONEDRIVE_PROVIDER, "https://graph.microsoft.com/x", "D|s1", 1, 1))

        assertTrue(purger.hasData())
        purger.purgeAll()

        assertFalse(purger.hasData())
        assertFalse(sourceDao.existsAny(SourceKind.ONEDRIVE))
        assertNull(mediaDao.getById(cloud))
        assertNull(mediaDao.getById(hidden))
        assertNull(albumDao.getById(cloudAlbum))
        assertTrue(db.cloudFolderDao().getAll().isEmpty())
        assertNull(db.syncCursorDao().get(ONEDRIVE_PROVIDER))
        // Miniatures OneDrive vidées après la transaction.
        assertEquals(1, thumbnails.clears)

        // Local intact : média, favori, album virtuel et son membre local.
        assertNotNull(mediaDao.getById(local))
        assertTrue(mediaDao.getById(local)!!.hasLocal)
        assertTrue(db.favoriteDao().isFavorite(local))
        assertNotNull(albumDao.getById(virtual))
        assertEquals(listOf(local), mediaDao.observeAlbumIds(virtual).first())
        // La couverture qui pointait vers le média OneDrive est effacée.
        assertNull(albumDao.getById(virtual)!!.coverMediaId)
    }

    @Test
    fun hasData_seesHiddenSourcesAlone() = runTest {
        val id = db.mediaDao().insert(media("hidden"))
        db.mediaSourceDao().insert(source(id, SourceKind.ONEDRIVE, "B", "onedrive:ROOT", missingSince = 5))
        assertEquals(0, db.mediaSourceDao().count(SourceKind.ONEDRIVE))
        assertTrue(purger.hasData())
    }
}
