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

/** Screen state: [list] null while loading; [busy] while moving to the trash. */
data class SpaceSaverUiState(
    val list: SpaceSaverList? = null,
    val age: SaverAge = SaverAge.DAYS_30,
    val deselected: Set<Long> = emptySet(),
    val busy: Boolean = false,
) {
    /** Copies in the current age filter. */
    fun filtered(now: Long): List<SpaceSaverCandidate> = filterByAge(list?.candidates.orEmpty(), age, now)
}

sealed interface SpaceSaverEvent {
    /** System request to move a batch to the trash (confirmed by the user). */
    class LaunchTrash(val sender: IntentSender) : SpaceSaverEvent

    /** Copies sent to the trash and space freed. */
    data class Freed(val count: Int, val bytes: Long) : SpaceSaverEvent

    /** Copies dropped on re-verification (modified or no longer on OneDrive in the meantime). */
    data class Changed(val count: Int) : SpaceSaverEvent

    data object Failed : SpaceSaverEvent
}

/**
 * Space saver: review of local copies already on OneDrive, then moving the checked copies to
 * the Android trash, after confirmation in the app and then in the system dialog (in batches
 * of [MAX_TRASH_BATCH]). Never a permanent deletion; never started automatically. The copies
 * are re-verified against the database right before the request.
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

    /** Reloads the proposed copies (opening, returning to the screen), except during a deletion. */
    fun reload() {
        if (_state.value.busy) return
        viewModelScope.launch { load() }
    }

    fun setAge(age: SaverAge) = _state.update { it.copy(age = age) }

    fun toggle(sourceId: Long) = _state.update {
        it.copy(deselected = if (sourceId in it.deselected) it.deselected - sourceId else it.deselected + sourceId)
    }

    /** Checks or unchecks everything in the current filter. */
    fun setAllSelected(selected: Boolean, now: Long) = _state.update { s ->
        val ids = s.filtered(now).map { it.sourceId }.toSet()
        s.copy(deselected = if (selected) s.deselected - ids else s.deselected + ids)
    }

    /** Confirmed by the user: re-verification, then the first batch of the trash request. */
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
                Log.w(TAG, "Could not re-verify copies", e)
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

    /** Result of the system dialog for the current batch. */
    fun onTrashResult(granted: Boolean) {
        if (!granted) {
            // Declined: stop (batches already confirmed are in the trash).
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
                // Always the trash (recoverable), whatever the deletion setting.
                writer.createDeleteRequest(batch.map { Uri.parse(it.uri) }, toTrash = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not request moving to the trash", e)
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
                // Reload MediaStore (app scope: completes even if the screen is closed),
                // then the list.
                appScope.launch(io) {
                    try {
                        indexer.sync()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore resync failed", e)
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
            Log.w(TAG, "Could not load proposed copies", e)
            _state.update { it.copy(list = it.list ?: SpaceSaverList(emptyList(), 0)) }
        }
    }

    private companion object {
        const val TAG = "SpaceSaver"
    }
}
