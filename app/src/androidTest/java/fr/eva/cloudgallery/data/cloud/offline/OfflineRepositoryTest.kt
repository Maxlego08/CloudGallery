package fr.eva.cloudgallery.data.cloud.offline

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.data.cloud.transfer.CdnResult
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.local.MISSING_GRACE_MS
import fr.eva.cloudgallery.data.net.NetworkStatus
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.model.ThemeMode
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import java.io.File

/**
 * Actions destructives des copies hors ligne (décision D1) sur une base en mémoire et un dossier
 * propre au test : « Supprimer les copies » / « Les conserver » limités à la liste confirmée et
 * revérifiés dans la transaction, « Libérer » d'une copie conservée seule ou d'une copie
 * ordinaire, comptage des dernières copies (copies masquées comprises).
 */
@RunWith(AndroidJUnit4::class)
class OfflineRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private lateinit var repository: OfflineRepositoryImpl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Réglages fixes : non lus par les actions testées. */
    private object FixedSettings : SettingsRepository {
        override val settings: Flow<AppSettings> = flowOf(AppSettings())
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setPhotosZoomLevel(level: Int) = Unit
        override suspend fun setAlbumZoomLevel(level: Int) = Unit
        override suspend fun setAlbumSort(sort: AlbumSort) = Unit
        override suspend fun setShowVideoDuration(show: Boolean) = Unit
        override suspend fun setAutoPlayVideos(enabled: Boolean) = Unit
        override suspend fun setConfirmBeforeDelete(enabled: Boolean) = Unit
        override suspend fun setUseTrash(enabled: Boolean) = Unit
        override suspend fun setShowCloudInPhotos(enabled: Boolean) = Unit
        override suspend fun setLoadOriginalsOnMobileData(enabled: Boolean) = Unit
        override suspend fun setOfflineWifiOnly(enabled: Boolean) = Unit
        override suspend fun setOfflineNotificationsAsked(asked: Boolean) = Unit
        override suspend fun setOriginalsCacheMaxBytes(bytes: Long) = Unit
        override suspend fun setThumbnailCacheMaxBytes(bytes: Long) = Unit
        override suspend fun setBackgroundSyncInterval(interval: BackgroundSyncInterval) = Unit
        override suspend fun setBackgroundSyncWifiOnly(enabled: Boolean) = Unit
        override suspend fun setOneDriveOnlyChosen(chosen: Boolean) = Unit
        override suspend fun setPrecacheKeepUpdated(enabled: Boolean) = Unit
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dir = File(context.cacheDir, "test-offline-repository").apply {
            deleteRecursively()
            mkdirs()
        }
        val store = OfflineStore(rootProvider = { dir }, storage = { null }, io = Dispatchers.IO, clock = { 0L })
        val engine = OfflineDownloadEngine(
            db = db,
            fetcher = OfflineFileFetcher { _, _, _, _, _, _ -> CdnResult(0, null, null) },
            store = store,
            generation = CloudDataGeneration(),
            canAccess = { false },
            io = Dispatchers.IO,
            clock = { 0L },
        )
        repository = OfflineRepositoryImpl(
            context = context,
            db = db,
            engine = engine,
            scheduler = OfflineJobScheduler(context),
            jobs = OfflineJobTracker(),
            store = store,
            // Jamais consultés par les actions testées.
            guard = CloudAccessGuard(
                feature = CloudFeature(),
                auth = { error("Aucun compte dans ce test") },
                syncer = { error("Aucune synchro dans ce test") },
                io = Dispatchers.IO,
            ),
            network = NetworkStatus(context, scope, Dispatchers.IO),
            settings = FixedSettings,
            io = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.deleteRecursively()
    }

    private val now = System.currentTimeMillis()
    private val expired = now - MISSING_GRACE_MS - 60_000L
    private val inGrace = now - 60_000L

    /**
     * Elément avec copie hors ligne (fichier compris). [cloudMissingSince] : élément OneDrive
     * masqué depuis cette date (null : visible) ; [withCloud] faux : copie conservée seule.
     */
    private suspend fun seedCopy(itemId: String, cloudMissingSince: Long?, withCloud: Boolean = true): Long {
        val id = db.mediaDao().insert(
            MediaEntity(
                mediaType = MediaType.IMAGE, displayName = "$itemId.jpg", mimeType = "image/jpeg", dateTaken = 1, dateAdded = 1,
                dateModified = 1, width = 1, height = 1, durationMs = 0, sizeBytes = 10, orientation = 0,
                primaryUri = null, hasLocal = false, hasCloud = false, hasOffline = false,
            ),
        )
        if (withCloud) {
            db.mediaSourceDao().insert(source(id, SourceKind.ONEDRIVE, itemId, cloudMissingSince))
        }
        val copyId = db.mediaSourceDao().insert(source(id, SourceKind.OFFLINE_COPY, itemId, cloudMissingSince))
        val name = "$itemId.jpg"
        File(dir, name).writeBytes(ByteArray(10))
        db.cachedFileDao().replace(
            CachedFileEntity(
                mediaId = id, sourceId = copyId, kind = CacheKind.OFFLINE, path = name, sizeBytes = 10,
                createdAt = 0, lastAccessAt = 0, pinned = true, version = "c1",
            ),
        )
        db.mediaDao().refreshSourceFlags(listOf(id))
        return id
    }

    private fun source(mediaId: Long, kind: SourceKind, itemId: String, missingSince: Long?) = MediaSourceEntity(
        mediaId = mediaId, kind = kind, externalId = itemId, uri = null,
        bucketKey = if (kind == SourceKind.ONEDRIVE) "onedrive:ROOT" else null, bucketName = null, relativePath = null,
        sizeBytes = 10, dateModified = 1, cTag = "c1", missingSince = missingSince,
    )

    private suspend fun restoreOnOneDrive(itemId: String) {
        val cloud = db.mediaSourceDao().find(SourceKind.ONEDRIVE, itemId)!!
        db.mediaSourceDao().update(cloud.copy(missingSince = null))
        db.mediaSourceDao().syncOfflineCopyVisibility(listOf(cloud.mediaId))
        db.mediaDao().refreshSourceFlags(listOf(cloud.mediaId))
    }

    @Test
    fun deleteCopies_onlyConfirmedItemsStillAwaitingDecision() = runTest {
        val a = seedCopy("A", expired)
        val notConfirmed = seedCopy("B", expired)
        val stillInGrace = seedCopy("C", inGrace)
        val restored = seedCopy("D", expired)
        db.favoriteDao().insertAll(listOf(FavoriteEntity(a, 1)))
        // D revient sur OneDrive (corbeille) entre l'affichage de la liste et la confirmation.
        restoreOnOneDrive("D")

        val deleted = repository.deleteCopiesOfDeletedItems(listOf(a, stillInGrace, restored))

        assertEquals(1, deleted)
        assertNull(db.mediaDao().getById(a))
        assertFalse(File(dir, "A.jpg").exists())
        assertFalse(db.favoriteDao().isFavorite(a))
        // Non confirmé (apparu après l'affichage) : intact.
        assertNotNull(db.cachedFileDao().get(notConfirmed, CacheKind.OFFLINE))
        assertTrue(File(dir, "B.jpg").exists())
        // Délai de grâce pas écoulé : intact.
        assertNotNull(db.mediaSourceDao().find(SourceKind.OFFLINE_COPY, "C"))
        assertTrue(File(dir, "C.jpg").exists())
        // Revenu sur OneDrive : élément OneDrive visible et copie gardés.
        assertNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "D")!!.missingSince)
        assertTrue(db.mediaDao().getById(restored)!!.hasCloud)
        assertTrue(db.mediaDao().getById(restored)!!.hasOffline)
        assertTrue(File(dir, "D.jpg").exists())
    }

    @Test
    fun keepCopies_copyBecomesVisibleOfflineOnly_restoredItemUntouched() = runTest {
        val a = seedCopy("A", expired)
        val restored = seedCopy("D", expired)
        restoreOnOneDrive("D")

        assertEquals(1, repository.keepCopiesOfDeletedItems(listOf(a, restored)))

        val kept = db.mediaDao().getById(a)!!
        assertTrue(kept.hasOffline)
        assertFalse(kept.hasCloud)
        assertNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A"))
        assertNull(db.mediaSourceDao().find(SourceKind.OFFLINE_COPY, "A")!!.missingSince)
        assertTrue(db.mediaSourceDao().awaitingDecisionMediaIds(now).isEmpty())
        assertTrue(File(dir, "A.jpg").exists())
        // D reste un élément OneDrive vivant (jamais « hors ligne uniquement »).
        assertTrue(db.mediaDao().getById(restored)!!.hasCloud)
        assertNotNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "D"))
    }

    @Test
    fun releaseKeptCopy_isALastCopy_andRemovesTheItem() = runTest {
        val a = seedCopy("A", expired)
        repository.keepCopiesOfDeletedItems(listOf(a))
        val albumId = db.albumDao().insert(
            AlbumEntity(name = "Album", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0),
        )
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(albumId, a, 0)))

        assertEquals(1, repository.lastCopiesAmong(listOf(a)))
        assertEquals(1, repository.release(listOf(a)))

        assertNull(db.mediaDao().getById(a))
        assertFalse(File(dir, "A.jpg").exists())
        assertNull(db.cachedFileDao().get(a, CacheKind.OFFLINE))
    }

    @Test
    fun releaseOrdinaryCopy_keepsTheOneDriveItem() = runTest {
        val a = seedCopy("A", cloudMissingSince = null)

        assertEquals(0, repository.lastCopiesAmong(listOf(a)))
        assertEquals(1, repository.release(listOf(a)))

        val media = db.mediaDao().getById(a)!!
        assertTrue(media.hasCloud)
        assertFalse(media.hasOffline)
        assertFalse(File(dir, "A.jpg").exists())
        assertNull(db.mediaSourceDao().find(SourceKind.OFFLINE_COPY, "A"))
    }

    @Test
    fun lastCopies_countHiddenCopiesThatReleaseWouldDelete() = runTest {
        // Masqué par une synchro (supprimé de OneDrive depuis un autre appareil), délai de grâce en cours.
        val hidden = seedCopy("B", inGrace)
        val visible = seedCopy("A", cloudMissingSince = null)
        assertFalse(db.mediaDao().getById(hidden)!!.hasOffline)
        assertEquals(1, repository.lastCopiesAmong(listOf(visible, hidden)))
    }
}
