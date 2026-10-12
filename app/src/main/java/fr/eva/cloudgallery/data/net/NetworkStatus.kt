package fr.eva.cloudgallery.data.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Etat du réseau par défaut ([NetworkState]), observé par un seul rappel pour toute l'appli
 * (le système limite le nombre de rappels réseau par appli). Le rappel n'est enregistré que
 * tant que [state] a des abonnés (puis 5 s de délai) ; chaque changement de capacités, de
 * blocage ou de l'économiseur de données relit l'état sur le dispatcher d'E/S.
 */
@Singleton
class NetworkStatus @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    private val connectivity: ConnectivityManager by lazy {
        context.getSystemService(ConnectivityManager::class.java)
    }

    /** Capacités du réseau par défaut (null : aucun réseau par défaut). */
    private class Snapshot(val capabilities: NetworkCapabilities?)

    val state: StateFlow<NetworkState> = callbackFlow {
        val cm = connectivity
        // Etat initial : le rappel ne se manifeste pas s'il n'y a aucun réseau par défaut.
        // Rappels réseau et récepteur s'exécutent sur des threads différents.
        val latest = AtomicReference(cm.activeNetwork?.let(cm::getNetworkCapabilities))
        trySend(Snapshot(latest.get()))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                latest.set(networkCapabilities)
                trySend(Snapshot(networkCapabilities))
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                // Peut accompagner un changement de l'économiseur de données : nouvelle lecture.
                trySend(Snapshot(latest.get()))
            }

            override fun onLost(network: Network) {
                latest.set(null)
                trySend(Snapshot(null))
            }
        }
        // Economiseur de données activé/désactivé : diffusion système, reçue uniquement par un
        // récepteur enregistré dynamiquement.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Snapshot(latest.get()))
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        cm.registerDefaultNetworkCallback(callback)
        awaitClose {
            cm.unregisterNetworkCallback(callback)
            context.unregisterReceiver(receiver)
        }
    }
        .conflate()
        .map { snapshot -> stateOf(snapshot.capabilities) }
        .distinctUntilChanged()
        .flowOn(io)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NetworkState.UNKNOWN)

    /** Etat courant, lu directement (sans attendre d'abonné). */
    suspend fun current(): NetworkState = withContext(io) {
        val cm = connectivity
        stateOf(cm.activeNetwork?.let(cm::getNetworkCapabilities))
    }

    /**
     * Le réseau [network] (celui qu'a choisi JobScheduler pour un travail) n'est pas facturé
     * (NET_CAPABILITY_NOT_METERED). Faux s'il a disparu.
     */
    suspend fun isUnmetered(network: Network): Boolean = withContext(io) {
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun stateOf(capabilities: NetworkCapabilities?): NetworkState = networkStateOf(
        hasNetwork = capabilities != null,
        internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        notMetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
        restrictBackgroundStatus = connectivity.restrictBackgroundStatus,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
