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
 * "Cancel" button of the progress notification (non-exported receiver). Goes through
 * [OfflineRepository.cancelAll]: stops the engine (files closed), then updates the database,
 * then deletes the partial files, off the main thread.
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
                Log.w(TAG, "Could not cancel offline copies", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "OfflineAction"
        private const val ACTION_CANCEL_ALL = "fr.eva.cloudgallery.action.OFFLINE_CANCEL_ALL"

        /** Intent of the "Cancel" button (explicit, immutable). */
        fun cancelAllIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, OfflineActionReceiver::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
