package fr.eva.cloudgallery.data.cloud.offline

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.transfer.CdnResult
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.CloudDownloadException
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moteur des copies hors ligne avec un téléchargeur factice : enregistrement, revérifications au
 * moment d'enregistrer (purge, libération, nouvelle version), reprise après arrêt, passages
 * successifs. Fichiers dans un dossier propre au test (jamais ceux de l'appli installée).
 */
@RunWith(AndroidJUnit4::class)
class OfflineDownloadEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private lateinit var store: OfflineStore
    private val generation = CloudDataGeneration()
    private val content = ByteArray(10) { it.toByte() }

    /** Comportement du téléchargeur factice pour le prochain appel. */
    private var fetch: suspend (itemId: String, part: File) -> CdnResult = { _, part ->
        part.writeBytes(content)
        CdnResult(content.size.toLong(), "c1", "e1")
    }
    private val fetchCount = AtomicInteger()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dir = File(context.cacheDir, "test-offline-engine").apply {
            deleteRecursively()
            mkdirs()
        }
        store = OfflineStore(rootProvider = { dir }, storage = { null }, io = Dispatchers.IO, clock = { 0L })
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    /** Progression signalée par le téléchargeur factice (null : aucune). */
    private var reportProgress: (suspend (suspend (Long, Long) -> Unit) -> Unit)? = null

    private fun engine(canAccess: Boolean = true, space: OfflineSpace? = null) = OfflineDownloadEngine(
        db = db,
        fetcher = OfflineFileFetcher { itemId, part, _, _, _, onProgress ->
            fetchCount.incrementAndGet()
            reportProgress?.invoke(onProgress)
            fetch(itemId, part)
        },
        store = store,
        generation = generation,
        canAccess = { canAccess },
        io = Dispatchers.IO,
        clock = { 1_000L },
        space = space ?: store,
    )

    /** Espace disque factice : [usable] libre, [allocatable] obtenable ; [reserved] = demandes faites. */
    private class FakeSpace(val usable: Long, val allocatable: Long) : OfflineSpace {
        val reserved = ArrayList<Long>()
        override suspend fun usableBytes() = usable
        override suspend fun allocatableBytes() = allocatable
        override suspend fun reserve(bytes: Long) {
            if (bytes > allocatable) throw IOException("ENOSPC")
            reserved += bytes
        }
    }

    /** Elément OneDrive visible avec une tâche en attente. */
    private suspend fun seed(itemId: String = "A", cTag: String? = "c1"): Long {
        val id = db.mediaDao().insert(
            MediaEntity(
                mediaType = MediaType.IMAGE, displayName = "$itemId.jpg", mimeType = "image/jpeg", dateTaken = 1, dateAdded = 1,
                dateModified = 1, width = 1, height = 1, durationMs = 0, sizeBytes = 10, orientation = 0,
                primaryUri = null, hasLocal = false, hasCloud = true, hasOffline = false,
            ),
        )
        db.mediaSourceDao().insert(
            MediaSourceEntity(
                mediaId = id, kind = SourceKind.ONEDRIVE, externalId = itemId, uri = null, bucketKey = "onedrive:ROOT",
                bucketName = null, relativePath = null, sizeBytes = 10, dateModified = 1, cTag = cTag, eTag = "e1",
            ),
        )
        db.mediaDao().refreshSourceFlags(listOf(id))
        db.downloadTaskDao().enqueueIfAbsent(
            listOf(
                DownloadTaskEntity(
                    mediaId = id, sourceId = null, state = DownloadState.QUEUED, bytesDownloaded = 0, bytesTotal = 10,
                    keepOffline = true, createdAt = id, updatedAt = id, cloudItemId = itemId, cTag = cTag,
                ),
            ),
        )
        return id
    }

    @Test
    fun success_commitsCopy_sourceRow_andRemovesTask() = runTest {
        val id = seed()
        val outcome = engine().drain(null)
        assertEquals(DrainOutcome.DONE, outcome)
        val media = db.mediaDao().getById(id)!!
        assertTrue(media.hasOffline)
        assertNull(media.primaryUri)
        val copy = db.mediaSourceDao().find(SourceKind.OFFLINE_COPY, "A")!!
        assertEquals(id, copy.mediaId)
        assertNull(copy.bucketKey)
        assertEquals("c1", copy.cTag)
        val row = db.cachedFileDao().get(id, CacheKind.OFFLINE)!!
        assertTrue(row.pinned)
        assertEquals("c1", row.version)
        val file = File(dir, row.path)
        assertTrue(file.isFile)
        assertEquals(10L, file.length())
        assertFalse(File(dir, row.path + ".part").exists())
        assertFalse(db.downloadTaskDao().existsAny())
    }

    @Test
    fun purgeBetweenDownloadAndCommit_keepsNothing() = runTest {
        val id = seed()
        fetch = { _, part ->
            part.writeBytes(content)
            // Déconnexion pendant le téléchargement : le numéro de vidage change.
            generation.bump()
            CdnResult(10, "c1", "e1")
        }
        engine().drain(null)
        assertNull(db.cachedFileDao().get(id, CacheKind.OFFLINE))
        assertNull(db.mediaSourceDao().find(SourceKind.OFFLINE_COPY, "A"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun releasedDuringDownload_taskGone_copyNotResurrected() = runTest {
        val id = seed()
        fetch = { _, part ->
            part.writeBytes(content)
            // « Libérer » / « Annuler » entre la fin du téléchargement et l'enregistrement.
            db.downloadTaskDao().deleteForMedia(listOf(id))
            CdnResult(10, "c1", "e1")
        }
        engine().drain(null)
        assertFalse(db.mediaDao().getById(id)!!.hasOffline)
        assertNull(db.cachedFileDao().get(id, CacheKind.OFFLINE))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    /**
     * Contenu plus récent sur OneDrive que dans l'index : pas d'essai compté, la file continue,
     * et la tâche attend la synchronisation (aucun nouveau téléchargement tant que l'index n'a
     * pas changé), puis repart avec la nouvelle version.
     */
    @Test
    fun newerContentThanIndex_waitsForSync_withoutCountingAttempts() = runTest {
        val id = seed("A")
        val other = seed("B")
        fetch = { itemId, part ->
            part.writeBytes(content)
            CdnResult(10, if (itemId == "A") "c2" else "c1", "e2")
        }
        val engine = engine()
        assertEquals(DrainOutcome.DONE, engine.drain(null))
        val task = db.downloadTaskDao().listOpen().single()
        assertEquals(id, task.mediaId)
        assertEquals(DownloadState.QUEUED, task.state)
        assertEquals(DownloadError.CONTENT_CHANGED.name, task.error)
        assertEquals(0, task.attempts)
        assertFalse(db.mediaDao().getById(id)!!.hasOffline)
        // La tâche suivante n'a pas été bloquée.
        assertTrue(db.mediaDao().getById(other)!!.hasOffline)
        assertFalse(db.downloadTaskDao().existsRunnable())
        // Nouveau passage avant la synchronisation : rien n'est retéléchargé.
        val before = fetchCount.get()
        engine.drain(null)
        assertEquals(before, fetchCount.get())
        // Synchronisation : l'index passe à c2, la copie est enfin enregistrée.
        val source = db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!
        db.mediaSourceDao().update(source.copy(cTag = "c2"))
        engine.drain(null)
        assertTrue(db.mediaDao().getById(id)!!.hasOffline)
        assertEquals("c2", db.cachedFileDao().get(id, CacheKind.OFFLINE)!!.version)
    }

    @Test
    fun itemGoneFromOneDrive_failsAndContinuesWithNext() = runTest {
        val a = seed("A")
        val b = seed("B")
        // A masqué (supprimé sur OneDrive) depuis la mise en file.
        db.mediaSourceDao().markMissing(listOf(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!.id), 5)
        engine().drain(null)
        val failed = db.downloadTaskDao().listOpen().single()
        assertEquals(a, failed.mediaId)
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadError.NOT_FOUND.name, failed.error)
        assertTrue(db.mediaDao().getById(b)!!.hasOffline)
    }

    @Test
    fun signedOut_failsWholeQueueWithoutDownloading() = runTest {
        seed("A")
        seed("B")
        engine(canAccess = false).drain(null)
        assertEquals(0, fetchCount.get())
        assertTrue(db.downloadTaskDao().listOpen().all { it.state == DownloadState.FAILED && it.error == DownloadError.AUTH_REQUIRED.name })
    }

    @Test
    fun networkError_retriesLater_keepsPartForResume() = runTest {
        seed()
        fetch = { _, part ->
            part.writeBytes(content.copyOf(4))
            throw CloudDownloadException(DownloadError.NETWORK)
        }
        assertEquals(DrainOutcome.RETRY_LATER, engine().drain(null))
        val task = db.downloadTaskDao().listOpen().single()
        assertEquals(DownloadState.QUEUED, task.state)
        assertEquals(1, task.attempts)
        // Le fichier partiel reste pour la reprise par Range.
        assertTrue(dir.listFiles().orEmpty().any { it.name.endsWith(".part") && it.length() == 4L })
    }

    @Test
    fun staleRunningRows_areRecoveredAtDrainStart() = runTest {
        val id = seed()
        val task = db.downloadTaskDao().nextQueued()!!
        db.downloadTaskDao().markRunning(task.id, 1)
        engine().drain(null)
        assertTrue(db.mediaDao().getById(id)!!.hasOffline)
    }

    @Test
    fun recoverIfIdle_pausesOnlyAfterAUserStopDuringADownload() = runTest {
        seed("A")
        seed("B")
        val engine = engine()
        // Appli balayée depuis les applis récentes sans téléchargement en cours : rien ne change.
        assertEquals(0, engine.recoverIfIdle(firstStart = true, userRequestedExit = true))
        assertTrue(db.downloadTaskDao().listOpen().all { it.state == DownloadState.QUEUED })
        // Arrêt par l'utilisateur pendant un téléchargement : toute la file en pause.
        val task = db.downloadTaskDao().nextQueued()!!
        db.downloadTaskDao().markRunning(task.id, 1)
        assertEquals(2, engine.recoverIfIdle(firstStart = true, userRequestedExit = true))
        assertTrue(db.downloadTaskDao().listOpen().all { it.state == DownloadState.PAUSED })
    }

    @Test
    fun recoverIfIdle_otherExit_requeuesRunningTasks() = runTest {
        seed()
        val task = db.downloadTaskDao().nextQueued()!!
        db.downloadTaskDao().markRunning(task.id, 1)
        assertEquals(1, engine().recoverIfIdle(firstStart = true, userRequestedExit = false))
        assertEquals(DownloadState.QUEUED, db.downloadTaskDao().getById(task.id)!!.state)
    }

    @Test
    fun networkErrorAfterProgress_doesNotCountAnAttempt() = runTest {
        seed()
        // Premier relevé : point de départ (reprise) ; le second montre des octets reçus.
        reportProgress = { onProgress ->
            onProgress(0, 10)
            onProgress(4, 10)
        }
        fetch = { _, part ->
            part.writeBytes(content.copyOf(4))
            throw CloudDownloadException(DownloadError.NETWORK)
        }
        assertEquals(DrainOutcome.RETRY_LATER, engine().drain(null))
        assertEquals(0, db.downloadTaskDao().listOpen().single().attempts)
    }

    @Test
    fun allowedTasksOnly_othersStayQueued() = runTest {
        val a = seed("A")
        val b = seed("B")
        val taskB = db.downloadTaskDao().listOpen().first { it.mediaId == b }
        // Réseau facturé avec « Wi-Fi uniquement » : seule la tâche de « Télécharger maintenant » passe.
        assertEquals(DrainOutcome.DONE, engine().drain(null, allowed = { setOf(taskB.id) }))
        assertTrue(db.mediaDao().getById(b)!!.hasOffline)
        assertFalse(db.mediaDao().getById(a)!!.hasOffline)
        assertEquals(DownloadState.QUEUED, db.downloadTaskDao().listOpen().single().state)
    }

    @Test
    fun releaseOfCurrentItem_stopsOnlyThatItem_andTheDrainContinues() = runTest {
        val a = seed("A")
        val b = seed("B")
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        fetch = { itemId, part ->
            if (itemId == "A") {
                part.writeBytes(content.copyOf(3))
                started.complete(Unit)
                never.await()
            }
            part.writeBytes(content)
            CdnResult(10, "c1", "e1")
        }
        val engine = engine()
        withContext(Dispatchers.Default) {
            val drain = async { engine.drain(null) }
            started.await()
            // « Libérer » / « Annuler » de A pendant son téléchargement.
            engine.withItemsStopped(listOf(a)) { db.downloadTaskDao().deleteForMedia(listOf(a)) }
            assertEquals(DrainOutcome.DONE, drain.await())
        }
        assertFalse(db.mediaDao().getById(a)!!.hasOffline)
        assertNull(db.cachedFileDao().get(a, CacheKind.OFFLINE))
        assertTrue(db.mediaDao().getById(b)!!.hasOffline)
    }

    @Test
    fun taskDeletedDuringDownload_stopsTheFetchAtTheNextProgress() = runTest {
        val id = seed()
        reportProgress = { onProgress ->
            // Tâche supprimée sans passer par le moteur : le relevé suivant arrête le téléchargement.
            db.downloadTaskDao().deleteForMedia(listOf(id))
            onProgress(4, 10)
            fail("Le téléchargement aurait dû s'arrêter")
        }
        assertEquals(DrainOutcome.DONE, engine().drain(null))
        assertFalse(db.mediaDao().getById(id)!!.hasOffline)
    }

    @Test
    fun space_otherAppsCacheIsReleasedWhenFreeSpaceIsShort() = runTest {
        val id = seed()
        val space = FakeSpace(usable = 0, allocatable = Long.MAX_VALUE / 2)
        engine(space = space).drain(null)
        // allocateBytes demandé (une écriture ordinaire ne libère pas le cache des autres applis).
        assertEquals(listOf(10L + OFFLINE_SPACE_MARGIN_BYTES), space.reserved)
        assertTrue(db.mediaDao().getById(id)!!.hasOffline)
    }

    @Test
    fun space_enoughFreeSpace_noAllocation_noSpace_failsQueue() = runTest {
        seed("A")
        val enough = FakeSpace(usable = Long.MAX_VALUE / 2, allocatable = Long.MAX_VALUE / 2)
        engine(space = enough).drain(null)
        assertTrue(enough.reserved.isEmpty())

        seed("B")
        val full = FakeSpace(usable = 0, allocatable = 0)
        engine(space = full).drain(null)
        assertTrue(full.reserved.isEmpty())
        assertEquals(DownloadError.NO_SPACE.name, db.downloadTaskDao().listOpen().single().error)
    }

    @Test
    fun stopDuringDownload_requeuesTask_andConcurrentDrainWaits() = runTest {
        val id = seed()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fetch = { _, part ->
            part.writeBytes(content.copyOf(3))
            started.complete(Unit)
            release.await()
            part.writeBytes(content)
            CdnResult(10, "c1", "e1")
        }
        val engine = engine()
        withContext(Dispatchers.Default) {
            val first = launch { engine.drain(null) }
            started.await()
            assertTrue(engine.isDraining)
            // Arrêt (purge, annulation) : le passage s'arrête, la tâche repart en attente.
            engine.stopAndJoin()
            assertTrue(first.isCancelled)
            assertEquals(DownloadState.QUEUED, db.downloadTaskDao().listOpen().single().state)
            // Nouveau passage : un seul à la fois, il reprend la tâche.
            release.complete(Unit)
            val second = async { engine.drain(null) }
            assertEquals(DrainOutcome.DONE, second.await())
        }
        assertTrue(db.mediaDao().getById(id)!!.hasOffline)
        assertNotNull(db.cachedFileDao().get(id, CacheKind.OFFLINE))
    }

    @Test
    fun newVersionCommitted_replacesOldCopy_andDeletesOldFile() = runTest {
        val id = seed(cTag = "c1")
        engine().drain(null)
        val oldPath = db.cachedFileDao().get(id, CacheKind.OFFLINE)!!.path
        // Synchro : nouvelle version, nouvelle tâche ; l'ancienne copie reste jusqu'au remplacement.
        val source = db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!
        db.mediaSourceDao().update(source.copy(cTag = "c2"))
        db.downloadTaskDao().enqueueIfAbsent(
            listOf(
                DownloadTaskEntity(
                    mediaId = id, sourceId = null, state = DownloadState.QUEUED, bytesDownloaded = 0, bytesTotal = 10,
                    keepOffline = true, createdAt = 5, updatedAt = 5, cloudItemId = "A", cTag = "c2",
                ),
            ),
        )
        assertTrue(File(dir, oldPath).isFile)
        fetch = { _, part ->
            part.writeBytes(content)
            CdnResult(10, "c2", "e2")
        }
        engine().drain(null)
        val row = db.cachedFileDao().get(id, CacheKind.OFFLINE)!!
        assertEquals("c2", row.version)
        assertTrue(row.path != oldPath)
        assertFalse(File(dir, oldPath).exists())
        assertTrue(File(dir, row.path).isFile)
        assertEquals("c2", db.mediaSourceDao().find(SourceKind.OFFLINE_COPY, "A")!!.cTag)
    }
}
