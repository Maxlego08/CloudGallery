package fr.eva.cloudgallery.data.cloud.auth

import android.app.Activity
import android.util.Log
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import fr.eva.cloudgallery.data.cloud.CloudDataPurger
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAccount
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.SignInOutcome
import fr.eva.cloudgallery.domain.repository.CloudTokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authentification Microsoft (MSAL, compte unique) : état du compte, connexion interactive,
 * jetons silencieux et déconnexion MSAL. Ne dépend ni de la synchronisation ni de Graph
 * (l'orchestration « connexion -> synchro », « déconnexion -> purge » vit dans
 * [fr.eva.cloudgallery.data.cloud.CloudSessionCoordinator]) : le graphe Hilt reste acyclique,
 * le client Graph pouvant dépendre de ce fournisseur de jetons.
 *
 * Toutes les API bloquantes de MSAL sont appelées sur le dispatcher d'E/S ; les rappels MSAL
 * arrivent sur le thread principal et ne font que publier un état. Les journaux ne contiennent
 * jamais de jeton ni de nom d'utilisateur.
 */
@Singleton
class MsalAuthService @Inject constructor(
    private val feature: CloudFeature,
    private val clients: MsalClientProvider,
    private val purger: CloudDataPurger,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CloudTokenProvider {

    private val _state = MutableStateFlow(
        if (feature.enabled) CloudAccountState.Loading else CloudAccountState.NotConfigured,
    )
    val state: StateFlow<CloudAccountState> = _state.asStateFlow()

    private val _signInRunning = MutableStateFlow(false)
    val signInRunning: StateFlow<Boolean> = _signInRunning.asStateFlow()

    private val _signInOutcomes = MutableSharedFlow<SignInOutcome>(extraBufferCapacity = 8)
    val signInOutcomes: SharedFlow<SignInOutcome> = _signInOutcomes.asSharedFlow()

    /** Sérialise les transitions d'état (relecture du compte, déconnexion). */
    private val stateMutex = Mutex()

    /** Une seule acquisition de jeton à la fois (MSAL rejette les commandes identiques simultanées). */
    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: CachedToken? = null

    /** Compte (id MSAL) pour lequel une reconnexion est requise, tant qu'aucun jeton n'a été obtenu. */
    @Volatile
    private var reauthAccountId: String? = null

    @Volatile
    private var signingOut = false

    /**
     * Numéro de session : incrémenté au début d'une déconnexion et à chaque connexion interactive
     * réussie. Un jeton silencieux demandé sous une session précédente (requête lente terminée
     * après la déconnexion) est ignoré : ni mis en cache, ni changement d'état.
     */
    private val sessionEpoch = AtomicLong(0)

    /** Dernière relecture automatique après une erreur de lecture du compte. */
    @Volatile
    private var lastErrorRetryAt = 0L

    private class CachedToken(val accountId: String, val value: String, val expiresAtMs: Long)

    /** Relit le compte persisté par MSAL. Sans effet pendant une déconnexion. */
    suspend fun refresh() {
        if (!feature.enabled) {
            _state.value = CloudAccountState.NotConfigured
            return
        }
        stateMutex.withLock {
            if (!signingOut) _state.value = readState()
        }
    }

    /**
     * Relit le compte si son état n'est pas encore connu ([CloudAccountState.Loading]) ou si la
     * dernière lecture a échoué ([CloudAccountState.Error], souvent passager : Keystore, client
     * MSAL), au plus une fois toutes les [ERROR_RETRY_INTERVAL_MS] dans ce second cas. Appelé
     * avant une synchronisation ou un téléchargement de miniature.
     */
    suspend fun refreshIfNeeded() {
        when (_state.value) {
            CloudAccountState.Loading -> refresh()
            is CloudAccountState.Error -> {
                val now = System.currentTimeMillis()
                val last = lastErrorRetryAt
                if (last > 0L && now >= last && now - last < ERROR_RETRY_INTERVAL_MS) return
                lastErrorRetryAt = now
                refresh()
            }
            else -> Unit
        }
    }

    private suspend fun readState(): CloudAccountState = withContext(io) {
        try {
            val account = clients.get().currentAccount.currentAccount
            if (account == null) {
                cachedToken = null
                reauthAccountId = null
                CloudAccountState.SignedOut(hasLeftoverData = purger.hasData())
            } else {
                val cloudAccount = account.toCloudAccount()
                if (reauthAccountId == account.id) {
                    CloudAccountState.NeedsReauth(cloudAccount)
                } else {
                    CloudAccountState.SignedIn(cloudAccount)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lecture du compte Microsoft impossible : ${describe(e)}")
            CloudAccountState.Error(errorCodeOf(e))
        }
    }

    /** Ouvre la page de connexion Microsoft. L'issue est publiée dans [signInOutcomes]. */
    fun signIn(activity: Activity) = startInteractive(activity, again = false)

    /** Reconnexion du compte courant (session expirée). */
    fun reauthenticate(activity: Activity) = startInteractive(activity, again = true)

    private fun startInteractive(activity: Activity, again: Boolean) {
        if (!feature.enabled || signingOut) return
        if (!_signInRunning.compareAndSet(expect = false, update = true)) return
        // E/S : création du client et lecture du compte persisté. MSAL lance ensuite sa propre
        // activité ; l'issue arrive par le rappel, même si l'écran a été recréé entre-temps.
        scope.launch(io) {
            try {
                val app = clients.get()
                val current = app.currentAccount.currentAccount
                if (!again && current != null) {
                    // MSAL refuserait la connexion (« un compte est déjà connecté ») : on relit l'état.
                    finishInteractive(SignInOutcome.Success)
                    refresh()
                    return@launch
                }
                if (activity.isFinishing || activity.isDestroyed) {
                    finishInteractive(SignInOutcome.Cancelled)
                    return@launch
                }
                val params = SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(GRAPH_SCOPES)
                    .withPrompt(if (again && current != null) Prompt.WHEN_REQUIRED else Prompt.SELECT_ACCOUNT)
                    .withCallback(interactiveCallback)
                    .build()
                if (again && current != null) app.signInAgain(params) else app.signIn(params)
            } catch (e: CancellationException) {
                _signInRunning.value = false
                throw e
            } catch (e: Exception) {
                onInteractiveError(e)
            }
        }
    }

    private val interactiveCallback = object : AuthenticationCallback {
        override fun onSuccess(result: IAuthenticationResult) {
            val account = result.account
            // Nouvelle session : un jeton silencieux encore en cours pour l'ancienne est ignoré.
            sessionEpoch.incrementAndGet()
            cacheToken(account.id, result)
            reauthAccountId = null
            // Sous le verrou d'état : une relecture lancée avant l'enregistrement du compte par
            // MSAL (retour au premier plan) ne peut pas écraser ce succès après coup.
            scope.launch {
                stateMutex.withLock {
                    if (!signingOut) _state.value = CloudAccountState.SignedIn(account.toCloudAccount())
                }
                finishInteractive(SignInOutcome.Success)
            }
        }

        override fun onError(exception: MsalException) = onInteractiveError(exception)

        override fun onCancel() = finishInteractive(SignInOutcome.Cancelled)
    }

    private fun onInteractiveError(error: Throwable) {
        val failure = classifyMsalError(error)
        Log.w(TAG, "Connexion Microsoft échouée : ${describe(error)}")
        val code = failure.toErrorCode()
        finishInteractive(if (code == null) SignInOutcome.Cancelled else SignInOutcome.Failed(code))
        // L'état a pu changer (compte retiré, configuration invalide…) : relecture.
        if (failure != MsalFailure.CANCELLED) scope.launch { refresh() }
    }

    private fun finishInteractive(outcome: SignInOutcome) {
        _signInRunning.value = false
        _signInOutcomes.tryEmit(outcome)
    }

    override suspend fun accessToken(forceRefresh: Boolean): String {
        if (!feature.enabled) throw CloudException(CloudErrorCode.CONFIG)
        return tokenMutex.withLock {
            val cached = cachedToken
            if (!forceRefresh && cached != null && isTokenFresh(cached.expiresAtMs, System.currentTimeMillis())) {
                cached.value
            } else {
                withContext(io) { acquireSilently(forceRefresh) }
            }
        }
    }

    /** Jeton silencieux (API bloquante de MSAL, sans rappel : thread d'E/S uniquement). */
    private suspend fun acquireSilently(forceRefresh: Boolean): String {
        val epoch = sessionEpoch.get()
        val app = clientOrThrow()
        var account: IAccount? = null
        try {
            account = app.currentAccount.currentAccount
                ?: throw MsalClientException(MsalClientException.NO_CURRENT_ACCOUNT)
            val params = AcquireTokenSilentParameters.Builder()
                .withScopes(GRAPH_SCOPES)
                .forAccount(account)
                .fromAuthority(account.authority)
                .forceRefresh(forceRefresh)
                .build()
            val result = app.acquireTokenSilent(params)
            if (!onTokenAcquired(account, result, epoch)) {
                // Déconnexion (ou autre connexion) pendant la requête : le jeton est abandonné.
                throw CloudAuthRequiredException()
            }
            return result.accessToken
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudAuthRequiredException) {
            throw e
        } catch (e: Exception) {
            val failure = classifyMsalError(e)
            Log.w(TAG, "Jeton silencieux indisponible : ${describe(e)}")
            when (failure) {
                MsalFailure.UI_REQUIRED -> {
                    account?.let { markNeedsReauth(it, epoch) }
                    throw CloudAuthRequiredException(e)
                }
                MsalFailure.NO_ACCOUNT -> {
                    cachedToken = null
                    refresh()
                    throw CloudAuthRequiredException(e)
                }
                // Hors ligne, délai, commande en double… : la session reste valide.
                else -> throw CloudException(failure.toErrorCode() ?: CloudErrorCode.UNKNOWN, e)
            }
        }
    }

    private suspend fun clientOrThrow(): ISingleAccountPublicClientApplication = try {
        clients.get()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CloudException(errorCodeOf(e), e)
    }

    /** Session toujours d'actualité : même numéro qu'au début de la requête, pas de déconnexion. */
    private fun isCurrentSession(epoch: Long) = !signingOut && sessionEpoch.get() == epoch

    /**
     * Reconnexion requise pour [account], seulement si la session n'a pas changé depuis la
     * demande de jeton et que le compte n'est pas déconnecté (jamais après une déconnexion).
     */
    private suspend fun markNeedsReauth(account: IAccount, epoch: Long) {
        stateMutex.withLock {
            if (!isCurrentSession(epoch)) return@withLock
            cachedToken = null
            when (_state.value) {
                is CloudAccountState.SignedIn,
                is CloudAccountState.NeedsReauth,
                is CloudAccountState.Error,
                CloudAccountState.Loading,
                -> {
                    reauthAccountId = account.id
                    _state.value = CloudAccountState.NeedsReauth(account.toCloudAccount())
                }
                else -> Unit
            }
        }
    }

    /**
     * Jeton silencieux obtenu : mis en cache seulement si la session n'a pas changé pendant la
     * requête (faux sinon : le jeton doit être abandonné). L'état ne peut passer que de
     * Loading / Error / NeedsReauth à SignedIn : un compte déconnecté ne redevient jamais connecté.
     */
    private suspend fun onTokenAcquired(account: IAccount, result: IAuthenticationResult, epoch: Long): Boolean =
        stateMutex.withLock {
            if (!isCurrentSession(epoch)) return@withLock false
            cacheToken(account.id, result)
            reauthAccountId = null
            when (_state.value) {
                is CloudAccountState.NeedsReauth,
                is CloudAccountState.Error,
                CloudAccountState.Loading,
                -> _state.value = CloudAccountState.SignedIn(account.toCloudAccount())
                else -> Unit
            }
            true
        }

    override suspend fun onTokenRejected(token: String) {
        stateMutex.withLock {
            val cached = cachedToken
            // Jeton déjà remplacé ou effacé (déconnexion, autre compte) : rien à faire.
            if (signingOut || cached == null || cached.value != token) return@withLock
            cachedToken = null
            val current = _state.value
            if (current is CloudAccountState.SignedIn) {
                Log.w(TAG, "Jeton renouvelé refusé par Microsoft Graph : reconnexion requise")
                reauthAccountId = cached.accountId
                _state.value = CloudAccountState.NeedsReauth(current.account)
            }
        }
    }

    private fun cacheToken(accountId: String, result: IAuthenticationResult) {
        cachedToken = CachedToken(accountId, result.accessToken, result.expiresOn.time)
    }

    /**
     * Début de déconnexion : l'état passe à [CloudAccountState.SigningOut] (aucune synchro ne
     * démarre, [refresh] est ignoré) jusqu'à [endSignOut].
     */
    suspend fun beginSignOut() {
        stateMutex.withLock {
            sessionEpoch.incrementAndGet()
            signingOut = true
            _state.value = CloudAccountState.SigningOut
        }
    }

    /** Déconnexion MSAL (efface le compte et ses jetons du cache MSAL). */
    suspend fun signOut(): Result<Unit> = withContext(io) {
        try {
            clients.get().signOut()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is MsalClientException && e.errorCode == MsalClientException.NO_CURRENT_ACCOUNT) {
                Result.success(Unit)
            } else {
                Log.w(TAG, "Déconnexion Microsoft échouée : ${describe(e)}")
                Result.failure(CloudException(errorCodeOf(e), e))
            }
        } finally {
            cachedToken = null
        }
    }

    /** Fin de déconnexion (réussie ou non) : l'état réel est relu (données restantes comprises). */
    suspend fun endSignOut() {
        stateMutex.withLock {
            signingOut = false
            _state.value = readState()
        }
    }

    private fun errorCodeOf(error: Throwable): CloudErrorCode = when (error) {
        is CloudException -> error.code
        is MsalException -> classifyMsalError(error).toErrorCode() ?: CloudErrorCode.UNKNOWN
        is IllegalStateException -> CloudErrorCode.CONFIG
        else -> CloudErrorCode.UNKNOWN
    }

    private companion object {
        const val TAG = "MsalAuth"

        /** Intervalle minimal entre deux relectures automatiques du compte après une erreur. */
        const val ERROR_RETRY_INTERVAL_MS = 30_000L

        fun IAccount.toCloudAccount() = CloudAccount(username = username?.takeIf { it.isNotBlank() }, id = id?.takeIf { it.isNotBlank() })

        /** Type et code d'erreur seulement : les messages MSAL peuvent contenir des données de compte. */
        fun describe(error: Throwable): String =
            error.javaClass.simpleName + ((error as? MsalException)?.let { " (${it.errorCode})" } ?: "")
    }
}
