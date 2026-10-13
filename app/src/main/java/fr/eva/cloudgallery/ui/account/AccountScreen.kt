package fr.eva.cloudgallery.ui.account

import android.app.Activity
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.CloudAccount
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudProfile
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.DeletedOnOneDriveCopy
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.OfflineQueueState
import fr.eva.cloudgallery.domain.model.OfflineThumbnail
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiCard
import fr.eva.cloudgallery.ui.components.OneUiCategoryHeader
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiListItem
import fr.eva.cloudgallery.ui.components.OneUiPillButton
import fr.eva.cloudgallery.ui.components.OneUiSwitchItem
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.offline.PurgeImpactUi
import fr.eva.cloudgallery.ui.offline.PurgeNotice
import fr.eva.cloudgallery.ui.offline.purgeConfirmEnabled
import fr.eva.cloudgallery.ui.offline.purgeNotice
import fr.eva.cloudgallery.ui.settings.IndexRunningIndicator
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters

/**
 * "OneDrive account" screen: sign-in (read-only access), re-sign-in after
 * expiry, profile and quota, sync, display in Photos, sign-out with
 * confirmation (OneDrive data is removed from the index, never the files), removal of
 * data left over from a previous session or another account.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    // Without a host activity, interactive sign-in is impossible: the buttons are hidden.
    val activity = LocalActivity.current
    val listState = rememberLazyListState()
    val titleAlpha by rememberCollapsedTitleAlpha(listState)
    val colors = OneUiTheme.colors
    val title = stringResource(R.string.account_title)
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }
    var showPurgeDialog by rememberSaveable { mutableStateOf(false) }
    var showReplaceDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteCopiesDialog by rememberSaveable { mutableStateOf(false) }
    var showCancelOfflineDialog by rememberSaveable { mutableStateOf(false) }
    // Offline copies the purge would delete (including last copies), read when a confirmation
    // opens: confirm button disabled until they are known (D3).
    val purgeDialogOpen = showSignOutDialog || showPurgeDialog || showReplaceDialog
    val purgeImpact by produceState<PurgeImpactUi>(PurgeImpactUi.Loading, purgeDialogOpen) {
        value = PurgeImpactUi.Loading
        if (purgeDialogOpen) value = viewModel.purgeImpact()
    }
    val offlineNotice = offlinePurgeNotice(purgeImpact)
    val purgeConfirmable = purgeConfirmEnabled(purgeImpact)

    // Account possibly persisted by MSAL while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is AccountEvent.SignInFailed ->
                    resources.getString(R.string.account_sign_in_failed, resources.getString(event.code.messageRes()))
                is AccountEvent.SignOutFailed ->
                    resources.getString(R.string.account_sign_out_failed, resources.getString(event.code.messageRes()))
                AccountEvent.SignedOut -> resources.getString(R.string.account_signed_out_toast)
                AccountEvent.PurgeDone -> resources.getString(R.string.account_purge_done)
                AccountEvent.PurgeFailed -> resources.getString(R.string.account_purge_failed)
                AccountEvent.ReplaceDone -> resources.getString(R.string.account_mismatch_done)
                AccountEvent.ReplaceFailed -> resources.getString(R.string.account_mismatch_failed)
                is AccountEvent.CopiesDeleted ->
                    resources.getQuantityString(R.plurals.offline_deleted_removed, event.count, event.count)
                is AccountEvent.CopiesKept ->
                    resources.getQuantityString(R.plurals.offline_deleted_kept, event.count, event.count)
                AccountEvent.OfflineActionFailed -> resources.getString(R.string.common_error_generic)
                AccountEvent.OfflineCancelled -> resources.getString(R.string.offline_cancelled)
                AccountEvent.OfflineResumed -> resources.getString(R.string.offline_resumed)
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        OneUiTopBar(
            title = title,
            titleAlpha = titleAlpha,
            navigationIcon = { OneUiBackButton(onClick = onBack) },
            containerColor = colors.groupedBackground,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalInsets.calculateStartPadding(layoutDirection) + 12.dp,
                end = horizontalInsets.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = bottomInset + 24.dp,
            ),
        ) {
            item(key = "header") {
                OneUiLargeTitleHeader(title = title, subtitle = null)
            }
            item(key = "content") {
                AccountContent(
                    state = uiState,
                    activity = activity,
                    onSignIn = viewModel::signIn,
                    onReauthenticate = viewModel::reauthenticate,
                    onSignOut = { showSignOutDialog = true },
                    onPurge = { showPurgeDialog = true },
                    onRetry = viewModel::refresh,
                    onSyncNow = viewModel::syncNow,
                    onShowCloudInPhotos = viewModel::setShowCloudInPhotos,
                    onReplace = { showReplaceDialog = true },
                    onDeleteCopies = { showDeleteCopiesDialog = true },
                    onKeepCopies = { viewModel.keepDeletedCopies(uiState.deletedCopies.map { it.mediaId }) },
                    onResumeOffline = viewModel::resumeOffline,
                    onDownloadOfflineNow = viewModel::downloadOfflineNow,
                    onCancelOffline = { showCancelOfflineDialog = true },
                )
            }
        }
    }

    if (showSignOutDialog) {
        OneUiDialog(
            title = stringResource(R.string.account_sign_out_title),
            message = stringResource(R.string.account_sign_out_message) + offlineNotice,
            onDismiss = { showSignOutDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.account_sign_out),
            destructive = true,
            confirmEnabled = purgeConfirmable,
            onConfirm = {
                showSignOutDialog = false
                viewModel.confirmSignOut()
            },
        )
    }
    if (showReplaceDialog) {
        OneUiDialog(
            title = stringResource(R.string.account_mismatch_dialog_title),
            message = stringResource(R.string.account_mismatch_dialog_message) + offlineNotice,
            onDismiss = { showReplaceDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.account_mismatch_confirm),
            destructive = true,
            confirmEnabled = purgeConfirmable,
            onConfirm = {
                showReplaceDialog = false
                viewModel.confirmReplaceData()
            },
        )
    }
    if (showPurgeDialog) {
        OneUiDialog(
            title = stringResource(R.string.account_purge_title),
            message = stringResource(R.string.account_purge_message) + offlineNotice,
            onDismiss = { showPurgeDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.common_delete),
            destructive = true,
            confirmEnabled = purgeConfirmable,
            onConfirm = {
                showPurgeDialog = false
                viewModel.confirmPurgeLeftover()
            },
        )
    }
    if (showDeleteCopiesDialog) {
        // Displayed list = confirmed list: only these items are processed.
        val confirmed = uiState.deletedCopies.map { it.mediaId }
        val count = confirmed.size
        OneUiDialog(
            title = pluralStringResource(R.plurals.offline_deleted_delete_title, count, count),
            message = stringResource(R.string.offline_deleted_delete_message),
            onDismiss = { showDeleteCopiesDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.common_delete),
            destructive = true,
            onConfirm = {
                showDeleteCopiesDialog = false
                viewModel.deleteDeletedCopies(confirmed)
            },
        )
    }
    if (showCancelOfflineDialog) {
        OneUiDialog(
            title = stringResource(R.string.offline_cancel_all_title),
            message = stringResource(R.string.offline_cancel_all_message),
            onDismiss = { showCancelOfflineDialog = false },
            dismissLabel = stringResource(R.string.common_close),
            confirmLabel = stringResource(R.string.offline_cancel_all),
            destructive = true,
            onConfirm = {
                showCancelOfflineDialog = false
                viewModel.cancelOffline()
            },
        )
    }
}

/**
 * Addendum to the purge confirmations (sign-out, leftover data, other account): offline
 * copies deleted from the device and, among them, last copies of items deleted from
 * OneDrive (decision D3). Empty without offline copies; cautious warning if the count could
 * not be read.
 */
