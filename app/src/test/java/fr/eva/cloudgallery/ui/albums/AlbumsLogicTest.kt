package fr.eva.cloudgallery.ui.albums

import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumsLogicTest {

    private fun album(
        id: Long,
        type: AlbumType = AlbumType.LOCAL_FOLDER,
        count: Int = 3,
        pinned: Boolean = false,
    ) = AlbumSummary(
        id = id,
        name = "Album $id",
        type = type,
        sourceKey = null,
        relativePath = null,
        mediaCount = count,
        coverMediaId = null,
        coverUri = null,
        coverType = null,
        coverDateModified = 0L,
        latestDate = 0L,
        isPinned = pinned,
    )

    @Test
    fun columnCount_portraitAndLandscape() {
        assertEquals(3, AlbumsLogic.columnCount(isLandscape = false))
        assertEquals(5, AlbumsLogic.columnCount(isLandscape = true))
    }

    @Test
    fun visibleAlbums_hidesEmptyFoldersButKeepsEmptyVirtual() {
        val albums = listOf(
            album(1, AlbumType.LOCAL_FOLDER, count = 0),
            album(2, AlbumType.CLOUD_FOLDER, count = 0),
            album(3, AlbumType.VIRTUAL, count = 0),
            album(4, AlbumType.LOCAL_FOLDER, count = 2),
        )
        assertEquals(listOf(3L, 4L), AlbumsLogic.visibleAlbums(albums).map { it.id })
    }

    @Test
    fun selectionActions_empty() {
        val a = AlbumsLogic.selectionActions(emptyList())
        assertFalse(a.canRename)
        assertFalse(a.canPin)
        assertFalse(a.canDelete)
    }

    @Test
    fun selectionActions_renameOnlyWithSingleSelection() {
        assertTrue(AlbumsLogic.selectionActions(listOf(album(1))).canRename)
        assertFalse(AlbumsLogic.selectionActions(listOf(album(1), album(2))).canRename)
    }

    @Test
    fun selectionActions_deleteOnlyWhenAllVirtual() {
        assertTrue(AlbumsLogic.selectionActions(listOf(album(1, AlbumType.VIRTUAL), album(2, AlbumType.VIRTUAL))).canDelete)
        assertFalse(AlbumsLogic.selectionActions(listOf(album(1, AlbumType.VIRTUAL), album(2))).canDelete)
        assertFalse(AlbumsLogic.selectionActions(listOf(album(1, AlbumType.CLOUD_FOLDER))).canDelete)
    }

    @Test
    fun selectionActions_pinTarget() {
        assertTrue(AlbumsLogic.selectionActions(listOf(album(1, pinned = true), album(2))).pinTarget)
        assertFalse(AlbumsLogic.selectionActions(listOf(album(1, pinned = true), album(2, pinned = true))).pinTarget)
        assertTrue(AlbumsLogic.selectionActions(listOf(album(1))).canPin)
    }

    @Test
    fun toggleAndToggleAll() {
        val albums = listOf(album(1), album(2))
        assertEquals(setOf(1L), AlbumsLogic.toggle(emptySet(), 1L))
        assertEquals(emptySet<Long>(), AlbumsLogic.toggle(setOf(1L), 1L))
        assertEquals(setOf(1L, 2L), AlbumsLogic.toggleAll(setOf(1L), albums))
        assertEquals(emptySet<Long>(), AlbumsLogic.toggleAll(setOf(1L, 2L), albums))
        assertFalse(AlbumsLogic.isAllSelected(emptySet(), emptyList()))
    }

    @Test
    fun pruneSelection_removesMissingAlbums() {
        val albums = listOf(album(1), album(2))
        assertEquals(setOf(1L), AlbumsLogic.pruneSelection(setOf(1L, 9L), albums))
        val same = setOf(1L, 2L)
        assertSame(same, AlbumsLogic.pruneSelection(same, albums))
    }

    @Test
    fun renameIsDisplayOnly_forFolders() {
        assertTrue(AlbumsLogic.renameIsDisplayOnly(album(1, AlbumType.LOCAL_FOLDER)))
        assertTrue(AlbumsLogic.renameIsDisplayOnly(album(1, AlbumType.CLOUD_FOLDER)))
        assertFalse(AlbumsLogic.renameIsDisplayOnly(album(1, AlbumType.VIRTUAL)))
    }

    @Test
    fun normalizeName() {
        assertEquals("Vacances 2026", AlbumsLogic.normalizeName("  Vacances   2026 "))
        assertNull(AlbumsLogic.normalizeName("   "))
    }
}
