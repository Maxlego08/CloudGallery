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
 * Session OneDrive vue par l'UI : relie l'authentification ([MsalAuthService]), la
 * synchronisation ([CloudMediaSyncer]) et les données cloud de l'index local
 * ([CloudDataPurger]). Rien ne dépend de cette classe hormis l'UI, ce qui évite tout cycle
 * d'injection (Graph -> jetons -> MSAL).
 *
 * Déconnexion et purges s'exécutent dans la portée applicative (quitter l'écran ne les
 * interrompt pas à mi-chemin), en exclusion mutuelle avec la synchronisation : aucune page
 * OneDrive ne peut être écrite pendant ou après la purge.
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

    /** Une seule déconnexion / purge à la fois. */
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
            // Etat « déconnexion » d'abord : plus aucune synchronisation ne peut démarrer.
            auth.beginSignOut()
            var result: Result<Unit>? = null
            try {
                result = syncer.exclusive {
                    // Copies hors ligne : téléchargements arrêtés avant la déconnexion, aucun ne
                    // peut redémarrer pendant la purge.
                    offline.get().whileStoppedForPurge {
                        // Déconnexion MSAL d'abord : en cas d'échec, les données sont conservées.
                        val signOut = auth.signOut()
                        if (signOut.isFailure) signOut else purge()
                    }
                }
                result
            } finally {
                withContext(NonCancellable) {
                    auth.endSignOut()
                    // Echec : rien n'a été retiré, le compte reste connecté ; les téléchargements
                    // en file reprennent (après endSignOut : pendant « déconnexion », l'accès au
                    // compte est refusé et rien ne serait replanifié).
                    if (result?.isFailure == true) offline.get().resumeIfNeeded()
                }
            }
        }
    }.await()

    override suspend fun purgeLeftoverData(): Result<Unit> = scope.async {
        sessionMutex.withLock {
            // Uniquement sans compte connecté : les données d'une session active ne sont retirées
            // que par la déconnexion.
            if (auth.state.value !is CloudAccountState.SignedOut) {
                return@withLock Result.failure(IllegalStateException("Un compte OneDrive est connecté"))
            }
            val result = syncer.exclusive { offline.get().whileStoppedForPurge { purge() } }
            withContext(NonCancellable) { auth.refresh() }
            result
        }
    }.await()

    override suspend fun replaceMismatchedData(): Result<Unit> = scope.async {
        sessionMutex.withLock {
            // Confirmé par l'utilisateur, et seulement tant que le décalage de compte est constaté.
            if (auth.state.value !is CloudAccountState.SignedIn || !syncer.state.value.accountMismatch) {
                return@withLock Result.failure(IllegalStateException("Aucun changement de compte OneDrive à confirmer"))
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
            Log.w(TAG, "Profil OneDrive indisponible : ${e.code}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Profil OneDrive indisponible", e)
            Result.failure(CloudException(CloudErrorCode.UNKNOWN, e))
        }
    }

    private suspend fun purge(): Result<Unit> = try {
        purger.purgeAll()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Echec du retrait des données OneDrive", e)
        Result.failure(e)
    }

    private companion object {
        const val TAG = "CloudSession"
    }
}