@Composable
private fun offlinePurgeNotice(impact: PurgeImpactUi): String = when (val notice = purgeNotice(impact)) {
    PurgeNotice.None -> ""
    PurgeNotice.Unknown -> "\n\n" + stringResource(R.string.account_offline_purge_unknown)
    is PurgeNotice.Copies -> {
        val copies = pluralStringResource(R.plurals.account_offline_purge_notice, notice.copies, notice.copies)
        val last = if (notice.lastCopies > 0) {
            "\n\n" + pluralStringResource(R.plurals.account_offline_last_copies_notice, notice.lastCopies, notice.lastCopies)
        } else {
            ""
        }
        "\n\n$copies$last"
    }
}

@Composable
private fun AccountContent(
    state: AccountUiState,
    activity: Activity?,
    onSignIn: (Activity) -> Unit,
    onReauthenticate: (Activity) -> Unit,
    onSignOut: () -> Unit,
    onPurge: () -> Unit,
    onRetry: () -> Unit,
    onSyncNow: () -> Unit,
    onShowCloudInPhotos: (Boolean) -> Unit,
    onReplace: () -> Unit,
    onDeleteCopies: () -> Unit,
    onKeepCopies: () -> Unit,
    onResumeOffline: () -> Unit,
    onDownloadOfflineNow: () -> Unit,
    onCancelOffline: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Decision D1: offline copies of items deleted from OneDrive more than 30 days ago.
        if (state.deletedCopies.isNotEmpty() && state.account != CloudAccountState.NotConfigured) {
            DeletedCopiesCard(
                copies = state.deletedCopies,
                busy = state.decidingCopies || state.purging,
                onDelete = onDeleteCopies,
                onKeep = onKeepCopies,
            )
            Spacer(Modifier.height(16.dp))
        }
        when (val account = state.account) {
            CloudAccountState.NotConfigured -> OneUiCard {
                OneUiListItem(title = stringResource(R.string.account_not_configured))
            }
            CloudAccountState.Loading -> OneUiCard {
                OneUiListItem(title = stringResource(R.string.common_loading), trailing = RunningTrailing)
            }
            CloudAccountState.SigningOut -> OneUiCard {
                OneUiListItem(title = stringResource(R.string.account_signing_out), trailing = RunningTrailing)
            }
            is CloudAccountState.Error -> InfoCard(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.account_error_title),
                message = stringResource(account.code.messageRes()),
                actionLabel = stringResource(R.string.common_retry),
                onAction = onRetry,
            )
            is CloudAccountState.SignedOut -> {
                InfoCard(
                    icon = Icons.Rounded.Cloud,
                    title = stringResource(R.string.account_intro_title),
                    message = stringResource(R.string.account_intro_message) + "\n" +
                        stringResource(R.string.account_personal_only),
                    actionLabel = stringResource(
                        if (state.signInRunning) R.string.account_signing_in else R.string.account_sign_in,
                    ),
                    actionEnabled = !state.signInRunning && !state.purging,
                    onAction = activity?.let { host -> { onSignIn(host) } },
                )
                if (account.hasLeftoverData) {
                    OneUiCategoryHeader(stringResource(R.string.account_section_device_data))
                    OneUiCard {
                        OneUiListItem(
                            title = stringResource(R.string.account_leftover_purge),
                            subtitle = stringResource(R.string.account_leftover_purge_summary),
                            onClick = if (state.purging || state.signInRunning) null else onPurge,
                            trailing = if (state.purging) RunningTrailing else null,
                            destructive = true,
                        )
                    }
                }
            }
            is CloudAccountState.NeedsReauth -> {
                InfoCard(
                    icon = Icons.Rounded.Lock,
                    title = stringResource(R.string.account_reauth_title),
                    message = stringResource(R.string.account_reauth_message),
                    actionLabel = stringResource(
                        if (state.signInRunning) R.string.account_signing_in else R.string.account_reauth_action,
                    ),
                    actionEnabled = !state.signInRunning,
                    onAction = activity?.let { host -> { onReauthenticate(host) } },
                )
                SignedInSections(
                    account = account.account,
                    profile = null,
                    signOutEnabled = !state.signInRunning,
                    onSignOut = onSignOut,
                )
            }
            is CloudAccountState.SignedIn -> {
                if (state.sync.accountMismatch) {
                    InfoCard(
                        icon = Icons.Rounded.SyncProblem,
                        title = stringResource(R.string.account_mismatch_title),
                        message = stringResource(R.string.account_mismatch_message),
                        actionLabel = stringResource(R.string.account_mismatch_action),
                        actionEnabled = !state.purging && !state.sync.running,
                        onAction = onReplace,
                    )
                }
                SignedInSections(
                    account = account.account,
                    profile = state.profile,
                    signOutEnabled = !state.signInRunning && !state.purging,
                    onSignOut = onSignOut,
                ) {
                    SyncSection(
                        sync = state.sync,
                        busy = state.purging,
                        showCloudInPhotos = state.showCloudInPhotos,
                        onSyncNow = onSyncNow,
                        onShowCloudInPhotos = onShowCloudInPhotos,
                    )
                    OfflineSection(
                        queue = state.offlineQueue,
                        onResume = onResumeOffline,
                        onDownloadNow = onDownloadOfflineNow,
                        onCancelAll = onCancelOffline,
                    )
                }
            }
        }
    }
}

