package fr.eva.cloudgallery.data.cloud

import android.app.Activity
import android.util.Log
import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.data.cloud.offline.OfflineRepositoryImpl
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudProfile
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.SignInOutcome
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OneDrive session as seen by the UI: ties together authentication ([MsalAuthService]),
 * synchronization ([CloudMediaSyncer]) and the cloud data of the local index
 * ([CloudDataPurger]). Nothing but the UI depends on this class, which avoids any injection
 * cycle (Graph -> tokens -> MSAL).
 *
 * Sign-out and purges run in the application scope (leaving the screen does not interrupt
 * them halfway), mutually exclusive with synchronization: no OneDrive page can be written
 * during or after the purge.
 */
@Singleton
class CloudSessionCoordinator @Inject constructor(
    private val feature: CloudFeature,
    private val auth: MsalAuthService,
    private val purger: CloudDataPurger,
    private val syncer: CloudMediaSyncer,
    private val graph: GraphApi,
    private val offline: dagger.Lazy<OfflineRepositoryImpl>,
    @param:ApplicationScope private val scope: CoroutineScope,
) : CloudAccountRepository {

    /** Only one sign-out / purge at a time. */
    private val sessionMutex = Mutex()

    override val isAvailable: Boolean get() = feature.enabled

    override val state: StateFlow<CloudAccountState> get() = auth.state

    override val signInRunning: StateFlow<Boolean> get() = auth.signInRunning

    override val signInOutcomes: Flow<SignInOutcome> get() = auth.signInOutcomes

    override val syncState: StateFlow<CloudSyncState> get() = syncer.state

    override suspend fun refresh() = auth.refresh()

    override fun signIn(activity: Activity) = auth.signIn(activity)

    override fun reauthenticate(activity: Activity) = auth.reauthenticate(activity)

    override fun syncNow() = syncer.requestSync(CloudSyncReason.MANUAL)

    override suspend fun signOutAndPurge(): Result<Unit> = scope.async {
        sessionMutex.withLock {
            // "Signing out" state first: no synchronization can start anymore.
            auth.beginSignOut()
            var result: Result<Unit>? = null
            try {
                result = syncer.exclusive {
                    // Offline copies: downloads stopped before the sign-out, none can restart
                    // during the purge.
                    offline.get().whileStoppedForPurge {
                        // MSAL sign-out first: if it fails, the data is kept.
                        val signOut = auth.signOut()
                        if (signOut.isFailure) signOut else purge()
                    }
                }
                result
            } finally {
                withContext(NonCancellable) {
                    auth.endSignOut()
                    // Failure: nothing was removed, the account stays signed in; queued downloads
                    // resume (after endSignOut: while "signing out", account access is denied
                    // and nothing would be rescheduled).
                    if (result?.isFailure == true) offline.get().resumeIfNeeded()
                }
            }
        }
    }.await()

    override suspend fun purgeLeftoverData(): Result<Unit> = scope.async {
        sessionMutex.withLock {
            // Only without a signed-in account: the data of an active session is only removed
            // by signing out.
            if (auth.state.value !is CloudAccountState.SignedOut) {
                return@withLock Result.failure(IllegalStateException("A OneDrive account is signed in"))
            }
            val result = syncer.exclusive { offline.get().whileStoppedForPurge { purge() } }
            withContext(NonCancellable) { auth.refresh() }
            result
        }
    }.await()

    override suspend fun replaceMismatchedData(): Result<Unit> = scope.async {
        sessionMutex.withLock {
            // Confirmed by the user, and only while the account mismatch is observed.
            if (auth.state.value !is CloudAccountState.SignedIn || !syncer.state.value.accountMismatch) {
                return@withLock Result.failure(IllegalStateException("No OneDrive account change to confirm"))
            }
            val result = syncer.exclusive { offline.get().whileStoppedForPurge { purge() } }
            if (result.isSuccess) syncer.requestSync(CloudSyncReason.MANUAL)
            result
        }
    }.await()

    override suspend fun loadProfile(): Result<CloudProfile> {
        val account = (auth.state.value as? CloudAccountState.SignedIn)?.account
            ?: return Result.failure(CloudAuthRequiredException())
        return try {
            val me = graph.me()
            val quota = graph.drive().quota
            Result.success(
                CloudProfile(
                    displayName = me.displayName?.takeIf { it.isNotBlank() },
                    email = sequenceOf(me.mail, me.userPrincipalName, account.username).firstOrNull { !it.isNullOrBlank() },
                    quotaUsed = quota?.used?.takeIf { it >= 0 },
                    quotaTotal = quota?.total?.takeIf { it > 0 },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            Log.w(TAG, "OneDrive profile unavailable: ${e.code}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "OneDrive profile unavailable", e)
            Result.failure(CloudException(CloudErrorCode.UNKNOWN, e))
        }
    }

    private suspend fun purge(): Result<Unit> = try {
        purger.purgeAll()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to remove OneDrive data", e)
        Result.failure(e)
    }

    private companion object {
        const val TAG = "CloudSession"
    }
}
