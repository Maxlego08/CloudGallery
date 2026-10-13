package fr.eva.cloudgallery.ui.securefolder

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.util.secureFolderShareIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Move to Secure Folder in progress: phone files sent and current step. */
@Immutable
data class SecureFolderPrompt(
    val step: SecureFolderStep,
    val uris: List<Uri>,
    val mimeTypes: List<String?>,
    /** Files also on OneDrive: the OneDrive copy stays visible in the app. */
    val alsoOnOneDrive: Int,
    /** OneDrive-only items of the selection, not sent. */
    val skippedCloudOnly: Int,
)

/** State of the move, held by the screen's ViewModel (grid or viewer). */
class SecureFolderMoveState {
    private val _prompt = MutableStateFlow<SecureFolderPrompt?>(null)
    val prompt: StateFlow<SecureFolderPrompt?> = _prompt.asStateFlow()

    /** Prepares sending the phone files of a ready [plan], in the order of its ids. */
    internal fun start(plan: SecureFolderPlan.Ready, uriOf: (Long) -> Uri?, mimeTypeOf: (Long) -> String?) {
        val ids = plan.ids.filter { uriOf(it) != null }
        if (ids.isEmpty()) return
        _prompt.value = SecureFolderPrompt(
            step = SecureFolderStep.INTRO,
            uris = ids.map { requireNotNull(uriOf(it)) },
            mimeTypes = ids.map(mimeTypeOf),
            alsoOnOneDrive = plan.alsoOnOneDrive,
            skippedCloudOnly = plan.skippedCloudOnly,
        )
    }

    fun onShareLaunched() {
        _prompt.update { it?.copy(step = SecureFolderStep.SHARING) }
    }

    fun onShareReturned() {
        _prompt.update { p -> p?.let { stepAfterShareReturn(it.step) }?.let { p.copy(step = it) } }
    }

    /** Finishes the move and returns the files to move to the trash (confirmation received). */
    fun takeForTrash(): SecureFolderPrompt? =
        _prompt.value?.takeIf { it.step == SecureFolderStep.CONFIRM_TRASH }.also { _prompt.value = null }

    fun dismiss() {
        _prompt.value = null
    }
}

/**
 * Dialogs for moving to Secure Folder: explanations then the share sheet
 * ([SecureFolderStep.INTRO] step), then on returning to the app, the "added successfully?" question
 * ([SecureFolderStep.CONFIRM_TRASH]). The originals only go to the trash on [onTrash], and the
 * system trash request is still confirmed by the user.
 *
 * @param onCancel abort before sending (selection kept).
 * @param onKeep originals kept after sending.
 */
@Composable
fun SecureFolderDialogs(
    prompt: SecureFolderPrompt?,
    onShareLaunched: () -> Unit,
    onShareReturned: () -> Unit,
    onTrash: () -> Unit,
    onCancel: () -> Unit,
    onKeep: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnShareReturned by rememberUpdatedState(onShareReturned)
    // Result code with no reliable meaning (the target may be in another profile): only
    // the return to the app matters, and the user says whether the addition succeeded.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        currentOnShareReturned()
    }
    prompt ?: return
    val count = prompt.uris.size
    val oneDriveNotice = if (prompt.alsoOnOneDrive > 0) {
        pluralStringResource(R.plurals.secure_folder_onedrive_notice, prompt.alsoOnOneDrive, prompt.alsoOnOneDrive)
    } else {
        null
    }
    when (prompt.step) {
        SecureFolderStep.INTRO -> {
            val skipped = if (prompt.skippedCloudOnly > 0) {
                pluralStringResource(R.plurals.grid_cloud_items_skipped, prompt.skippedCloudOnly, prompt.skippedCloudOnly)
            } else {
                null
            }
            OneUiDialog(
                title = pluralStringResource(R.plurals.secure_folder_intro_title, count, count),
                message = listOfNotNull(stringResource(R.string.secure_folder_intro_steps), oneDriveNotice, skipped)
                    .joinToString("\n\n"),
                confirmLabel = stringResource(R.string.secure_folder_continue),
                dismissLabel = stringResource(R.string.common_cancel),
                onConfirm = {
                    try {
                        launcher.launch(
                            secureFolderShareIntent(context, prompt.uris, prompt.mimeTypes, R.string.secure_folder_chooser_title),
                        )
                        onShareLaunched()
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show()
                        onCancel()
                    }
                },
                onDismiss = onCancel,
            )
        }
        SecureFolderStep.SHARING -> Unit
        SecureFolderStep.CONFIRM_TRASH -> OneUiDialog(
            title = pluralStringResource(R.plurals.secure_folder_confirm_title, count, count),
            message = listOfNotNull(
                pluralStringResource(R.plurals.secure_folder_confirm_message, count, count),
                oneDriveNotice,
            ).joinToString("\n\n"),
            confirmLabel = stringResource(R.string.secure_folder_trash_originals),
            dismissLabel = stringResource(R.string.secure_folder_keep_originals),
            destructive = true,
            onConfirm = onTrash,
            onDismiss = onKeep,
        )
    }
}
