package fr.eva.cloudgallery.ui.saver

import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.SaverAge
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import fr.eva.cloudgallery.domain.model.SpaceSaverList
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.LocalMediaWriter
import fr.eva.cloudgallery.domain.repository.SpaceSaverRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Etat de l'écran : [list] null pendant la lecture ; [busy] pendant une mise à la corbeille. */
data class SpaceSaverUiState(
    val list: SpaceSaverList? = null,
    val age: SaverAge = SaverAge.DAYS_30,
    val deselected: Set<Long> = emptySet(),
    val busy: Boolean = false,
) {
    /** Copies du filtre d'ancienneté courant. */
    fun filtered(now: Long): List<SpaceSaverCandidate> = filterByAge(list?.candidates.orEmpty(), age, now)
}

sealed interface SpaceSaverEvent {
    /** Demande système de mise à la corbeille d'un lot (confirmée par l'utilisateur). */
    class LaunchTrash(val sender: IntentSender) : SpaceSaverEvent

    /** Copies envoyées à la corbeille et espace libéré. */
    data class Freed(val count: Int, val bytes: Long) : SpaceSaverEvent

    /** Copies écartées à la revérification (modifiées ou plus sur OneDrive entre-temps). */
    data class Changed(val count: Int) : SpaceSaverEvent

    data object Failed : SpaceSaverEvent
}

/**
 * Economiseur d'espace : revue des copies locales déjà sur OneDrive, puis mise à la corbeille
 * Android des copies cochées, après confirmation dans l'appli puis dans la boîte de dialogue
 * système (par lots de [MAX_TRASH_BATCH]). Jamais de suppression définitive ; jamais lancé
 * automatiquement. Les copies sont revérifiées en base juste avant la demande.
 */
@HiltViewModel
class SpaceSaverViewModel @Inject constructor(
    private val repository: SpaceSaverRepository,
    private val writer: LocalMediaWriter,
    private val indexer: LocalMediaIndexer,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(SpaceSaverUiState())
    val state: StateFlow<SpaceSaverUiState> = _state.asStateFlow()

    private val eventChannel = Channel<SpaceSaverEvent>(Channel.BUFFERED)
    val events: Flow<SpaceSaverEvent> = eventChannel.receiveAsFlow()

    private val pendingBatches = ArrayDeque<List<SpaceSaverCandidate>>()
    private var currentBatch: List<SpaceSaverCandidate> = emptyList()
    private var freedCount = 0
    private var freedBytes = 0L

    init {
        reload()
    }

    /** Relecture des copies proposées (ouverture, retour sur l'écran), sauf pendant une suppression. */
    fun reload() {
        if (_state.value.busy) return
        viewModelScope.launch { load() }
    }

    fun setAge(age: SaverAge) = _state.update { it.copy(age = age) }

    fun toggle(sourceId: Long) = _state.update {
        it.copy(deselected = if (sourceId in it.deselected) it.deselected - sourceId else it.deselected + sourceId)
    }

    /** Tout cocher ou tout décocher dans le filtre courant. */
    fun setAllSelected(selected: Boolean, now: Long) = _state.update { s ->
        val ids = s.filtered(now).map { it.sourceId }.toSet()
        s.copy(deselected = if (selected) s.deselected - ids else s.deselected + ids)
    }

    /** Confirmé par l'utilisateur : revérification puis premier lot de la demande de corbeille. */
    fun freeUp(now: Long) {
        val s = _state.value
        if (s.busy) return
        val reviewed = selectedIn(s.filtered(now), s.deselected)
        if (reviewed.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val valid = try {
                repository.revalidate(reviewed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Revérification des copies impossible", e)
                eventChannel.trySend(SpaceSaverEvent.Failed)
                _state.update { it.copy(busy = false) }
                return@launch
            }
            val dropped = reviewed.size - valid.size
            if (dropped > 0) eventChannel.trySend(SpaceSaverEvent.Changed(dropped))
            freedCount = 0
            freedBytes = 0L
            pendingBatches.clear()
            pendingBatches.addAll(trashBatches(valid))
            launchNextBatch()
        }
    }

    /** Retour de la boîte de dialogue système pour le lot en cours. */
    fun onTrashResult(granted: Boolean) {
        if (!granted) {
            // Refus : on s'arrête (les lots déjà confirmés sont dans la corbeille).
            pendingBatches.clear()
            finish()
            return
        }
        freedCount += currentBatch.size
        freedBytes += currentBatch.sumOf { it.sizeBytes }
        viewModelScope.launch { launchNextBatch() }
    }

    private suspend fun launchNextBatch() {
        val batch = pendingBatches.removeFirstOrNull()
        if (batch == null) {
            finish()
            return
        }
        currentBatch = batch
        val sender = withContext(io) {
            try {
                // Toujours la corbeille (récupérable), quel que soit le réglage de suppression.
                writer.createDeleteRequest(batch.map { Uri.parse(it.uri) }, toTrash = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Demande de mise à la corbeille impossible", e)
                null
            }
        }
        if (sender != null) {
            eventChannel.trySend(SpaceSaverEvent.LaunchTrash(sender))
        } else {
            pendingBatches.clear()
            eventChannel.trySend(SpaceSaverEvent.Failed)
            finish()
        }
    }

    private fun finish() {
        currentBatch = emptyList()
        val count = freedCount
        val bytes = freedBytes
        if (count > 0) eventChannel.trySend(SpaceSaverEvent.Freed(count, bytes))
        viewModelScope.launch {
            if (count > 0) {
                // Relecture de MediaStore (portée de l'appli : terminée même si l'écran est fermé),
                // puis de la liste.
                appScope.launch(io) {
                    try {
                        indexer.sync()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Resynchronisation MediaStore impossible", e)
                    }
                }.join()
            }
            _state.update { it.copy(busy = false, deselected = emptySet()) }
            load()
        }
    }

    private suspend fun load() {
        try {
            val list = repository.candidates()
            _state.update { it.copy(list = list) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lecture des copies proposées impossible", e)
            _state.update { it.copy(list = it.list ?: SpaceSaverList(emptyList(), 0)) }
        }
    }

    private companion object {
        const val TAG = "SpaceSaver"
    }
}
