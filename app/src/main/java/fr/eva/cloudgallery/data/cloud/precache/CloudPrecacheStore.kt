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
 * Miniatures et aperçus OneDrive mis en cache par « Mettre en cache tout OneDrive » : fichiers
 * privés de l'appli (`noBackupFilesDir/onedrive_precache`), hors du cache disque Coil et de son
 * plafond LRU, jamais évincés automatiquement. Retirés quand l'élément change (cTag) ou
 * disparaît (nettoyage après chaque passage), par « Vider le cache » et avec les données
 * OneDrive (déconnexion, purge).
 *
 * Toutes les méthodes font des E/S disque : à appeler sur le dispatcher d'E/S. Construction
 * légère (aucun accès disque).
 */
@Singleton
class CloudPrecacheStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val generation: CloudDataGeneration,
) {
    private val root: File by lazy { File(context.noBackupFilesDir, PRECACHE_DIR) }
    private val tmpDir: File get() = File(root, TMP_DIR)

    /** Nombre de vidages de la mise en cache (voir [writeToken]). */
    private val clears = AtomicLong(0)

    /**
     * Fichier enregistré pour [ref] à la taille la plus adaptée à [requested] px (voir
     * [precacheLookupSides]), null s'il n'y en a pas.
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

    /** Vrai si le fichier exact ([side]) est déjà enregistré. */
    fun contains(ref: CloudThumbRef, kind: PrecacheKind, side: Int): Boolean =
        File(root, precacheRelativePath(precacheHash(ref), kind, side)).let { it.isFile && it.length() > 0 }

    /**
     * Jeton à relever avant un téléchargement et à passer à [write] : change quand les données
     * OneDrive sont vidées (déconnexion, purge) ou quand la mise en cache est vidée.
     */
    fun writeToken(): WriteToken = WriteToken(generation.value, clears.get())

    /**
     * Enregistre [body] pour [ref] ; rend la taille écrite. Fichier temporaire puis renommage :
     * jamais de fichier partiel visible. Rien n'est gardé si les données OneDrive ou la mise en
     * cache ont été vidées depuis [token] (déconnexion ou « Vider le cache » pendant le téléchargement).
     */
    fun write(ref: CloudThumbRef, kind: PrecacheKind, side: Int, body: BufferedSource, token: WriteToken): Long {
        val target = File(root, precacheRelativePath(precacheHash(ref), kind, side))
        val tmp = File(tmpDir.apply { mkdirs() }, UUID.randomUUID().toString())
        try {
            tmp.sink().buffer().use { it.writeAll(body) }
            if (generation.value != token.generation) throw CloudAuthRequiredException()
            if (clears.get() != token.clears) throw PrecacheClearedException()
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) throw IOException("Enregistrement de la mise en cache impossible")
            return target.length()
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Supprime les fichiers des éléments absents de [keep] (empreintes [precacheHash] des
     * éléments encore concernés, dans leur version courante) et les fichiers temporaires
     * abandonnés. Rend le nombre de fichiers supprimés.
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
            // Dossier vide : retiré (il sera recréé au besoin).
            if (dir.list()?.isEmpty() == true) dir.delete()
        }
        return removed
    }

    /** Taille totale (octets) et nombre de fichiers enregistrés. */
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

    /** Vrai s'il reste au moins un fichier enregistré. */
    fun isEmpty(): Boolean = root.list()?.none { it != TMP_DIR } ?: true

    /** Espace libre et taille du volume de la mise en cache (octets). */
    fun volumeSpace(): Pair<Long, Long> {
        val dir = context.noBackupFilesDir
        return dir.usableSpace to dir.totalSpace
    }

    /** Supprime tout (« Vider le cache », données OneDrive retirées). */
    fun clear() {
        clears.incrementAndGet()
        if (root.exists()) root.deleteRecursively()
    }

    /** Relevé de [writeToken]. */
    data class WriteToken(val generation: Long, val clears: Long)

    internal companion object {
        const val PRECACHE_DIR = "onedrive_precache"
        private const val TMP_DIR = "tmp"
    }
}

/** Mise en cache vidée pendant un téléchargement : le fichier n'est pas gardé, le passage s'arrête. */
class PrecacheClearedException : IOException("Mise en cache vidée")

/** Espace occupé par la mise en cache : octets et nombre de fichiers (vignettes et aperçus). */
data class PrecacheUsage(val bytes: Long, val files: Int)
