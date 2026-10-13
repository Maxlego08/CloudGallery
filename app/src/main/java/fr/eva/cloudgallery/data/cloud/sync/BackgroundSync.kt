package fr.eva.cloudgallery.data.cloud.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background OneDrive sync pass (WorkManager), coalesced with other requests and skipped if
 * the last sync is less than 5 min old. Failures (network, Graph throttling) are recorded by
 * the sync itself: the work always succeeds and resumes at the next period. New versions of
 * offline copies found here are downloaded the next time the app returns to the foreground
 * ("user-initiated" job, only schedulable while the app is visible).
 */
@HiltWorker
class OneDriveSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: CloudMediaSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            syncer.syncAndWait(CloudSyncReason.BACKGROUND)
        } catch (e: CancellationException) {
            // Work stopped by WorkManager: cancellation propagated. Otherwise, pass interrupted by
            // a sign-out or a purge: nothing to redo.
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            Log.w(TAG, "Background OneDrive sync failed", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "OneDriveSyncWorker"
    }
}

/**
 * Periodic OneDrive sync work, kept up to date with the setting (frequency, Wi-Fi only) and the
 * account state ([backgroundSyncPlan]): scheduled for a signed-in account, cancelled on
 * sign-out or when the setting is disabled. First pass after a full period (returning to the
 * foreground already syncs). Low battery: wait.
 */
@Singleton
class BackgroundSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val feature: CloudFeature,
    private val settings: SettingsRepository,
    private val auth: dagger.Lazy<MsalAuthService>,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val started = AtomicBoolean(false)

    /** Tracks the setting and the account for the lifetime of the process (Application.onCreate). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            val prefs = settings.settings
                .map { it.backgroundSyncInterval to it.backgroundSyncWifiOnly }
                .distinctUntilChanged()
            combine(prefs, auth.get().state) { (interval, wifiOnly), account ->
                backgroundSyncPlan(feature.enabled, interval, wifiOnly, account)
            }
                .distinctUntilChanged()
                .collect { plan ->
                    try {
                        apply(plan)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to schedule background sync", e)
                    }
                }
        }
    }

    private fun apply(plan: BackgroundSyncPlan) {
        val work = WorkManager.getInstance(context)
        when (plan) {
            BackgroundSyncPlan.Unchanged -> Unit
            BackgroundSyncPlan.Cancel -> work.cancelUniqueWork(WORK_NAME)
            is BackgroundSyncPlan.Schedule -> {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (plan.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
                val request = PeriodicWorkRequestBuilder<OneDriveSyncWorker>(plan.intervalHours, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInitialDelay(plan.intervalHours, TimeUnit.HOURS)
                    .build()
                // UPDATE: changed frequency or network taken into account, running pass not interrupted.
                work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }
    }

    private companion object {
        const val TAG = "BackgroundSync"
        const val WORK_NAME = "onedrive_background_sync"
    }
}
