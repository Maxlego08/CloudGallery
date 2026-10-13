package fr.eva.cloudgallery.data.cloud

import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single access rule for OneDrive content (thumbnails, playback URLs, originals, offline
 * copies): feature available in this build, account signed in (never during a sign-out), and
 * the connected OneDrive is the same as the one the index data came from. Items from another
 * account are thus never requested with this token.
 *
 * MSAL and the syncer are injected via dagger.Lazy: created on the first check, on the I/O
 * dispatcher. Never uses the sync lock (no deadlock during a purge).
 */
@Singleton
class CloudAccessGuard @Inject constructor(
    private val feature: CloudFeature,
    private val auth: dagger.Lazy<MsalAuthService>,
    private val syncer: dagger.Lazy<CloudMediaSyncer>,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val accountMutex = Mutex()

    /** OneDrive is configured in this build (otherwise no access is ever possible). */
    val configured: Boolean get() = feature.enabled

    /**
     * Throws [CloudException] ([CloudErrorCode.CONFIG]) if OneDrive is not configured, or
     * [CloudAuthRequiredException] without a usable signed-in account.
     */
    suspend fun ensureSignedIn() {
        if (!feature.enabled) throw CloudException(CloudErrorCode.CONFIG)
        withContext(io) {
            val service = auth.get()
            val state = service.state.value
            if (state is CloudAccountState.Loading || state is CloudAccountState.Error) {
                // Process startup or previous read failed: a single re-read of the MSAL
                // account for all concurrent callers.
                accountMutex.withLock { service.refreshIfNeeded() }
            }
            val signedIn = service.state.value as? CloudAccountState.SignedIn ?: throw CloudAuthRequiredException()
            // Different drive detected, or account not yet verified by a sync (persisted state:
            // true right from process startup, without waiting for the first pass).
            if (!syncer.get().accountAllowed(signedIn.account.id)) throw CloudAuthRequiredException()
        }
    }

    /** True if [ensureSignedIn] succeeds now. */
    suspend fun canAccess(): Boolean = try {
        ensureSignedIn()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: CloudException) {
        false
    }
}
