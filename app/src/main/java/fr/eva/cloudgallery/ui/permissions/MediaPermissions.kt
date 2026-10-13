package fr.eva.cloudgallery.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fr.eva.cloudgallery.data.local.readMediaAccess
import fr.eva.cloudgallery.domain.model.MediaAccess

object MediaPermissions {
    /**
     * Requested together: Android then shows "Allow all / Select photos / Don't allow".
     * ACCESS_MEDIA_LOCATION (EXIF GPS) is granted without an extra dialog when access to
     * images is; a denial does not prevent the app from working.
     */
    val REQUEST = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

    fun currentAccess(context: Context): MediaAccess = readMediaAccess(context)

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Observable media permission state, re-checked on every ON_RESUME. */
@Stable
class MediaPermissionController internal constructor(
    private val context: Context,
) {
    var access: MediaAccess by mutableStateOf(MediaPermissions.currentAccess(context))
        private set

    /**
     * True when the system no longer shows the request (permanent denial): the app settings
     * must then be used.
     */
    var permanentlyDenied: Boolean by mutableStateOf(false)
        private set

    /**
     * Incremented each time the system request returns with non-empty access: allows
     * reindexing even if the state stays PARTIAL (new photo selection).
     */
    var grantedResults: Int by mutableIntStateOf(0)
        private set

    internal var launch: (() -> Unit)? = null

    /** Permanent denial detected when the request returns: open the app settings then. */
    private var openSettingsIfDenied = false

    /** Launches the system request (or the photo picker in partial mode). */
    fun request() {
        openSettingsIfDenied = false
        launch?.invoke()
    }

    /**
     * System request, or the app settings if the system no longer shows it (permanent
     * denial, already known or detected when the request returns): "Allow" in the
     * "OneDrive only" banner thus never does nothing.
     */
    fun requestOrOpenSettings() {
        if (permanentlyDenied) {
            openAppSettings()
            return
        }
        openSettingsIfDenied = true
        launch?.invoke()
    }

    fun openAppSettings() {
        runCatching { context.startActivity(MediaPermissions.appSettingsIntent(context)) }
    }

    internal fun refresh() {
        access = MediaPermissions.currentAccess(context)
        if (access != MediaAccess.NONE) permanentlyDenied = false
    }

    internal fun onRequestResult() {
        refresh()
        if (access != MediaAccess.NONE) grantedResults++
        if (access == MediaAccess.NONE) {
            val activity = context.findActivity()
            // After a denial, the absence of a "rationale" means the system will not ask again.
            permanentlyDenied = activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)
            if (permanentlyDenied && openSettingsIfDenied) openAppSettings()
        }
        openSettingsIfDenied = false
    }
}

@Composable
fun rememberMediaPermissionController(): MediaPermissionController {
    val context = LocalContext.current
    val controller = remember(context) { MediaPermissionController(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.onRequestResult()
    }
    SideEffect { controller.launch = { launcher.launch(MediaPermissions.REQUEST) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.refresh() }
    return controller
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
