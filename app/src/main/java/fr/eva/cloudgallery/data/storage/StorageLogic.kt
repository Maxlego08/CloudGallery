package fr.eva.cloudgallery.data.storage

import java.io.File

/*
 * Mesure de l'espace occupé par les dossiers de l'appli (testée en JVM).
 */

/**
 * Taille totale des fichiers ordinaires de [dir] et de ses sous-dossiers ; 0 si le dossier
 * n'existe pas. Les liens symboliques ne sont pas suivis (aucun n'est créé par l'appli).
 */
internal fun directoryBytes(dir: File): Long {
    if (!dir.isDirectory) return 0L
    return dir.walkTopDown()
        .onFail { _, _ -> }
        .filter { it.isFile }
        .sumOf { it.length() }
}
