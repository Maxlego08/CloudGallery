package fr.eva.cloudgallery.ui.account

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.ui.util.Formatters

/** Localised message for a cloud error (resolved at display time, never persisted). */
@StringRes
fun CloudErrorCode.messageRes(): Int = when (this) {
    CloudErrorCode.NETWORK -> R.string.data_cloud_error_network
    CloudErrorCode.AUTH_REQUIRED -> R.string.data_cloud_error_auth
    CloudErrorCode.THROTTLED -> R.string.data_cloud_error_throttled
    CloudErrorCode.SERVER -> R.string.data_cloud_error_server
    CloudErrorCode.CONFIG -> R.string.data_cloud_error_config
    CloudErrorCode.DECLINED -> R.string.data_cloud_error_declined
    CloudErrorCode.NO_BROWSER -> R.string.data_cloud_error_no_browser
    CloudErrorCode.UNKNOWN -> R.string.data_cloud_error_unknown
}

/** One-line summary of the OneDrive account state (Menu, Settings). */
@Composable
fun cloudAccountSummary(state: CloudAccountState): String = when (state) {
    CloudAccountState.NotConfigured -> stringResource(R.string.account_not_configured)
    CloudAccountState.Loading -> stringResource(R.string.common_loading)
    is CloudAccountState.SignedOut -> stringResource(R.string.account_status_signed_out)
    is CloudAccountState.SignedIn -> state.account.username ?: stringResource(R.string.account_signed_in)
    is CloudAccountState.NeedsReauth -> stringResource(R.string.account_status_reauth)
    CloudAccountState.SigningOut -> stringResource(R.string.account_status_signing_out)
    is CloudAccountState.Error -> stringResource(state.code.messageRes())
}

/** OneDrive sync status in one line. */
@Composable
fun cloudSyncStatusText(state: CloudSyncState): String = when {
    state.running -> pluralStringResource(R.plurals.account_sync_running, state.processedItems, state.processedItems)
    state.accountMismatch -> stringResource(R.string.account_sync_paused)
    state.lastError != null -> stringResource(R.string.account_sync_error, stringResource(state.lastError.messageRes()))
    state.lastSyncAt > 0L -> stringResource(
        R.string.account_sync_last,
        Formatters.fullDateTime(state.lastSyncAt),
        pluralStringResource(R.plurals.common_items_count, state.itemCount, state.itemCount),
    )
    else -> stringResource(R.string.account_sync_never)
}
