package fr.eva.cloudgallery.data.cloud

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.data.cloud.offline.OfflineStore
import fr.eva.cloudgallery.data.cloud.sync.ONEDRIVE_PROVIDER
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.cloud.sync.CloudSyncThrottleStore
import fr.eva.cloudgallery.data.cloud.transfer.CloudDownloadUrlProvider
import fr.eva.cloudgallery.data.cloud.transfer.CloudFileDownloader
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.CloudFolderEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CloudDataPurgerTest {

    private lateinit var db: AppDatabase
    private lateinit var purger: CloudDataPurger
    private lateinit var originalsDir: File
    private lateinit var offlineDir: File
    private val generation = CloudDataGeneration()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        // Dossier propre au test : les originaux de l'appli installée ne sont jamais touchés.
        originalsDir = File(context.cacheDir, "test-purger-originals").apply {
            deleteRecursively()
            mkdirs()
        }
        // Dossier « offline » propre au test (OfflineStore.clear ne vide qu'un dossier de ce nom).
        offlineDir = File(File(context.cacheDir, "test-purger-files"), OfflineStore.OFFLINE_DIR).apply {
            parentFile?.deleteRecursively()
            mkdirs()
        }
        val urls = CloudDownloadUrlProvider(
            fetch = { error("Aucun appel réseau dans ce test") },
            ensureAccess = {},
            clock = { 0L },
            io = Dispatchers.IO,
        )
        val originals = CloudOriginalStore(
            root = originalsDir,
            downloader = CloudFileDownloader(
                client = dagger.Lazy<OkHttpClient> { error("Aucun appel réseau dans ce test") },
                urls = urls,
                io = Dispatchers.IO,
            ),
            db = db,
            cachedFiles = db.cachedFileDao(),
            sources = db.mediaSourceDao(),
            generation = generation,
            scope = scope,
            io = Dispatchers.IO,
            clock = { 0L },
        )
        purger = CloudDataPurger(
            db = db,
            mediaDao = db.mediaDao(),
            sourceDao = db.mediaSourceDao(),
            albumDao = db.albumDao(),
            syncCursorDao = db.syncCursorDao(),
            cloudFolderDao = db.cloudFolderDao(),
            cachedFileDao = db.cachedFileDao(),
            downloadTaskDao = db.downloadTaskDao(),
            thumbnails = thumbnails,
            throttle = CloudSyncThrottleStore(context, Dispatchers.IO),
            downloadUrls = urls,
            originals = originals,
            offlineStore = OfflineStore(rootProvider = { offlineDir }, storage = { null }, io = Dispatchers.IO, clock = { 0L }),
            generation = generation,
            io = Dispatchers.IO,
        )
    }

    /** Cache de miniatures factice : le test ne touche pas au cache Coil de l'appareil. */
    private class FakeThumbnailCache : CloudThumbnailCache {
        var clears = 0
        override suspend fun clear() {
            clears++
        }
    }

    private val thumbnails = FakeThumbnailCache()

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        originalsDir.deleteRecursively()
        offlineDir.parentFile?.deleteRecursively()
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
    fun purgeAll_removesDownloadedOriginals_rowsAndFiles_andBumpsGeneration() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val cachedFiles = db.cachedFileDao()
        // Média présent sur l'appareil ET sur OneDrive : conservé, mais son original OneDrive part.
        val both = mediaDao.insert(media("both"))
        sourceDao.insert(source(both, SourceKind.LOCAL, "image:9", "local:1"))
        val cloudSource = sourceDao.insert(source(both, SourceKind.ONEDRIVE, "C", "onedrive:ROOT"))
        mediaDao.refreshSourceFlags(listOf(both))
        val file = File(originalsDir, "0123456789abcdef_01234567.jpg").apply { writeBytes(ByteArray(5)) }
        val partial = File(originalsDir, "fedcba9876543210_76543210.jpg.part").apply { writeBytes(ByteArray(2)) }
        cachedFiles.replace(
            CachedFileEntity(
                mediaId = both, sourceId = cloudSource, kind = CacheKind.ORIGINAL, path = file.name, sizeBytes = 5,
                createdAt = 0, lastAccessAt = 0, pinned = false, version = "c1",
            ),
        )
        val before = generation.value

        assertTrue(purger.hasData())
        purger.purgeAll()

        assertTrue(generation.value > before)
        assertNull(cachedFiles.get(both, CacheKind.ORIGINAL))
        assertFalse(cachedFiles.existsAny())
        assertFalse(file.exists())
        assertFalse(partial.exists())
        assertFalse(purger.hasData())
        // Le média local reste, sans OneDrive.
        val kept = mediaDao.getById(both)!!
        assertTrue(kept.hasLocal)
        assertFalse(kept.hasCloud)
    }

    @Test
    fun hasData_seesCachedFilesAlone() = runTest {
        val id = db.mediaDao().insert(media("local"))
        db.mediaSourceDao().insert(source(id, SourceKind.LOCAL, "image:1", "local:1"))
        assertFalse(purger.hasData())
        db.cachedFileDao().replace(
            CachedFileEntity(
                mediaId = id, sourceId = null, kind = CacheKind.ORIGINAL, path = "x.jpg", sizeBytes = 1,
                createdAt = 0, lastAccessAt = 0, pinned = false,
            ),
        )
        assertTrue(purger.hasData())
    }

    @Test
    fun hasData_seesHiddenSourcesAlone() = runTest {
        val id = db.mediaDao().insert(media("hidden"))
        db.mediaSourceDao().insert(source(id, SourceKind.ONEDRIVE, "B", "onedrive:ROOT", missingSince = 5))
        assertEquals(0, db.mediaSourceDao().count(SourceKind.ONEDRIVE))
        assertTrue(purger.hasData())
    }

    /**
     * Copies hors ligne : purgées avec les données OneDrive (décision D3). Un média qui n'avait
     * que OneDrive et sa copie disparaît ; un média local garde son fichier local.
     */
    @Test
    fun purgeAll_removesOfflineCopies_rowsTasksAndFiles() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val pinned = mediaDao.insert(media("pinned"))
        val cloudSource = sourceDao.insert(source(pinned, SourceKind.ONEDRIVE, "P", "onedrive:ROOT"))
        sourceDao.insert(source(pinned, SourceKind.OFFLINE_COPY, "P", "onedrive:ROOT").copy(bucketKey = null))
        // Copie conservée d'un élément déjà supprimé de OneDrive (dernière copie).
        val kept = mediaDao.insert(media("kept"))
        sourceDao.insert(source(kept, SourceKind.OFFLINE_COPY, "K", "onedrive:ROOT").copy(bucketKey = null))
        mediaDao.refreshSourceFlags(listOf(pinned, kept))
        val file = File(offlineDir, "aaaaaaaaaaaaaaaa_bbbbbbbb.jpg").apply { writeBytes(ByteArray(3)) }
        val part = File(offlineDir, "cccccccccccccccc_dddddddd.jpg.part").apply { writeBytes(ByteArray(1)) }
        db.cachedFileDao().replace(
            CachedFileEntity(
                mediaId = pinned, sourceId = cloudSource, kind = CacheKind.OFFLINE, path = file.name, sizeBytes = 3,
                createdAt = 0, lastAccessAt = 0, pinned = true, version = "c1",
            ),
        )
        db.downloadTaskDao().enqueueIfAbsent(
            listOf(
                DownloadTaskEntity(
                    mediaId = pinned, sourceId = null, state = DownloadState.QUEUED, bytesDownloaded = 0, bytesTotal = 3,
                    keepOffline = true, createdAt = 0, updatedAt = 0, cloudItemId = "P", cTag = "c2",
                ),
            ),
        )
        assertTrue(mediaDao.getById(kept)!!.hasOffline)
        assertEquals(1, sourceDao.countOfflineLastCopies())

        assertTrue(purger.hasData())
        purger.purgeAll()

        assertFalse(purger.hasData())
        assertNull(mediaDao.getById(pinned))
        assertNull(mediaDao.getById(kept))
        assertFalse(sourceDao.existsAny(SourceKind.OFFLINE_COPY))
        assertFalse(db.downloadTaskDao().existsAny())
        assertFalse(db.cachedFileDao().existsAny())
        assertFalse(file.exists())
        assertFalse(part.exists())
    }

    @Test
    fun hasData_seesOfflineCopyAlone() = runTest {
        val id = db.mediaDao().insert(media("kept"))
        assertFalse(purger.hasData())
        db.mediaSourceDao().insert(source(id, SourceKind.OFFLINE_COPY, "K", "onedrive:ROOT").copy(bucketKey = null))
        assertTrue(purger.hasData())
    }
}
