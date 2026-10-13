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
 * Response from the OneDrive download host and a way to interrupt the exchange, including
 * while the body is being read (cancellation of the OkHttp call).
 */
internal class CdnExchange(val response: Response, val cancel: () -> Unit)

/** Download finished: size and version (cTag/eTag) of the written content. */
class CdnResult(val sizeBytes: Long, val cTag: String?, val eTag: String?)

/**
 * Downloads a OneDrive file from its pre-authenticated URL into a partial file, independent of
 * OkHttpClient (injected transport: testable on the JVM with canned responses).
 *
 * - HTTPS URLs only, never Microsoft Graph; no Authorization header; the URL never appears in
 *   any message or log.
 * - Resumes the partial file (`Range: bytes=N-`) only for the same known version
 *   ([resumePlan]), checked with Graph just before (never based on a cached URL);
 *   200 in response to a Range: the file restarts from zero.
 * - URL rejected (401/403/404/410): a single new URL requested from Graph.
 * - 429/503: Retry-After honored up to [MAX_THROTTLE_WAIT_MS], at most twice.
 * - Body written as it streams (256 KiB buffer, never all in memory), cancellable at any
 *   time, synced to disk, then size checked.
 * Every error becomes [CloudDownloadException]; cancellation is propagated as is.
 */
internal class CdnDownloader(
    private val userAgent: String,
    private val transport: suspend (Request) -> CdnExchange,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /** Next step of an attempt. */
    private sealed interface Step {
        class Done(val result: CdnResult) : Step

        /** URL rejected by the host: request a new one. */
        data object RefreshUrl : Step

        /** Wait requested by the host already honored: try again. */
        data object Throttled : Step

        /** Resume rejected inconsistently: restart from zero. */
        data object Restart : Step
    }

    /**
     * Downloads the item described by [info] (called with `true` to force a new URL) into
     * [part]. [expectedCTag]: expected version (the index's, and that of an existing partial
     * file); content of another version is rejected ([DownloadError.CONTENT_CHANGED]).
     * [onProgress] receives the bytes written and the total size.
     */
    suspend fun download(
        info: suspend (forceRefresh: Boolean) -> CloudDownloadInfo,
        part: File,
        expectedCTag: String?,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): CdnResult {
        // Resuming a partial file: version and URL requested again from Graph (never the cached
        // ones, which may be two minutes old) before appending bytes to the file. Graph does not
        // document If-Range: the fresh version is the only reliable check.
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
                    // Still rejected with a fresh URL: unusable link.
                    if (urlRefreshed) throw CloudDownloadException(DownloadError.SERVER)
                    urlRefreshed = true
                    refreshUrl = true
                }
                Step.Throttled -> {
                    throttleWaits++
                    if (throttleWaits > MAX_THROTTLE_WAITS) throw CloudDownloadException(DownloadError.THROTTLED)
                }
                Step.Restart -> {
                    // A single full restart.
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
            // Raw bytes: progress and resume count the file's bytes.
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
                // URL expired or revoked: a new one is requested from Graph (which will answer 404
                // if the item is gone).
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
     * Writes the body into [part]. The (blocking) read runs in a child coroutine: if the caller
     * is cancelled, the OkHttp call is cancelled immediately, which unblocks the read.
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
                        // Read interrupted by cancellation (OkHttp call cancelled): this is a
                        // cancellation, not a network error (otherwise the error would win over it).
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

        /** Maximum host-requested wait (Retry-After) accepted during a download. */
        const val MAX_THROTTLE_WAIT_MS = 60_000L
        private const val MAX_THROTTLE_WAITS = 2
    }
}

/** Download failure code matching a Graph or account-access failure. */
internal fun downloadErrorFor(code: CloudErrorCode): DownloadError = when (code) {
    CloudErrorCode.NETWORK -> DownloadError.NETWORK
    CloudErrorCode.AUTH_REQUIRED, CloudErrorCode.CONFIG, CloudErrorCode.DECLINED -> DownloadError.AUTH_REQUIRED
    CloudErrorCode.THROTTLED -> DownloadError.THROTTLED
    CloudErrorCode.SERVER, CloudErrorCode.NO_BROWSER, CloudErrorCode.UNKNOWN -> DownloadError.SERVER
}

/**
 * The app's OneDrive file downloader: URL obtained from [CloudDownloadUrlProvider] (account
 * access check on every request), shared OkHttp client (no interceptor: the Graph token is only
 * added per Graph request), work on the I/O dispatcher.
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

    /** Client bound to a specific network (and its factory), reused as long as the network doesn't change. */
    @Volatile
    private var bound: Pair<Network, CdnDownloader>? = null

    /**
     * Downloads [itemId] into [part] (see [CdnDownloader.download]).
     *
     * [network]: enforced network (the one JobScheduler picked for a "Wi-Fi only" job: otherwise
     * the default network, possibly metered, would be used). Null: default network.
     * [expectedDriveId]: OneDrive drive of the index data; an item from another drive (another
     * account) is rejected ([DownloadError.AUTH_REQUIRED]).
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
 * Same OneDrive drive: identifiers compared case-insensitively (personal OneDrive sometimes
 * returns them in lower case, sometimes in upper case). Unknown on one side: accepted (the
 * index's account check already applies).
 */
internal fun sameDrive(expected: String?, actual: String?): Boolean =
    expected.isNullOrBlank() || actual.isNullOrBlank() || expected.equals(actual, ignoreCase = true)
