package fr.eva.cloudgallery.data.cloud.precache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.Formatter
import fr.eva.cloudgallery.MainActivity
import fr.eva.cloudgallery.R

/**
 * Notification du service de premier plan de la mise en cache de tout OneDrive (progression et
 * bouton « Annuler »). Canal de faible importance, sans son. Aucun nom de fichier ni de compte.
 * Sans l'autorisation d'afficher des notifications, le système la montre au moins dans le
 * Gestionnaire de tâches.
 */
internal object PrecacheNotifications {

    const val CHANNEL_ID = "onedrive_precache"
    const val PROGRESS_ID = 4101

    /** Crée (ou renomme, après un changement de langue) le canal. Appel Binder court. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.precache_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.precache_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Progression : [done] éléments sur [total] (0 : préparation), [bytes] téléchargés. */
    fun progress(context: Context, done: Int, total: Int, bytes: Long, cancel: PendingIntent): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.precache_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    context.getString(R.string.common_cancel),
                    cancel,
                ).build(),
            )
        if (total <= 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, precachePercent(done, total), false)
                .setContentText(
                    context.getString(
                        R.string.precache_progress,
                        done.coerceAtMost(total),
                        total,
                        Formatter.formatShortFileSize(context, bytes),
                    ),
                )
        }
        return builder.build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
