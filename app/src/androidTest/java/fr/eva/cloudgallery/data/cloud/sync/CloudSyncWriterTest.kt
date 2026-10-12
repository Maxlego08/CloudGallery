package fr.eva.cloudgallery.data.cloud.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.cloud.graph.DeletedFacetDto
import fr.eva.cloudgallery.data.cloud.graph.DriveItemDto
import fr.eva.cloudgallery.data.cloud.graph.FileFacetDto
import fr.eva.cloudgallery.data.cloud.graph.FolderFacetDto
import fr.eva.cloudgallery.data.cloud.graph.ImageFacetDto
import fr.eva.cloudgallery.data.cloud.graph.ItemReferenceDto
import fr.eva.cloudgallery.data.cloud.graph.VideoFacetDto
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.data.local.MISSING_GRACE_MS
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
class CloudSyncWriterTest {

    private lateinit var db: AppDatabase
    private lateinit var writer: CloudSyncWriter
    private val labels = CloudFolderLabels(root = "OneDrive", unnamed = "Sans nom")
    private val t0 = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        writer = CloudSyncWriter(db, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Eléments delta ------------------------------------------------------------------

    private fun root() = DriveItemDto(id = "ROOT", name = "root", root = JsonObject(emptyMap()), folder = FolderFacetDto())

    private fun folder(id: String, name: String, parent: String = "ROOT") =
        DriveItemDto(id = id, name = name, folder = FolderFacetDto(), parentReference = ItemReferenceDto(id = parent))

    private fun image(id: String, parent: String, cTag: String = "c1") = DriveItemDto(
        id = id,
        name = "$id.jpg",
        size = 100,
        cTag = cTag,
        eTag = "e-$cTag",
        createdDateTime = "2024-06-01T10:00:00Z",
        lastModifiedDateTime = "2024-06-01T10:00:00Z",
        file = FileFacetDto(mimeType = "image/jpeg"),
        image = ImageFacetDto(400, 300),
        parentReference = ItemReferenceDto(id = parent),
    )

    private fun video(id: String, parent: String) = DriveItemDto(
        id = id,
        name = "$id.mp4",
        size = 1000,
        cTag = "v1",
        createdDateTime = "2024-07-01T10:00:00Z",
        file = FileFacetDto(mimeType = "video/mp4"),
        video = VideoFacetDto(duration = 5_000, width = 1920, height = 1080),
        parentReference = ItemReferenceDto(id = parent),
    )

    private fun deleted(id: String) = DriveItemDto(id = id, deleted = DeletedFacetDto())

    /** Passage complet : pages appliquées puis fin de synchro. */
    private suspend fun sync(full: Boolean, vararg pages: List<DriveItemDto>, now: Long = t0): CloudSyncRun {
        val run = CloudSyncRun(full)
        for (page in pages) writer.applyPage(page, run, now)
        writer.finishSync(run, now, labels)
        return run
    }

    private suspend fun mediaOf(itemId: String): MediaEntity? =
        db.mediaSourceDao().find(SourceKind.ONEDRIVE, itemId)?.let { db.mediaDao().getById(it.mediaId) }

    private suspend fun timeline(includeCloudOnly: Boolean) = db.mediaDao().observeTimelineIds(includeCloudOnly).first()

    /** Média local existant : jamais modifié par la synchronisation OneDrive. */
    private suspend fun seedLocal(): Long {
        val id = db.mediaDao().insert(
            MediaEntity(
                mediaType = MediaType.IMAGE, displayName = "local.jpg", mimeType = "image/jpeg", dateTaken = 5, dateAdded = 5,
                dateModified = 5, width = 10, height = 10, durationMs = 0, sizeBytes = 100, orientation = 0,
                primaryUri = "content://media/external/images/media/1", hasLocal = true, hasCloud = false, hasOffline = false,
            ),
        )
        db.mediaSourceDao().insert(
            MediaSourceEntity(
                mediaId = id, kind = SourceKind.LOCAL, externalId = "image:1", uri = "content://media/external/images/media/1",
                bucketKey = "local:9", bucketName = "Camera", relativePath = "DCIM/Camera/", sizeBytes = 100, dateModified = 5,
            ),
        )
        db.mediaDao().refreshSourceFlags(listOf(id))
        return id
    }

    private suspend fun assertLocalUntouched(id: Long) {
        val media = db.mediaDao().getById(id)!!
        assertTrue(media.hasLocal)
        assertFalse(media.hasCloud)
        assertEquals("content://media/external/images/media/1", media.primaryUri)
        assertNull(db.mediaSourceDao().find(SourceKind.LOCAL, "image:1")!!.missingSince)
    }

    // --- Tests ---------------------------------------------------------------------------

    @Test
    fun firstPages_createCloudMediaFoldersAndAlbums() = runTest {
        val local = seedLocal()
        val run = sync(full = true, listOf(root(), folder("PH", "Photos")), listOf(image("A", "PH"), video("V", "ROOT")))
        assertFalse(run.restoredFolderWithHiddenItems)

        val a = mediaOf("A")!!
        assertTrue(a.hasCloud)
        assertFalse(a.hasLocal)
        assertNull(a.primaryUri)
        assertEquals("A", a.cloudItemId)
        assertEquals("c1", a.cloudCTag)
        assertEquals(400, a.width)
        val v = mediaOf("V")!!
        assertEquals(MediaType.VIDEO, v.mediaType)
        assertEquals(5_000L, v.durationMs)

        // Réglage « Afficher les éléments OneDrive dans Photos » : désactivé, seul le local apparaît.
        assertEquals(listOf(local), timeline(includeCloudOnly = false))
        assertEquals(setOf(local, a.id, v.id), timeline(includeCloudOnly = true).toSet())
        assertEquals(0, db.mediaDao().observeVideoCount(includeCloudOnly = false).first())
        assertEquals(1, db.mediaDao().observeVideoCount(includeCloudOnly = true).first())

        val albums = db.albumDao().getByType(AlbumType.CLOUD_FOLDER).associateBy { it.sourceKey }
        assertEquals("Photos", albums["onedrive:PH"]!!.name)
        assertEquals("OneDrive/Photos/", albums["onedrive:PH"]!!.relativePath)
        assertEquals("OneDrive", albums["onedrive:ROOT"]!!.name)
        val album = albums["onedrive:PH"]!!
        assertEquals(listOf(a.id), db.mediaDao().observeAlbumIds(album.id).first())

        val source = db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!
        assertEquals("Photos", source.bucketName)
        assertEquals("OneDrive/Photos/", source.relativePath)
        assertEquals(2, writer.visibleCount())
        assertLocalUntouched(local)
    }

    @Test
    fun contentChange_updatesSameMediaAndKeepsFavorite() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        val before = mediaOf("A")!!
        db.favoriteDao().insertAll(listOf(FavoriteEntity(before.id, 1)))

        sync(full = false, listOf(image("A", "PH", cTag = "c2")))
        val after = mediaOf("A")!!
        assertEquals(before.id, after.id)
        assertEquals("c2", after.cloudCTag)
        assertTrue(db.favoriteDao().isFavorite(after.id))
    }

