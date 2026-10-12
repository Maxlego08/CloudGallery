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
 * Dossier des copies hors ligne : `filesDir/offline/`, privé à l'appli, exclu des sauvegardes
 * (backup_rules, data_extraction_rules) et jamais vidé par le système (contrairement au cache).
 * Uniquement des copies de fichiers OneDrive créées par l'appli : aucun fichier de l'utilisateur,
 * rien dans MediaStore. Noms de fichiers sans nom d'utilisateur (voir `cacheFileName`), partagés
 * tels quels par le FileProvider (`<files-path name="offline" path="offline/"/>`).
 *
 * Toutes les opérations sur fichiers et sur StorageManager passent par le dispatcher d'E/S ; le
 * chemin du dossier lui-même est résolu au premier usage (getFilesDir peut créer le dossier).
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

    /** Dossier des copies (aucune E/S tant qu'il n'est pas utilisé). */
    val root: File by lazy(rootProvider)

    /** Fichier d'une copie enregistrée ([name] relatif au dossier), null si le nom n'est pas sûr. */
    fun fileOf(name: String): File? {
        if (!isSafeName(name)) return null
        return File(root, name)
    }

    /** Fichier partiel (téléchargement en cours ou à reprendre) d'une copie. */
    fun partOf(name: String): File? = fileOf(name)?.let { File(root, it.name + PART_SUFFIX) }

    /** Crée le dossier si besoin ; lève [IOException] s'il est inaccessible. */
    suspend fun ensureRoot(): File = withContext(io) {
        val dir = root
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Dossier hors ligne inaccessible")
        dir
    }

    /** Volume qui héberge les copies (pour l'écran système « Gérer l'espace »). */
    suspend fun storageUuid(): UUID? = withContext(io) {
        try {
            storage()?.getUuidForPath(ensureRoot())
        } catch (_: IOException) {
            null
        }
    }

    /** Espace libre du volume des copies (sans libérer le cache d'autres applis). */
    override suspend fun usableBytes(): Long = withContext(io) { ensureRoot().usableSpace }

    /**
     * Espace utilisable pour les copies, y compris le cache d'autres applis que le système peut
     * libérer (StorageManager.getAllocatableBytes). Repli : espace libre du volume.
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
     * Demande au système de libérer [bytes] octets pour les copies (cache d'autres applis si
     * nécessaire). Appelé au plus une fois par passage du travail (recommandation de la doc).
     * Lève [IOException] si l'espace ne peut pas être obtenu.
     */
    override suspend fun reserve(bytes: Long): Unit = withContext(io) {
        if (bytes <= 0) return@withContext
        val sm = storage() ?: return@withContext
        sm.allocateBytes(sm.getUuidForPath(ensureRoot()), bytes)
    }

    /** Supprime les copies (et leurs fichiers partiels) de ces noms. Fichiers de l'appli uniquement. */
    suspend fun delete(names: Collection<String>): Unit = withContext(io) {
        for (name in names) {
            fileOf(name)?.delete()
            partOf(name)?.delete()
        }
    }

    /** Supprime les fichiers partiels de ces noms (téléchargements annulés). */
    suspend fun deleteParts(names: Collection<String>): Unit = withContext(io) {
        for (name in names) partOf(name)?.delete()
    }

    /**
     * Vide le dossier des copies (purge des données OneDrive). Ne supprime que le contenu de
     * `filesDir/offline`, vérifié avant toute suppression.
     */
    suspend fun clear(): Unit = withContext(io) {
        val dir = root
        if (dir.name != OFFLINE_DIR) return@withContext
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    /**
     * Fichiers sans ligne `cached_file` ([known] = noms enregistrés) plus vieux d'une heure, et
     * fichiers partiels abandonnés depuis plus de 24 h ; jamais ceux d'une tâche ouverte
     * ([busy], noms de base : reprise possible). Une copie juste renommée mais pas encore
     * enregistrée a moins d'une heure : elle n'est jamais touchée (voir [filesToSweep]).
     */
    suspend fun sweepOrphans(known: Set<String>, busy: Set<String>): Int = withContext(io) {
        val now = clock()
        val files = root.listFiles().orEmpty().associateBy { it.name }
        val entries = files.values.map { SweepEntry(it.name, it.isFile, now - it.lastModified()) }
        filesToSweep(entries, known, busy).count { name -> files[name]?.delete() == true }
    }

    companion object {
        /** Dossier des copies sous `filesDir` (exposé au partage par FileProvider). */
        const val OFFLINE_DIR = "offline"

        /** Nom simple, sans séparateur ni `.`/`..` (les noms viennent de la base, jamais de l'utilisateur). */
        internal fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') &&
                !name.contains('\u0000')
    }
}
