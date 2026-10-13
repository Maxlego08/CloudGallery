package fr.eva.cloudgallery.data.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Measuring the space used by an app folder. */
class StorageLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun directoryBytes_sumsFilesRecursively_includingPartialFiles() {
        val root = tmp.newFolder("originals")
        File(root, "a.jpg").writeBytes(ByteArray(100))
        File(root, "b.mp4.part").writeBytes(ByteArray(40))
        File(root, "sub").mkdir()
        File(root, "sub/c.heic").writeBytes(ByteArray(7))
        assertEquals(147L, directoryBytes(root))
    }

    @Test
    fun directoryBytes_isZeroForMissingOrEmptyDirectories() {
        assertEquals(0L, directoryBytes(File(tmp.root, "absent")))
        assertEquals(0L, directoryBytes(tmp.newFolder("empty")))
        // A file is not a cache folder.
        assertEquals(0L, directoryBytes(tmp.newFile("file.bin").apply { writeBytes(ByteArray(5)) }))
    }
}
