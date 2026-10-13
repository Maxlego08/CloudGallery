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
     * Ouvrir la visionneuse sur un média indexé résolu depuis un intent VIEW. [exitOnBack] : le
     * retour rend la main à l'appli appelante.
     */
    data class OpenViewer(val mediaId: Long, val exitOnBack: Boolean) : MainEvent

    /**
     * Média non indexé (URI FileProvider d'une autre appli, sélecteur de photos, accès partiel…) :
     * affichage en lecture seule directement depuis l'URI accordée par l'appelant.
     */
    data class OpenExternal(
        val uri: Uri,
        val mimeType: String?,
        val title: String?,
        val isVideo: Boolean,
        val exitOnBack: Boolean,
    ) : MainEvent

    /** L'URI reçue n'est ni une image ni une vidéo lisible. [finish] : rendre la main à l'appelant. */
    data class CannotOpen(val finish: Boolean) : MainEvent
}

/** ViewModel de l'activité : réglages (thème), intents VIEW, démarrage de l'indexation. */
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

    /** OneDrive existe dans ce build : « Utiliser OneDrive seulement » est alors proposé. */
    val cloudAvailable: Boolean = cloudAccounts.isAvailable

    /** Choix « OneDrive seul » enregistré, null tant que les réglages ne sont pas lus. */
    val oneDriveOnlyChosen: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.oneDriveOnlyChosen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Compte OneDrive connecté (ou à reconnecter) : sinon l'onglet Photos invite à se connecter. */
    val cloudSignedIn: StateFlow<Boolean> = cloudAccounts.state
        .map { it is CloudAccountState.SignedIn || it is CloudAccountState.NeedsReauth }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events: Flow<MainEvent> = _events.receiveAsFlow()

    private var lastAccess: MediaAccess? = null

    init {
        // Etat du compte lu au démarrage (Photos en « OneDrive seul » en dépend).
        val current = cloudAccounts.state.value
        if (cloudAvailable && (current is CloudAccountState.Loading || current is CloudAccountState.Error)) {
            viewModelScope.launch { cloudAccounts.refresh() }
        }
    }

    /** Appelé à chaque changement d'accès détecté par l'UI (y compris au premier affichage). */
    fun onMediaAccessChanged(access: MediaAccess) {
        mediaAccess.update(access)
        if (access == lastAccess) return
        lastAccess = access
        if (access != MediaAccess.NONE) {
            syncCoordinator.onAccessChanged()
            // Accès accordé : le choix « OneDrive seul » est oublié (un retrait ultérieur redemande).
            viewModelScope.launch {
                if (settingsRepository.settings.first().oneDriveOnlyChosen) settingsRepository.setOneDriveOnlyChosen(false)
            }
        }
    }

    /** « Utiliser OneDrive seulement » sur l'écran d'autorisation. */
    fun chooseOneDriveOnly() {
        if (!cloudAvailable) return
        viewModelScope.launch { settingsRepository.setOneDriveOnlyChosen(true) }
    }

    /** Retour de la demande d'autorisation avec un accès accordé (ex. nouvelle sélection partielle). */
    fun onMediaAccessRegranted() {
        syncCoordinator.onAccessChanged()
    }

    /**
     * Résout une URI content:// reçue par intent VIEW, hors du thread principal : média indexé si
     * possible (visionneuse complète), sinon affichage direct de l'URI accordée.
     */
    fun handleViewIntent(uri: Uri, intentType: String?, exitOnBack: Boolean) {
        viewModelScope.launch {
            val id = withContext(io) {
                try {
                    mediaRepository.findByContentUri(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "URI non résolue : $uri", e)
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
        }.onFailure { Log.w(TAG, "Nom du fichier illisible : $uri", it) }.getOrNull()
        return MainEvent.OpenExternal(uri, mime, title, isVideo, exitOnBack)
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
