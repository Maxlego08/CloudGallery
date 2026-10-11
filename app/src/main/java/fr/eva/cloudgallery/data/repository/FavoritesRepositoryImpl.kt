package fr.eva.cloudgallery.data.repository

import androidx.room.withTransaction
import fr.eva.cloudgallery.data.db.AppDatabase
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.repository.FavoritesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : FavoritesRepository {

    private val favoriteDao = db.favoriteDao()

    override suspend fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean) {
        if (mediaIds.isEmpty()) return
        withContext(io) {
            val now = System.currentTimeMillis()
            db.withTransaction {
                for (chunk in mediaIds.distinct().chunked(CHUNK)) {
                    if (favorite) {
                        favoriteDao.insertAll(chunk.map { FavoriteEntity(mediaId = it, addedAt = now) })
                    } else {
                        favoriteDao.deleteAll(chunk)
                    }
                }
            }
        }
    }

    /** Inverse l'état favori et renvoie le nouvel état. */
    override suspend fun toggle(mediaId: Long): Boolean = withContext(io) {
        db.withTransaction {
            val newState = !favoriteDao.isFavorite(mediaId)
            if (newState) {
                favoriteDao.insertAll(listOf(FavoriteEntity(mediaId, System.currentTimeMillis())))
            } else {
                favoriteDao.deleteAll(listOf(mediaId))
            }
            newState
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
