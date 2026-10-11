package fr.eva.cloudgallery.ui.account

import android.app.Activity
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
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
import fr.eva.cloudgallery.ui.settings.IndexRunningIndicator
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters

/**
 * Ecran « Compte OneDrive » : connexion (accès en lecture seule), reconnexion après
 * expiration, profil et quota, synchronisation, affichage dans Photos, déconnexion avec
 * confirmation (les données OneDrive sont retirées de l'index, jamais les fichiers), retrait des
 * données restées d'une session précédente ou d'un autre compte.
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
    // Sans activité hôte, la connexion interactive est impossible : les boutons sont masqués.
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

    // Compte éventuellement persisté par MSAL pendant que l'appli était en arrière-plan.
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
                )
            }
        }
    }

    if (showSignOutDialog) {
        OneUiDialog(
            title = stringResource(R.string.account_sign_out_title),
            message = stringResource(R.string.account_sign_out_message),
            onDismiss = { showSignOutDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.account_sign_out),
            destructive = true,
            onConfirm = {
                showSignOutDialog = false
                viewModel.confirmSignOut()
            },
        )
    }
    if (showReplaceDialog) {
        OneUiDialog(
            title = stringResource(R.string.account_mismatch_dialog_title),
            message = stringResource(R.string.account_mismatch_dialog_message),
            onDismiss = { showReplaceDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.account_mismatch_confirm),
            destructive = true,
            onConfirm = {
                showReplaceDialog = false
                viewModel.confirmReplaceData()
            },
        )
    }
    if (showPurgeDialog) {
        OneUiDialog(
            title = stringResource(R.string.account_purge_title),
            message = stringResource(R.string.account_purge_message),
            onDismiss = { showPurgeDialog = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmLabel = stringResource(R.string.common_delete),
            destructive = true,
            onConfirm = {
                showPurgeDialog = false
                viewModel.confirmPurgeLeftover()
            },
        )
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
) {
    Column(Modifier.fillMaxWidth()) {
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
                }
            }
        }
    }
}

/** Synchronisation : état, « Synchroniser maintenant » et affichage dans Photos. */
@Composable
private fun SyncSection(
    sync: CloudSyncState,
    busy: Boolean,
    showCloudInPhotos: Boolean?,
    onSyncNow: () -> Unit,
    onShowCloudInPhotos: (Boolean) -> Unit,
) {
    // Limitation Microsoft Graph : aucune requête avant retryNotBefore, l'action redevient active ensuite.
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
            // Pas de bouton inopérant : inactif pendant une synchro, une purge, une limitation ou un changement de compte.
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
 * Compte connecté (ou en attente de reconnexion) : identité, quota, accès en lecture seule,
 * sections supplémentaires ([extra]) puis déconnexion.
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
 * Carte d'explication avec une action principale. [onAction] null (pas d'activité hôte) :
 * le bouton n'est pas affiché plutôt que de ne rien faire.
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
