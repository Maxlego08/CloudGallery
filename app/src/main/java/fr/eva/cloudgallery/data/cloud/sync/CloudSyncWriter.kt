package fr.eva.cloudgallery.data.cloud.sync

import androidx.room.withTransaction
import fr.eva.cloudgallery.data.cloud.graph.DriveItemDto
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.CloudFolderEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suivi d'une synchronisation en cours (une instance par passage).
 *
 * En énumération complète, les éléments et dossiers reçus sont mémorisés : ceux de l'index qui
 * n'ont pas été vus sont masqués en fin de passage. Les dossiers supprimés ne sont traités qu'en
 * fin de passage (un dossier peut réapparaître plus loin dans le flux delta).
 */
internal class CloudSyncRun(full: Boolean) {
    var full: Boolean = full
        private set

    /** Identifiants des médias reçus (énumération complète uniquement). */
    var seen: MutableSet<String>? = if (full) HashSet() else null
        private set

    /** Identifiants des dossiers reçus (énumération complète uniquement). */
    var seenFolders: MutableSet<String>? = if (full) HashSet() else null
        private set

    /** Dossiers connus de l'index signalés supprimés et non réapparus depuis. */
    val removedFolders: MutableSet<String> = HashSet()

    /**
     * Un dossier qui contient des éléments masqués, directement ou dans ses sous-dossiers, est
     * réapparu (restauré depuis la corbeille) : ses éléments ne sont pas forcément renvoyés un par
     * un, une énumération complète s'impose. Le deltaLink enregistré est effacé dans la même
     * transaction que la page : même interrompu, le passage suivant sera une énumération complète.
     */
    var restoredFolderWithHiddenItems: Boolean = false

    /** Bascule en énumération complète (jeton expiré, dossier restauré). */
    fun restartFull() {
        full = true
        seen = HashSet()
        seenFolders = HashSet()
        restoredFolderWithHiddenItems = false
    }
}

/** Libellés des albums OneDrive (résolus par l'appelant dans la langue courante). */
internal data class CloudFolderLabels(val root: String, val unnamed: String)

/**
 * Ecriture de la synchronisation OneDrive dans l'index (base Room uniquement, aucun accès
 * réseau ni fichier). Chaque page est appliquée en une transaction : média, source et
 * indicateurs dénormalisés sont toujours cohérents (le nettoyage des orphelins de l'indexation
 * locale est global). Un élément OneDrive = un média : jamais de rapprochement par nom ou
 * empreinte avec un fichier local. Rien n'est supprimé directement : une source disparue est
 * masquée, puis purgée après le délai de grâce.
 */
