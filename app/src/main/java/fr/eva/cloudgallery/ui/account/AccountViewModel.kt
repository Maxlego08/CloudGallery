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
import fr.eva.cloudgallery.domain.model.SignInOutcome
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
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

/** Etat affiché par l'écran Compte. */
data class AccountUiState(
    val account: CloudAccountState,
    /** Page de connexion Microsoft ouverte. */
    val signInRunning: Boolean = false,
    /** Retrait de données OneDrive en cours (session précédente ou autre compte). */
    val purging: Boolean = false,
    val sync: CloudSyncState = CloudSyncState(),
    /** Profil Graph du compte connecté (null tant qu'il n'est pas chargé). */
    val profile: CloudProfile? = null,
    /** Réglage « Afficher les éléments OneDrive dans Photos » (null avant lecture). */
    val showCloudInPhotos: Boolean? = null,
)

sealed interface AccountEvent {
    data class SignInFailed(val code: CloudErrorCode) : AccountEvent
    data class SignOutFailed(val code: CloudErrorCode) : AccountEvent
    data object SignedOut : AccountEvent
    data object PurgeDone : AccountEvent
    data object PurgeFailed : AccountEvent
    data object ReplaceDone : AccountEvent
    data object ReplaceFailed : AccountEvent
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: CloudAccountRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val purging = MutableStateFlow(false)
    private val profile = MutableStateFlow<CloudProfile?>(null)

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
        combine(baseState, settingsRepository.settings.map { it.showCloudInPhotos }) { base, showCloud ->
            base.copy(showCloudInPhotos = showCloud)
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
        // Profil (nom, adresse, quota) chargé à chaque connexion ; effacé hors connexion.
        viewModelScope.launch {
            accounts.state.map { it is CloudAccountState.SignedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) loadProfile() else profile.value = null
            }
        }
    }

    /** Relit le compte (à chaque retour au premier plan de l'écran : compte persisté par MSAL entre-temps). */
    fun refresh() {
        viewModelScope.launch {
            accounts.refresh()
            if (accounts.state.value is CloudAccountState.SignedIn && profile.value == null) loadProfile()
        }
    }

    private suspend fun loadProfile() {
        // Echec (hors ligne…) : l'écran garde le nom du compte MSAL, sans quota.
        accounts.loadProfile().onSuccess { profile.value = it }
    }

    fun signIn(activity: Activity) = accounts.signIn(activity)

    fun reauthenticate(activity: Activity) = accounts.reauthenticate(activity)

    fun syncNow() = accounts.syncNow()

    fun setShowCloudInPhotos(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowCloudInPhotos(enabled) }
    }

    /** Déconnexion confirmée par l'utilisateur (données OneDrive retirées de l'index). */
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

    /** Retrait confirmé des données OneDrive d'une session précédente. */
    fun confirmPurgeLeftover() = runPurge(
        action = { accounts.purgeLeftoverData() },
        done = AccountEvent.PurgeDone,
        failed = AccountEvent.PurgeFailed,
    )

    /** Remplacement confirmé des données d'un autre compte OneDrive par celles du compte connecté. */
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
