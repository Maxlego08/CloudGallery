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
 * Microsoft authentication (MSAL, single account): account state, interactive sign-in, silent
 * tokens and MSAL sign-out. Depends neither on synchronization nor on Graph (the "sign-in ->
 * sync" and "sign-out -> purge" orchestration lives in
 * [fr.eva.cloudgallery.data.cloud.CloudSessionCoordinator]): the Hilt graph stays acyclic,
 * since the Graph client can depend on this token provider.
 *
 * All blocking MSAL APIs are called on the I/O dispatcher; MSAL callbacks arrive on the main
 * thread and only publish a state. Logs never contain a token or a user name.
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

    /** Serializes state transitions (account re-read, sign-out). */
    private val stateMutex = Mutex()

    /** Only one token acquisition at a time (MSAL rejects identical concurrent commands). */
    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: CachedToken? = null

    /** Account (MSAL id) for which re-authentication is required, until a token is obtained. */
    @Volatile
    private var reauthAccountId: String? = null

    @Volatile
    private var signingOut = false

    /**
     * Session number: incremented at the start of a sign-out and on every successful interactive
     * sign-in. A silent token requested under a previous session (slow request finishing after
     * the sign-out) is ignored: neither cached nor causing a state change.
     */
    private val sessionEpoch = AtomicLong(0)

    /** Last automatic re-read after an account read error. */
    @Volatile
    private var lastErrorRetryAt = 0L

    private class CachedToken(val accountId: String, val value: String, val expiresAtMs: Long)

    /** Re-reads the account persisted by MSAL. No effect during a sign-out. */
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
     * Re-reads the account if its state is not known yet ([CloudAccountState.Loading]) or if the
     * last read failed ([CloudAccountState.Error], often transient: Keystore, MSAL client), at
     * most once every [ERROR_RETRY_INTERVAL_MS] in the latter case. Called before a sync or a
     * thumbnail download.
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
            Log.w(TAG, "Unable to read the Microsoft account: ${describe(e)}")
            CloudAccountState.Error(errorCodeOf(e))
        }
    }

    /** Opens the Microsoft sign-in page. The outcome is published in [signInOutcomes]. */
    fun signIn(activity: Activity) = startInteractive(activity, again = false)

    /** Re-authentication of the current account (expired session). */
    fun reauthenticate(activity: Activity) = startInteractive(activity, again = true)

    private fun startInteractive(activity: Activity, again: Boolean) {
        if (!feature.enabled || signingOut) return
        if (!_signInRunning.compareAndSet(expect = false, update = true)) return
        // I/O: client creation and read of the persisted account. MSAL then launches its own
        // activity; the outcome arrives through the callback, even if the screen was recreated
        // in the meantime.
        scope.launch(io) {
            try {
                val app = clients.get()
                val current = app.currentAccount.currentAccount
                if (!again && current != null) {
                    // MSAL would refuse the sign-in ("an account is already signed in"): re-read the state.
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
            // New session: a silent token still in flight for the old one is ignored.
            sessionEpoch.incrementAndGet()
            cacheToken(account.id, result)
            reauthAccountId = null
            // Under the state lock: a re-read started before MSAL saved the account (return to
            // the foreground) cannot overwrite this success afterwards.
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
        Log.w(TAG, "Microsoft sign-in failed: ${describe(error)}")
        val code = failure.toErrorCode()
        finishInteractive(if (code == null) SignInOutcome.Cancelled else SignInOutcome.Failed(code))
        // The state may have changed (account removed, invalid configuration...): re-read.
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

    /** Silent token (blocking MSAL API, no callback: I/O thread only). */
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
                // Sign-out (or another sign-in) during the request: the token is discarded.
                throw CloudAuthRequiredException()
            }
            return result.accessToken
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudAuthRequiredException) {
            throw e
        } catch (e: Exception) {
            val failure = classifyMsalError(e)
            Log.w(TAG, "Silent token unavailable: ${describe(e)}")
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
                // Offline, timeout, duplicate command...: the session stays valid.
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

    /** Session still current: same number as at the start of the request, no sign-out. */
    private fun isCurrentSession(epoch: Long) = !signingOut && sessionEpoch.get() == epoch

    /**
     * Re-authentication required for [account], only if the session has not changed since the
     * token request and the account is not signed out (never after a sign-out).
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
     * Silent token obtained: cached only if the session did not change during the request
     * (false otherwise: the token must be discarded). The state can only go from
     * Loading / Error / NeedsReauth to SignedIn: a signed-out account never becomes signed in again.
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
            // Token already replaced or cleared (sign-out, other account): nothing to do.
            if (signingOut || cached == null || cached.value != token) return@withLock
            cachedToken = null
            val current = _state.value
            if (current is CloudAccountState.SignedIn) {
                Log.w(TAG, "Renewed token rejected by Microsoft Graph: re-authentication required")
                reauthAccountId = cached.accountId
                _state.value = CloudAccountState.NeedsReauth(current.account)
            }
        }
    }

    private fun cacheToken(accountId: String, result: IAuthenticationResult) {
        cachedToken = CachedToken(accountId, result.accessToken, result.expiresOn.time)
    }

    /**
     * Start of sign-out: the state switches to [CloudAccountState.SigningOut] (no sync starts,
     * [refresh] is ignored) until [endSignOut].
     */
    suspend fun beginSignOut() {
        stateMutex.withLock {
            sessionEpoch.incrementAndGet()
            signingOut = true
            _state.value = CloudAccountState.SigningOut
        }
    }

    /** MSAL sign-out (clears the account and its tokens from the MSAL cache). */
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
                Log.w(TAG, "Microsoft sign-out failed: ${describe(e)}")
                Result.failure(CloudException(errorCodeOf(e), e))
            }
        } finally {
            cachedToken = null
        }
    }

    /** End of sign-out (successful or not): the actual state is re-read (leftover data included). */
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

        /** Minimum interval between two automatic account re-reads after an error. */
        const val ERROR_RETRY_INTERVAL_MS = 30_000L

        fun IAccount.toCloudAccount() = CloudAccount(username = username?.takeIf { it.isNotBlank() }, id = id?.takeIf { it.isNotBlank() })

        /** Type and error code only: MSAL messages may contain account data. */
        fun describe(error: Throwable): String =
            error.javaClass.simpleName + ((error as? MsalException)?.let { " (${it.errorCode})" } ?: "")
    }
}
