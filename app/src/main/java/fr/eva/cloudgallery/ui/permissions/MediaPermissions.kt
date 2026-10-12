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
     * Demandées ensemble : Android affiche alors « Autoriser tout / Sélectionner des photos / Refuser ».
     * ACCESS_MEDIA_LOCATION (GPS des EXIF) est accordée sans dialogue supplémentaire quand
     * l'accès aux images l'est ; un refus n'empêche pas l'appli de fonctionner.
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

/** Etat observable des autorisations médias, revérifié à chaque ON_RESUME. */
@Stable
class MediaPermissionController internal constructor(
    private val context: Context,
) {
    var access: MediaAccess by mutableStateOf(MediaPermissions.currentAccess(context))
        private set

    /**
     * Vrai quand le système ne montre plus la demande (refus définitif) : il faut alors
     * passer par les paramètres de l'appli.
     */
    var permanentlyDenied: Boolean by mutableStateOf(false)
        private set

    /**
     * Incrémenté à chaque retour de la demande système avec un accès non nul : permet de
     * réindexer même si l'état reste PARTIAL (nouvelle sélection de photos).
     */
    var grantedResults: Int by mutableIntStateOf(0)
        private set

    internal var launch: (() -> Unit)? = null

    /** Refus définitif constaté au retour de la demande : ouvrir alors les paramètres de l'appli. */
    private var openSettingsIfDenied = false

    /** Lance la demande système (ou le sélecteur de photos en mode partiel). */
    fun request() {
        openSettingsIfDenied = false
        launch?.invoke()
    }

    /**
     * Demande système, ou paramètres de l'appli si le système ne la montre plus (refus
     * définitif, déjà connu ou constaté au retour de la demande) : « Autoriser » du bandeau
     * « OneDrive seul » n'est ainsi jamais sans effet.
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
            // Après un refus, l'absence de « rationale » signifie que le système ne redemandera plus.
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
