package fr.eva.cloudgallery.data.cloud.offline

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bouton « Annuler » de la notification de progression (récepteur non exporté). Passe par
 * [OfflineRepository.cancelAll] : arrêt du moteur (fichiers fermés) puis mise à jour de la base,
 * puis suppression des fichiers partiels, hors du thread principal.
 */
class OfflineActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun offlineRepository(): OfflineRepository

        @ApplicationScope
        fun applicationScope(): CoroutineScope

        @IoDispatcher
        fun io(): CoroutineDispatcher
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_ALL) return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val pending = goAsync()
        deps.applicationScope().launch(deps.io()) {
            try {
                deps.offlineRepository().cancelAll()
            } catch (e: Exception) {
                Log.w(TAG, "Annulation des copies hors ligne impossible", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "OfflineAction"
        private const val ACTION_CANCEL_ALL = "fr.eva.cloudgallery.action.OFFLINE_CANCEL_ALL"

        /** Intent du bouton « Annuler » (explicite, immuable). */
        fun cancelAllIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, OfflineActionReceiver::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
