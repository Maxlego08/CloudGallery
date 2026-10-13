package fr.eva.cloudgallery.ui.main

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.local.MediaAccessState
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.MediaAccess
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface MainEvent {
    /**
     * Open the viewer on an indexed media item resolved from a VIEW intent. [exitOnBack]: back
     * returns control to the calling app.
     */
    data class OpenViewer(val mediaId: Long, val exitOnBack: Boolean) : MainEvent

    /**
     * Non-indexed media (FileProvider URI from another app, photo picker, partial access…):
     * read-only display directly from the URI granted by the caller.
     */
    data class OpenExternal(
        val uri: Uri,
        val mimeType: String?,
        val title: String?,
        val isVideo: Boolean,
        val exitOnBack: Boolean,
    ) : MainEvent

    /** The received URI is neither a readable image nor a readable video. [finish]: return control to the caller. */
    data class CannotOpen(val finish: Boolean) : MainEvent
}

/** Activity ViewModel: settings (theme), VIEW intents, starting indexing. */
@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
    private val syncCoordinator: MediaSyncCoordinator,
    private val mediaAccess: MediaAccessState,
    cloudAccounts: CloudAccountRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** OneDrive exists in this build: "Use OneDrive only" is then offered. */
    val cloudAvailable: Boolean = cloudAccounts.isAvailable

    /** Saved "OneDrive only" choice, null until the settings are read. */
    val oneDriveOnlyChosen: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.oneDriveOnlyChosen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** OneDrive account signed in (or needing re-sign-in): otherwise the Photos tab prompts to sign in. */
    val cloudSignedIn: StateFlow<Boolean> = cloudAccounts.state
        .map { it is CloudAccountState.SignedIn || it is CloudAccountState.NeedsReauth }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events: Flow<MainEvent> = _events.receiveAsFlow()

    private var lastAccess: MediaAccess? = null

    init {
        // Account state read at startup (Photos in "OneDrive only" mode depends on it).
        val current = cloudAccounts.state.value
        if (cloudAvailable && (current is CloudAccountState.Loading || current is CloudAccountState.Error)) {
            viewModelScope.launch { cloudAccounts.refresh() }
        }
    }

    /** Called on every access change detected by the UI (including the first display). */
    fun onMediaAccessChanged(access: MediaAccess) {
        mediaAccess.update(access)
        if (access == lastAccess) return
        lastAccess = access
        if (access != MediaAccess.NONE) {
            syncCoordinator.onAccessChanged()
            // Access granted: the "OneDrive only" choice is forgotten (a later revocation asks again).
            viewModelScope.launch {
                if (settingsRepository.settings.first().oneDriveOnlyChosen) settingsRepository.setOneDriveOnlyChosen(false)
            }
        }
    }

    /** "Use OneDrive only" on the permission screen. */
    fun chooseOneDriveOnly() {
        if (!cloudAvailable) return
        viewModelScope.launch { settingsRepository.setOneDriveOnlyChosen(true) }
    }

    /** Back from the permission request with access granted (e.g. new partial selection). */
    fun onMediaAccessRegranted() {
        syncCoordinator.onAccessChanged()
    }

    /**
     * Resolves a content:// URI received through a VIEW intent, off the main thread: indexed media
     * if possible (full viewer), otherwise direct display of the granted URI.
     */
    fun handleViewIntent(uri: Uri, intentType: String?, exitOnBack: Boolean) {
        viewModelScope.launch {
            val id = withContext(io) {
                try {
                    mediaRepository.findByContentUri(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Unresolved URI: $uri", e)
                    null
                }
            }
            if (id != null) {
                _events.send(MainEvent.OpenViewer(id, exitOnBack))
                return@launch
            }
            val event = withContext(io) { externalEvent(uri, intentType, exitOnBack) }
            _events.send(event)
        }
    }

    private fun externalEvent(uri: Uri, intentType: String?, exitOnBack: Boolean): MainEvent {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return MainEvent.CannotOpen(exitOnBack)
        val resolver = appContext.contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?.takeIf { it.startsWith("image/") || it.startsWith("video/") }
            ?: intentType
        val isVideo = mime?.startsWith("video/") == true
        if (!isVideo && mime?.startsWith("image/") != true) return MainEvent.CannotOpen(exitOnBack)
        val title = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.onFailure { Log.w(TAG, "Unreadable file name: $uri", it) }.getOrNull()
        return MainEvent.OpenExternal(uri, mime, title, isVideo, exitOnBack)
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