/** Sync: status, "Sync now" and display in Photos. */
@Composable
private fun SyncSection(
    sync: CloudSyncState,
    busy: Boolean,
    showCloudInPhotos: Boolean?,
    onSyncNow: () -> Unit,
    onShowCloudInPhotos: (Boolean) -> Unit,
) {
    // Microsoft Graph throttling: no request before retryNotBefore, the action becomes active again afterwards.
    val throttled by produceState(sync.retryNotBefore > System.currentTimeMillis(), sync.retryNotBefore) {
        val wait = sync.retryNotBefore - System.currentTimeMillis()
        if (wait > 0) {
            value = true
            delay(wait)
        }
        value = false
    }
    OneUiCategoryHeader(stringResource(R.string.account_section_sync))
    OneUiCard {
        OneUiListItem(
            title = stringResource(R.string.account_sync_now),
            subtitle = cloudSyncStatusText(sync),
            icon = Icons.Rounded.Sync,
            // No dead button: disabled during a sync, a purge, throttling or an account change.
            onClick = if (sync.running || busy || throttled || sync.accountMismatch) null else onSyncNow,
            trailing = if (sync.running) RunningTrailing else null,
        )
        if (showCloudInPhotos != null) {
            OneUiDivider(startIndent = OneUiTheme.dimens.cardPadding)
            OneUiSwitchItem(
                title = stringResource(R.string.settings_show_cloud_in_photos),
                subtitle = stringResource(R.string.settings_show_cloud_in_photos_summary),
                checked = showCloudInPhotos,
                onCheckedChange = onShowCloudInPhotos,
            )
        }
    }
}

