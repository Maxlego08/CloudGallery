package fr.eva.cloudgallery.data.cloud.graph

import fr.eva.cloudgallery.data.cloud.sync.DELTA_SELECT
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRetryLogicTest {

    @Test
    fun classifyGraphStatus_mapsEachFamily() {
        assertEquals(GraphStatusAction.SUCCESS, classifyGraphStatus(200))
        assertEquals(GraphStatusAction.SUCCESS, classifyGraphStatus(204))
        assertEquals(GraphStatusAction.REFRESH_TOKEN, classifyGraphStatus(401))
        assertEquals(GraphStatusAction.FAIL, classifyGraphStatus(403))
        assertEquals(GraphStatusAction.FAIL, classifyGraphStatus(404))
        assertEquals(GraphStatusAction.RESYNC, classifyGraphStatus(410))
        assertEquals(GraphStatusAction.RETRY, classifyGraphStatus(429))
        assertEquals(GraphStatusAction.RETRY, classifyGraphStatus(502))
        assertEquals(GraphStatusAction.RETRY, classifyGraphStatus(503))
        assertEquals(GraphStatusAction.RETRY, classifyGraphStatus(504))
        assertEquals(GraphStatusAction.FAIL, classifyGraphStatus(500))
    }

    @Test
    fun parseRetryAfterMs_acceptsDeltaSecondsOnly() {
        assertEquals(10_000L, parseRetryAfterMs("10"))
        assertEquals(3_000L, parseRetryAfterMs(" 3 "))
        assertEquals(0L, parseRetryAfterMs("0"))
        assertNull(parseRetryAfterMs("-1"))
        assertNull(parseRetryAfterMs("abc"))
        assertNull(parseRetryAfterMs(null))
        assertNull(parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun retryDelayMs_honoursRetryAfter() {
        assertEquals(7_000L, retryDelayMs(attempt = 0, retryAfterMs = 7_000L))
        assertEquals(RETRY_AFTER_MAX_MS, retryDelayMs(attempt = 3, retryAfterMs = RETRY_AFTER_MAX_MS))
    }

    @Test
    fun retryDelayMs_exponentialThenCapped() {
        assertEquals(2_000L, retryDelayMs(0, null))
        assertEquals(4_000L, retryDelayMs(1, null))
        assertEquals(8_000L, retryDelayMs(2, null))
        assertEquals(16_000L, retryDelayMs(3, null))
        assertEquals(32_000L, retryDelayMs(4, null))
        // Plafond : jamais plus de 60 s, même avec beaucoup de tentatives autorisées.
        assertTrue((0 until MAX_RETRIES).all { (retryDelayMs(it, null) ?: 0) <= BACKOFF_CAP_MS })
    }

    @Test
    fun retryDelayMs_givesUp() {
        assertNull(retryDelayMs(MAX_RETRIES, null))
        assertNull(retryDelayMs(MAX_RETRIES, 1_000L))
        assertNull(retryDelayMs(0, RETRY_AFTER_MAX_MS + 1))
    }

    @Test
    fun resyncStartUrl_prefersGraphLocation() {
        val initial = "https://graph.microsoft.com/v1.0/me/drive/root/delta"
        val location = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=abc"
        assertEquals(location, resyncStartUrl(location, initial))
        assertEquals(initial, resyncStartUrl(null, initial))
        assertEquals(initial, resyncStartUrl("  ", initial))
        // Jamais de jeton vers un autre hôte.
        assertEquals(initial, resyncStartUrl("https://evil.example.com/delta", initial))
    }

    @Test
    fun isTrustedGraphUrl_requiresHttpsGraphHost() {
        assertTrue(isTrustedGraphUrl("https://graph.microsoft.com/v1.0/me/drive"))
        assertFalse(isTrustedGraphUrl("http://graph.microsoft.com/v1.0/me/drive"))
        assertFalse(isTrustedGraphUrl("https://graph.microsoft.com.evil.example/v1.0"))
        assertFalse(isTrustedGraphUrl("https://public.dm.files.1drv.com/thumb"))
        assertFalse(isTrustedGraphUrl("not a url"))
    }

    @Test
    fun initialDeltaUrl_selectsAllNeededFields() {
        val url = buildInitialDeltaUrl()
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/delta?\$select=$DELTA_SELECT", url)
        assertTrue(isTrustedGraphUrl(url))
        for (field in listOf("deleted", "root", "parentReference", "file", "photo", "video", "image", "folder", "package")) {
            assertTrue(field, DELTA_SELECT.split(',').contains(field))
        }
    }

    @Test
    fun thumbnailContentUrl_encodesItemIdAndSize() {
        assertEquals(
            "https://graph.microsoft.com/v1.0/me/drive/items/ABC123!456/thumbnails/0/c384x384_crop/content",
            thumbnailContentUrl("ABC123!456", "c384x384_crop"),
        )
        // Identifiant inattendu : encodé comme un segment, jamais interprété comme un chemin.
        val odd = thumbnailContentUrl("a b/../c", "large")
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/items/a%20b%2F..%2Fc/thumbnails/0/large/content", odd)
        assertTrue(isTrustedGraphUrl(odd))
    }

    @Test
    fun errorCodeForStatus_mapsToUserFacingCodes() {
        assertEquals(CloudErrorCode.AUTH_REQUIRED, errorCodeForStatus(401))
        assertEquals(CloudErrorCode.DECLINED, errorCodeForStatus(403))
        assertEquals(CloudErrorCode.THROTTLED, errorCodeForStatus(429))
        assertEquals(CloudErrorCode.SERVER, errorCodeForStatus(500))
        assertEquals(CloudErrorCode.SERVER, errorCodeForStatus(503))
        assertEquals(CloudErrorCode.UNKNOWN, errorCodeForStatus(404))
    }
}