@Singleton
class CloudSyncWriter @Inject constructor(
    private val db: AppDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mediaDao = db.mediaDao()
    private val sourceDao = db.mediaSourceDao()
    private val albumDao = db.albumDao()
    private val folderDao = db.cloudFolderDao()
    private val cursorDao = db.syncCursorDao()

    /**
     * Vrai si l'index contient des données OneDrive visibles par l'utilisateur ou rattachées à
     * ses favoris et albums : sources (même masquées) ou albums OneDrive. Les seules lignes de
     * dossiers ne comptent pas (elles sont recalculées par une énumération complète).
     */
    suspend fun hasIndexedData(): Boolean = withContext(io) {
        sourceDao.existsAny(SourceKind.ONEDRIVE) || albumDao.existsByType(AlbumType.CLOUD_FOLDER)
    }

    /** Eléments OneDrive visibles. */
    suspend fun visibleCount(): Int = withContext(io) { sourceDao.count(SourceKind.ONEDRIVE) }

    /** Applique une page de la requête delta. */
    internal suspend fun applyPage(items: List<DriveItemDto>, run: CloudSyncRun, now: Long) = withContext(io) {
        if (items.isEmpty()) return@withContext
        val actions = lastOccurrenceById(items).map(::classifyDeltaItem)
        db.withTransaction {
            val touched = HashSet<Long>()
            applyFolders(actions.filterIsInstance<DeltaAction.UpsertFolder>().map { it.folder }, run, now)
            applyRemovals(actions.filterIsInstance<DeltaAction.Remove>().map { it.itemId }, run, now, touched)
            applyMedia(actions.filterIsInstance<DeltaAction.UpsertMedia>().map { it.media }, run, touched)
            touched.chunked(CHUNK).forEach { mediaDao.refreshSourceFlags(it) }
        }
    }

    private suspend fun applyFolders(folders: List<CloudFolderFields>, run: CloudSyncRun, now: Long) {
        if (folders.isEmpty()) return
        for (chunk in folders.chunked(CHUNK)) {
            val ids = chunk.map { it.itemId }
            if (!run.full && !run.restoredFolderWithHiddenItems) {
                // Dossier inconnu reçu alors que des éléments masqués sont dans ce dossier ou dans
                // un dossier retiré de l'index (sous-dossier d'un dossier supprimé) : restauration
                // possible. Les lignes de dossiers retirées ne permettent plus de savoir lesquels
                // descendaient du dossier restauré, d'où le test large.
                val known = folderDao.knownIds(ids).toSet()
                val newKeys = ids.filter { it !in known }.mapNotNull { cloudBucketKey(it) }
                if (newKeys.isNotEmpty() && sourceDao.existsHiddenCloudInBucketsOrOrphaned(newKeys)) {
                    run.restoredFolderWithHiddenItems = true
                    // Décision durable : enregistrée avec le dossier, dans la transaction de la page.
                    cursorDao.clearLink(ONEDRIVE_PROVIDER)
                }
            }
            folderDao.upsertAll(
                chunk.map { CloudFolderEntity(itemId = it.itemId, parentId = it.parentId, name = it.name, isRoot = it.isRoot, updatedAt = now) },
            )
        }
        for (f in folders) {
            run.removedFolders.remove(f.itemId)
            run.seenFolders?.add(f.itemId)
        }
    }

    private suspend fun applyRemovals(ids: List<String>, run: CloudSyncRun, now: Long, touched: MutableSet<Long>) {
        if (ids.isEmpty()) return
        for (chunk in ids.chunked(CHUNK)) {
            // Dossier ou fichier : d'après l'index (un élément supprimé n'a pas toujours la facette folder).
            val folders = folderDao.knownIds(chunk).toSet()
            run.removedFolders += folders
            val sources = sourceDao.findAll(SourceKind.ONEDRIVE, chunk.filter { it !in folders })
            if (sources.isNotEmpty()) {
                sourceDao.markMissing(sources.map { it.id }, now)
                touched += sources.map { it.mediaId }
            }
        }
        for (id in ids) {
            run.seen?.remove(id)
            run.seenFolders?.remove(id)
        }
    }

    private suspend fun applyMedia(items: List<CloudMediaFields>, run: CloudSyncRun, touched: MutableSet<Long>) {
        if (items.isEmpty()) return
        for (chunk in items.chunked(CHUNK)) {
            val sources = sourceDao.findAll(SourceKind.ONEDRIVE, chunk.map { it.itemId }).associateBy { it.externalId }
            val medias = mediaDao.getByIds(sources.values.map { it.mediaId }).associateBy { it.id }
            for (fields in chunk) {
                val source = sources[fields.itemId]
                val media = source?.let { medias[it.mediaId] }
                if (source != null && media != null) {
                    val updatedMedia = fields.applyTo(media)
                    if (updatedMedia != media) mediaDao.update(updatedMedia)
                    val updatedSource = fields.applyTo(source)
                    if (updatedSource != source) {
                        sourceDao.update(updatedSource)
                        touched += media.id
                    }
                } else {
                    // Source orpheline impossible (clé étrangère en cascade) ; par prudence elle est remplacée.
                    if (source != null) sourceDao.deleteByIds(listOf(source.id))
                    val mediaId = mediaDao.insert(fields.toMediaEntity())
                    sourceDao.insert(fields.toSourceEntity(mediaId))
                    touched += mediaId
                }
                run.seen?.add(fields.itemId)
            }
        }
    }

    /**
     * Fin de passage (après la dernière page) : masquage des éléments absents d'une énumération
     * complète et du contenu des dossiers supprimés, purge après délai de grâce, puis albums
     * OneDrive.
     */
    internal suspend fun finishSync(run: CloudSyncRun, now: Long, labels: CloudFolderLabels) = withContext(io) {
        db.withTransaction {
            val touched = HashSet<Long>()

            // 1. Eléments : masqués s'ils manquent à une énumération complète, purgés après le délai de grâce.
            val stamps = sourceDao.stamps(SourceKind.ONEDRIVE).map { CloudStamp(it.id, it.mediaId, it.externalId, it.missingSince) }
            val plan = planCloudRemovals(stamps, run.seen, now)
            for (chunk in plan.hide.chunked(CHUNK)) sourceDao.markMissing(chunk.map { it.sourceId }, now)
            for (chunk in plan.purge.chunked(CHUNK)) sourceDao.deleteByIds(chunk.map { it.sourceId })
            plan.hide.mapTo(touched) { it.mediaId }
            plan.purge.mapTo(touched) { it.mediaId }

            // 2. Dossiers supprimés (toujours absents en fin de passage) : leur contenu est masqué.
            val folders = folderDao.getAll().map { it.toFields() }
            val knownIds = folders.mapTo(HashSet()) { it.itemId }
            val seenFolders = run.seenFolders
            val goneFolders: Set<String> = if (seenFolders != null) {
                // Enumération complète : les éléments non vus sont déjà masqués à l'étape 1.
                knownIds - seenFolders
            } else {
                descendantFolderIds(run.removedFolders.filterTo(HashSet()) { it in knownIds }, folders)
            }
            if (seenFolders == null && goneFolders.isNotEmpty()) {
                for (chunk in goneFolders.mapNotNull { cloudBucketKey(it) }.chunked(CHUNK)) {
                    touched += sourceDao.visibleCloudMediaIdsInBuckets(chunk)
                    sourceDao.markMissingInBuckets(chunk, now)
                }
            }

            touched.chunked(CHUNK).forEach { mediaDao.refreshSourceFlags(it) }
            mediaDao.deleteOrphans()
            albumDao.clearDanglingCovers()

            // Un dossier non vu qui garde des éléments visibles (cas limite) conserve son nom.
            val deletable = goneFolders.filter { id ->
                cloudBucketKey(id)?.let { !sourceDao.existsVisibleCloudInBucket(it) } ?: true
            }
            for (chunk in deletable.chunked(CHUNK)) folderDao.deleteByIds(chunk)

            syncCloudFolderAlbums(labels, now)
        }
    }

    /**
     * Albums OneDrive : un par dossier contenant des éléments visibles. Nom et chemin suivent les
     * renommages ; un album sans personnalisation de l'utilisateur disparaît avec son dossier.
     */
    private suspend fun syncCloudFolderAlbums(labels: CloudFolderLabels, now: Long) {
        val buckets = sourceDao.visibleBuckets(SourceKind.ONEDRIVE)
        val folders = folderDao.getAll().associate { it.itemId to it.toFields() }
        val albums = albumDao.getByType(AlbumType.CLOUD_FOLDER)
        val keys = buckets.mapTo(HashSet()) { it.bucketKey }
        val stale = albums.filter { it.sourceKey == null || it.sourceKey !in keys }.map { it.id }
        for (chunk in stale.chunked(CHUNK)) albumDao.deleteUncustomizedFolders(chunk, AlbumType.CLOUD_FOLDER)
        val byKey = albums.filter { it.sourceKey != null }.associateBy { it.sourceKey!! }
        for (bucket in buckets) {
            val key = bucket.bucketKey
            val folderId = folderIdOfCloudBucket(key) ?: continue
            val name = cloudFolderAlbumName(folders[folderId], labels.root, labels.unnamed)
            val path = cloudFolderPath(folderId, folders, labels.root)
            sourceDao.updateCloudBucketInfo(key, name, path)
            val album = byKey[key]
            if (album == null) {
                // Un album d'un autre type peut déjà porter cette clé unique.
                if (albumDao.getBySourceKey(key) != null) continue
                albumDao.insert(
                    AlbumEntity(
                        name = name,
                        type = AlbumType.CLOUD_FOLDER,
                        sourceKey = key,
                        relativePath = path,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else if (album.name != name || album.relativePath != path) {
                albumDao.updateFolderInfo(album.id, name, path, now)
            }
        }
    }

    private companion object {
        const val CHUNK = 500

        fun CloudFolderEntity.toFields() = CloudFolderFields(itemId = itemId, parentId = parentId, name = name, isRoot = isRoot)

        fun CloudMediaFields.toMediaEntity() = MediaEntity(
            mediaType = type,
            displayName = name,
            mimeType = mimeType,
            dateTaken = dateTakenMs,
            dateAdded = dateAddedMs,
            dateModified = dateModifiedMs,
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            // Les miniatures Graph sont déjà orientées.
            orientation = 0,
            primaryUri = null,
            hasLocal = false,
            hasCloud = true,
            hasOffline = false,
            contentHash = contentHash,
        )

        fun CloudMediaFields.applyTo(media: MediaEntity) = media.copy(
            mediaType = type,
            displayName = name,
            mimeType = mimeType,
            dateTaken = dateTakenMs,
            dateAdded = dateAddedMs,
            dateModified = dateModifiedMs,
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            contentHash = contentHash,
        )

        fun CloudMediaFields.toSourceEntity(mediaId: Long) = MediaSourceEntity(
            mediaId = mediaId,
            kind = SourceKind.ONEDRIVE,
            externalId = itemId,
            uri = null,
            bucketKey = bucketKey,
            // Nom et chemin du dossier renseignés en fin de passage (albums OneDrive).
            bucketName = null,
            relativePath = null,
            sizeBytes = sizeBytes,
            dateModified = dateModifiedMs,
            eTag = eTag,
            cTag = cTag,
            sha1Hash = sha1Hash,
            quickXorHash = quickXorHash,
            missingSince = null,
        )

        /** Mise à jour d'une source existante ; un élément revenu (restauré) redevient visible. */
        fun CloudMediaFields.applyTo(source: MediaSourceEntity): MediaSourceEntity {
            val sameBucket = source.bucketKey == bucketKey
            return source.copy(
                bucketKey = bucketKey,
                bucketName = if (sameBucket) source.bucketName else null,
                relativePath = if (sameBucket) source.relativePath else null,
                sizeBytes = sizeBytes,
                dateModified = dateModifiedMs,
                eTag = eTag,
                cTag = cTag,
                sha1Hash = sha1Hash,
                quickXorHash = quickXorHash,
                missingSince = null,
            )
        }
    }
}
