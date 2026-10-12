package fr.eva.cloudgallery.data.search

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recherches récentes ([MAX_RECENT_SEARCHES] au plus, la plus récente en tête), gardées sur
 * l'appareil seulement : préférences dédiées `search_history.xml`, exclues des sauvegardes,
 * lues et écrites sur le dispatcher d'E/S.
 */
@Singleton
class SearchHistoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : SearchHistoryRepository {

    /** Ouvert au premier usage, sur le dispatcher d'E/S. */
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    private val mutex = Mutex()
    private val state = MutableStateFlow<List<String>?>(null)

    override val recent: Flow<List<String>> = state.asStateFlow()
        .onStart { load() }
        .filterNotNull()

    override suspend fun add(query: String) = update { withRecentSearch(it, query) }

    override suspend fun remove(query: String) = update { list -> list.filter { it != query } }

    override suspend fun clear() = update { emptyList() }

    private suspend fun load() {
        mutex.withLock {
            if (state.value == null) state.value = withContext(io) { read() }
        }
    }

    private suspend fun update(transform: (List<String>) -> List<String>) {
        mutex.withLock {
            val current = state.value ?: withContext(io) { read() }
            val next = transform(current)
            if (next != current) withContext(io) { prefs.edit { putString(KEY_RECENT, next.joinToString(SEPARATOR)) } }
            state.value = next
        }
    }

    private fun read(): List<String> =
        prefs.getString(KEY_RECENT, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }?.take(MAX_RECENT_SEARCHES).orEmpty()

    private companion object {
        const val FILE_NAME = "search_history"
        const val KEY_RECENT = "recent"

        /** Une recherche ne contient jamais de saut de ligne (espaces normalisés par withRecentSearch). */
        const val SEPARATOR = "\n"
    }
}
