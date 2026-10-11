package fr.eva.cloudgallery.data.cloud.graph

import fr.eva.cloudgallery.BuildConfig
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudThrottledException
import fr.eva.cloudgallery.domain.repository.CloudTokenProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** 410 Gone : le jeton delta a expiré, une énumération complète doit reprendre depuis [location]. */
class GraphResyncRequiredException(val location: String?) : Exception("Jeton delta expiré (410)")

/** Réponse Graph en échec (hors 401, 410 et limitations). Ni URL ni jeton dans le message. */
class GraphHttpException(val status: Int, val graphCode: String?) :
    CloudException(errorCodeForStatus(status)) {
    override val message: String get() = "Microsoft Graph : HTTP $status (${graphCode ?: "?"})"
}

/**
 * Pause globale demandée par Graph (« pause all further requests ») : toute requête attend la
 * fin du blocage le plus long annoncé (Retry-After ou attente exponentielle).
 */
internal class ThrottleGate(private val clock: () -> Long) {
    @Volatile
    private var blockedUntilMs = 0L

    fun remainingMs(): Long = (blockedUntilMs - clock()).coerceAtLeast(0L)

    @Synchronized
    fun block(delayMs: Long) {
        val until = clock() + delayMs
        if (until > blockedUntilMs) blockedUntilMs = until
    }
}

/**
 * Boucle de requête GET authentifiée, indépendante d'OkHttpClient (le transport est injecté :
 * testable en JVM avec des réponses préparées) :
 * - jeton `Bearer` envoyé uniquement à https://graph.microsoft.com ;
 * - 401 : renouvellement forcé du jeton puis une seule nouvelle tentative ;
 * - 429/502/503/504 : Retry-After respecté (à défaut attente exponentielle), pause globale ;
 * - 410 : [GraphResyncRequiredException] avec l'URL de reprise ;
 * - erreur réseau : quelques nouvelles tentatives espacées, puis [CloudErrorCode.NETWORK].
 * Toute réponse est fermée après usage.
 *
 * [get] accepte un en-tête Accept et un nombre maximal de nouvelles tentatives par appel : les
 * miniatures (chargées pendant le défilement) abandonnent vite, mais la pause globale demandée
 * par Graph est toujours appliquée aux requêtes suivantes. Une pause plus longue que
 * [RETRY_AFTER_MAX_MS] n'est pas attendue : chaque appel échoue alors aussitôt, sans requête,
 * avec [CloudThrottledException].
 */
internal class GraphCaller(
    private val tokens: CloudTokenProvider,
    private val userAgent: String,
    private val gate: ThrottleGate,
    private val transport: suspend (Request) -> Response,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun <T> get(
        url: String,
        accept: String = ACCEPT_JSON,
        maxRetries: Int = MAX_RETRIES,
        block: suspend (Response) -> T,
    ): T {
        val httpUrl = url.toHttpUrlOrNull()?.takeIf { isTrustedGraphUrl(it) }
            ?: throw CloudException(CloudErrorCode.SERVER, IllegalArgumentException("URL hors Microsoft Graph refusée"))
        var forceRefresh = false
        var tokenRefreshed = false
        var retries = 0
        var ioRetries = 0
        while (true) {
            val paused = gate.remainingMs()
            // Pause annoncée trop longue pour être attendue dans l'appel : abandon sans requête.
            if (paused > RETRY_AFTER_MAX_MS) throw CloudThrottledException(paused)
            if (paused > 0) sleep(paused)
            val token = tokens.accessToken(forceRefresh)
            forceRefresh = false
            val request = Request.Builder()
                .url(httpUrl)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .get()
                .build()
            try {
                transport(request).use { response ->
                    when (classifyGraphStatus(response.code)) {
                        GraphStatusAction.SUCCESS -> return block(response)
                        GraphStatusAction.REFRESH_TOKEN -> {
                            if (tokenRefreshed) {
                                // Jeton tout juste renouvelé et pourtant refusé (accès révoqué côté
                                // service) : le compte doit être reconnecté.
                                tokens.onTokenRejected(token)
                                throw CloudAuthRequiredException(GraphHttpException(response.code, graphErrorCode(response)))
                            }
                            tokenRefreshed = true
                            forceRefresh = true
                        }
                        GraphStatusAction.RETRY -> {
                            val retryAfter = parseRetryAfterMs(response.header("Retry-After"))
                            val wait = retryDelayMs(retries, retryAfter)
                            // Pause globale toujours appliquée aux requêtes suivantes, même quand cet
                            // appel abandonne (budget épuisé, Retry-After trop long pour être attendu).
                            gate.block(wait ?: retryAfter ?: BACKOFF_CAP_MS)
                            if (wait == null || retries >= maxRetries) {
                                throw if (response.code == 429 || retryAfter != null) {
                                    CloudThrottledException(retryAfter ?: BACKOFF_CAP_MS)
                                } else {
                                    GraphHttpException(response.code, graphErrorCode(response))
                                }
                            }
                            retries++
                        }
                        GraphStatusAction.RESYNC -> throw GraphResyncRequiredException(response.header("Location"))
                        GraphStatusAction.FAIL -> throw GraphHttpException(response.code, graphErrorCode(response))
                    }
                }
            } catch (e: IOException) {
                if (ioRetries >= IO_MAX_RETRIES) throw CloudException(CloudErrorCode.NETWORK, e)
                sleep(min(BACKOFF_CAP_MS, BACKOFF_BASE_MS shl ioRetries))
                ioRetries++
            }
        }
    }

    /** Code d'erreur Graph (`error.code`) lu sans consommer le corps, ou null. */
    private fun graphErrorCode(response: Response): String? = try {
        GraphJson.decodeFromString(GraphErrorEnvelope.serializer(), response.peekBody(ERROR_BODY_MAX_BYTES).string())
            .error?.code
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        const val ACCEPT_JSON = "application/json"
        private const val ERROR_BODY_MAX_BYTES = 16_384L
    }
}

/**
 * Client HTTP de Microsoft Graph (OkHttp). L'OkHttpClient n'est créé qu'au premier appel, sur
 * le dispatcher d'E/S (initialisation TLS hors du thread principal).
 */
@Singleton
class GraphHttpClient @Inject constructor(
    private val client: dagger.Lazy<OkHttpClient>,
    tokens: CloudTokenProvider,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val caller = GraphCaller(
        tokens = tokens,
        userAgent = "CloudGallery/${BuildConfig.VERSION_NAME} (Android)",
        gate = ThrottleGate(System::currentTimeMillis),
        transport = { request -> client.get().newCall(request).await() },
    )

    /** GET authentifié ; [block] lit la réponse (fermée ensuite). */
    suspend fun <T> get(
        url: String,
        accept: String = GraphCaller.ACCEPT_JSON,
        maxRetries: Int = MAX_RETRIES,
        block: suspend (Response) -> T,
    ): T = withContext(io) { caller.get(url, accept, maxRetries, block) }
}

/** Exécution asynchrone d'un appel OkHttp, annulée avec la coroutine. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Coroutine annulée entre-temps : la réponse est fermée.
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
