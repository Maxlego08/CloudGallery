package fr.eva.cloudgallery.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.storage.spaceSaverSignature
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Copies locales proposées par l'économiseur d'espace : empreinte à jour, élément OneDrive
 * visible de même empreinte et taille, regroupées ou non dans un même média.
 */
@RunWith(AndroidJUnit4::class)
class SpaceSaverDaoTest {

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

    private suspend fun media(name: String, dateTaken: Long = 1): Long = db.mediaDao().insert(
        MediaEntity(
            mediaType = MediaType.IMAGE, displayName = name, mimeType = "image/jpeg", dateTaken = dateTaken, dateAdded = 1,
            dateModified = 1, width = 1, height = 1, durationMs = 0, sizeBytes = 10, orientation = 0,
            primaryUri = null, hasLocal = true, hasCloud = false, hasOffline = false,
        ),
    )

    private suspend fun local(
        mediaId: Long,
        externalId: String,
        hash: String? = "H",
        size: Long = 10,
        signature: String? = spaceSaverSignature(size, 5, 3),
        missingSince: Long? = null,
    ): Long = db.mediaSourceDao().insert(
        MediaSourceEntity(
            mediaId = mediaId, kind = SourceKind.LOCAL, externalId = externalId,
            uri = "content://media/external/images/media/$externalId", bucketKey = "local:1", bucketName = "Camera",
            relativePath = "DCIM/Camera/", sizeBytes = size, dateModified = 5, generation = 3,
            quickXorHash = hash, hashSignature = signature, missingSince = missingSince,
        ),
    )

    private suspend fun cloud(mediaId: Long, itemId: String, hash: String = "H", size: Long = 10, missingSince: Long? = null) =
        db.mediaSourceDao().insert(
            MediaSourceEntity(
                mediaId = mediaId, kind = SourceKind.ONEDRIVE, externalId = itemId, uri = null,
                bucketKey = "onedrive:P", bucketName = "Photos", relativePath = null, sizeBytes = size,
                dateModified = 7, quickXorHash = hash, missingSince = missingSince,
            ),
        )

    @Test
    fun candidates_requireAnUpToDateHashMatchingAVisibleOneDriveItem() = runTest {
        val dao = db.mediaSourceDao()
        // Regroupés dans un même média.
        val merged = media("merged.jpg", dateTaken = 3)
        val mergedSource = local(merged, "1")
        cloud(merged, "A")
        // Médias distincts (pas encore regroupés) : proposés aussi.
        val separate = media("separate.jpg", dateTaken = 2)
        val separateSource = local(separate, "2", hash = "S")
        cloud(media("separate-cloud.jpg"), "B", hash = "S")
        // Écartés : empreinte périmée, empreinte jamais calculée, taille différente, OneDrive masqué,
        // copie locale masquée, aucun élément OneDrive.
        local(media("stale.jpg"), "3", hash = "T", signature = "10:4:3").also { cloud(media("c3"), "C", hash = "T") }
        local(media("nohash.jpg"), "4", hash = null, signature = null)
        local(media("size.jpg"), "5", hash = "Z", size = 11).also { cloud(media("c5"), "E", hash = "Z", size = 12) }
        local(media("hidden-cloud.jpg"), "6", hash = "K").also { cloud(media("c6"), "F", hash = "K", missingSince = 1) }
        local(media("hidden-local.jpg"), "7", hash = "L", missingSince = 1).also { cloud(media("c7"), "G", hash = "L") }
        local(media("alone.jpg"), "8", hash = "N")

        val rows = dao.spaceSaverCandidates()
        assertEquals(listOf(mergedSource, separateSource), rows.map { it.sourceId })
        assertTrue(rows[0].mergedWithCloud)
        assertFalse(rows[1].mergedWithCloud)
        assertEquals("content://media/external/images/media/1", rows[0].uri)
        assertEquals(listOf(separateSource), dao.spaceSaverCandidatesAmong(listOf(separateSource, 9_999)).map { it.sourceId })
    }

    @Test
    fun candidates_flagFavoritesAndVirtualAlbumMembers() = runTest {
        val dao = db.mediaSourceDao()
        val fav = media("fav.jpg")
        local(fav, "1")
        cloud(fav, "A")
        db.favoriteDao().insertAll(listOf(FavoriteEntity(fav, 1)))
        val member = media("member.jpg")
        local(member, "2", hash = "M")
        cloud(media("m-cloud"), "B", hash = "M")
        val albumId = db.albumDao().insert(
            AlbumEntity(name = "Vacances", type = AlbumType.VIRTUAL, sourceKey = null, relativePath = null, createdAt = 0, updatedAt = 0),
        )
        db.albumDao().insertMembers(listOf(AlbumMediaEntity(albumId, member, 0)))
        val plain = media("plain.jpg")
        local(plain, "3", hash = "P")
        cloud(media("p-cloud"), "C", hash = "P")

        val byName = dao.spaceSaverCandidates().associateBy { it.displayName }
        assertTrue(byName.getValue("fav.jpg").hasUserData)
        assertTrue(byName.getValue("member.jpg").hasUserData)
        assertFalse(byName.getValue("plain.jpg").hasUserData)
    }
}
