package fr.eva.cloudgallery.data.cloud.stream

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * URL-free Media3 logger: messages and exception traces (causes included) are stripped of any
 * URL before reaching logcat. A (pre-authenticated) OneDrive download URL thus never shows up
 * there, whatever error Media3 logs.
 */
@OptIn(UnstableApi::class)
object RedactingMedia3Logger : Log.Logger {

    /** Replaces Media3's default logger (once, at process startup). */
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
