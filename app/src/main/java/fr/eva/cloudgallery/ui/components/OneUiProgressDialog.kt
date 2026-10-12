package fr.eva.cloudgallery.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Boîte de progression One UI avec un seul bouton d'annulation. [progress] : 0..1, ou null pour
 * une progression indéterminée. Retour et toucher hors de la boîte annulent aussi.
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
