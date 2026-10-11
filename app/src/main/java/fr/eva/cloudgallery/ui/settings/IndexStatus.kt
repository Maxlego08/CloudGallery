package fr.eva.cloudgallery.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters

/** Texte d'état de l'indexation locale (partagé par Menu et Paramètres). */
@Composable
fun indexStatusText(state: IndexState): String = when {
    state.running -> stringResource(R.string.settings_index_running)
    state.lastError != null -> stringResource(R.string.settings_index_error, state.lastError)
    state.lastSyncAt > 0L -> stringResource(
        R.string.settings_index_last,
        Formatters.fullDateTime(state.lastSyncAt),
        pluralStringResource(R.plurals.common_items_count, state.indexedCount, state.indexedCount),
    )
    else -> stringResource(R.string.settings_index_never)
}

/** Petit indicateur de progression affiché en fin de ligne pendant l'indexation. */
@Composable
fun IndexRunningIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(20.dp),
        color = OneUiTheme.colors.accent,
        strokeWidth = 2.dp,
    )
}