/**
 * Offline copies: queue status (in progress, waiting for Wi-Fi, paused, failures) and
 * existing copies, with "Download now", resume and cancel.
 */
@Composable
private fun OfflineSection(
    queue: OfflineQueueState,
    onResume: () -> Unit,
    onDownloadNow: () -> Unit,
    onCancelAll: () -> Unit,
) {
    val context = LocalContext.current
    val parts = buildList {
        if (queue.running > 0) add(pluralStringResource(R.plurals.offline_queue_running, queue.running, queue.running))
        if (queue.queued > 0) {
            val waiting = if (queue.waitingForUnmetered) {
                pluralStringResource(R.plurals.offline_queue_waiting_wifi, queue.queued, queue.queued)
            } else {
                pluralStringResource(R.plurals.offline_queue_queued, queue.queued, queue.queued)
            }
            // Size left to download (known sizes), useful before "Download now".
            add(if (queue.remainingBytes > 0) "$waiting · ${Formatter.formatShortFileSize(context, queue.remainingBytes)}" else waiting)
        }
        if (queue.paused > 0) add(pluralStringResource(R.plurals.offline_queue_paused, queue.paused, queue.paused))
        if (queue.failed > 0) add(pluralStringResource(R.plurals.offline_queue_failed, queue.failed, queue.failed))
        if (queue.availableCount > 0) {
            add(
                pluralStringResource(
                    R.plurals.offline_queue_available,
                    queue.availableCount,
                    queue.availableCount,
                    Formatter.formatShortFileSize(context, queue.availableBytes),
                ),
            )
        }
    }
    val indent = OneUiTheme.dimens.cardPadding
    OneUiCategoryHeader(stringResource(R.string.offline_section_title))
    OneUiCard {
        OneUiListItem(
            title = stringResource(R.string.offline_queue_title),
            subtitle = if (parts.isEmpty()) stringResource(R.string.offline_queue_empty) else parts.joinToString("\n"),
            icon = Icons.Rounded.OfflinePin,
            trailing = if (queue.running > 0) RunningTrailing else null,
        )
        if (queue.waitingForUnmetered) {
            OneUiDivider(startIndent = indent)
            OneUiListItem(title = stringResource(R.string.offline_download_now), onClick = onDownloadNow)
        }
        if (queue.paused + queue.failed > 0) {
            OneUiDivider(startIndent = indent)
            OneUiListItem(title = stringResource(R.string.offline_resume_all), onClick = onResume)
        }
        if (!queue.isEmpty) {
            OneUiDivider(startIndent = indent)
            OneUiListItem(title = stringResource(R.string.offline_cancel_all), onClick = onCancelAll, destructive = true)
        }
    }
}

