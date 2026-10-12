package fr.eva.cloudgallery.data.cloud.transfer

import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudDownloadException
import fr.eva.cloudgallery.domain.model.CloudDownloadInfo
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.DownloadError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Téléchargement depuis l'hôte OneDrive avec un transport factice (aucun accès réseau). */
class CdnDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = "0123456789".toByteArray()
    private val secretUrl = "https://b0mpua.files.1drv.com/y4m/file.jpg?tempauth=SECRET"

    private fun info(cTag: String? = "c1", size: Long = content.size.toLong(), url: String = secretUrl) =
        CloudDownloadInfo(itemId = "ITEM!1", sizeBytes = size, cTag = cTag, eTag = "e1", driveId = "D", url = url)

    private class Harness(
        private val infos: List<() -> CloudDownloadInfo>,
        vararg steps: (Request) -> CdnExchange,
    ) {
        val requests = ArrayList<Request>()
        val infoCalls = ArrayList<Boolean>()
        val sleeps = ArrayList<Long>()
        private val queue = ArrayDeque(steps.toList())
        val downloader = CdnDownloader(
            userAgent = "CloudGallery/test (Android)",
            transport = { request ->
                requests += request
                queue.removeFirst().invoke(request)
            },
            sleep = { sleeps += it },
        )
        val infoProvider: suspend (Boolean) -> CloudDownloadInfo = { force ->
            infoCalls += force
            infos[minOf(infoCalls.size - 1, infos.lastIndex)].invoke()
        }
    }

    private fun response(code: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()): (Request) -> CdnExchange = { req ->
        CdnExchange(
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("HTTP $code")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(body.toResponseBody())
                .build(),
            cancel = {},
        )
    }

    private fun part(initial: ByteArray? = null): File = File(tmp.root, "x.part").also { f ->
        if (initial != null) f.writeBytes(initial)
    }

    private suspend fun expectError(expected: DownloadError, block: suspend () -> Unit) {
        try {
            block()
            fail("$expected attendu")
        } catch (e: CloudDownloadException) {
            assertEquals(expected, e.error)
            // Jamais d'URL ni de jeton dans un message d'erreur.
            generateSequence<Throwable>(e) { it.cause }.forEach { assertFalse(it.message.orEmpty().contains("tempauth")) }
        }
    }

    @Test
    fun full200_writesFile_withoutAuthorization_andWithIdentityEncoding() = runTest {
        val h = Harness(listOf({ info() }), response(200, content))
        val file = part()
        val progress = ArrayList<Long>()
        val result = h.downloader.download(h.infoProvider, file, expectedCTag = "c1") { done, _ -> progress += done }
        assertArrayEquals(content, file.readBytes())
        assertEquals(10L, result.sizeBytes)
        assertEquals("c1", result.cTag)
        val req = h.requests.single()
        assertNull(req.header("Authorization"))
        assertNull(req.header("Range"))
        assertEquals("identity", req.header("Accept-Encoding"))
        assertEquals("CloudGallery/test (Android)", req.header("User-Agent"))
        assertEquals(10L, progress.last())
    }

    @Test
    fun partialPart_sameVersion_resumesWithRange_andAppends() = runTest {
        val h = Harness(
            listOf({ info() }),
            response(206, "456789".toByteArray(), mapOf("Content-Range" to "bytes 4-9/10")),
        )
        val file = part("0123".toByteArray())
        h.downloader.download(h.infoProvider, file, expectedCTag = "c1")
        assertEquals("bytes=4-", h.requests.single().header("Range"))
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun resume_asksGraphForAFreshVersion_neverACachedOne() = runTest {
        val h = Harness(
            listOf({ info() }),
            response(206, "456789".toByteArray(), mapOf("Content-Range" to "bytes 4-9/10")),
        )
        h.downloader.download(h.infoProvider, part("0123".toByteArray()), expectedCTag = "c1")
        assertEquals(listOf(true), h.infoCalls)
        // Sans fichier partiel : l'URL en cache peut servir.
        val fresh = Harness(listOf({ info() }), response(200, content))
        fresh.downloader.download(fresh.infoProvider, File(tmp.root, "new.part"), expectedCTag = "c1")
        assertEquals(listOf(false), fresh.infoCalls)
    }

    @Test
    fun resume_contentChangedMeanwhile_isRefusedBeforeAppending() = runTest {
        // Version fraîche différente (fichier modifié sur OneDrive) : aucun octet ajouté au fichier partiel.
        val h = Harness(listOf({ info(cTag = "c2") }))
        val file = part("0123".toByteArray())
        expectError(DownloadError.CONTENT_CHANGED) { h.downloader.download(h.infoProvider, file, expectedCTag = "c1") }
        assertTrue(h.requests.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun rangeIgnored_200_restartsFromZero() = runTest {
        val h = Harness(listOf({ info() }), response(200, content))
        val file = part("0123".toByteArray())
        h.downloader.download(h.infoProvider, file, expectedCTag = "c1")
        assertEquals("bytes=4-", h.requests.single().header("Range"))
        // Pas d'ajout au fichier partiel : contenu complet réécrit depuis le début.
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun completePart_sameVersion_needsNoRequest() = runTest {
        val h = Harness(listOf({ info() }))
        val file = part(content)
        val result = h.downloader.download(h.infoProvider, file, expectedCTag = "c1")
        assertTrue(h.requests.isEmpty())
        assertEquals(10L, result.sizeBytes)
    }

    @Test
    fun unexpected416_onResume_restartsOnceFromZero() = runTest {
        val h = Harness(
            listOf({ info() }),
            response(416, headers = mapOf("Content-Range" to "bytes */10")),
            response(200, content),
        )
        val file = part("0123".toByteArray())
        h.downloader.download(h.infoProvider, file, expectedCTag = "c1")
        assertEquals("bytes=4-", h.requests[0].header("Range"))
        assertNull(h.requests[1].header("Range"))
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun unknownVersion_neverResumes() = runTest {
        val h = Harness(listOf({ info(cTag = null) }), response(200, content))
        val file = part("0123".toByteArray())
        h.downloader.download(h.infoProvider, file, expectedCTag = null)
        assertNull(h.requests.single().header("Range"))
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun expiredUrl_refreshesOnce_thenSucceeds() = runTest {
        val h = Harness(
            listOf({ info() }, { info(url = "https://other.files.1drv.com/new?tempauth=NEW") }),
            response(403),
            response(206, "456789".toByteArray(), mapOf("Content-Range" to "bytes 4-9/10")),
        )
        val file = part("0123".toByteArray())
        h.downloader.download(h.infoProvider, file, expectedCTag = "c1")
        // Reprise : version fraîche demandée d'emblée, puis nouvelle URL après le refus.
        assertEquals(listOf(true, true), h.infoCalls)
        assertEquals("other.files.1drv.com", h.requests[1].url.host)
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun expiredUrl_twice_fails() = runTest {
        val h = Harness(listOf({ info() }), response(401), response(410))
        expectError(DownloadError.SERVER) { h.downloader.download(h.infoProvider, part(), expectedCTag = "c1") }
        assertEquals(listOf(false, true), h.infoCalls)
    }

    @Test
    fun sizeMismatch_deletesPart() = runTest {
        val h = Harness(listOf({ info(size = 12) }), response(200, content))
        val file = part()
        expectError(DownloadError.SIZE_MISMATCH) { h.downloader.download(h.infoProvider, file, expectedCTag = "c1") }
        assertFalse(file.exists())
    }

    @Test
    fun nonHttpsUrl_isRefused_withoutRequest() = runTest {
        val h = Harness(listOf({ info(url = "http://files.example.com/x") }))
        expectError(DownloadError.SERVER) { h.downloader.download(h.infoProvider, part(), expectedCTag = "c1") }
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun graphUrl_isRefused_withoutRequest() = runTest {
        val h = Harness(listOf({ info(url = "https://graph.microsoft.com/v1.0/me/drive/items/X/content") }))
        expectError(DownloadError.SERVER) { h.downloader.download(h.infoProvider, part(), expectedCTag = "c1") }
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun contentChanged_isRefused_andPartDeleted() = runTest {
        val h = Harness(listOf({ info(cTag = "c2") }))
        val file = part("0123".toByteArray())
        expectError(DownloadError.CONTENT_CHANGED) { h.downloader.download(h.infoProvider, file, expectedCTag = "c1") }
        assertFalse(file.exists())
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun throttled_shortRetryAfter_waitsThenRetries() = runTest {
        val h = Harness(listOf({ info() }), response(429, headers = mapOf("Retry-After" to "2")), response(200, content))
        h.downloader.download(h.infoProvider, part(), expectedCTag = "c1")
        assertEquals(listOf(2_000L), h.sleeps)
        assertEquals(2, h.requests.size)
    }

    @Test
    fun throttled_longOrMissingRetryAfter_fails() = runTest {
        val h = Harness(listOf({ info() }), response(503, headers = mapOf("Retry-After" to "120")))
        expectError(DownloadError.THROTTLED) { h.downloader.download(h.infoProvider, part(), expectedCTag = "c1") }
        assertTrue(h.sleeps.isEmpty())
        val h2 = Harness(listOf({ info() }), response(429))
        expectError(DownloadError.THROTTLED) { h2.downloader.download(h2.infoProvider, part(), expectedCTag = "c1") }
    }

    @Test
    fun serverError_fails() = runTest {
        val h = Harness(listOf({ info() }), response(500))
        expectError(DownloadError.SERVER) { h.downloader.download(h.infoProvider, part(), expectedCTag = "c1") }
    }

    @Test
    fun transportFailure_isNetwork() = runTest {
        val h = Harness(listOf({ info() }), { throw IOException("connexion coupée") })
        expectError(DownloadError.NETWORK) { h.downloader.download(h.infoProvider, part(), expectedCTag = "c1") }
    }

    @Test
    fun graphFailures_areMapped() = runTest {
        suspend fun failingWith(e: Exception, expected: DownloadError) {
            val h = Harness(listOf({ throw e }))
            expectError(expected) { h.downloader.download(h.infoProvider, part(), expectedCTag = "c1") }
        }
        failingWith(CloudAuthRequiredException(), DownloadError.AUTH_REQUIRED)
        failingWith(GraphHttpException(404, "itemNotFound"), DownloadError.NOT_FOUND)
        failingWith(CloudException(CloudErrorCode.NETWORK), DownloadError.NETWORK)
        failingWith(CloudException(CloudErrorCode.CONFIG), DownloadError.AUTH_REQUIRED)
        failingWith(CloudException(CloudErrorCode.SERVER), DownloadError.SERVER)
    }

    /** Corps qui livre quelques octets puis bloque jusqu'à l'annulation de l'appel. */
    private class BlockingSource(private val cancelled: CountDownLatch) : Source {
        private var sent = false
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (!sent) {
                sent = true
                sink.write("0123".toByteArray())
                return 4
            }
            if (!cancelled.await(10, TimeUnit.SECONDS)) throw IOException("délai du test dépassé")
            throw IOException("Canceled")
        }

        override fun timeout(): Timeout = Timeout.NONE
        override fun close() = Unit
    }

    @Test
    fun cancellationDuringBody_cancelsTheCall() = runBlocking {
        val cancelled = CountDownLatch(1)
        val cancelCalled = AtomicBoolean(false)
        val firstBytes = CompletableDeferred<Unit>()
        val downloader = CdnDownloader(
            userAgent = "test",
            transport = { req ->
                CdnExchange(
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(BlockingSource(cancelled).buffer().asResponseBody(null, 10))
                        .build(),
                    cancel = {
                        cancelCalled.set(true)
                        cancelled.countDown()
                    },
                )
            },
        )
        val job = launch(Dispatchers.IO) {
            downloader.download({ info() }, part(), expectedCTag = "c1") { done, _ -> if (done > 0) firstBytes.complete(Unit) }
        }
        withTimeout(10_000) {
            firstBytes.await()
            job.cancelAndJoin()
        }
        assertTrue(cancelCalled.get())
        assertTrue(job.isCancelled)
    }
}
