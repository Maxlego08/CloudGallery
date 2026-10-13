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
 * State of the default network ([NetworkState]), observed through a single callback for the
 * whole app (the system limits the number of network callbacks per app). The callback is only
 * registered while [state] has subscribers (plus a 5 s delay); every change of capabilities,
 * blocked status or Data Saver re-reads the state on the I/O dispatcher.
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

    /** Capabilities of the default network (null: no default network). */
    private class Snapshot(val capabilities: NetworkCapabilities?)

    val state: StateFlow<NetworkState> = callbackFlow {
        val cm = connectivity
        // Initial state: the callback does not fire if there is no default network.
        // Network callbacks and the receiver run on different threads.
        val latest = AtomicReference(cm.activeNetwork?.let(cm::getNetworkCapabilities))
        trySend(Snapshot(latest.get()))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                latest.set(networkCapabilities)
                trySend(Snapshot(networkCapabilities))
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                // May accompany a Data Saver change: read again.
                trySend(Snapshot(latest.get()))
            }

            override fun onLost(network: Network) {
                latest.set(null)
                trySend(Snapshot(null))
            }
        }
        // Data Saver turned on/off: system broadcast, only received by a dynamically
        // registered receiver.
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

    /** Current state, read directly (without waiting for a subscriber). */
    suspend fun current(): NetworkState = withContext(io) {
        val cm = connectivity
        stateOf(cm.activeNetwork?.let(cm::getNetworkCapabilities))
    }

    /**
     * The [network] (the one JobScheduler picked for a job) is not metered
     * (NET_CAPABILITY_NOT_METERED). False if it has gone away.
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
