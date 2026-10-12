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
 * Règle unique d'accès au contenu OneDrive (miniatures, URL de lecture, originaux, copies hors
 * ligne) : fonction disponible dans ce build, compte connecté (jamais pendant une déconnexion),
 * et OneDrive connecté identique à celui des données de l'index. Les éléments d'un autre compte
 * ne sont ainsi jamais demandés avec ce jeton.
 *
 * MSAL et la synchro sont injectés via dagger.Lazy : créés au premier contrôle, sur le
 * dispatcher d'E/S. N'utilise jamais le verrou de la synchro (pas d'interblocage pendant une purge).
 */
@Singleton
class CloudAccessGuard @Inject constructor(
    private val feature: CloudFeature,
    private val auth: dagger.Lazy<MsalAuthService>,
    private val syncer: dagger.Lazy<CloudMediaSyncer>,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val accountMutex = Mutex()

    /** OneDrive est configuré dans ce build (sinon aucun accès n'est jamais possible). */
    val configured: Boolean get() = feature.enabled

    /**
     * Lève [CloudException] ([CloudErrorCode.CONFIG]) si OneDrive n'est pas configuré, ou
     * [CloudAuthRequiredException] sans compte connecté utilisable.
     */
    suspend fun ensureSignedIn() {
        if (!feature.enabled) throw CloudException(CloudErrorCode.CONFIG)
        withContext(io) {
            val service = auth.get()
            val state = service.state.value
            if (state is CloudAccountState.Loading || state is CloudAccountState.Error) {
                // Démarrage du processus ou lecture précédente en échec : une seule relecture du
                // compte MSAL pour tous les appelants simultanés.
                accountMutex.withLock { service.refreshIfNeeded() }
            }
            if (service.state.value !is CloudAccountState.SignedIn) throw CloudAuthRequiredException()
            if (syncer.get().state.value.accountMismatch) throw CloudAuthRequiredException()
        }
    }

    /** Vrai si [ensureSignedIn] réussit maintenant. */
    suspend fun canAccess(): Boolean = try {
        ensureSignedIn()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: CloudException) {
        false
    }
}
