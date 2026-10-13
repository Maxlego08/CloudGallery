package fr.eva.cloudgallery.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One UI progress dialog with a single cancel button. [progress]: 0..1, or null for
 * indeterminate progress. Back and touching outside the dialog also cancel.
 */
@Composable
fun OneUiProgressDialog(
    title: String,
    message: String?,
    progress: Float?,
    cancelLabel: String,
    onCancel: () -> Unit,
) {
    OneUiDialog(
        title = title,
        message = message,
        confirmLabel = cancelLabel,
        onConfirm = onCancel,
        onDismiss = onCancel,
        content = {
            val modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier)
            } else {
                LinearProgressIndicator(modifier = modifier)
            }
        },
    )
}
