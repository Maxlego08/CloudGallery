package fr.eva.cloudgallery.data.cloud.transfer

import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudDownloadInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudDownloadUrlProviderTest {

    private var now = 0L
    private val fetched = mutableListOf<String>()
    private var accessAllowed = true

    private fun info(itemId: String, n: Int) =
        CloudDownloadInfo(itemId, sizeBytes = 10, cTag = "c", eTag = "e", driveId = "d", url = "https://cdn.example/$itemId/$n?tempauth=x")

    private fun provider(fetch: (suspend (String) -> CloudDownloadInfo)? = null, dispatcher: CoroutineDispatcher) =
        CloudDownloadUrlProvider(
            fetch = fetch ?: { id ->
                fetched += id
                info(id, fetched.size)
            },
            ensureAccess = { if (!accessAllowed) throw CloudAuthRequiredException() },
            clock = { now },
            io = dispatcher,
        )

    @Test
    fun freshUrl_isReused_untilTtl() = runTest {
        val urls = provider(dispatcher = StandardTestDispatcher(testScheduler))
        val first = urls.get("A")
        now += DOWNLOAD_URL_TTL_MS - 1
        assertEquals(first.url, urls.get("A").url)
        assertEquals(listOf("A"), fetched)
        now += 1
        val second = urls.get("A")
        assertEquals(listOf("A", "A"), fetched)
        assertTrue(first.url != second.url)
    }

    @Test
    fun forceRefresh_andInvalidate_fetchAgain() = runTest {
        val urls = provider(dispatcher = StandardTestDispatcher(testScheduler))
        urls.get("A")
        urls.get("A", forceRefresh = true)
        assertEquals(2, fetched.size)
        urls.invalidate("A")
        urls.get("A")
        assertEquals(3, fetched.size)
        urls.get("A")
        assertEquals(3, fetched.size)
    }

    @Test
    fun accessDenied_neverFetches_evenWithCachedUrl() = runTest {
        val urls = provider(dispatcher = StandardTestDispatcher(testScheduler))
        urls.get("A")
        accessAllowed = false
        try {
            urls.get("A")
            fail("accès refusé attendu")
        } catch (_: CloudAuthRequiredException) {
            // attendu
        }
        assertEquals(1, fetched.size)
    }

    @Test
    fun clear_forgetsEverything() = runTest {
        val urls = provider(dispatcher = StandardTestDispatcher(testScheduler))
        urls.get("A")
        urls.get("B")
        urls.clear()
        urls.get("A")
        urls.get("B")
        assertEquals(listOf("A", "B", "A", "B"), fetched)
    }

    @Test
    fun clearDuringFetch_failsInsteadOfCachingOldAccountUrl() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        lateinit var urls: CloudDownloadUrlProvider
        urls = provider(
            fetch = { id ->
                calls++
                if (calls == 1) gate.await()
                info(id, calls)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val pending = async { runCatching { urls.get("A") } }
        testScheduler.advanceUntilIdle()
        urls.clear()
        gate.complete(Unit)
        val result = pending.await()
        assertTrue(result.exceptionOrNull() is CloudAuthRequiredException)
        // Rien n'a été mis en cache : nouvelle requête au prochain appel.
        urls.get("A")
        assertEquals(2, calls)
    }

    @Test
    fun cache_isBounded_leastRecentlyUsedEvicted() = runTest {
        val urls = provider(dispatcher = StandardTestDispatcher(testScheduler))
        repeat(32) { urls.get("I$it") }
        urls.get("I0") // I0 redevient le plus récent : I1 est le plus ancien.
        urls.get("NEW")
        assertEquals(33, fetched.size)
        urls.get("I0")
        assertEquals(33, fetched.size)
        urls.get("I1")
        assertEquals(34, fetched.size)
    }

    @Test
    fun toString_neverContainsTheUrl() {
        val text = info("A", 1).toString()
        assertFalse(text.contains("http"))
        assertFalse(text.contains("tempauth"))
    }
}
