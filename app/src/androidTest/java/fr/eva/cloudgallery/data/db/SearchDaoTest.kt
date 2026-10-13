package fr.eva.cloudgallery.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.search.ftsMatchExpression
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * `media_fts` search index on an in-memory database: population, incremental update
 * (rename, folder, album, deletion), date labels, result visibility.
 */
@RunWith(AndroidJUnit4::class)
class SearchDaoTest {

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

    /** August 12, 2026 at noon, device local time (a Wednesday). */
    private val august12 = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 12, 12, 0, 0) }.timeInMillis

    private suspend fun insertMedia(
        name: String,
        type: MediaType = MediaType.IMAGE,
        dateTaken: Long = august12,
        hasLocal: Boolean = true,
        hasCloud: Boolean = false,
    ): Long = db.mediaDao().insert(
        MediaEntity(
            mediaType = type,
            displayName = name,
            mimeType = null,
            dateTaken = dateTaken,
            dateAdded = dateTaken,
            dateModified = dateTaken,
            width = 1,
            height = 1,
            durationMs = 0,
            sizeBytes = 1,
            orientation = 0,
            primaryUri = null,
            hasLocal = hasLocal,
            hasCloud = hasCloud,
            hasOffline = false,
        ),
    )

    private suspend fun insertSource(mediaId: Long, bucket: String, missing: Boolean = false, kind: SourceKind = SourceKind.LOCAL) {
        db.mediaSourceDao().insert(
            MediaSourceEntity(
                mediaId = mediaId,
                kind = kind,
                externalId = "$kind-$mediaId-$bucket",
                uri = null,
                bucketKey = "local:$bucket",
                bucketName = bucket,
                relativePath = null,
                sizeBytes = 1,
                dateModified = 0,
                missingSince = if (missing) 1L else null,
            ),
        )
    }

    private suspend fun search(input: String): List<Long> =
        db.searchDao().observeSearchIds(ftsMatchExpression(input)!!).first()

    @Test
    fun refresh_indexesNameFoldersDatesAndType() = runTest {
        val beach = insertMedia("IMG_0001.jpg")
        insertSource(beach, "Vacances")
        val clip = insertMedia("clip.mp4", type = MediaType.VIDEO)
        insertSource(clip, "Camera")
        db.searchDao().refreshIndex()

        assertEquals(2, db.searchDao().entryCount())
        assertEquals(listOf(beach), search("vacances"))
        assertEquals(listOf(beach), search("img"))
        assertEquals(setOf(beach, clip), search("août 2026").toSet())
        assertEquals(setOf(beach, clip), search("AUGUST 12").toSet())
        assertEquals(setOf(beach, clip), search("mercredi").toSet())
        assertEquals(listOf(clip), search("vidéo"))
        // Accents ignored (unicode61) and prefix match.
        assertEquals(listOf(clip), search("video cam"))
        assertEquals(emptyList<Long>(), search("hiver"))
    }

    @Test
    fun refresh_isIncremental_forRenamesFoldersAlbumsAndDeletions() = runTest {
        val id = insertMedia("a.jpg")
        insertSource(id, "Camera")
        val other = insertMedia("b.jpg")
        db.searchDao().refreshIndex()

        // Rename: only the media's entry changes.
        db.mediaDao().update(db.mediaDao().getById(id)!!.copy(displayName = "plage.jpg"))
        assertEquals(1, db.searchDao().refreshIndex())
        assertEquals(listOf(id), search("plage"))

        // Virtual album: its name becomes searchable.
        val albumId = db.albumDao().insert(AlbumEntity(name = "Anniversaire", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0))
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(albumId, other, 0)))
        db.searchDao().refreshIndex()
        assertEquals(listOf(other), search("anniv"))

        // Deleted media: entry removed; nothing else changes afterwards.
        db.mediaDao().deleteByIds(listOf(other))
        db.searchDao().refreshIndex()
        assertEquals(1, db.searchDao().entryCount())
        assertEquals(0, db.searchDao().refreshIndex())
    }

    @Test
    fun results_includeVisibleOneDriveItems_butNotHiddenOnesOrHiddenFolders() = runTest {
        val cloudOnly = insertMedia("nuage.jpg", hasLocal = false, hasCloud = true)
        insertSource(cloudOnly, "Photos", kind = SourceKind.ONEDRIVE)
        val hidden = insertMedia("cache.jpg", hasLocal = false)
        insertSource(hidden, "Corbeille", missing = true)
        db.searchDao().refreshIndex()

        assertEquals(listOf(cloudOnly), search("nuage"))
        assertEquals(1, db.searchDao().observeSearchCount(ftsMatchExpression("photos")!!).first())
        assertEquals(1, db.searchDao().observeSearchDates(ftsMatchExpression("photos")!!).first().size)
        // Media without a visible source: absent from results; hidden folder: not indexed.
        assertEquals(emptyList<Long>(), search("cache"))
        assertEquals(emptyList<Long>(), search("corbeille"))
    }
}
