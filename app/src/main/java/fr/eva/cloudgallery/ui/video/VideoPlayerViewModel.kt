package fr.eva.cloudgallery.ui.video

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.content.ShareUriProvider
import fr.eva.cloudgallery.data.cloud.stream.CloudStreamSourceFactory
import fr.eva.cloudgallery.data.cloud.stream.RemoteStream
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.PlaybackPositionRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.ui.navigation.VideoPlayerRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Etat affiché par le lecteur. Les positions sont en millisecondes. */
data class PlayerUiState(
    val loading: Boolean = true,
    val title: String = "",
    val uri: Uri? = null,
    val mimeType: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val muted: Boolean = false,
    /** Largeur / hauteur affichée de la vidéo, null tant qu'elle est inconnue. */
    val aspectRatio: Float? = null,
    val error: PlaybackErrorKind? = null,
)

/**
 * Possède l'[ExoPlayer] (créé ici, libéré dans [onCleared]) : il survit ainsi aux changements
 * de configuration et à la rotation demandée par l'utilisateur.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val positionRepository: PlaybackPositionRepository,
    private val cloudAccess: CloudAccessGuard,
    private val cloudStreams: CloudStreamSourceFactory,
    private val shareUris: ShareUriProvider,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val route: VideoPlayerRoute = savedStateHandle.toRoute<VideoPlayerRoute>()
    private val mediaId: Long = route.mediaId

    /** Vidéo reçue d'une autre appli et absente de l'index : lue directement, sans position mémorisée. */
    private val externalUri: Uri? = route.externalUri?.let(Uri::parse)

    /** Exposé à l'UI pour PlayerSurface / ContentFrame et les state holders media3-ui-compose. */
    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** Durée connue par la base, utilisée tant qu'ExoPlayer ne l'a pas déterminée. */
    private var knownDurationMs: Long = 0L
    private var prepared = false
    private var lastSaveAt = 0L
    private var loadJob: Job? = null
    private var tickerJob: Job? = null

    /** Lecture en streaming OneDrive en cours (null pour un fichier local). */
    private var remote: RemoteStream? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            refreshProgress()
            // Rafraîchissement périodique uniquement pendant la lecture (pas de réveil en pause / arrière-plan).
            if (isPlaying) startProgressTicker() else stopProgressTicker()
            if (!isPlaying && player.playbackState != Player.STATE_ENDED) savePosition()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _uiState.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    ended = playbackState == Player.STATE_ENDED,
                )
            }
            refreshProgress()
            if (playbackState == Player.STATE_ENDED) clearPosition()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val ratio = VideoPlayerLogic.aspectRatio(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            if (ratio != null) _uiState.update { it.copy(aspectRatio = ratio) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _uiState.update { it.copy(speed = playbackParameters.speed) }
        }

        override fun onVolumeChanged(volume: Float) {
            _uiState.update { it.copy(muted = volume == 0f) }
        }

        override fun onPlayerError(error: PlaybackException) {
            savePosition()
            // Les échecs OneDrive (URL de lecture) sont dans la chaîne des causes ; l'URL de
            // téléchargement n'apparaît jamais dans l'erreur (URI stable rapportée par Media3).
            val cloudFailure = VideoPlayerLogic.cloudFailureOf(generateSequence(error.cause) { it.cause })
            _uiState.update {
                it.copy(
                    error = VideoPlayerLogic.classifyError(error.errorCode, cloudFailure),
                    isBuffering = false,
                    isPlaying = false,
                )
            }
        }
    }

    init {
        player.addListener(listener)
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val external = externalUri
            if (external != null) {
                loadExternal(external)
                return@launch
            }
            val details = try {
                withContext(ioDispatcher) { mediaRepository.getMedia(mediaId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (details == null) {
                _uiState.update { it.copy(loading = false, error = PlaybackErrorKind.MEDIA_NOT_FOUND) }
                return@launch
            }
            knownDurationMs = details.durationMs
            val ratio = VideoPlayerLogic.aspectRatio(
                if (details.orientation % 180 == 0) details.width else details.height,
                if (details.orientation % 180 == 0) details.height else details.width,
            )
            _uiState.update {
                it.copy(
                    title = details.displayName,
                    // « Ouvrir avec » : uniquement pour un fichier local (jamais un élément OneDrive).
                    uri = details.localSource?.uri,
                    mimeType = details.mimeType,
                    durationMs = details.durationMs.coerceAtLeast(0L),
                    aspectRatio = it.aspectRatio ?: ratio,
                )
            }
            // Copie hors ligne vérifiée (présente, taille attendue) sur le dispatcher d'E/S.
            val offlineOk = withContext(ioDispatcher) { PlaybackSourceResolver.isOfflineFileUsable(details.offlineCopy) }
            // Accès OneDrive vérifié seulement si la lecture en dépend (compte, autre compte, build).
            val remoteAllowed = PlaybackSourceResolver.needsRemote(details, offlineOk) && cloudAccess.canAccess()
            when (val source = PlaybackSourceResolver.resolve(details, remoteAllowed, offlineOk)) {
                is PlaybackSource.Local -> {
                    val (start, autoPlay) = startParameters(details.durationMs)
                    _uiState.update { it.copy(loading = false, uri = source.uri, positionMs = start) }
                    remote = null
                    player.setMediaItem(PlaybackSourceResolver.toMediaItem(source), start)
                    player.playWhenReady = autoPlay
                    player.prepare()
                    prepared = true
                }

                is PlaybackSource.Offline -> {
                    val (start, autoPlay) = startParameters(details.durationMs)
                    // « Ouvrir avec » : URI content:// du FileProvider (lecture seule), jamais file://.
                    val openWithUri = try {
                        shareUris.uriFor(source.file.file, details.displayName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    _uiState.update { it.copy(loading = false, uri = openWithUri, positionMs = start) }
                    remote = null
                    player.setMediaItem(PlaybackSourceResolver.toMediaItem(source), start)
                    player.playWhenReady = autoPlay
                    player.prepare()
                    prepared = true
                }

                is PlaybackSource.Remote -> {
                    val (start, autoPlay) = startParameters(details.durationMs)
                    // « Ouvrir avec » reste masqué : aucune URI partageable pour un flux OneDrive.
                    _uiState.update { it.copy(loading = false, uri = null, positionMs = start) }
                    val stream = cloudStreams.create(source.itemId, mediaId)
                    remote = stream
                    // Réseau (URL temporaire puis HTTP Range) : uniquement sur le thread de chargement de Media3.
                    player.setMediaSource(stream.source, start)
                    player.playWhenReady = autoPlay
                    player.prepare()
                    prepared = true
                }

                is PlaybackSource.Unavailable -> _uiState.update {
                    it.copy(
                        loading = false,
                        error = VideoPlayerLogic.unavailableError(source.reason, cloudAccess.configured),
                    )
                }
            }
        }
    }

    /** Position de reprise mémorisée et lecture automatique (réglage). */
    private suspend fun startParameters(durationMs: Long): Pair<Long, Boolean> {
        val saved = withContext(ioDispatcher) {
            runCatching { positionRepository.get(mediaId) }.getOrDefault(0L)
        }
        val autoPlay = runCatching { settingsRepository.settings.first().autoPlayVideos }.getOrDefault(true)
        return VideoPlayerLogic.resumePosition(saved, durationMs) to autoPlay
    }

    private suspend fun loadExternal(uri: Uri) {
        val autoPlay = runCatching { settingsRepository.settings.first().autoPlayVideos }.getOrDefault(true)
        _uiState.update {
            it.copy(loading = false, title = route.title.orEmpty(), uri = uri, mimeType = route.mimeType, positionMs = 0L)
        }
        player.setMediaItem(PlaybackSourceResolver.toMediaItem(PlaybackSource.Local(uri, route.mimeType)), 0L)
        player.playWhenReady = autoPlay
        player.prepare()
        prepared = true
    }

    /** Rafraîchit la position (~200 ms) et sauvegarde périodiquement, seulement pendant la lecture. */
    private fun startProgressTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                refreshProgress()
                if (player.isPlaying) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSaveAt >= VideoPlayerLogic.SAVE_INTERVAL_MS) savePosition()
                }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun stopProgressTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun refreshProgress() {
        if (!prepared) return
        val duration = currentDuration()
        _uiState.update {
            it.copy(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                durationMs = duration,
            )
        }
    }

    private fun currentDuration(): Long {
        val d = player.duration
        return if (d == C.TIME_UNSET || d <= 0) knownDurationMs.coerceAtLeast(0L) else d
    }

    fun pause() {
        if (prepared) player.pause()
        savePosition()
    }

    fun seekTo(positionMs: Long) {
        if (!prepared) return
        player.seekTo(VideoPlayerLogic.seekTarget(positionMs, 0L, currentDuration()))
        refreshProgress()
    }

    fun seekBy(deltaMs: Long) {
        if (!prepared) return
        player.seekTo(VideoPlayerLogic.seekTarget(player.currentPosition, deltaMs, currentDuration()))
        refreshProgress()
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    fun toggleMute() {
        player.volume = if (player.volume == 0f) 1f else 0f
    }

    /**
     * Relance après une erreur : nouvelle préparation à la position courante (avec une nouvelle
     * URL de téléchargement pour un flux OneDrive), ou rechargement complet.
     */
    fun retry() {
        _uiState.update { it.copy(error = null) }
        if (prepared && player.currentMediaItem != null) {
            remote?.invalidateUrl()
            player.prepare()
            player.play()
        } else {
            load()
        }
    }

    /**
     * Retour sur le lecteur (par exemple après l'écran Compte) alors qu'une connexion OneDrive
     * était demandée : nouvel essai, qui réussit si le compte est désormais connecté.
     */
    fun retryIfSignInPending() {
        if (_uiState.value.error == PlaybackErrorKind.AUTH_REQUIRED) retry()
    }

    /** Sauvegarde la position (portée applicative : survit à la fermeture de l'écran). */
    fun savePosition() {
        if (!prepared || player.currentMediaItem == null || externalUri != null) return
        lastSaveAt = SystemClock.elapsedRealtime()
        val position = player.currentPosition
        val duration = currentDuration()
        if (player.playbackState == Player.STATE_ENDED) {
            clearPosition()
            return
        }
        val id = mediaId
        applicationScope.launch(ioDispatcher) {
            runCatching { positionRepository.save(id, position, duration) }
        }
    }

    private fun clearPosition() {
        if (externalUri != null) return
        val id = mediaId
        applicationScope.launch(ioDispatcher) {
            runCatching { positionRepository.clear(id) }
        }
    }

    override fun onCleared() {
        savePosition()
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_TICK_MS = 200L
    }
}