    @Test
    fun deletedItem_isHiddenThenPurgedAfterGracePeriod() = runTest {
        val local = seedLocal()
        sync(full = true, listOf(root(), image("A", "ROOT")))
        val id = mediaOf("A")!!.id
        db.favoriteDao().insertAll(listOf(FavoriteEntity(id, 1)))

        sync(full = false, listOf(deleted("A")))
        val hidden = db.mediaDao().getById(id)!!
        assertFalse(hidden.hasCloud)
        assertNull(hidden.cloudItemId)
        assertFalse(id in timeline(includeCloudOnly = true))
        // Masqué, pas supprimé : le favori est conservé pendant le délai de grâce.
        assertTrue(db.favoriteDao().isFavorite(id))
        assertNotNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A"))

        sync(full = false, emptyList(), now = t0 + MISSING_GRACE_MS - 1)
        assertNotNull(db.mediaDao().getById(id))

        sync(full = false, emptyList(), now = t0 + MISSING_GRACE_MS)
        assertNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A"))
        assertNull(db.mediaDao().getById(id))
        assertLocalUntouched(local)
    }

    @Test
    fun restoredItem_becomesVisibleAgain() = runTest {
        sync(full = true, listOf(root(), image("A", "ROOT")))
        sync(full = false, listOf(deleted("A")))
        assertFalse(mediaOf("A")!!.hasCloud)
        sync(full = false, listOf(image("A", "ROOT")))
        assertTrue(mediaOf("A")!!.hasCloud)
    }

