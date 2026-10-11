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
        val folders = db.cloudFolderDao().getAll().map { it.itemId }.toSet()
        assertEquals(setOf("ROOT"), folders)
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
        // Les deux lignes de dossiers sont retirées (plus aucun élément visible).
        assertTrue(db.cloudFolderDao().getAll().none { it.itemId == "P" || it.itemId == "C" })
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
        // Dossier non vu et vide : retiré, ainsi que son album sans personnalisation.
        assertNull(db.cloudFolderDao().getAll().firstOrNull { it.itemId == "OLD" })
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
}
