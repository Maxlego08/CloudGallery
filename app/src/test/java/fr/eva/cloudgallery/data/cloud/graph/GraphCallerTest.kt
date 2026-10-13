package fr.eva.cloudgallery.data.cloud.graph

import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudThrottledException
import fr.eva.cloudgallery.domain.repository.CloudTokenProvider
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Graph request loop with a fake transport (no network access). */
class GraphCallerTest {

    private val url = "https://graph.microsoft.com/v1.0/me/drive"

    private class FakeTokens : CloudTokenProvider {
        val calls = ArrayList<Boolean>()
        val rejected = ArrayList<String>()
        override suspend fun accessToken(forceRefresh: Boolean): String {
            calls += forceRefresh
            return if (forceRefresh) "fresh" else "cached"
        }

        override suspend fun onTokenRejected(token: String) {
            rejected += token
        }
    }

    private class Harness(vararg steps: (Request) -> Response) {
        val tokens = FakeTokens()
        val requests = ArrayList<Request>()
        val sleeps = ArrayList<Long>()
        private val queue = ArrayDeque(steps.toList())
        var now = 1_000L
        val gate = ThrottleGate { now }
        val caller = GraphCaller(
            tokens = tokens,
            userAgent = "CloudGallery/test (Android)",
            gate = gate,
            transport = { request ->
                requests += request
                queue.removeFirst().invoke(request)
            },
            sleep = { ms ->
                sleeps += ms
                now += ms
            },
        )
    }

