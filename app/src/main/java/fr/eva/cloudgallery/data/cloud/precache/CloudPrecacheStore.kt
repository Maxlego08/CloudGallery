package fr.eva.cloudgallery.data.cloud.precache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import okio.BufferedSource
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OneDrive thumbnails and previews cached by "Cache all of OneDrive": app-private files
 * (`noBackupFilesDir/onedrive_precache`), outside Coil's disk cache and its LRU cap, never evicted
 * automatically. Removed when the item changes (cTag) or disappears (cleanup after each pass), by
 * "Clear cache" and together with the OneDrive data (sign-out, purge).
 *
 * All methods do disk I/O: call them on the I/O dispatcher. Lightweight construction (no disk
 * access).
 */
@Singleton
class CloudPrecacheStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val generation: CloudDataGeneration,
) {
    private val root: File by lazy { File(context.noBackupFilesDir, PRECACHE_DIR) }
    private val tmpDir: File get() = File(root, TMP_DIR)

    /** Number of times the cache has been cleared (see [writeToken]). */
    private val clears = AtomicLong(0)

    /**
     * File saved for [ref] at the size best suited to [requested] px (see [precacheLookupSides]),
     * null if there is none.
     */
    fun find(ref: CloudThumbRef, kind: PrecacheKind, requested: Int, allowLower: Boolean): File? {
        if (!root.isDirectory) return null
        val hash = precacheHash(ref)
        for (side in precacheLookupSides(requested, kind, allowLower)) {
            val file = File(root, precacheRelativePath(hash, kind, side))
            if (file.isFile && file.length() > 0) return file
        }
        return null
    }

    /** True if the exact file ([side]) is already saved. */
    fun contains(ref: CloudThumbRef, kind: PrecacheKind, side: Int): Boolean =
        File(root, precacheRelativePath(precacheHash(ref), kind, side)).let { it.isFile && it.length() > 0 }

    /**
     * Token to take before a download and pass to [write]: changes when the OneDrive data is
     * cleared (sign-out, purge) or when the cache is cleared.
     */
    fun writeToken(): WriteToken = WriteToken(generation.value, clears.get())

    /**
     * Saves [body] for [ref]; returns the written size. Temporary file then rename: never a
     * visible partial file. Nothing is kept if the OneDrive data or the cache has been cleared
     * since [token] (sign-out or "Clear cache" during the download).
     */
    fun write(ref: CloudThumbRef, kind: PrecacheKind, side: Int, body: BufferedSource, token: WriteToken): Long {
        val target = File(root, precacheRelativePath(precacheHash(ref), kind, side))
        val tmp = File(tmpDir.apply { mkdirs() }, UUID.randomUUID().toString())
        try {
            tmp.sink().buffer().use { it.writeAll(body) }
            if (generation.value != token.generation) throw CloudAuthRequiredException()
            if (clears.get() != token.clears) throw PrecacheClearedException()
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) throw IOException("Could not save the cached file")
            return target.length()
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Deletes the files of items missing from [keep] ([precacheHash] fingerprints of the items
     * still concerned, in their current version) and abandoned temporary files. Returns the
     * number of deleted files.
     */
    fun retainOnly(keep: Set<String>): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        root.listFiles()?.forEach { dir ->
            if (dir.name == TMP_DIR) {
                dir.listFiles()?.forEach { if (it.delete()) removed++ }
                return@forEach
            }
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { file ->
                val hash = precacheHashOf(file.name)
                if (hash == null || hash !in keep) {
                    if (file.delete()) removed++
                }
            }
            // Empty directory: removed (it will be recreated if needed).
            if (dir.list()?.isEmpty() == true) dir.delete()
        }
        return removed
    }

    /** Total size (bytes) and number of saved files. */
    fun usage(): PrecacheUsage {
        if (!root.isDirectory) return PrecacheUsage(0L, 0)
        var bytes = 0L
        var count = 0
        root.walkTopDown().onFail { _, _ -> }.filter { it.isFile }.forEach {
            bytes += it.length()
            count++
        }
        return PrecacheUsage(bytes, count)
    }

    /** True if no saved file remains. */
    fun isEmpty(): Boolean = root.list()?.none { it != TMP_DIR } ?: true

    /** Free space and size of the cache's volume (bytes). */
    fun volumeSpace(): Pair<Long, Long> {
        val dir = context.noBackupFilesDir
        return dir.usableSpace to dir.totalSpace
    }

    /** Deletes everything ("Clear cache", OneDrive data removed). */
    fun clear() {
        clears.incrementAndGet()
        if (root.exists()) root.deleteRecursively()
    }

    /** Snapshot taken by [writeToken]. */
    data class WriteToken(val generation: Long, val clears: Long)

    internal companion object {
        const val PRECACHE_DIR = "onedrive_precache"
        private const val TMP_DIR = "tmp"
    }
}

/** Cache cleared during a download: the file is not kept, the pass stops. */
class PrecacheClearedException : IOException("Cache cleared")

/** Space used by the cache: bytes and number of files (thumbnails and previews). */
data class PrecacheUsage(val bytes: Long, val files: Int)
