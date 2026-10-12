package fr.eva.cloudgallery.data.cloud.transfer

import android.net.Network
import fr.eva.cloudgallery.data.cloud.graph.CLOUD_USER_AGENT
import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.data.cloud.graph.await
import fr.eva.cloudgallery.data.cloud.graph.isDownloadUrlAcceptable
import fr.eva.cloudgallery.data.cloud.graph.parseRetryAfterMs
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudDownloadException
import fr.eva.cloudgallery.domain.model.CloudDownloadInfo
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudThrottledException
import fr.eva.cloudgallery.domain.model.DownloadError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Réponse de l'hôte de téléchargement OneDrive et moyen d'interrompre l'échange, y compris
 * pendant la lecture du corps (annulation de l'appel OkHttp).
 */
internal class CdnExchange(val response: Response, val cancel: () -> Unit)

/** Téléchargement terminé : taille et version (cTag/eTag) du contenu écrit. */
class CdnResult(val sizeBytes: Long, val cTag: String?, val eTag: String?)

/**
 * Téléchargement d'un fichier OneDrive depuis son URL pré-authentifiée vers un fichier partiel,
 * indépendant d'OkHttpClient (transport injecté : testable en JVM avec des réponses préparées).
 *
 * - URL HTTPS uniquement, hors Microsoft Graph ; aucun en-tête Authorization ; l'URL n'apparaît
 *   dans aucun message ni journal.
 * - Reprise du fichier partiel (`Range: bytes=N-`) seulement pour la même version connue
 *   ([resumePlan]), vérifiée auprès de Graph juste avant (jamais d'après une URL en cache) ;
 *   200 en réponse à un Range : le fichier repart de zéro.
 * - URL refusée (401/403/404/410) : une seule nouvelle URL demandée à Graph.
 * - 429/503 : Retry-After respecté jusqu'à [MAX_THROTTLE_WAIT_MS], deux fois au plus.
 * - Corps écrit au fil de l'eau (tampon de 256 Kio, jamais tout en mémoire), annulable à tout
 *   moment, synchronisé sur disque, puis taille vérifiée.
 * Toute erreur devient [CloudDownloadException] ; l'annulation est propagée telle quelle.
 */
internal class CdnDownloader(
    private val userAgent: String,
    private val transport: suspend (Request) -> CdnExchange,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /** Etape suivante d'une tentative. */
    private sealed interface Step {
        class Done(val result: CdnResult) : Step

        /** URL refusée par l'hôte : en demander une nouvelle. */
        data object RefreshUrl : Step

        /** Attente demandée par l'hôte déjà respectée : nouvel essai. */
        data object Throttled : Step

        /** Reprise refusée de façon incohérente : repartir de zéro. */
        data object Restart : Step
    }

    /**
     * Télécharge l'élément décrit par [info] (appelé avec `true` pour forcer une nouvelle URL)
     * dans [part]. [expectedCTag] : version attendue (celle de l'index, et celle d'un fichier
     * partiel existant) ; un contenu d'une autre version est refusé ([DownloadError.CONTENT_CHANGED]).
     * [onProgress] reçoit les octets écrits et la taille totale.
     */
    suspend fun download(
        info: suspend (forceRefresh: Boolean) -> CloudDownloadInfo,
        part: File,
        expectedCTag: String?,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): CdnResult {
        // Reprise d'un fichier partiel : version et URL redemandées à Graph (jamais celles du
        // cache, qui peuvent dater de deux minutes) avant d'ajouter des octets au fichier. Graph
        // ne documente pas If-Range : la version fraîche est le seul contrôle fiable.
        var refreshUrl = part.isFile && part.length() > 0
        var urlRefreshed = false
        var throttleWaits = 0
        var restarts = 0
        while (true) {
            val current = fetchInfo(info, refreshUrl)
            refreshUrl = false
            if (expectedCTag != null && current.cTag != null && current.cTag != expectedCTag) {
                part.delete()
                throw CloudDownloadException(DownloadError.CONTENT_CHANGED)
            }
            if (!isDownloadUrlAcceptable(current.url)) throw CloudDownloadException(DownloadError.SERVER)
            val size = current.sizeBytes
            val partLength = if (part.isFile) part.length() else 0L
            val offset = when (val plan = resumePlan(partLength, size, expectedCTag, current.cTag)) {
                ResumePlan.FromZero -> 0L
                is ResumePlan.From -> plan.offset
                ResumePlan.Complete -> return CdnResult(size, current.cTag, current.eTag)
            }
            when (val step = attempt(current, part, offset, onProgress)) {
                is Step.Done -> return step.result
                Step.RefreshUrl -> {
                    // Toujours refusée avec une URL neuve : lien inutilisable.
                    if (urlRefreshed) throw CloudDownloadException(DownloadError.SERVER)
                    urlRefreshed = true
                    refreshUrl = true
                }
                Step.Throttled -> {
                    throttleWaits++
                    if (throttleWaits > MAX_THROTTLE_WAITS) throw CloudDownloadException(DownloadError.THROTTLED)
                }
                Step.Restart -> {
                    // Une seule reprise complète.
                    if (restarts > 0) throw CloudDownloadException(DownloadError.SERVER)
                    restarts++
                    part.delete()
                }
            }
        }
    }

    private suspend fun attempt(
        info: CloudDownloadInfo,
        part: File,
        offset: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ): Step {
        val size = info.sizeBytes
        val request = Request.Builder()
            .url(info.url)
            .header("User-Agent", userAgent)
            // Octets bruts : la progression et la reprise comptent les octets du fichier.
            .header("Accept-Encoding", "identity")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .get()
            .build()
        val exchange = try {
            transport(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw CloudDownloadException(DownloadError.NETWORK, e)
        }
        exchange.response.use { response ->
            val code = response.code
            if (isExpiredUrlStatus(code)) {
                // URL expirée ou révoquée : une nouvelle est demandée à Graph (qui répondra 404 si
                // l'élément a disparu).
                return Step.RefreshUrl
            }
            if (code == 429 || code == 503) {
                val wait = parseRetryAfterMs(response.header("Retry-After"))
                if (wait == null || wait > MAX_THROTTLE_WAIT_MS) throw CloudDownloadException(DownloadError.THROTTLED)
                sleep(wait)
                return Step.Throttled
            }
            val outcome = classifyRangeResponse(code, parseContentRange(response.header("Content-Range")), offset, size)
            when (outcome) {
                RangeOutcome.Append -> writeBody(exchange, part, append = offset > 0, startBytes = offset, total = size, onProgress)
                RangeOutcome.RestartFromZero -> writeBody(exchange, part, append = false, startBytes = 0L, total = size, onProgress)
                RangeOutcome.AlreadyComplete -> Unit
                is RangeOutcome.Invalid -> {
                    if (offset > 0 && (code == 206 || code == 416)) return Step.Restart
                    throw CloudDownloadException(DownloadError.SERVER)
                }
            }
        }
        val written = if (part.isFile) part.length() else -1L
        if (written != size) {
            part.delete()
            throw CloudDownloadException(DownloadError.SIZE_MISMATCH)
        }
        onProgress(written, size)
        return Step.Done(CdnResult(size, info.cTag, info.eTag))
    }

    /**
     * Ecrit le corps dans [part]. La lecture (bloquante) tourne dans une coroutine enfant : si
     * l'appelant est annulé, l'appel OkHttp est annulé aussitôt, ce qui débloque la lecture.
     */
    private suspend fun writeBody(
        exchange: CdnExchange,
        part: File,
        append: Boolean,
        startBytes: Long,
        total: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ) = coroutineScope {
        val reading = async {
            val out = try {
                FileOutputStream(part, append)
            } catch (e: IOException) {
                throw writeFailure(e)
            }
            out.use {
                val source = exchange.response.body.source()
                val buffer = ByteArray(BUFFER_BYTES)
                var done = startBytes
                while (true) {
                    ensureActive()
                    val n = try {
                        source.read(buffer)
                    } catch (e: IOException) {
                        // Lecture interrompue par l'annulation (appel OkHttp annulé) : c'est une
                        // annulation, pas une erreur réseau (sinon l'erreur primerait sur l'annulation).
                        ensureActive()
                        throw CloudDownloadException(DownloadError.NETWORK, e)
                    }
                    if (n == -1) break
                    try {
                        out.write(buffer, 0, n)
                    } catch (e: IOException) {
                        ensureActive()
                        throw writeFailure(e)
                    }
                    done += n
                    onProgress(done, total)
                }
                try {
                    out.fd.sync()
                } catch (e: IOException) {
                    throw writeFailure(e)
                }
            }
        }
        try {
            reading.await()
        } catch (e: CancellationException) {
            exchange.cancel()
            throw e
        }
    }

    private fun writeFailure(e: IOException): CloudDownloadException {
        val noSpace = generateSequence<Throwable>(e) { it.cause }.take(8).any { it.message?.contains("ENOSPC") == true }
        return CloudDownloadException(if (noSpace) DownloadError.NO_SPACE else DownloadError.SERVER, e)
    }

    private suspend fun fetchInfo(info: suspend (Boolean) -> CloudDownloadInfo, force: Boolean): CloudDownloadInfo = try {
        info(force)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudAuthRequiredException) {
        throw CloudDownloadException(DownloadError.AUTH_REQUIRED, e)
    } catch (e: CloudThrottledException) {
        throw CloudDownloadException(DownloadError.THROTTLED, e)
    } catch (e: GraphHttpException) {
        throw CloudDownloadException(if (e.status == 404) DownloadError.NOT_FOUND else DownloadError.SERVER, e)
    } catch (e: CloudException) {
        throw CloudDownloadException(downloadErrorFor(e.code), e)
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024

        /** Attente maximale demandée par l'hôte (Retry-After) acceptée dans un téléchargement. */
        const val MAX_THROTTLE_WAIT_MS = 60_000L
        private const val MAX_THROTTLE_WAITS = 2
    }
}

/** Code d'échec de téléchargement correspondant à un échec Graph ou d'accès au compte. */
internal fun downloadErrorFor(code: CloudErrorCode): DownloadError = when (code) {
    CloudErrorCode.NETWORK -> DownloadError.NETWORK
    CloudErrorCode.AUTH_REQUIRED, CloudErrorCode.CONFIG, CloudErrorCode.DECLINED -> DownloadError.AUTH_REQUIRED
    CloudErrorCode.THROTTLED -> DownloadError.THROTTLED
    CloudErrorCode.SERVER, CloudErrorCode.NO_BROWSER, CloudErrorCode.UNKNOWN -> DownloadError.SERVER
}

/**
 * Téléchargeur de fichiers OneDrive de l'appli : URL obtenue par [CloudDownloadUrlProvider]
 * (contrôle d'accès au compte à chaque demande), client OkHttp partagé (sans intercepteur : le
 * jeton Graph n'est ajouté que par requête Graph), travail sur le dispatcher d'E/S.
 */
@Singleton
class CloudFileDownloader @Inject constructor(
    private val client: dagger.Lazy<OkHttpClient>,
    private val urls: CloudDownloadUrlProvider,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val core = CdnDownloader(
        userAgent = CLOUD_USER_AGENT,
        transport = { request ->
            val call = client.get().newCall(request)
            CdnExchange(call.await(), call::cancel)
        },
    )

    /** Client lié à un réseau précis (et sa fabrique), réutilisé tant que le réseau ne change pas. */
    @Volatile
    private var bound: Pair<Network, CdnDownloader>? = null

    /**
     * Télécharge [itemId] dans [part] (voir [CdnDownloader.download]).
     *
     * [network] : réseau imposé (celui que JobScheduler a choisi pour un travail « Wi-Fi
     * uniquement » : sinon le réseau par défaut, éventuellement facturé, serait utilisé). Null :
     * réseau par défaut. [expectedDriveId] : lecteur OneDrive des données de l'index ; un élément
     * d'un autre lecteur (autre compte) est refusé ([DownloadError.AUTH_REQUIRED]).
     */
    suspend fun download(
        itemId: String,
        part: File,
        expectedCTag: String?,
        network: Network? = null,
        expectedDriveId: String? = null,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): CdnResult = withContext(io) {
        val downloader = if (network == null) core else boundTo(network)
        downloader.download(
            info = { force ->
                val info = urls.get(itemId, forceRefresh = force)
                if (!sameDrive(expectedDriveId, info.driveId)) throw CloudAuthRequiredException()
                info
            },
            part = part,
            expectedCTag = expectedCTag,
            onProgress = onProgress,
        )
    }

    private fun boundTo(network: Network): CdnDownloader {
        bound?.let { (n, d) -> if (n == network) return d }
        val networkClient = client.get().newBuilder()
            .socketFactory(network.socketFactory)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
                },
            )
            .build()
        val downloader = CdnDownloader(
            userAgent = CLOUD_USER_AGENT,
            transport = { request ->
                val call = networkClient.newCall(request)
                CdnExchange(call.await(), call::cancel)
            },
        )
        bound = network to downloader
        return downloader
    }
}

/**
 * Même lecteur OneDrive : identifiants comparés sans tenir compte de la casse (OneDrive
 * personnel les renvoie parfois en minuscules ou en majuscules). Inconnu d'un côté : accepté
 * (le contrôle de compte de l'index s'applique déjà).
 */
internal fun sameDrive(expected: String?, actual: String?): Boolean =
    expected.isNullOrBlank() || actual.isNullOrBlank() || expected.equals(actual, ignoreCase = true)
