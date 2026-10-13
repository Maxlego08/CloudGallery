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

/** 410 Gone: the delta token has expired, a full enumeration must restart from [location]. */
class GraphResyncRequiredException(val location: String?) : Exception("Delta token expired (410)")

/** Failed Graph response (other than 401, 410 and throttling). No URL or token in the message. */
class GraphHttpException(val status: Int, val graphCode: String?) :
    CloudException(errorCodeForStatus(status)) {
    override val message: String get() = "Microsoft Graph: HTTP $status (${graphCode ?: "?"})"
}

/**
 * Global pause requested by Graph ("pause all further requests"): every request waits for the
 * end of the longest announced block (Retry-After or exponential backoff).
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
 * Authenticated GET request loop, independent of OkHttpClient (the transport is injected:
 * testable on the JVM with canned responses):
 * - `Bearer` token sent only to https://graph.microsoft.com;
 * - 401: forced token refresh, then a single retry;
 * - 429/502/503/504: Retry-After honored (exponential backoff otherwise), global pause;
 * - 410: [GraphResyncRequiredException] with the resume URL;
 * - network error: a few spaced-out retries, then [CloudErrorCode.NETWORK].
 * Every response is closed after use.
 *
 * [get] accepts an Accept header and a maximum number of retries per call: thumbnails (loaded
 * while scrolling) give up quickly, but the global pause requested by Graph is always applied
 * to subsequent requests. A pause longer than [RETRY_AFTER_MAX_MS] is not waited out: each call
 * then fails immediately, without a request, with [CloudThrottledException].
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
            ?: throw CloudException(CloudErrorCode.SERVER, IllegalArgumentException("Non-Microsoft Graph URL rejected"))
        var forceRefresh = false
        var tokenRefreshed = false
        var retries = 0
        var ioRetries = 0
        while (true) {
            val paused = gate.remainingMs()
            // Announced pause too long to wait out within the call: give up without a request.
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
                                // Token just refreshed and yet rejected (access revoked on the
                                // service side): the account must be signed in again.
                                tokens.onTokenRejected(token)
                                throw CloudAuthRequiredException(GraphHttpException(response.code, graphErrorCode(response)))
                            }
                            tokenRefreshed = true
                            forceRefresh = true
                        }
                        GraphStatusAction.RETRY -> {
                            val retryAfter = parseRetryAfterMs(response.header("Retry-After"))
                            val wait = retryDelayMs(retries, retryAfter)
                            // Global pause always applied to subsequent requests, even when this
                            // call gives up (budget exhausted, Retry-After too long to wait out).
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

    /** Graph error code (`error.code`) read without consuming the body, or null. */
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
 * Microsoft Graph HTTP client (OkHttp). The OkHttpClient is only created on the first call, on
 * the I/O dispatcher (TLS initialization off the main thread).
 */
@Singleton
class GraphHttpClient @Inject constructor(
    private val client: dagger.Lazy<OkHttpClient>,
    tokens: CloudTokenProvider,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val caller = GraphCaller(
        tokens = tokens,
        userAgent = CLOUD_USER_AGENT,
        gate = ThrottleGate(System::currentTimeMillis),
        transport = { request -> client.get().newCall(request).await() },
    )

    /** Authenticated GET; [block] reads the response (closed afterwards). */
    suspend fun <T> get(
        url: String,
        accept: String = GraphCaller.ACCEPT_JSON,
        maxRetries: Int = MAX_RETRIES,
        block: suspend (Response) -> T,
    ): T = withContext(io) { caller.get(url, accept, maxRetries, block) }
}

/** User-Agent of all the app's HTTP requests (Graph and the OneDrive download host). */
internal val CLOUD_USER_AGENT: String = "CloudGallery/${BuildConfig.VERSION_NAME} (Android)"

/**
 * Asynchronous execution of an OkHttp call up to the response headers, cancelled with the
 * coroutine. Reading the body afterwards is not covered: a caller reading a long body must
 * cancel the call itself (see CdnDownloader).
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Coroutine cancelled in the meantime: the response is closed.
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
