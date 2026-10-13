package fr.eva.cloudgallery.data.storage

import java.io.File

/*
 * Measures the space used by the app's folders (tested on the JVM).
 */

/**
 * Total size of the regular files in [dir] and its subfolders; 0 if the folder does not exist.
 * Symbolic links are not followed (the app never creates any).
 */
internal fun directoryBytes(dir: File): Long {
    if (!dir.isDirectory) return 0L
    return dir.walkTopDown()
        .onFail { _, _ -> }
        .filter { it.isFile }
        .sumOf { it.length() }
}
