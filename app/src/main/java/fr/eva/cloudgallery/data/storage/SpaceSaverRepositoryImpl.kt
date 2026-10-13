package fr.eva.cloudgallery.data.storage

import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import fr.eva.cloudgallery.domain.model.SpaceSaverList
import fr.eva.cloudgallery.domain.repository.SpaceSaverRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Offered copies read from the database ([MediaSourceDao.spaceSaverCandidates]), on the I/O dispatcher. */
@Singleton
class SpaceSaverRepositoryImpl @Inject constructor(
    private val sources: MediaSourceDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : SpaceSaverRepository {

    override suspend fun candidates(): SpaceSaverList = withContext(io) {
        spaceSaverListOf(sources.spaceSaverCandidates())
    }

    override suspend fun revalidate(reviewed: List<SpaceSaverCandidate>): List<SpaceSaverCandidate> = withContext(io) {
        val fresh = reviewed.map { it.sourceId }.chunked(SQL_CHUNK).flatMap { ids ->
            spaceSaverListOf(sources.spaceSaverCandidatesAmong(ids)).candidates
        }
        stillCandidates(reviewed, fresh)
    }

    private companion object {
        /** SQLite variable limit per query. */
        const val SQL_CHUNK = 500
    }
}