    private fun response(code: Int, body: String = "{}", headers: Map<String, String> = emptyMap()): (Request) -> Response = { req ->
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody())
            .build()
    }

    private val ioFailure: (Request) -> Response = { throw IOException("connection dropped") }

    @Test
    fun success_sendsBearerAndUserAgent() = runTest {
        val h = Harness(response(200, """{"id":"d1"}"""))
        val body = h.caller.get(url) { it.body.string() }
        assertEquals("""{"id":"d1"}""", body)
        val req = h.requests.single()
        assertEquals("Bearer cached", req.header("Authorization"))
        assertEquals("CloudGallery/test (Android)", req.header("User-Agent"))
        assertEquals(listOf(false), h.tokens.calls)
    }

    @Test
    fun untrustedHost_isRejectedWithoutToken() = runTest {
        val h = Harness()
        try {
            h.caller.get("https://example.com/delta") { it.code }
            fail("non-Graph URL accepted")
        } catch (e: CloudException) {
            assertEquals(CloudErrorCode.SERVER, e.code)
        }
        assertTrue(h.tokens.calls.isEmpty())
        assertTrue(h.requests.isEmpty())
    }

    @Test
    fun unauthorized_refreshesTokenOnceThenSucceeds() = runTest {
        val h = Harness(response(401), response(200))
        assertEquals(200, h.caller.get(url) { it.code })
        assertEquals(listOf(false, true), h.tokens.calls)
        assertEquals("Bearer fresh", h.requests[1].header("Authorization"))
        // A first 401 (expired token) does not require signing in again.
        assertTrue(h.tokens.rejected.isEmpty())
    }

    @Test
    fun unauthorizedTwice_requiresSignIn() = runTest {
        val h = Harness(response(401), response(401))
        try {
            h.caller.get(url) { it.code }
            fail("repeated 401 accepted")
        } catch (e: CloudAuthRequiredException) {
            assertEquals(CloudErrorCode.AUTH_REQUIRED, e.code)
        }
        assertEquals(2, h.requests.size)
        // Freshly renewed token rejected: reported to the provider (account must sign in again).
        assertEquals(listOf("fresh"), h.tokens.rejected)
    }

    @Test
    fun throttled_waitsRetryAfterThenRetries() = runTest {
        val h = Harness(response(429, headers = mapOf("Retry-After" to "7")), response(200))
        assertEquals(200, h.caller.get(url) { it.code })
        assertEquals(listOf(7_000L), h.sleeps)
        assertEquals(2, h.requests.size)
    }

    @Test
    fun serviceUnavailable_withoutRetryAfter_usesExponentialBackoff() = runTest {
        val h = Harness(response(503), response(503), response(200))
        assertEquals(200, h.caller.get(url) { it.code })
        assertEquals(listOf(2_000L, 4_000L), h.sleeps)
    }

    @Test
    fun retryAfterTooLong_givesUpAsThrottled() = runTest {
        val h = Harness(response(429, headers = mapOf("Retry-After" to "3600")))
        try {
            h.caller.get(url) { it.code }
            fail("one-hour Retry-After expected")
        } catch (e: CloudThrottledException) {
            assertEquals(3_600_000L, e.retryAfterMs)
        }
        assertTrue(h.sleeps.isEmpty())
        // The announced pause applies to all following requests, even beyond 10 minutes.
        assertEquals(3_600_000L, h.gate.remainingMs())
    }

    @Test
    fun longGlobalPause_failsFollowingCallsWithoutRequest() = runTest {
        val h = Harness(response(429, headers = mapOf("Retry-After" to "3600")))
        try {
            h.caller.get(url) { it.code }
            fail("one-hour Retry-After expected")
        } catch (_: CloudThrottledException) {
        }
        h.now += 60_000L
        // Thumbnail requested during the pause: no token, no request, no wait.
        try {
            h.caller.get(url, maxRetries = 1) { it.code }
            fail("global pause ignored")
        } catch (e: CloudThrottledException) {
            assertEquals(3_540_000L, e.retryAfterMs)
        }
        assertEquals(1, h.requests.size)
        assertEquals(1, h.tokens.calls.size)
        assertTrue(h.sleeps.isEmpty())
    }

    @Test
    fun retryBudgetExhausted_pausesFollowingRequests() = runTest {
        val steps = Array(MAX_RETRIES + 1) { response(429, headers = mapOf("Retry-After" to "3")) }
        val h = Harness(*steps)
        try {
            h.caller.get(url) { it.code }
            fail("give-up expected")
        } catch (e: CloudThrottledException) {
            assertEquals(3_000L, e.retryAfterMs)
        }
        assertEquals(MAX_RETRIES + 1, h.requests.size)
        // Last Retry-After honoured by following requests despite giving up.
        assertEquals(3_000L, h.gate.remainingMs())
    }

    @Test
    fun tooManyRetries_givesUp() = runTest {
        val steps = Array(MAX_RETRIES + 1) { response(502) }
        val h = Harness(*steps)
        try {
            h.caller.get(url) { it.code }
            fail("give-up expected")
        } catch (e: GraphHttpException) {
            assertEquals(502, e.status)
            assertEquals(CloudErrorCode.SERVER, e.code)
        }
        assertEquals(MAX_RETRIES + 1, h.requests.size)
    }

    @Test
    fun gone_reportsResyncWithLocation() = runTest {
        val location = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=new"
        val h = Harness(
            response(410, body = """{"error":{"code":"resyncChangesApplyDifferences"}}""", headers = mapOf("Location" to location)),
        )
        try {
            h.caller.get(url) { it.code }
            fail("410 expected")
        } catch (e: GraphResyncRequiredException) {
            assertEquals(location, e.location)
        }
    }

    @Test
    fun notFound_failsWithGraphErrorCode() = runTest {
        val h = Harness(response(404, body = """{"error":{"code":"itemNotFound","message":"x"}}"""))
        try {
            h.caller.get(url) { it.code }
            fail("404 expected")
        } catch (e: GraphHttpException) {
            assertEquals(404, e.status)
            assertEquals("itemNotFound", e.graphCode)
        }
    }

    @Test
    fun forbidden_withUnreadableBody_hasNoGraphCode() = runTest {
        val h = Harness(response(403, body = "<html>"))
        try {
            h.caller.get(url) { it.code }
            fail("403 expected")
        } catch (e: GraphHttpException) {
            assertEquals(CloudErrorCode.DECLINED, e.code)
            assertNull(e.graphCode)
        }
    }

    @Test
    fun ioError_isRetriedThenReportedAsNetwork() = runTest {
        val ok = Harness(ioFailure, response(200))
        assertEquals(200, ok.caller.get(url) { it.code })
        assertEquals(listOf(BACKOFF_BASE_MS), ok.sleeps)

        val ko = Harness(ioFailure, ioFailure, ioFailure)
        try {
            ko.caller.get(url) { it.code }
            fail("network error expected")
        } catch (e: CloudException) {
            assertEquals(CloudErrorCode.NETWORK, e.code)
        }
        assertEquals(IO_MAX_RETRIES + 1, ko.requests.size)
    }

    @Test
    fun throttleGate_pausesFollowingRequests() = runTest {
        val h = Harness(response(200))
        h.gate.block(5_000L)
        assertEquals(200, h.caller.get(url) { it.code })
        assertEquals(listOf(5_000L), h.sleeps)
    }

    @Test
    fun accept_defaultsToJson_andCanBeOverridden() = runTest {
        val h = Harness(response(200), response(200))
        h.caller.get(url) { it.code }
        h.caller.get(url, accept = "image/*") { it.code }
        assertEquals("application/json", h.requests[0].header("Accept"))
        assertEquals("image/*", h.requests[1].header("Accept"))
    }

    @Test
    fun noRetryBudget_givesUpAtOnce_butPausesFollowingRequests() = runTest {
        val h = Harness(response(429, headers = mapOf("Retry-After" to "7")))
        try {
            h.caller.get(url, maxRetries = 0) { it.code }
            fail("immediate give-up expected")
        } catch (e: CloudThrottledException) {
            assertEquals(7_000L, e.retryAfterMs)
        }
        assertEquals(1, h.requests.size)
        assertTrue(h.sleeps.isEmpty())
        // The pause requested by Graph still applies to following requests.
        assertEquals(7_000L, h.gate.remainingMs())
    }

    @Test
    fun smallRetryBudget_retriesOnceThenFails() = runTest {
        val h = Harness(response(503), response(503))
        try {
            h.caller.get(url, maxRetries = 1) { it.code }
            fail("give-up expected")
        } catch (e: GraphHttpException) {
            assertEquals(503, e.status)
        }
        assertEquals(2, h.requests.size)
        assertEquals(listOf(2_000L), h.sleeps)
        assertEquals(4_000L, h.gate.remainingMs())
    }
}
