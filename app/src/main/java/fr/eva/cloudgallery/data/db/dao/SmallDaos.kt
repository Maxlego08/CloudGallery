package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.PlaybackPositionEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.data.db.entity.UserSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorite WHERE mediaId IN (:mediaIds)")
    suspend fun deleteAll(mediaIds: List<Long>)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE mediaId = :mediaId)")
    suspend fun isFavorite(mediaId: Long): Boolean
}

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursor WHERE provider = :provider")
    suspend fun get(provider: String): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursor WHERE provider = :provider")
    fun observe(provider: String): Flow<SyncCursorEntity?>

    @Upsert
    suspend fun upsert(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursor WHERE provider = :provider")
    suspend fun delete(provider: String)

    /** Efface le lien de reprise (deltaLink) : la prochaine synchro repartira de zéro. */
    @Query("UPDATE sync_cursor SET cursor = NULL WHERE provider = :provider")
    suspend fun clearLink(provider: String)
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT positionMs FROM playback_position WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): Long?

    @Upsert
    suspend fun upsert(position: PlaybackPositionEntity)

    @Query("DELETE FROM playback_position WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)
}

@Dao
interface UserSettingDao {
    @Query("SELECT value FROM user_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun upsert(setting: UserSettingEntity)
}

/**
 * Fichiers OneDrive copiés sur l'appareil (originaux temporaires, copies hors ligne). Une seule
 * ligne par (média, type) : [replace] met à jour la ligne existante au lieu d'en créer une.
 */
@Dao
interface CachedFileDao {
    @Query("SELECT * FROM cached_file WHERE mediaId = :mediaId AND kind = :kind")
    suspend fun get(mediaId: Long, kind: CacheKind): CachedFileEntity?

    @Query("SELECT * FROM cached_file WHERE mediaId IN (:mediaIds) AND kind = :kind")
    suspend fun getMany(mediaIds: List<Long>, kind: CacheKind): List<CachedFileEntity>

    @Query("SELECT * FROM cached_file WHERE mediaId = :mediaId AND kind = :kind")
    fun observe(mediaId: Long, kind: CacheKind): Flow<CachedFileEntity?>

    @Query("SELECT * FROM cached_file WHERE kind = :kind")
    suspend fun listByKind(kind: CacheKind): List<CachedFileEntity>

    @Query("SELECT COUNT(*) FROM cached_file WHERE kind = :kind")
    fun observeCount(kind: CacheKind): Flow<Int>

    /**
     * Insertion brute. Ne pas l'utiliser pour remplacer une ligne : avec un identifiant 0 et une
     * ligne (média, type) déjà présente, l'upsert de Room se rabat sur une mise à jour par clé
     * primaire qui ne touche aucune ligne (voir [replace]).
     */
    @Upsert
    suspend fun upsert(file: CachedFileEntity)

    /**
     * Enregistre [file] comme LA copie (média, type) : la ligne existante est mise à jour (même
     * identifiant), sinon une ligne est créée. Renvoie l'ancienne ligne (son fichier, s'il diffère,
     * reste à supprimer par l'appelant après la transaction).
     */
    @Transaction
    suspend fun replace(file: CachedFileEntity): CachedFileEntity? {
        val existing = get(file.mediaId, file.kind)
        upsert(file.copy(id = existing?.id ?: 0))
        return existing
    }

    @Query("UPDATE cached_file SET lastAccessAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM cached_file WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * Supprime la ligne [id] seulement si elle est restée telle qu'elle a été lue (même fichier,
     * aucun accès depuis) : un nettoyage ne retire jamais une ligne réutilisée ou remplacée entre
     * sa lecture et sa suppression. Renvoie 1 si la ligne a été supprimée.
     */
    @Query("DELETE FROM cached_file WHERE id = :id AND path = :path AND lastAccessAt = :seenAccessAt")
    suspend fun deleteIfUnchanged(id: Long, path: String, seenAccessAt: Long): Int

    @Query("DELETE FROM cached_file WHERE mediaId IN (:mediaIds) AND kind = :kind")
    suspend fun deleteForMedia(mediaIds: List<Long>, kind: CacheKind)

    @Query("DELETE FROM cached_file WHERE kind = :kind")
    suspend fun deleteByKind(kind: CacheKind)

    @Query("DELETE FROM cached_file")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM cached_file)")
    suspend fun existsAny(): Boolean

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cached_file WHERE kind = :kind")
    fun observeTotalSize(kind: CacheKind): Flow<Long>
}

/**
 * File des copies hors ligne à télécharger. Une seule tâche ouverte (en attente, en cours, en
 * pause ou en échec) par média : garanti par [enqueueIfAbsent] (pas d'index unique partiel, que
 * Room ne sait pas déclarer). Une tâche réussie est supprimée (la copie est alors dans
 * `cached_file` et `media_source`). Aucune URL ni texte libre : `error` = nom d'un DownloadError.
 */
