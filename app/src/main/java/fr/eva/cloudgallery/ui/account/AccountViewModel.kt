package fr.eva.cloudgallery.ui.account

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudProfile
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.DeletedOnOneDriveCopy
import fr.eva.cloudgallery.domain.model.OfflineQueueState
import fr.eva.cloudgallery.domain.model.SignInOutcome
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.OfflinePurgeImpact
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.ui.offline.PurgeImpactUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State displayed by the Account screen. */
data class AccountUiState(
    val account: CloudAccountState,
    /** Microsoft sign-in page open. */
    val signInRunning: Boolean = false,
    /** OneDrive data removal in progress (previous session or other account). */
    val purging: Boolean = false,
    val sync: CloudSyncState = CloudSyncState(),
    /** Graph profile of the signed-in account (null until loaded). */
    val profile: CloudProfile? = null,
    /** "Show OneDrive items in Photos" setting (null before it is read). */
    val showCloudInPhotos: Boolean? = null,
    /** Offline queue and copies. */
    val offlineQueue: OfflineQueueState = OfflineQueueState(),
    /** Copies of items deleted from OneDrive awaiting a decision (D1). */
    val deletedCopies: List<DeletedOnOneDriveCopy> = emptyList(),
    /** D1 decision being applied. */
    val decidingCopies: Boolean = false,
)

