package fr.eva.cloudgallery.data.repository

import android.database.sqlite.SQLiteConstraintException
import fr.eva.cloudgallery.data.db.dao.PlaybackPositionDao
import fr.eva.cloudgallery.data.db.entity.PlaybackPositionEntity
import fr.eva.cloudgallery.data.local.shouldKeepPlaybackPosition
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.repository.PlaybackPositionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackPositionRepositoryImpl @Inject constructor(
    private val dao: PlaybackPositionDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : PlaybackPositionRepository {

    /** Position mémorisée en ms, 0 si aucune. */
    override suspend fun get(mediaId: Long): Long = withContext(io) { dao.get(mediaId) ?: 0L }

    /** Les positions proches du début ou de la fin (< 3 s) effacent la reprise au lieu de l'enregistrer. */
    override suspend fun save(mediaId: Long, positionMs: Long, durationMs: Long): Unit = withContext(io) {
        if (!shouldKeepPlaybackPosition(positionMs, durationMs)) {
            dao.delete(mediaId)
            return@withContext
        }
        try {
            dao.upsert(PlaybackPositionEntity(mediaId, positionMs, durationMs, System.currentTimeMillis()))
        } catch (_: SQLiteConstraintException) {
            // Média supprimé entre-temps (clé étrangère) : rien à mémoriser.
        }
    }

    override suspend fun clear(mediaId: Long): Unit = withContext(io) { dao.delete(mediaId) }
}