@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    /** Tâches ouvertes, dans l'ordre de la file. */
    @Query("SELECT * FROM download_task WHERE state IN ($OPEN_STATES) ORDER BY createdAt, id")
    fun observeOpen(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task WHERE state IN ($OPEN_STATES) ORDER BY createdAt, id")
    suspend fun listOpen(): List<DownloadTaskEntity>

    /** Tâche ouverte d'un média (null s'il n'en a pas). */
    @Query("SELECT * FROM download_task WHERE mediaId = :mediaId AND state IN ($OPEN_STATES) ORDER BY id DESC LIMIT 1")
    fun observeOpenForMedia(mediaId: Long): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_task WHERE mediaId IN (:mediaIds) AND state IN ($OPEN_STATES)")
    suspend fun openForMedia(mediaIds: List<Long>): List<DownloadTaskEntity>

    /** Prochaine tâche à télécharger (la plus ancienne en attente). */
    @Query("SELECT * FROM download_task WHERE state = 'QUEUED' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextQueued(): DownloadTaskEntity?

    /**
     * Prochaine tâche téléchargeable : la plus ancienne en attente, hors tâches qui attendent que
     * l'index rattrape OneDrive ([RUNNABLE_QUEUED]) et hors [skip] (déjà écartées par le passage).
     */
    @Query("SELECT * FROM download_task t WHERE $RUNNABLE_QUEUED AND t.id NOT IN (:skip) ORDER BY t.createdAt, t.id LIMIT 1")
    suspend fun nextRunnable(skip: List<Long>): DownloadTaskEntity?

    /** Comme [nextRunnable], parmi les tâches [ids] seulement (« Télécharger maintenant » sur réseau mobile). */
    @Query(
        "SELECT * FROM download_task t WHERE $RUNNABLE_QUEUED AND t.id IN (:ids) AND t.id NOT IN (:skip) " +
            "ORDER BY t.createdAt, t.id LIMIT 1",
    )
    suspend fun nextRunnableAmong(ids: List<Long>, skip: List<Long>): DownloadTaskEntity?

    /** Vrai s'il reste une tâche téléchargeable (voir [nextRunnable]). */
    @Query("SELECT EXISTS(SELECT 1 FROM download_task t WHERE $RUNNABLE_QUEUED)")
    suspend fun existsRunnable(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM download_task t WHERE $RUNNABLE_QUEUED AND t.id IN (:ids))")
    suspend fun existsRunnableAmong(ids: List<Long>): Boolean

    @Query("SELECT * FROM download_task WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<DownloadTaskEntity>

    /** Tâches en attente ou en cours (lot concerné par « Télécharger maintenant »). */
    @Query("SELECT * FROM download_task WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun listPending(): List<DownloadTaskEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM download_task WHERE state = 'RUNNING')")
    suspend fun existsRunning(): Boolean

    @Query("SELECT COALESCE(SUM(MAX(bytesTotal - bytesDownloaded, 0)), 0) FROM download_task WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun remainingBytes(): Long

    @Query("SELECT EXISTS(SELECT 1 FROM download_task WHERE state = 'QUEUED')")
    suspend fun existsQueued(): Boolean

    @Query("SELECT COUNT(*) FROM download_task WHERE state = 'QUEUED'")
    suspend fun countQueued(): Int

    /** Version et taille du contenu que la tâche en cours télécharge. */
    @Query("UPDATE download_task SET cTag = :cTag, bytesTotal = :total WHERE id = :id")
    suspend fun setVersion(id: Long, cTag: String?, total: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM download_task)")
    suspend fun existsAny(): Boolean

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    /**
     * Met en file un téléchargement par élément de [tasks] sans créer de doublon : une tâche
     * ouverte du même média est réutilisée (même identifiant, P0-1) et remise en attente avec la
     * nouvelle version ; une tâche en cours garde son état (le moteur détecte le changement de
     * version au moment d'enregistrer). Renvoie le nombre de tâches créées ou remises en attente.
     */
    @Transaction
    suspend fun enqueueIfAbsent(tasks: List<DownloadTaskEntity>): Int {
        var changed = 0
        for (chunk in tasks.chunked(CHUNK)) {
            val open = openForMedia(chunk.map { it.mediaId }).associateBy { it.mediaId }
            for (task in chunk) {
                val existing = open[task.mediaId]
                when {
                    existing == null -> {
                        insert(task.copy(id = 0))
                        changed++
                    }
                    existing.state == DownloadState.RUNNING -> {
                        if (existing.cTag != task.cTag || existing.cloudItemId != task.cloudItemId) {
                            update(existing.copy(cloudItemId = task.cloudItemId, cTag = task.cTag, bytesTotal = task.bytesTotal, updatedAt = task.updatedAt))
                        }
                    }
                    existing.state == DownloadState.QUEUED &&
                        existing.cTag == task.cTag && existing.cloudItemId == task.cloudItemId -> Unit
                    else -> {
                        // En pause, en échec, ou en attente d'une version précédente : remise en attente.
                        val sameVersion = existing.cTag == task.cTag && existing.cloudItemId == task.cloudItemId
                        update(
                            existing.copy(
                                state = DownloadState.QUEUED,
                                cloudItemId = task.cloudItemId,
                                cTag = task.cTag,
                                bytesTotal = task.bytesTotal,
                                bytesDownloaded = if (sameVersion) existing.bytesDownloaded else 0,
                                error = null,
                                attempts = 0,
                                updatedAt = task.updatedAt,
                            ),
                        )
                        changed++
                    }
                }
            }
        }
        return changed
    }

    @Query("UPDATE download_task SET state = 'RUNNING', error = NULL, updatedAt = :now WHERE id = :id AND state = 'QUEUED'")
    suspend fun markRunning(id: Long, now: Long): Int

    /** Progression d'une tâche en cours ; 0 si elle n'existe plus (libérée, annulée) ou n'est plus en cours. */
    @Query("UPDATE download_task SET bytesDownloaded = :bytes, bytesTotal = :total, updatedAt = :now WHERE id = :id AND state = 'RUNNING'")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long, now: Long): Int

    /** Remet une tâche en cours en attente (arrêt du travail, nouvel essai plus tard). */
    @Query(
        "UPDATE download_task SET state = 'QUEUED', error = :error, attempts = :attempts, updatedAt = :now " +
            "WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun requeue(id: Long, error: String?, attempts: Int, now: Long)

    @Query("UPDATE download_task SET state = 'FAILED', error = :error, attempts = :attempts, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, attempts: Int, now: Long)

    /** Toutes les tâches en attente échouent avec [error] (compte déconnecté, plus d'espace). */
    @Query("UPDATE download_task SET state = 'FAILED', error = :error, updatedAt = :now WHERE state = 'QUEUED'")
    suspend fun failAllQueued(error: String, now: Long)

    /** Tâches en cours d'un processus disparu (ou d'un travail arrêté) : de nouveau en attente. */
    @Query("UPDATE download_task SET state = 'QUEUED', updatedAt = :now WHERE state = 'RUNNING'")
    suspend fun resetRunningToQueued(now: Long): Int

    /** Arrêt demandé par l'utilisateur : en pause jusqu'à sa reprise explicite. */
    @Query("UPDATE download_task SET state = 'PAUSED', updatedAt = :now WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun pauseActive(now: Long): Int

    /** Reprise explicite : tâches en pause ou en échec de nouveau en attente. */
    @Query("UPDATE download_task SET state = 'QUEUED', error = NULL, attempts = 0, updatedAt = :now WHERE state IN ('PAUSED', 'FAILED')")
    suspend fun resumeStopped(now: Long): Int

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_task WHERE mediaId IN (:mediaIds)")
    suspend fun deleteForMedia(mediaIds: List<Long>)

    @Query("DELETE FROM download_task WHERE state IN ($OPEN_STATES)")
    suspend fun deleteOpen()

    /** Lignes terminées (annulées, réussies d'une version précédente) plus anciennes que [before]. */
    @Query("DELETE FROM download_task WHERE state IN ('DONE', 'CANCELLED') AND updatedAt < :before")
    suspend fun deleteFinishedBefore(before: Long)

    @Query("DELETE FROM download_task")
    suspend fun deleteAll()
}

private const val CHUNK = 500

/** Etats d'une tâche encore à traiter ou à reprendre (liste SQL). */
private const val OPEN_STATES = "'QUEUED', 'RUNNING', 'PAUSED', 'FAILED'"

/**
 * Tâche `t` en attente et téléchargeable : pas une tâche refusée pour contenu modifié sur
 * OneDrive (CONTENT_CHANGED) tant que l'index n'a pas changé depuis (même version et même
 * taille de l'élément OneDrive) ; elle repart dès que la synchronisation a rattrapé OneDrive.
 * `IS` (et non `=`) : une erreur NULL ne doit pas exclure la tâche.
 */
private const val RUNNABLE_QUEUED = "t.state = 'QUEUED' AND NOT (t.error IS 'CONTENT_CHANGED' AND EXISTS(" +
    "SELECT 1 FROM media_source s WHERE s.kind = 'ONEDRIVE' AND s.externalId = t.cloudItemId " +
    "AND s.cTag IS t.cTag AND s.sizeBytes = t.bytesTotal))"
