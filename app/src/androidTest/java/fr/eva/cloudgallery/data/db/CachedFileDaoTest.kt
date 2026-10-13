package fr.eva.cloudgallery.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Copied OneDrive files (`cached_file`) and number of shareable items. */
@RunWith(AndroidJUnit4::class)
class CachedFileDaoTest {

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

    private fun media(name: String) = MediaEntity(
        mediaType = MediaType.IMAGE, displayName = name, mimeType = "image/jpeg", dateTaken = 1, dateAdded = 1,
        dateModified = 1, width = 1, height = 1, durationMs = 0, sizeBytes = 1, orientation = 0,
        primaryUri = null, hasLocal = false, hasCloud = false, hasOffline = false,
    )

    private fun original(mediaId: Long, path: String, version: String?, lastAccessAt: Long = 0) = CachedFileEntity(
        mediaId = mediaId, sourceId = null, kind = CacheKind.ORIGINAL, path = path, sizeBytes = 5,
        createdAt = 0, lastAccessAt = lastAccessAt, pinned = false, version = version,
    )

    /**
     * Replacing the (media, kind) copy updates THE existing row: a raw upsert with an
     * id of 0 would touch no row (unique index (mediaId, kind)) and would leave
     * the old path and the old version.
     */
    @Test
    fun replace_updatesTheExistingRow_andReturnsThePreviousOne() = runTest {
        val dao = db.cachedFileDao()
        val id = db.mediaDao().insert(media("a"))
        assertNull(dao.replace(original(id, "old.jpg", "c1")))
        val first = dao.get(id, CacheKind.ORIGINAL)!!
        assertEquals("old.jpg", first.path)

        val previous = dao.replace(original(id, "new.jpg", "c2", lastAccessAt = 9))
        assertEquals("old.jpg", previous?.path)
        assertEquals("c1", previous?.version)
        val current = dao.get(id, CacheKind.ORIGINAL)!!
        assertEquals(first.id, current.id)
        assertEquals("new.jpg", current.path)
        assertEquals("c2", current.version)
        assertEquals(9L, current.lastAccessAt)
        assertEquals(1, dao.listByKind(CacheKind.ORIGINAL).size)
    }

    @Test
    fun touch_deleteAndTotals() = runTest {
        val dao = db.cachedFileDao()
        val a = db.mediaDao().insert(media("a"))
        val b = db.mediaDao().insert(media("b"))
        dao.replace(original(a, "a.jpg", "c1"))
        dao.replace(original(b, "b.jpg", "c1"))
        assertTrue(dao.existsAny())
        assertEquals(10L, dao.observeTotalSize(CacheKind.ORIGINAL).first())
        assertEquals(0L, dao.observeTotalSize(CacheKind.OFFLINE).first())

        val rowA = dao.get(a, CacheKind.ORIGINAL)!!
        dao.touch(rowA.id, 42)
        assertEquals(42L, dao.get(a, CacheKind.ORIGINAL)!!.lastAccessAt)
        assertEquals(2, dao.getMany(listOf(a, b), CacheKind.ORIGINAL).size)

        dao.deleteForMedia(listOf(a), CacheKind.ORIGINAL)
        assertNull(dao.get(a, CacheKind.ORIGINAL))
        dao.deleteByIds(listOf(dao.get(b, CacheKind.ORIGINAL)!!.id))
        assertFalse(dao.existsAny())
    }

    @Test
    fun rowsFollowTheirMedia() = runTest {
        val dao = db.cachedFileDao()
        val a = db.mediaDao().insert(media("a"))
        dao.replace(original(a, "a.jpg", "c1"))
        db.mediaDao().deleteByIds(listOf(a))
        // Cascading foreign key: the file itself is removed by the startup cleanup.
        assertFalse(dao.existsAny())
    }

    @Test
    fun countShareable_localOfflineOrCloud() = runTest {
        val mediaDao = db.mediaDao()
        val sourceDao = db.mediaSourceDao()
        val local = mediaDao.insert(media("local"))
        sourceDao.insert(
            MediaSourceEntity(
                mediaId = local, kind = SourceKind.LOCAL, externalId = "image:1",
                uri = "content://media/external/images/media/1", bucketKey = "local:1", bucketName = null,
                relativePath = null, sizeBytes = 1, dateModified = 1,
            ),
        )
        val cloud = mediaDao.insert(media("cloud"))
        sourceDao.insert(
            MediaSourceEntity(
                mediaId = cloud, kind = SourceKind.ONEDRIVE, externalId = "C1", uri = null,
                bucketKey = "onedrive:ROOT", bucketName = null, relativePath = null, sizeBytes = 1, dateModified = 1,
            ),
        )
        val hidden = mediaDao.insert(media("hidden"))
        sourceDao.insert(
            MediaSourceEntity(
                mediaId = hidden, kind = SourceKind.ONEDRIVE, externalId = "C2", uri = null,
                bucketKey = "onedrive:ROOT", bucketName = null, relativePath = null, sizeBytes = 1, dateModified = 1,
                missingSince = 5,
            ),
        )
        mediaDao.refreshSourceFlags(listOf(local, cloud, hidden))

        assertEquals(1, mediaDao.countWithLocalFile(listOf(local, cloud, hidden)))
        // Visible OneDrive item: shareable (original downloaded); hidden: not.
        assertEquals(2, mediaDao.countShareable(listOf(local, cloud, hidden)))
        assertEquals(0, mediaDao.countShareable(listOf(hidden)))
    }

    /**
     * Cleanup: a row is only deleted if it has not changed since it was read (same
     * file, no access since). A row reopened or replaced in the meantime is kept.
     */
    @Test
    fun deleteIfUnchanged_keepsTouchedOrReplacedRows() = runTest {
        val dao = db.cachedFileDao()
        val a = db.mediaDao().insert(media("a"))
        val b = db.mediaDao().insert(media("b"))
        val c = db.mediaDao().insert(media("c"))
        dao.replace(original(a, "a.jpg", "c1", lastAccessAt = 1))
        dao.replace(original(b, "b.jpg", "c1", lastAccessAt = 1))
        dao.replace(original(c, "c.jpg", "c1", lastAccessAt = 1))
        val rowA = dao.get(a, CacheKind.ORIGINAL)!!
        val rowB = dao.get(b, CacheKind.ORIGINAL)!!
        val rowC = dao.get(c, CacheKind.ORIGINAL)!!
        // B reopened (viewer, share) after the cleanup read it.
        dao.touch(rowB.id, 50)
        // C replaced by a new version (same row, different file).
        dao.replace(original(c, "c2.jpg", "c2", lastAccessAt = 1))

        assertEquals(1, dao.deleteIfUnchanged(rowA.id, rowA.path, rowA.lastAccessAt))
        assertEquals(0, dao.deleteIfUnchanged(rowB.id, rowB.path, rowB.lastAccessAt))
        assertEquals(0, dao.deleteIfUnchanged(rowC.id, rowC.path, rowC.lastAccessAt))
        assertNull(dao.get(a, CacheKind.ORIGINAL))
        assertEquals("b.jpg", dao.get(b, CacheKind.ORIGINAL)!!.path)
        assertEquals("c2.jpg", dao.get(c, CacheKind.ORIGINAL)!!.path)
    }
}