sealed interface AccountEvent {
    data class SignInFailed(val code: CloudErrorCode) : AccountEvent
    data class SignOutFailed(val code: CloudErrorCode) : AccountEvent
    data object SignedOut : AccountEvent
    data object PurgeDone : AccountEvent
    data object PurgeFailed : AccountEvent
    data object ReplaceDone : AccountEvent
    data object ReplaceFailed : AccountEvent
    data class CopiesDeleted(val count: Int) : AccountEvent
    data class CopiesKept(val count: Int) : AccountEvent
    data object OfflineActionFailed : AccountEvent
    data object OfflineCancelled : AccountEvent
    data object OfflineResumed : AccountEvent
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: CloudAccountRepository,
    private val settingsRepository: SettingsRepository,
    private val offline: OfflineRepository,
) : ViewModel() {

    private val purging = MutableStateFlow(false)
    private val profile = MutableStateFlow<CloudProfile?>(null)
    private val decidingCopies = MutableStateFlow(false)

    /** Offline state: queue, copies awaiting a decision, decision in progress. */
    private val offlineState: Flow<Triple<OfflineQueueState, List<DeletedOnOneDriveCopy>, Boolean>> =
        combine(offline.queue, offline.deletedOnOneDrive, decidingCopies) { queue, deleted, deciding ->
            Triple(queue, deleted, deciding)
        }

    private val baseState: Flow<AccountUiState> =
        combine(accounts.state, accounts.signInRunning, purging, accounts.syncState, profile) { account, running, purgingNow, sync, prof ->
            AccountUiState(
                account = account,
                signInRunning = running,
                purging = purgingNow,
                sync = sync,
                profile = prof.takeIf { account is CloudAccountState.SignedIn },
            )
        }

    val state: StateFlow<AccountUiState> =
        combine(baseState, settingsRepository.settings.map { it.showCloudInPhotos }, offlineState) { base, showCloud, off ->
            base.copy(
                showCloudInPhotos = showCloud,
                offlineQueue = off.first,
                deletedCopies = off.second,
                decidingCopies = off.third,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AccountUiState(accounts.state.value, sync = accounts.syncState.value),
        )

    private val eventChannel = Channel<AccountEvent>(Channel.BUFFERED)
    val events: Flow<AccountEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            accounts.signInOutcomes.collect { outcome ->
                if (outcome is SignInOutcome.Failed) eventChannel.trySend(AccountEvent.SignInFailed(outcome.code))
            }
        }
        // Profile (name, address, quota) loaded on every sign-in; cleared when signed out.
        viewModelScope.launch {
            accounts.state.map { it is CloudAccountState.SignedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) loadProfile() else profile.value = null
            }
        }
    }

    /** Re-reads the account (each time the screen returns to the foreground: account persisted by MSAL in the meantime). */
    fun refresh() {
        viewModelScope.launch {
            accounts.refresh()
            if (accounts.state.value is CloudAccountState.SignedIn && profile.value == null) loadProfile()
        }
    }

    private suspend fun loadProfile() {
        // Failure (offline…): the screen keeps the MSAL account name, without quota.
        accounts.loadProfile().onSuccess { profile.value = it }
    }

    fun signIn(activity: Activity) = accounts.signIn(activity)

    fun reauthenticate(activity: Activity) = accounts.reauthenticate(activity)

    fun syncNow() = accounts.syncNow()

    fun setShowCloudInPhotos(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowCloudInPhotos(enabled) }
    }

    /**
     * Offline copies removed by a sign-out or a purge (including last copies);
     * [PurgeImpactUi.Unknown] if reading fails (the confirmation then warns cautiously).
     */
    internal suspend fun purgeImpact(): PurgeImpactUi = try {
        val impact: OfflinePurgeImpact = offline.purgeImpact()
        PurgeImpactUi.Known(impact.copies, impact.lastCopies)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PurgeImpactUi.Unknown
    }

    /**
     * "Delete copies" confirmed for [mediaIds], the list displayed and counted in the
     * confirmation (never a list re-read at click time).
     */
    fun deleteDeletedCopies(mediaIds: List<Long>) =
        decide { offline.deleteCopiesOfDeletedItems(mediaIds).let { AccountEvent.CopiesDeleted(it) } }

    /** "Keep them" for [mediaIds] (displayed list): copies kept as the only instances, visible. */
    fun keepDeletedCopies(mediaIds: List<Long>) =
        decide { offline.keepCopiesOfDeletedItems(mediaIds).let { AccountEvent.CopiesKept(it) } }

    private fun decide(action: suspend () -> AccountEvent) {
        if (decidingCopies.value) return
        decidingCopies.value = true
        viewModelScope.launch {
            try {
                eventChannel.trySend(action())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                eventChannel.trySend(AccountEvent.OfflineActionFailed)
            } finally {
                decidingCopies.value = false
            }
        }
    }

    /** Resumes paused or failed offline downloads. */
    fun resumeOffline() = offlineAction(AccountEvent.OfflineResumed) { offline.resume() }

    /** "Download now" (queue waiting for Wi-Fi). */
    fun downloadOfflineNow() = offlineAction(null) { offline.downloadNow() }

    /** Cancels all offline downloads (existing copies remain). */
    fun cancelOffline() = offlineAction(AccountEvent.OfflineCancelled) { offline.cancelAll() }

    private fun offlineAction(done: AccountEvent?, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                done?.let { eventChannel.trySend(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                eventChannel.trySend(AccountEvent.OfflineActionFailed)
            }
        }
    }

    /** Sign-out confirmed by the user (OneDrive data removed from the index). */
    fun confirmSignOut() {
        viewModelScope.launch {
            accounts.signOutAndPurge()
                .onSuccess { eventChannel.trySend(AccountEvent.SignedOut) }
                .onFailure { e ->
                    val code = (e as? CloudException)?.code ?: CloudErrorCode.UNKNOWN
                    eventChannel.trySend(AccountEvent.SignOutFailed(code))
                }
        }
    }

    /** Confirmed removal of OneDrive data from a previous session. */
    fun confirmPurgeLeftover() = runPurge(
        action = { accounts.purgeLeftoverData() },
        done = AccountEvent.PurgeDone,
        failed = AccountEvent.PurgeFailed,
    )

    /** Confirmed replacement of another OneDrive account's data with the signed-in account's. */
    fun confirmReplaceData() = runPurge(
        action = { accounts.replaceMismatchedData() },
        done = AccountEvent.ReplaceDone,
        failed = AccountEvent.ReplaceFailed,
    )

    private fun runPurge(action: suspend () -> Result<Unit>, done: AccountEvent, failed: AccountEvent) {
        if (purging.value) return
        purging.value = true
        viewModelScope.launch {
            try {
                action()
                    .onSuccess { eventChannel.trySend(done) }
                    .onFailure { eventChannel.trySend(failed) }
            } finally {
                purging.value = false
            }
        }
    }
}