/**
 * Offline copies of items deleted from OneDrive (grace period elapsed): previews taken from
 * the copies themselves, then "Delete copies" (confirmation) or "Keep them".
 */
@Composable
private fun DeletedCopiesCard(
    copies: List<DeletedOnOneDriveCopy>,
    busy: Boolean,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val colors = OneUiTheme.colors
    val dimens = OneUiTheme.dimens
    val shown = copies.take(MAX_DELETED_THUMBNAILS)
    OneUiCard {
        Column(Modifier.padding(horizontal = dimens.cardPadding, vertical = 20.dp)) {
            Icon(imageVector = Icons.Rounded.CloudOff, contentDescription = null, tint = colors.danger, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = pluralStringResource(R.plurals.offline_deleted_title, copies.size, copies.size),
                style = OneUiTheme.typography.sectionHeader,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(text = stringResource(R.string.offline_deleted_message), style = OneUiTheme.typography.body, color = colors.textSecondary)
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.mediaId }) { copy ->
                    ThumbnailImage(
                        uri = null,
                        isVideo = copy.type == MediaType.VIDEO,
                        version = 0L,
                        offline = OfflineThumbnail(copy.mediaId, "d${copy.sizeBytes}", copy.mimeType),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .semantics { contentDescription = copy.displayName },
                    )
                }
                if (copies.size > shown.size) {
                    item(key = "more") {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.placeholder),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.offline_deleted_more, copies.size - shown.size),
                                style = OneUiTheme.typography.listTitle,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OneUiPillButton(
                    text = stringResource(R.string.offline_deleted_delete),
                    onClick = onDelete,
                    filled = false,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                OneUiPillButton(
                    text = stringResource(R.string.offline_deleted_keep),
                    onClick = onKeep,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val MAX_DELETED_THUMBNAILS = 12

/**
 * Signed-in account (or awaiting re-sign-in): identity, quota, read-only access,
 * additional sections ([extra]) then sign-out.
 */
@Composable
private fun SignedInSections(
    account: CloudAccount,
    profile: CloudProfile?,
    signOutEnabled: Boolean,
    onSignOut: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val indent = OneUiTheme.dimens.cardPadding + 40.dp
    OneUiCategoryHeader(stringResource(R.string.account_section_account))
    OneUiCard {
        OneUiListItem(
            title = profile?.displayName ?: account.username ?: stringResource(R.string.account_signed_in),
            subtitle = profile?.email ?: stringResource(R.string.account_microsoft_personal),
            icon = Icons.Rounded.Cloud,
        )
        val used = profile?.quotaUsed
        val total = profile?.quotaTotal
        if (used != null && total != null) {
            OneUiDivider(startIndent = indent)
            OneUiListItem(
                title = stringResource(R.string.account_storage),
                subtitle = stringResource(R.string.account_quota, Formatters.fileSize(used), Formatters.fileSize(total)),
                icon = Icons.Rounded.Storage,
            )
        }
        OneUiDivider(startIndent = indent)
        OneUiListItem(
            title = stringResource(R.string.account_read_only),
            subtitle = stringResource(R.string.account_read_only_summary),
            icon = Icons.Rounded.Lock,
        )
    }
    extra()
    OneUiCard(Modifier.padding(top = 16.dp)) {
        OneUiListItem(
            title = stringResource(R.string.account_sign_out),
            onClick = if (signOutEnabled) onSignOut else null,
            destructive = true,
        )
    }
}

/**
 * Explanation card with a main action. [onAction] null (no host activity):
 * the button is not shown rather than doing nothing.
 */
@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String,
    onAction: (() -> Unit)?,
    actionEnabled: Boolean = true,
) {
    val colors = OneUiTheme.colors
    val dimens = OneUiTheme.dimens
    OneUiCard {
        Column(Modifier.padding(horizontal = dimens.cardPadding, vertical = 20.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(text = title, style = OneUiTheme.typography.sectionHeader, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(text = message, style = OneUiTheme.typography.body, color = colors.textSecondary)
            if (onAction != null) {
                Spacer(Modifier.height(20.dp))
                OneUiPillButton(
                    text = actionLabel,
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val RunningTrailing: @Composable () -> Unit = { IndexRunningIndicator() }
