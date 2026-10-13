package fr.eva.cloudgallery.data.cloud.stream

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Journal de Media3 sans URL : messages et traces d'exception (causes comprises) sont expurgés
 * de toute URL avant d'atteindre logcat. Une URL de téléchargement OneDrive (pré-authentifiée)
 * n'y apparaît ainsi jamais, quelle que soit l'erreur journalisée par Media3.
 */
@OptIn(UnstableApi::class)
object RedactingMedia3Logger : Log.Logger {

    /** Remplace le journal par défaut de Media3 (une fois, au démarrage du processus). */
    fun install() {
        Log.setLogger(this)
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.d(tag, format(message, throwable))
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.i(tag, format(message, throwable))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.w(tag, format(message, throwable))
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.e(tag, format(message, throwable))
    }

    private fun format(message: String, throwable: Throwable?): String =
        redactUrls(Log.appendThrowableString(message, throwable))
}
