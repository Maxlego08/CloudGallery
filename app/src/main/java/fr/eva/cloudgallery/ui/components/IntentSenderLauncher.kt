package fr.eva.cloudgallery.ui.components

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Lance un [IntentSender] système (confirmation de suppression / d'écriture MediaStore…)
 * et rapporte `granted = resultCode == RESULT_OK`.
 */
@Composable
fun rememberIntentSenderLauncher(onResult: (granted: Boolean) -> Unit): (IntentSender) -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        currentOnResult(result.resultCode == Activity.RESULT_OK)
    }
    return remember(launcher) {
        { sender: IntentSender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
    }
}
