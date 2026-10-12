package fr.eva.cloudgallery.data.local

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.dao.SourceStamp
import fr.eva.cloudgallery.data.db.dao.StaleSourceRow
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Miroir incrémental MediaStore -> Room.
 *
 * - Curseur = génération MediaStore relevée avant la lecture ; relecture complète si la version
 *   MediaStore change (ré-association des sources par empreinte, les _ID n'étant plus fiables).
 * - Sources absentes : masquées (missingSince) tant que leur disparition n'est pas confirmée
 *   (accès partiel, corbeille, volume démonté, disparition massive) ; supprimées de la base
 *   seulement en accès complet, une fois confirmées. Favoris et albums survivent au masquage.
 * - Ne supprime JAMAIS de fichier : seule la base est modifiée.
 */
@Singleton
class MediaStoreIndexer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : LocalMediaIndexer {

    private val resolver: ContentResolver get() = context.contentResolver
    private val mediaDao = db.mediaDao()
    private val sourceDao = db.mediaSourceDao()
    private val albumDao = db.albumDao()
    private val cursorDao = db.syncCursorDao()

    private val _state = MutableStateFlow(IndexState())
    override val state: StateFlow<IndexState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** Numéro de demande : une synchro démarrée après une demande la satisfait (coalescence). */
    private val requestCounter = AtomicLong(0)

    @Volatile
    private var lastStartedRequest = 0L

    private val observing = AtomicBoolean(false)
    private val changeSignals = Channel<Unit>(Channel.CONFLATED)

    init {
        appScope.launch(io) {
            runCatching {
                val cursor = cursorDao.get(MEDIASTORE_PROVIDER)
                val count = sourceDao.count(SourceKind.LOCAL)
                _state.update {
                    it.copy(lastSyncAt = cursor?.lastSyncAt ?: 0L, indexedCount = count, lastError = cursor?.lastError)
                }
            }
        }
    }

    override suspend fun sync(full: Boolean) {
        val ticket = requestCounter.incrementAndGet()
        withContext(io) {
            mutex.withLock {
                // Une synchro incrémentale démarrée après cette demande a déjà tout couvert.
                if (!full && lastStartedRequest >= ticket) return@withLock
                lastStartedRequest = requestCounter.get()
                runSync(full)
            }
        }
    }

    @OptIn(FlowPreview::class)
    override fun startObserving() {
        if (!observing.compareAndSet(false, true)) return
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                changeSignals.trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        appScope.launch(io) {
            changeSignals.receiveAsFlow()
                .debounce(OBSERVER_DEBOUNCE_MS)
                .collect {
                    try {
                        sync(full = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Synchronisation après changement MediaStore impossible", e)
                    }
                }
        }
    }

    // ------------------------------------------------------------------------------------

    private suspend fun runSync(forceFull: Boolean) {
        _state.update { it.copy(running = true) }
        try {
            if (!hasAnyMediaPermission()) {
                // Sans permission MediaStore renvoie une liste vide : ne surtout pas vider la base.
                fail(context.getString(R.string.data_error_no_permission))
                return
            }
            // Accès complet = images ET vidéos. Sinon (sélection partielle, un seul des deux types),
            // MediaStore ne renvoie qu'une partie de la bibliothèque : aucune suppression définitive.
            val fullAccess = hasFullMediaAccess()
            val stored = cursorDao.get(MEDIASTORE_PROVIDER)
            val version = currentVersion()
            val full = needsFullSync(forceFull, stored?.cursor, stored?.version, version)
            val previousGeneration = if (full) -1L else stored?.cursor?.toLongOrNull() ?: -1L
            if (mediaStoreVersionChanged(stored?.version, version)) {
                // Les _ID ne sont plus comparables : ré-association par empreinte pendant la relecture.
                sourceDao.markLocalStale()
            }
            // Génération relevée AVANT toute lecture : une modification validée pendant la synchro
            // aura une génération supérieure et sera relue la prochaine fois (relecture idempotente).
            val generationSnapshot = currentGeneration()

            // 1. Identifiants visibles (léger).
            val visible = HashSet<String>(4096)
            for (c in COLLECTIONS) queryIds(c, visible)
            val initialStamps = sourceDao.stamps(SourceKind.LOCAL)
            val known = HashSet<String>(initialStamps.size * 2)
            val hiddenKnown = HashSet<String>()
            for (s in initialStamps) {
                known += s.externalId
                if (s.missingSince != null) hiddenKnown += s.externalId
            }

            // 2. Nouveaux / modifiés.
            val stale = StaleMatcher(sourceDao.staleLocal())
            var maxGeneration = previousGeneration.coerceAtLeast(0L)
            val written = HashSet<String>()
            for (c in COLLECTIONS) {
                val selection = if (full) null else "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
                val args = if (full) null else arrayOf(previousGeneration.toString())
                maxGeneration = maxOf(maxGeneration, queryAndWrite(c, selection, args, full, written, stale))
            }
            if (!full) {
                // Eléments visibles mais absents de la base (ou masqués) dont la génération est
                // ancienne : nouvelles photos accordées en accès partiel, sortie de corbeille,
                // volume remonté, retour à l'accès complet…
                for (c in COLLECTIONS) {
                    val missing = visible.asSequence()
                        .filter { (it !in known || it in hiddenKnown) && it !in written }
                        .mapNotNull { parseExternalId(it) }
                        .filter { it.first == c.type }
                        .map { it.second }
                        .toList()
                    for (chunk in missing.chunked(BATCH_SIZE)) {
                        val sel = "${BaseColumns._ID} IN (${chunk.joinToString(",")})"
                        maxGeneration = maxOf(
                            maxGeneration,
                            queryAndWrite(c, sel, null, full = false, written = written, stale = stale),
                        )
                    }
                }
            }

            // 3. Sources absentes : masquées ou supprimées (base uniquement, jamais les fichiers).
            val massBlocked = applyRemovals(visible, written, fullAccess)

            // 4. Albums-dossiers.
            syncFolderAlbums(fullAccess)

            val now = System.currentTimeMillis()
            val warning = if (massBlocked) context.getString(R.string.data_warning_mass_removal) else null
            cursorDao.upsert(
                SyncCursorEntity(
                    provider = MEDIASTORE_PROVIDER,
                    cursor = (generationSnapshot ?: maxGeneration).toString(),
                    version = version,
                    lastSyncAt = now,
                    lastFullSyncAt = if (full) now else stored?.lastFullSyncAt ?: 0L,
                    lastError = warning,
                ),
            )
            val count = sourceDao.count(SourceKind.LOCAL)
            _state.update { it.copy(lastSyncAt = now, indexedCount = count, lastError = warning) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Accès MediaStore refusé", e)
            fail(context.getString(R.string.data_error_no_permission))
        } catch (e: Exception) {
            Log.e(TAG, "Echec de l'indexation", e)
            fail(context.getString(R.string.data_error_sync_failed, e.message ?: e.javaClass.simpleName))
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    /**
     * Traite les sources LOCAL absentes de la requête standard. Renvoie vrai si le frein
     * anti-suppression massive a été déclenché.
     */
    private suspend fun applyRemovals(visible: Set<String>, written: Set<String>, fullAccess: Boolean): Boolean {
        val stamps = sourceDao.stamps(SourceKind.LOCAL)
        val absent = stamps.filter { it.externalId !in visible && it.externalId !in written }
        if (absent.isEmpty()) return false
        if (visible.isEmpty() && written.isEmpty()) {
            // MediaStore ne renvoie plus rien alors que la base connaît des médias : état transitoire
            // (fournisseur en cours de reconstruction, stockage indisponible). On ne touche à rien.
            Log.w(TAG, "MediaStore vide avec ${stamps.size} sources connues : suppressions ignorées")
            return false
        }
        val now = System.currentTimeMillis()
        val candidates = if (fullAccess) classifyAbsent(absent, now) else {
            absent.map { MissingCandidate(it.id, it.mediaId, MissingVerdict.HIDDEN, it.missingSince) }
        }
        val plan = planRemovals(candidates, stamps.size, fullAccess, now)
        if (plan.massRemovalBlocked) {
            Log.w(TAG, "Frein anti-suppression : ${plan.delete.size + plan.hide.size} sources absentes masquées")
        }
        val toHide = plan.hide.filter { it.missingSince == null }
        for (chunk in toHide.chunked(BATCH_SIZE)) {
            db.withTransaction {
                sourceDao.markMissing(chunk.map { it.sourceId }, now)
                mediaDao.refreshSourceFlags(chunk.map { it.mediaId }.distinct())
            }
        }
        for (chunk in plan.delete.chunked(BATCH_SIZE)) {
            db.withTransaction {
                sourceDao.deleteByIds(chunk.map { it.sourceId })
                mediaDao.deleteOrphans()
                mediaDao.refreshSourceFlags(chunk.map { it.mediaId }.distinct())
            }
        }
        return plan.massRemovalBlocked
    }

    /**
     * Accès complet : distingue une disparition réelle d'un élément seulement invisible
     * (corbeille, en attente — relus avec MATCH_INCLUDE) ou situé sur un volume démonté.
     */
    private fun classifyAbsent(absent: List<SourceStamp>, now: Long): List<MissingCandidate> {
        val attachedVolumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrNull()
        val stillPresent = HashSet<String>()
        for (c in COLLECTIONS) {
            val ids = absent.mapNotNull { s -> parseExternalId(s.externalId)?.takeIf { it.first == c.type }?.second }
            for (chunk in ids.chunked(BATCH_SIZE)) {
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${BaseColumns._ID} IN (${chunk.joinToString(",")})")
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
                resolver.query(c.uri, arrayOf(BaseColumns._ID), args, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                    while (cursor.moveToNext()) stillPresent += buildExternalId(c.type, cursor.getLong(idCol))
                } ?: error("MediaStore query returned null for ${c.uri}")
            }
        }
        return absent.map { s ->
            val parsed = parseExternalId(s.externalId)
            val volumeDetached = s.volumeName != null && attachedVolumes != null && s.volumeName !in attachedVolumes
            val verdict = when {
                // Source en attente de ré-identification (changement de version MediaStore) :
                // invérifiable, purgée seulement après le délai de grâce.
                parsed == null ->
                    if (s.missingSince != null && now - s.missingSince >= MISSING_GRACE_MS && !volumeDetached) {
                        MissingVerdict.GONE
                    } else {
                        MissingVerdict.HIDDEN
                    }
                s.externalId in stillPresent -> MissingVerdict.HIDDEN
                volumeDetached || attachedVolumes == null -> MissingVerdict.HIDDEN
                else -> MissingVerdict.GONE
            }
            MissingCandidate(s.id, s.mediaId, verdict, s.missingSince)
        }
    }

    private suspend fun fail(message: String) {
        _state.update { it.copy(lastError = message) }
        runCatching {
            val stored = cursorDao.get(MEDIASTORE_PROVIDER)
            if (stored != null) cursorDao.upsert(stored.copy(lastError = message))
        }
    }

    private fun hasAnyMediaPermission(): Boolean = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun hasFullMediaAccess(): Boolean = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun currentVersion(): String? = try {
        MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
    } catch (e: Exception) {
        runCatching { MediaStore.getVersion(context) }.getOrNull()
    }

    /** Génération MediaStore courante (maximum sur les volumes externes montés), ou null. */
    private fun currentGeneration(): Long? = runCatching {
        MediaStore.getExternalVolumeNames(context)
            .mapNotNull { volume -> runCatching { MediaStore.getGeneration(context, volume) }.getOrNull() }
            .maxOrNull()
    }.getOrNull()?.takeIf { it >= 0 }

    private fun queryArgs(selection: String?, selectionArgs: Array<String>?): Bundle = Bundle().apply {
        if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
    }

    private fun queryIds(collection: StoreCollection, into: MutableSet<String>) {
        resolver.query(collection.uri, arrayOf(BaseColumns._ID), queryArgs(null, null), null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
            while (c.moveToNext()) into += buildExternalId(collection.type, c.getLong(idCol))
        } ?: error("MediaStore query returned null for ${collection.uri}")
    }

    /** Lit les lignes et les écrit par lots. Renvoie la plus grande génération rencontrée. */
    private suspend fun queryAndWrite(
        collection: StoreCollection,
        selection: String?,
        selectionArgs: Array<String>?,
        full: Boolean,
        written: MutableSet<String>,
        stale: StaleMatcher,
    ): Long {
        var maxGen = 0L
        val projection = collection.projection
        val cursor = resolver.query(collection.uri, projection, queryArgs(selection, selectionArgs), null)
            ?: error("MediaStore query returned null for ${collection.uri}")
        cursor.use { c ->
            val cols = Columns(c)
            val batch = ArrayList<StoreRow>(BATCH_SIZE)
            while (c.moveToNext()) {
                val row = readRow(c, cols, collection)
                if (row.generation > maxGen) maxGen = row.generation
                batch += row
                written += buildExternalId(row.type, row.storeId)
                if (batch.size >= BATCH_SIZE) {
                    writeBatch(batch, full, stale)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) writeBatch(batch, full, stale)
        }
        return maxGen
    }

    private fun readRow(c: Cursor, cols: Columns, collection: StoreCollection): StoreRow {
        val id = c.getLong(cols.id)
        val dateAddedSec = c.longOrNull(cols.dateAdded)
        val dateModifiedSec = c.longOrNull(cols.dateModified)
        val bucketId = c.longOrNull(cols.bucketId)
        return StoreRow(
            type = collection.type,
            storeId = id,
            uri = ContentUris.withAppendedId(collection.uri, id).toString(),
            displayName = c.stringOrNull(cols.displayName) ?: "",
            mimeType = c.stringOrNull(cols.mimeType),
            dateTaken = resolveDateTaken(c.longOrNull(cols.dateTaken), dateAddedSec, dateModifiedSec),
            dateAdded = (dateAddedSec ?: 0L) * 1000,
            dateModified = (dateModifiedSec ?: 0L) * 1000,
            width = c.longOrNull(cols.width)?.toInt() ?: 0,
            height = c.longOrNull(cols.height)?.toInt() ?: 0,
            orientation = c.longOrNull(cols.orientation)?.toInt() ?: 0,
            durationMs = c.longOrNull(cols.duration) ?: 0L,
            sizeBytes = c.longOrNull(cols.size) ?: 0L,
            bucketKey = bucketId?.let { localBucketKey(it) },
            bucketName = c.stringOrNull(cols.bucketName),
            relativePath = c.stringOrNull(cols.relativePath),
            volumeName = c.stringOrNull(cols.volumeName),
            generation = c.longOrNull(cols.generation) ?: 0L,
        )
    }

    private suspend fun writeBatch(rows: List<StoreRow>, full: Boolean, stale: StaleMatcher) {
        db.withTransaction {
            val externalIds = rows.map { buildExternalId(it.type, it.storeId) }
            val existing = sourceDao.findAll(SourceKind.LOCAL, externalIds).associateBy { it.externalId }
            val media = mediaDao.getByIds(existing.values.map { it.mediaId }).associateBy { it.id }
            val touched = ArrayList<Long>(rows.size)
            rows.forEachIndexed { index, row ->
                val externalId = externalIds[index]
                val source = existing[externalId]
                val entity = source?.let { media[it.mediaId] }
                if (source != null && entity != null) {
                    // Relecture complète avec la même version : inutile de réécrire l'inchangé.
                    if (full && source.missingSince == null && source.generation == row.generation &&
                        source.uri == row.uri && source.dateModified == row.dateModified &&
                        source.relativePath == row.relativePath && source.volumeName == row.volumeName
                    ) {
                        return@forEachIndexed
                    }
                    mediaDao.update(row.applyTo(entity))
                    sourceDao.update(row.applyTo(source))
                    touched += entity.id
                } else {
                    if (source != null) sourceDao.deleteByIds(listOf(source.id))
                    // Après un changement de version MediaStore : ré-association par empreinte au
                    // média existant, pour conserver favoris, albums et position de lecture.
                    val match = stale.take(localFingerprint(row.relativePath, row.displayName, row.sizeBytes))
                    val staleSource = match?.let { sourceDao.getById(it.id) }
                    val staleMedia = match?.let { mediaDao.getById(it.mediaId) }
                    if (staleSource != null && staleMedia != null) {
                        mediaDao.update(row.applyTo(staleMedia))
                        sourceDao.update(row.applyTo(staleSource).copy(externalId = externalId))
                        touched += staleMedia.id
                    } else {
                        val mediaId = mediaDao.insert(row.toMediaEntity())
                        sourceDao.insert(row.toSourceEntity(mediaId, externalId))
                        touched += mediaId
                    }
                }
            }
            if (touched.isNotEmpty()) mediaDao.refreshSourceFlags(touched)
        }
    }

    /**
     * Albums-dossiers : création / mise à jour depuis les sources. Un dossier dont le bucket a
     * disparu n'est jamais supprimé s'il porte une personnalisation (nom, épingle, couverture,
     * masquage) ; sinon il n'est nettoyé qu'en accès complet. La liste des albums masque déjà
     * les dossiers vides.
     */
    private suspend fun syncFolderAlbums(fullAccess: Boolean) {
        db.withTransaction {
            val buckets = sourceDao.buckets(SourceKind.LOCAL).associateBy { it.bucketKey }
            val albums = albumDao.getByType(AlbumType.LOCAL_FOLDER)
            val now = System.currentTimeMillis()
            if (fullAccess) {
                val stale = albums.filter { it.sourceKey == null || it.sourceKey !in buckets }.map { it.id }
                for (chunk in stale.chunked(BATCH_SIZE)) albumDao.deleteUncustomizedFolders(chunk, AlbumType.LOCAL_FOLDER)
            }
            val byKey = albums.filter { it.sourceKey != null }.associateBy { it.sourceKey!! }
            for ((key, bucket) in buckets) {
                val name = bucket.bucketName?.takeIf { it.isNotBlank() }
                    ?: bucket.relativePath?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.data_folder_unnamed)
                val album = byKey[key]
                if (album == null) {
                    // Un album d'un autre type (ex. ancien) peut déjà porter cette clé unique.
                    if (albumDao.getBySourceKey(key) != null) continue
                    albumDao.insert(
                        AlbumEntity(
                            name = name,
                            type = AlbumType.LOCAL_FOLDER,
                            sourceKey = key,
                            relativePath = bucket.relativePath,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else if (album.name != name || album.relativePath != bucket.relativePath) {
                    albumDao.updateFolderInfo(album.id, name, bucket.relativePath, now)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------

    /** Sources en attente de ré-identification, indexées par empreinte (consommées une à une). */
    private class StaleMatcher(rows: List<StaleSourceRow>) {
        private val byFingerprint: MutableMap<String, ArrayDeque<StaleSourceRow>> = HashMap<String, ArrayDeque<StaleSourceRow>>().apply {
            for (r in rows) getOrPut(localFingerprint(r.relativePath, r.displayName, r.sizeBytes)) { ArrayDeque() }.addLast(r)
        }

        fun take(fingerprint: String): StaleSourceRow? {
            if (byFingerprint.isEmpty()) return null
            val queue = byFingerprint[fingerprint] ?: return null
            val row = queue.removeFirstOrNull()
            if (queue.isEmpty()) byFingerprint.remove(fingerprint)
            return row
        }
    }

    // ------------------------------------------------------------------------------------

    private class StoreCollection(val type: MediaType, val uri: Uri, val projection: Array<String>)

    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(BaseColumns._ID)
        val displayName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeType = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val dateTaken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val dateAdded = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val dateModified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val orientation = c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
        val duration = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
        val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val bucketId = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
        val bucketName = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val relativePath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val generation = c.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED)
        val volumeName = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
    }

    private data class StoreRow(
        val type: MediaType,
        val storeId: Long,
        val uri: String,
        val displayName: String,
        val mimeType: String?,
        val dateTaken: Long,
        val dateAdded: Long,
        val dateModified: Long,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val durationMs: Long,
        val sizeBytes: Long,
        val bucketKey: String?,
        val bucketName: String?,
        val relativePath: String?,
        val volumeName: String?,
        val generation: Long,
    ) {
        fun toMediaEntity() = MediaEntity(
            mediaType = type,
            displayName = displayName,
            mimeType = mimeType,
            dateTaken = dateTaken,
            dateAdded = dateAdded,
            dateModified = dateModified,
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            orientation = orientation,
            primaryUri = uri,
            hasLocal = true,
            hasCloud = false,
            hasOffline = false,
        )

        fun applyTo(e: MediaEntity) = e.copy(
            mediaType = type,
            displayName = displayName,
            mimeType = mimeType,
            dateTaken = dateTaken,
            dateAdded = dateAdded,
            dateModified = dateModified,
            width = width,
            height = height,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            orientation = orientation,
        )

        fun toSourceEntity(mediaId: Long, externalId: String) = MediaSourceEntity(
            mediaId = mediaId,
            kind = SourceKind.LOCAL,
            externalId = externalId,
            uri = uri,
            bucketKey = bucketKey,
            bucketName = bucketName,
            relativePath = relativePath,
            sizeBytes = sizeBytes,
            dateModified = dateModified,
            generation = generation,
            volumeName = volumeName,
        )

        /** Met à jour une source existante ; elle redevient visible. */
        fun applyTo(s: MediaSourceEntity) = s.copy(
            uri = uri,
            bucketKey = bucketKey,
            bucketName = bucketName,
            relativePath = relativePath,
            sizeBytes = sizeBytes,
            dateModified = dateModified,
            generation = generation,
            volumeName = volumeName,
            missingSince = null,
        )
    }

    private companion object {
        const val TAG = "MediaStoreIndexer"
        const val BATCH_SIZE = 500
        const val OBSERVER_DEBOUNCE_MS = 1_500L

        private val BASE_PROJECTION = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.VOLUME_NAME,
        )

        private val COLLECTIONS = listOf(
            StoreCollection(
                MediaType.IMAGE,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                BASE_PROJECTION,
            ),
            StoreCollection(
                MediaType.VIDEO,
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                BASE_PROJECTION + MediaStore.MediaColumns.DURATION,
            ),
        )
    }
}

private fun Cursor.longOrNull(index: Int): Long? =
    if (index < 0 || isNull(index)) null else getLong(index)

private fun Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)
