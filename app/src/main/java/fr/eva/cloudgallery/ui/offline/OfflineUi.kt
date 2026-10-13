package fr.eva.cloudgallery.ui.offline

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.OfflineItemState
import fr.eva.cloudgallery.domain.model.OfflineStatus
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.util.downloadErrorMessage

/**
 * Dialogs for offline actions: large batch to confirm, waiting for Wi-Fi
 * ("Download now"), insufficient space (system "Free up space" screen),
 * last copy to release (destructive confirmation).
 */
@Composable
fun OfflinePromptDialog(
    prompt: OfflinePrompt,
    onConfirmLargeBatch: (List<Long>) -> Unit,
    onDownloadNow: () -> Unit,
    onConfirmRelease: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    when (prompt) {
        is OfflinePrompt.ConfirmLargeBatch -> OneUiDialog(
            title = pluralStringResource(R.plurals.offline_large_title, prompt.count, prompt.count),
            message = stringResource(
                R.string.offline_large_message,
                Formatter.formatShortFileSize(context, prompt.bytes),
                Formatter.formatShortFileSize(context, prompt.freeBytes),
            ),
            confirmLabel = stringResource(R.string.offline_large_confirm),
            dismissLabel = stringResource(R.string.common_cancel),
            onConfirm = {
                onDismiss()
                onConfirmLargeBatch(prompt.ids)
            },
            onDismiss = onDismiss,
        )
        is OfflinePrompt.WaitingForWifi -> OneUiDialog(
            title = stringResource(R.string.offline_wifi_title),
            message = pluralStringResource(
                R.plurals.offline_wifi_message,
                prompt.count,
                prompt.count,
                Formatter.formatShortFileSize(context, prompt.bytes),
            ),
            confirmLabel = stringResource(R.string.offline_download_now),
            dismissLabel = stringResource(R.string.offline_wait_wifi),
            onConfirm = {
                onDismiss()
                onDownloadNow()
            },
            onDismiss = onDismiss,
        )
        is OfflinePrompt.NoSpace -> OneUiDialog(
            title = stringResource(R.string.offline_no_space_title),
            message = stringResource(R.string.offline_no_space_message, Formatter.formatShortFileSize(context, prompt.missingBytes)),
            confirmLabel = stringResource(R.string.offline_no_space_manage),
            dismissLabel = stringResource(R.string.common_cancel),
            onConfirm = {
                onDismiss()
                // System storage management screen, with the volume and the requested size.
                val intent = Intent(StorageManager.ACTION_MANAGE_STORAGE).apply {
                    prompt.storageUuid?.let { putExtra(StorageManager.EXTRA_UUID, it) }
                    putExtra(StorageManager.EXTRA_REQUESTED_BYTES, prompt.missingBytes)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // No management screen on this device: the message stays displayed.
                }
            },
            onDismiss = onDismiss,
        )
        is OfflinePrompt.ConfirmRelease -> OneUiDialog(
            title = pluralStringResource(R.plurals.offline_release_last_title, prompt.lastCopies),
            message = pluralStringResource(R.plurals.offline_release_last_message, prompt.lastCopies, prompt.lastCopies),
            confirmLabel = stringResource(R.string.common_delete),
            dismissLabel = stringResource(R.string.common_cancel),
            destructive = true,
            onConfirm = {
                onDismiss()
                onConfirmRelease(prompt.ids)
            },
            onDismiss = onDismiss,
        )
    }
}

/**
 * Notification permission requested on the first "Keep offline" ([alreadyAsked]
 * false), then never again ([onAsked] records it). The action runs in every case,
 * granted or not: downloads work without notifications.
 */
@Composable
fun rememberNotificationPermissionGate(alreadyAsked: Boolean, onAsked: () -> Unit): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    val asked by rememberUpdatedState(alreadyAsked)
    val markAsked by rememberUpdatedState(onAsked)
    return remember(launcher) {
        { action ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted || asked) {
                action()
            } else {
                markAsked()
                pending = action
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: ActivityNotFoundException) {
                    pending = null
                    action()
                }
            }
        }
    }
}

/** Offline status line of an item (viewer, Details), null if there is nothing to say. */
@Composable
fun offlineStatusText(state: OfflineItemState, deletedOnOneDrive: Boolean, offlineBytes: Long): String? {
    val context = LocalContext.current
    return when (state.status) {
        OfflineStatus.NONE -> null
        OfflineStatus.QUEUED -> stringResource(
            if (state.waitingForUnmetered) R.string.offline_status_waiting_wifi else R.string.offline_status_queued,
        )
        OfflineStatus.DOWNLOADING -> if (state.bytesTotal > 0) {
            stringResource(
                R.string.offline_status_downloading,
                Formatter.formatShortFileSize(context, state.bytesDone),
                Formatter.formatShortFileSize(context, state.bytesTotal),
            )
        } else {
            stringResource(R.string.offline_status_downloading_unknown)
        }
        OfflineStatus.PAUSED -> stringResource(R.string.offline_status_paused)
        OfflineStatus.FAILED -> stringResource(
            R.string.offline_status_failed,
            stringResource(downloadErrorMessage(state.error ?: fr.eva.cloudgallery.domain.model.DownloadError.SERVER)),
        )
        OfflineStatus.AVAILABLE -> if (deletedOnOneDrive) {
            stringResource(R.string.offline_status_deleted_on_onedrive)
        } else {
            stringResource(R.string.offline_status_available, Formatter.formatShortFileSize(context, offlineBytes))
        }
        OfflineStatus.UPDATING -> stringResource(R.string.offline_status_updating)
        // Copy served, but the new version is not arriving: cause shown (can be resumed).
        OfflineStatus.UPDATE_STOPPED -> if (state.error == null) {
            stringResource(R.string.offline_status_update_paused)
        } else {
            stringResource(R.string.offline_status_update_failed, stringResource(downloadErrorMessage(state.error)))
        }
    }
}