    @Test
    fun deletedFolderWithoutFolderFacet_hidesItsContentAtEndOfRun() = runTest {
        val local = seedLocal()
        sync(
            full = true,
            listOf(root(), folder("PH", "Photos"), folder("Y24", "2024", parent = "PH"), image("A", "PH"), image("B", "Y24"), image("C", "ROOT")),
        )
        // Elément supprimé sans facette folder : reconnu comme dossier grâce à l'index.
        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(deleted("PH")), run, t0)
        // Avant la fin du passage, rien n'est encore masqué.
        assertTrue(mediaOf("A")!!.hasCloud)
        assertEquals(setOf("PH"), run.removedFolders)
        writer.finishSync(run, t0, labels)

        assertFalse(mediaOf("A")!!.hasCloud)
        assertFalse(mediaOf("B")!!.hasCloud)
        assertTrue(mediaOf("C")!!.hasCloud)
        assertEquals(setOf("ROOT"), liveFolders())
        // Dossiers supprimés gardés comme marqueurs : leurs éléments masqués peuvent être restaurés.
        assertEquals(setOf("PH", "Y24"), markedFolders())
        val albumKeys = db.albumDao().getByType(AlbumType.CLOUD_FOLDER).map { it.sourceKey }.toSet()
        assertEquals(setOf("onedrive:ROOT"), albumKeys)
        assertLocalUntouched(local)
    }

    @Test
    fun folderDeletedThenRestoredInSameRun_keepsContentVisible() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        val run = sync(full = false, listOf(deleted("PH")), listOf(folder("PH", "Photos")))
        assertTrue(run.removedFolders.isEmpty())
        assertTrue(mediaOf("A")!!.hasCloud)
        assertNotNull(db.albumDao().getBySourceKey("onedrive:PH"))
    }

    @Test
    fun folderRestoredInLaterRun_requestsFullEnumeration() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        sync(full = false, listOf(deleted("PH")))
        assertFalse(mediaOf("A")!!.hasCloud)

        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("PH", "Photos")), run, t0)
        assertTrue(run.restoredFolderWithHiddenItems)

        // L'énumération complète renvoie l'élément : il redevient visible.
        run.restartFull()
        writer.applyPage(listOf(root(), folder("PH", "Photos"), image("A", "PH")), run, t0)
        writer.finishSync(run, t0, labels)
        assertTrue(mediaOf("A")!!.hasCloud)
    }

    /** Curseur d'un passage incrémental réussi (deltaLink enregistré). */
    private suspend fun seedCursor(link: String = "https://graph.microsoft.com/v1.0/delta?token=old") {
        db.syncCursorDao().upsert(
            SyncCursorEntity(provider = ONEDRIVE_PROVIDER, cursor = link, version = "D1|$DELTA_SELECT_VERSION", lastSyncAt = t0, lastFullSyncAt = t0),
        )
    }

    @Test
    fun restoredFolder_clearsDeltaLinkWithThePage_soAnInterruptedRunStillEnumeratesFully() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        sync(full = false, listOf(deleted("PH")))
        seedCursor()

        // Le dossier restauré arrive sur une page qui n'est pas la dernière, puis le passage est
        // interrompu (réseau, processus tué) avant la fin du flux delta.
        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("PH", "Photos")), run, t0)
        assertTrue(run.restoredFolderWithHiddenItems)

        // Décision enregistrée avec la page : le deltaLink est effacé, le lecteur reste lié.
        val cursor = db.syncCursorDao().get(ONEDRIVE_PROVIDER)!!
        assertNull(cursor.cursor)
        assertEquals("D1|$DELTA_SELECT_VERSION", cursor.version)
        // Le passage suivant ne peut plus reprendre depuis l'ancien deltaLink.
        assertEquals(
            SyncStart.Full,
            planSyncStart(cursor.cursor, cursor.version, "D1", DELTA_SELECT_VERSION, writer.hasIndexedData()),
        )
        // Rejoué en incrémental, le dossier est déjà connu : la détection seule ne suffirait pas.
        val replay = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("PH", "Photos")), replay, t0)
        assertFalse(replay.restoredFolderWithHiddenItems)
    }

    @Test
    fun restoredParentFolder_detectsHiddenItemsInItsSubfolders() = runTest {
        // P ne contient que le sous-dossier C, qui contient les photos.
        sync(full = true, listOf(root(), folder("P", "Parent"), folder("C", "Enfant", parent = "P"), image("A", "C")))
        sync(full = false, listOf(deleted("P")))
        assertFalse(mediaOf("A")!!.hasCloud)
        // Les deux dossiers sont marqués : P (sans élément) rattache C, qui a des éléments masqués.
        assertEquals(setOf("P", "C"), markedFolders())
        seedCursor()

        // Seul P est renvoyé par la requête delta.
        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("P", "Parent")), run, t0)
        assertTrue(run.restoredFolderWithHiddenItems)
        assertNull(db.syncCursorDao().get(ONEDRIVE_PROVIDER)!!.cursor)

        run.restartFull()
        writer.applyPage(listOf(root(), folder("P", "Parent"), folder("C", "Enfant", parent = "P"), image("A", "C")), run, t0)
        writer.finishSync(run, t0, labels)
        assertTrue(mediaOf("A")!!.hasCloud)
        assertNotNull(db.albumDao().getBySourceKey("onedrive:C"))
        // Restaurés : de nouveau présents, plus aucun marqueur.
        assertTrue(markedFolders().isEmpty())
        assertTrue(liveFolders().containsAll(setOf("P", "C")))
    }

    /** Dossiers présents (non marqués supprimés). */
    private suspend fun liveFolders() = db.cloudFolderDao().getAll().filter { it.deletedAt == null }.map { it.itemId }.toSet()

    /** Dossiers marqués supprimés. */
    private suspend fun markedFolders() = db.cloudFolderDao().getAll().filter { it.deletedAt != null }.map { it.itemId }.toSet()

    @Test
    fun newFolder_whileADeletedFolderHasHiddenItems_keepsIncrementalSync() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        sync(full = false, listOf(deleted("PH")))
        assertFalse(mediaOf("A")!!.hasCloud)
        assertEquals(setOf("PH"), markedFolders())
        seedCursor()

        // Nouveau dossier (aucun marqueur) : pas d'énumération complète, malgré les éléments
        // masqués du dossier supprimé (avant la v5, chaque nouveau dossier en déclenchait une).
        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("NEW", "Nouveau"), image("B", "NEW")), run, t0)
        assertFalse(run.restoredFolderWithHiddenItems)
        assertNotNull(db.syncCursorDao().get(ONEDRIVE_PROVIDER)!!.cursor)
        writer.finishSync(run, t0, labels)
        assertTrue(mediaOf("B")!!.hasCloud)
        assertFalse(mediaOf("A")!!.hasCloud)
        // Le marqueur n'est jamais montré : pas d'album pour le dossier supprimé.
        assertNull(db.albumDao().getBySourceKey("onedrive:PH"))
    }

    @Test
    fun marker_isRemovedOnceItsHiddenItemsArePurged() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), folder("Y24", "2024", parent = "PH"), image("A", "Y24")))
        sync(full = false, listOf(deleted("PH")))
        assertEquals(setOf("PH", "Y24"), markedFolders())

        sync(full = false, emptyList(), now = t0 + MISSING_GRACE_MS - 1)
        assertEquals(setOf("PH", "Y24"), markedFolders())

        // Délai de grâce écoulé : éléments purgés, plus rien à restaurer, marqueurs retirés.
        sync(full = false, emptyList(), now = t0 + MISSING_GRACE_MS)
        assertNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A"))
        assertTrue(markedFolders().isEmpty())
        assertEquals(setOf("ROOT"), liveFolders())

        // Le dossier revenu ensuite est un dossier ordinaire : pas d'énumération complète.
        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("PH", "Photos")), run, t0 + MISSING_GRACE_MS)
        assertFalse(run.restoredFolderWithHiddenItems)
    }

    @Test
    fun deletedFolderWithoutItems_isNotKeptAsMarker() = runTest {
        sync(full = true, listOf(root(), folder("EMPTY", "Vide"), image("A", "ROOT")))
        sync(full = false, listOf(deleted("EMPTY")))
        assertTrue(markedFolders().isEmpty())
        assertEquals(setOf("ROOT"), liveFolders())
    }

    @Test
    fun hiddenItemsWithoutAnyFolderRow_stillRequestFullEnumeration() = runTest {
        // Données d'avant la v5 : dossier supprimé sans marqueur, éléments masqués orphelins.
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        sync(full = false, listOf(deleted("PH")))
        db.cloudFolderDao().deleteByIds(listOf("PH"))
        seedCursor()

        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("NEW", "Nouveau")), run, t0)
        assertTrue(run.restoredFolderWithHiddenItems)
    }

    @Test
    fun newFolder_withoutHiddenItems_keepsIncrementalSync() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        // Elément supprimé dans un dossier toujours présent : pas une restauration de dossier.
        sync(full = false, listOf(deleted("A")))
        seedCursor()
        val run = CloudSyncRun(full = false)
        writer.applyPage(listOf(folder("NEW", "Nouveau"), image("B", "NEW")), run, t0)
        assertFalse(run.restoredFolderWithHiddenItems)
        assertNotNull(db.syncCursorDao().get(ONEDRIVE_PROVIDER)!!.cursor)
    }

    @Test
    fun fullEnumeration_hidesUnseenItemsOnly() = runTest {
        val local = seedLocal()
        sync(full = true, listOf(root(), folder("OLD", "Ancien"), image("A", "ROOT"), image("B", "OLD")))
        sync(full = true, listOf(root(), image("A", "ROOT")))
        assertTrue(mediaOf("A")!!.hasCloud)
        val b = mediaOf("B")!!
        assertFalse(b.hasCloud)
        // Dossier non vu : marqué supprimé (son élément masqué peut revenir), son album sans
        // personnalisation est retiré.
        assertEquals(setOf("OLD"), markedFolders())
        assertNull(db.albumDao().getBySourceKey("onedrive:OLD"))
        assertLocalUntouched(local)
    }

    @Test
    fun folderRename_renamesAlbumAndPaths() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), folder("Y24", "2024", parent = "PH"), image("A", "Y24")))
        assertEquals("OneDrive/Photos/2024/", db.albumDao().getBySourceKey("onedrive:Y24")!!.relativePath)

        sync(full = false, listOf(folder("PH", "Images")))
        val album = db.albumDao().getBySourceKey("onedrive:Y24")!!
        assertEquals("2024", album.name)
        assertEquals("OneDrive/Images/2024/", album.relativePath)
        assertEquals("OneDrive/Images/2024/", db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A")!!.relativePath)
    }

    @Test
    fun movedItem_changesFolderAlbum() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), folder("TR", "Travail"), image("A", "PH")))
        sync(full = false, listOf(image("A", "TR")))
        val trAlbum = db.albumDao().getBySourceKey("onedrive:TR")!!
        assertEquals(listOf(mediaOf("A")!!.id), db.mediaDao().observeAlbumIds(trAlbum.id).first())
        // L'ancien dossier n'a plus d'élément : son album sans personnalisation disparaît.
        assertNull(db.albumDao().getBySourceKey("onedrive:PH"))
    }

    @Test
    fun nonMediaFile_isIgnored() = runTest {
        val pdf = image("DOC", "ROOT").copy(file = FileFacetDto(mimeType = "application/pdf"), image = null)
        sync(full = true, listOf(root(), pdf))
        assertNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "DOC"))
        // Seul le dossier racine est indexé : aucune donnée de l'utilisateur à protéger.
        assertEquals(1, db.cloudFolderDao().getAll().size)
        assertFalse(writer.hasIndexedData())
        assertEquals(0, writer.visibleCount())
    }

    // --- Copies hors ligne ---------------------------------------------------------------

    /** Copie hors ligne enregistrée pour l'élément [itemId] (comme le moteur la crée). */
    private suspend fun pinOffline(itemId: String, cTag: String = "c1"): Long {
        val mediaId = mediaOf(itemId)!!.id
        db.mediaSourceDao().insert(
            MediaSourceEntity(
                mediaId = mediaId, kind = SourceKind.OFFLINE_COPY, externalId = itemId, uri = null, bucketKey = null,
                bucketName = null, relativePath = null, sizeBytes = 100, dateModified = 1, cTag = cTag,
            ),
        )
        db.cachedFileDao().replace(
            CachedFileEntity(
                mediaId = mediaId, sourceId = null, kind = CacheKind.OFFLINE, path = "$itemId.jpg", sizeBytes = 100,
                createdAt = 0, lastAccessAt = 0, pinned = true, version = cTag,
            ),
        )
        db.mediaDao().refreshSourceFlags(listOf(mediaId))
        return mediaId
    }

    private suspend fun offlineCopyOf(itemId: String) = db.mediaSourceDao().find(SourceKind.OFFLINE_COPY, itemId)

    @Test
    fun deletedItem_hidesItsOfflineCopy_andKeepsItAfterGracePeriodForUserDecision() = runTest {
        sync(full = true, listOf(root(), image("A", "ROOT"), image("B", "ROOT")))
        val a = pinOffline("A")
        val b = mediaOf("B")!!.id
        assertTrue(a in timeline(includeCloudOnly = false))

        sync(full = false, listOf(deleted("A"), deleted("B")))
        // Masquée avec son élément OneDrive : plus dans Photos.
        assertNotNull(offlineCopyOf("A")!!.missingSince)
        assertFalse(db.mediaDao().getById(a)!!.hasOffline)
        assertFalse(a in timeline(includeCloudOnly = false))

        // Délai de grâce écoulé : sans copie, purgé ; avec copie, gardé (masqué) en attente de décision.
        sync(full = false, emptyList(), now = t0 + MISSING_GRACE_MS)
        assertNull(db.mediaDao().getById(b))
        assertNotNull(db.mediaDao().getById(a))
        assertNotNull(db.mediaSourceDao().find(SourceKind.ONEDRIVE, "A"))
        assertNotNull(db.cachedFileDao().get(a, CacheKind.OFFLINE))
        assertEquals(listOf(a), db.mediaSourceDao().awaitingDecisionMediaIds(t0 + 1))
        // Passages suivants : toujours en attente, jamais purgé automatiquement.
        sync(full = false, emptyList(), now = t0 + 2 * MISSING_GRACE_MS)
        assertNotNull(db.mediaDao().getById(a))
    }

    @Test
    fun fullEnumeration_hidesUnseenItemsOfflineCopy() = runTest {
        sync(full = true, listOf(root(), image("A", "ROOT"), image("B", "ROOT")))
        val a = pinOffline("A")
        sync(full = true, listOf(root(), image("B", "ROOT")))
        assertNotNull(offlineCopyOf("A")!!.missingSince)
        assertFalse(db.mediaDao().getById(a)!!.hasOffline)
    }

    @Test
    fun deletedFolder_hidesOfflineCopiesOfItsContent() = runTest {
        sync(full = true, listOf(root(), folder("PH", "Photos"), image("A", "PH")))
        val a = pinOffline("A")
        sync(full = false, listOf(deleted("PH")))
        assertNotNull(offlineCopyOf("A")!!.missingSince)
        assertFalse(db.mediaDao().getById(a)!!.hasOffline)
        assertFalse(a in timeline(includeCloudOnly = false))
    }

    @Test
    fun restoredItem_showsItsOfflineCopyAgain() = runTest {
        sync(full = true, listOf(root(), image("A", "ROOT")))
        val a = pinOffline("A")
        sync(full = false, listOf(deleted("A")))
        assertFalse(db.mediaDao().getById(a)!!.hasOffline)
        sync(full = false, listOf(image("A", "ROOT")))
        assertNull(offlineCopyOf("A")!!.missingSince)
        assertTrue(db.mediaDao().getById(a)!!.hasOffline)
        assertTrue(a in timeline(includeCloudOnly = false))
    }

    @Test
    fun contentChange_ofPinnedItem_enqueuesExactlyOneRedownload_andDropsStaleOriginal() = runTest {
        sync(full = true, listOf(root(), image("A", "ROOT")))
        val a = pinOffline("A", cTag = "c1")
        db.cachedFileDao().replace(
            CachedFileEntity(
                mediaId = a, sourceId = null, kind = CacheKind.ORIGINAL, path = "orig.jpg", sizeBytes = 100,
                createdAt = 0, lastAccessAt = 0, pinned = false, version = "c1",
            ),
        )
        sync(full = false, listOf(image("A", "ROOT", cTag = "c2")))
        sync(full = false, listOf(image("A", "ROOT", cTag = "c2")))
        val task = db.downloadTaskDao().listOpen().single()
        assertEquals(a, task.mediaId)
        assertEquals("c2", task.cTag)
        assertEquals(DownloadState.QUEUED, task.state)
        // L'ancienne copie reste servie jusqu'au remplacement.
        assertTrue(db.mediaDao().getById(a)!!.hasOffline)
        assertEquals("c1", offlineCopyOf("A")!!.cTag)
        assertNull(db.cachedFileDao().get(a, CacheKind.ORIGINAL))
    }

    @Test
    fun unchangedContent_neverEnqueues() = runTest {
        sync(full = true, listOf(root(), image("A", "ROOT")))
        pinOffline("A", cTag = "c1")
        sync(full = false, listOf(image("A", "ROOT", cTag = "c1")))
        assertFalse(db.downloadTaskDao().existsAny())
    }

    @Test
    fun keptCopy_isRelinked_whenTheItemComesBackOnOneDrive() = runTest {
        sync(full = true, listOf(root(), image("A", "ROOT")))
        val a = pinOffline("A")
        db.favoriteDao().insertAll(listOf(FavoriteEntity(a, 1)))
        // « Les conserver » : plus de source OneDrive, la copie reste seule et visible.
        db.mediaSourceDao().deleteForMedia(SourceKind.ONEDRIVE, listOf(a))
        db.mediaSourceDao().syncOfflineCopyVisibility(listOf(a))
        db.mediaDao().refreshSourceFlags(listOf(a))
        assertTrue(a in timeline(includeCloudOnly = false))

        sync(full = false, listOf(image("A", "ROOT", cTag = "c1")))
        // Même média (favori conservé), aucun doublon.
        assertEquals(a, mediaOf("A")!!.id)
        assertTrue(db.favoriteDao().isFavorite(a))
        assertEquals(1, timeline(includeCloudOnly = true).count { it == a })
        assertEquals(1, db.mediaDao().observeTimelineCount(includeCloudOnly = true).first())
        assertTrue(db.mediaDao().getById(a)!!.hasCloud)
        assertTrue(db.mediaDao().getById(a)!!.hasOffline)
    }
}
