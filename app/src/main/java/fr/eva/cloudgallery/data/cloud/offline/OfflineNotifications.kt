package fr.eva.cloudgallery.data.cloud.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.Formatter
import android.util.Log
import fr.eva.cloudgallery.MainActivity
import fr.eva.cloudgallery.R

/**
 * Notifications des copies hors ligne : progression du travail (obligatoire pour une tâche
 * lancée par l'utilisateur, avec un bouton « Annuler ») et bilan en fin de passage. Canal de
 * faible importance (aucun son). Aucun nom de fichier n'est journalisé.
 *
 * Les mises à jour et le bilan ne sont publiés que si les notifications sont autorisées ; la
 * notification exigée par JobScheduler (setNotification) est fournie dans tous les cas (le
 * système la montre au moins dans le Gestionnaire de tâches).
 */
internal object OfflineNotifications {

    const val CHANNEL_ID = "offline_downloads"
    const val PROGRESS_ID = 4001
    private const val SUMMARY_ID = 4002
    private const val TAG = "OfflineNotifications"

    /** Crée (ou renomme, après un changement de langue) le canal. Appel Binder court. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.offline_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.offline_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Notification de progression ([progress] null : démarrage, avant le premier relevé). */
    fun progress(context: Context, progress: OfflineProgress?): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    context.getString(R.string.offline_notification_cancel),
                    OfflineActionReceiver.cancelAllIntent(context),
                ).build(),
            )
        if (progress == null) {
            builder.setContentTitle(context.getString(R.string.offline_notification_preparing))
                .setProgress(0, 0, true)
        } else {
            val title = context.resources.getQuantityString(
                R.plurals.offline_notification_title,
                progress.remaining,
                progress.remaining,
            )
            builder.setContentTitle(title)
            if (progress.itemTotal > 0) {
                val percent = (progress.itemDone.coerceIn(0, progress.itemTotal) * 100 / progress.itemTotal).toInt()
                builder.setProgress(100, percent, false)
                    .setContentText(
                        context.getString(
                            R.string.offline_notification_progress,
                            progress.displayName,
                            Formatter.formatShortFileSize(context, progress.itemDone),
                            Formatter.formatShortFileSize(context, progress.itemTotal),
                        ),
                    )
            } else {
                builder.setProgress(0, 0, true).setContentText(progress.displayName)
            }
        }
        return builder.build()
    }

    /** Met à jour la progression affichée (si les notifications sont autorisées). */
    fun update(context: Context, progress: OfflineProgress) {
        notifyIfAllowed(context, PROGRESS_ID, progress(context, progress))
    }

    /** Bilan d'un passage : copies terminées et échecs (rien si aucun des deux). */
    fun showSummary(context: Context, summary: DrainSummary) {
        if (summary.completed == 0 && summary.failed == 0) return
        val title = if (summary.failed > 0) {
            context.resources.getQuantityString(R.plurals.offline_notification_failed, summary.failed, summary.failed)
        } else {
            context.resources.getQuantityString(R.plurals.offline_notification_done, summary.completed, summary.completed)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (summary.failed > 0) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .apply {
                if (summary.failed > 0 && summary.completed > 0) {
                    setContentText(
                        context.resources.getQuantityString(R.plurals.offline_notification_done, summary.completed, summary.completed),
                    )
                }
            }
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notifyIfAllowed(context, SUMMARY_ID, notification)
    }

    private fun notifyIfAllowed(context: Context, id: Int, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification refusée", e)
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
