package fr.eva.cloudgallery.data.cloud.offline

import android.content.Context
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.transfer.PART_SUFFIX
import fr.eva.cloudgallery.data.cloud.transfer.SweepEntry
import fr.eva.cloudgallery.data.cloud.transfer.filesToSweep
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline copy directory: `filesDir/offline/`, private to the app, excluded from backups
 * (backup_rules, data_extraction_rules) and never cleared by the system (unlike the cache).
 * Only copies of OneDrive files created by the app: no user files, nothing in MediaStore. File
 * names carry no user name (see `cacheFileName`) and are shared as-is by the FileProvider
 * (`<files-path name="offline" path="offline/"/>`).
 *
 * All file and StorageManager operations go through the I/O dispatcher; the directory path itself
 * is resolved on first use (getFilesDir may create the directory).
 */
@Singleton
class OfflineStore internal constructor(
    rootProvider: () -> File,
    private val storage: () -> StorageManager?,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) : OfflineSpace {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        rootProvider = { File(context.filesDir, OFFLINE_DIR) },
        storage = { context.getSystemService(StorageManager::class.java) },
        io = io,
        clock = System::currentTimeMillis,
    )

    /** Copy directory (no I/O until it is used). */
    val root: File by lazy(rootProvider)

    /** File of a recorded copy ([name] relative to the directory), null if the name is not safe. */
    fun fileOf(name: String): File? {
        if (!isSafeName(name)) return null
        return File(root, name)
    }

    /** Partial file (download in progress or to be resumed) of a copy. */
    fun partOf(name: String): File? = fileOf(name)?.let { File(root, it.name + PART_SUFFIX) }

    /** Creates the directory if needed; throws [IOException] if it is inaccessible. */
    suspend fun ensureRoot(): File = withContext(io) {
        val dir = root
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Offline directory inaccessible")
        dir
    }

    /** Volume hosting the copies (for the system "Manage space" screen). */
    suspend fun storageUuid(): UUID? = withContext(io) {
        try {
            storage()?.getUuidForPath(ensureRoot())
        } catch (_: IOException) {
            null
        }
    }

    /** Free space on the copies' volume (without freeing other apps' cache). */
    override suspend fun usableBytes(): Long = withContext(io) { ensureRoot().usableSpace }

    /**
     * Space usable for the copies, including other apps' cache that the system can free
     * (StorageManager.getAllocatableBytes). Fallback: free space on the volume.
     */
    override suspend fun allocatableBytes(): Long = withContext(io) {
        val dir = ensureRoot()
        val sm = storage()
        try {
            if (sm != null) sm.getAllocatableBytes(sm.getUuidForPath(dir)) else dir.usableSpace
        } catch (_: IOException) {
            dir.usableSpace
        }
    }

    /**
     * Asks the system to free [bytes] bytes for the copies (other apps' cache if needed). Called
     * at most once per job pass (as recommended by the docs).
     * Throws [IOException] if the space cannot be obtained.
     */
    override suspend fun reserve(bytes: Long): Unit = withContext(io) {
        if (bytes <= 0) return@withContext
        val sm = storage() ?: return@withContext
        sm.allocateBytes(sm.getUuidForPath(ensureRoot()), bytes)
    }

    /** Deletes the copies (and their partial files) with these names. App files only. */
    suspend fun delete(names: Collection<String>): Unit = withContext(io) {
        for (name in names) {
            fileOf(name)?.delete()
            partOf(name)?.delete()
        }
    }

    /** Deletes the partial files with these names (cancelled downloads). */
    suspend fun deleteParts(names: Collection<String>): Unit = withContext(io) {
        for (name in names) partOf(name)?.delete()
    }

    /**
     * Empties the copy directory (OneDrive data purge). Only deletes the contents of
     * `filesDir/offline`, checked before any deletion.
     */
    suspend fun clear(): Unit = withContext(io) {
        val dir = root
        if (dir.name != OFFLINE_DIR) return@withContext
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    /**
     * Files without a `cached_file` row ([known] = recorded names) older than one hour, and
     * partial files abandoned for more than 24 h; never those of an open task ([busy], base
     * names: resume possible). A copy just renamed but not yet recorded is less than one hour
     * old: it is never touched (see [filesToSweep]).
     */
    suspend fun sweepOrphans(known: Set<String>, busy: Set<String>): Int = withContext(io) {
        val now = clock()
        val files = root.listFiles().orEmpty().associateBy { it.name }
        val entries = files.values.map { SweepEntry(it.name, it.isFile, now - it.lastModified()) }
        filesToSweep(entries, known, busy).count { name -> files[name]?.delete() == true }
    }

    companion object {
        /** Copy directory under `filesDir` (exposed for sharing through FileProvider). */
        const val OFFLINE_DIR = "offline"

        /** Plain name, with no separator nor `.`/`..` (names come from the database, never from the user). */
        internal fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') &&
                !name.contains('\u0000')
    }
}
