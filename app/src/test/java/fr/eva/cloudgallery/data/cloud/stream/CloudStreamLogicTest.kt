package fr.eva.cloudgallery.data.cloud.stream

import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.data.cloud.graph.GraphResyncRequiredException
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudStreamException
import fr.eva.cloudgallery.domain.model.CloudStreamFailure
import fr.eva.cloudgallery.domain.model.CloudThrottledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class CloudStreamLogicTest {

    @Test
    fun failureMapping_fromGraphAndMsalErrors() {
        assertEquals(CloudStreamFailure.AUTH_REQUIRED, cloudStreamFailureFor(CloudAuthRequiredException()))
        assertEquals(CloudStreamFailure.THROTTLED, cloudStreamFailureFor(CloudThrottledException(30_000)))
        assertEquals(CloudStreamFailure.NOT_FOUND, cloudStreamFailureFor(GraphHttpException(404, "itemNotFound")))
        assertEquals(CloudStreamFailure.NOT_FOUND, cloudStreamFailureFor(GraphResyncRequiredException(null)))
        assertEquals(CloudStreamFailure.SERVER, cloudStreamFailureFor(GraphHttpException(500, null)))
        assertEquals(CloudStreamFailure.SERVER, cloudStreamFailureFor(GraphHttpException(403, "accessDenied")))
        assertEquals(CloudStreamFailure.NETWORK, cloudStreamFailureFor(CloudException(CloudErrorCode.NETWORK, IOException())))
        assertEquals(CloudStreamFailure.AUTH_REQUIRED, cloudStreamFailureFor(CloudException(CloudErrorCode.AUTH_REQUIRED)))
        assertEquals(CloudStreamFailure.SERVER, cloudStreamFailureFor(CloudException(CloudErrorCode.CONFIG)))
        assertEquals(CloudStreamFailure.SERVER, cloudStreamFailureFor(CloudException(CloudErrorCode.SERVER)))
        assertEquals(CloudStreamFailure.NETWORK, cloudStreamFailureFor(SocketTimeoutException()))
        assertEquals(CloudStreamFailure.SERVER, cloudStreamFailureFor(IllegalStateException()))
    }

    @Test
    fun retryDecision_expiredUrlStatusesInvalidate() {
        listOf(401, 403, 404, 410).forEach {
            assertEquals("$it", StreamRetry.INVALIDATE_URL_THEN_DEFAULT, streamRetryDecision(it, null))
        }
        assertEquals(StreamRetry.DEFAULT, streamRetryDecision(500, null))
        assertEquals(StreamRetry.DEFAULT, streamRetryDecision(null, null))
    }

    @Test
    fun retryDecision_cloudFailuresWinOverStatus() {
        assertEquals(StreamRetry.FATAL, streamRetryDecision(null, CloudStreamFailure.AUTH_REQUIRED))
        assertEquals(StreamRetry.FATAL, streamRetryDecision(null, CloudStreamFailure.NOT_FOUND))
        assertEquals(StreamRetry.FATAL, streamRetryDecision(403, CloudStreamFailure.THROTTLED))
        assertEquals(StreamRetry.DEFAULT, streamRetryDecision(null, CloudStreamFailure.NETWORK))
        assertEquals(StreamRetry.DEFAULT, streamRetryDecision(null, CloudStreamFailure.SERVER))
    }

    @Test
    fun causeChain_isBoundedAndFindsWrappedFailure() {
        val failure = CloudStreamException(CloudStreamFailure.NETWORK)
        val chain = causeChain(IOException("a", IllegalStateException("b", failure))).toList()
        assertEquals(3, chain.size)
        assertTrue(chain.last() === failure)
        assertTrue(causeChain(null).none())

        // Chaîne cyclique : jamais de boucle infinie.
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertEquals(16, causeChain(a).count())
    }

    @Test
    fun streamException_messageHasNoUrl() {
        val e = CloudStreamException(CloudStreamFailure.SERVER, RuntimeException("x"))
        assertFalse(e.message!!.contains("http"))
        assertTrue(e.message!!.contains("SERVER"))
    }

    @Test
    fun stableStreamUri_isNotTheDownloadUrl() {
        assertEquals("onedrive://item/ABC123%21456", streamUriString("ABC123!456"))
        assertEquals("onedrive://item/a+b%2F..%2Fc", streamUriString("a b/../c"))
    }

    @Test
    fun redactUrls_removesEveryUrlAndTempauth() {
        val line = "Playback error\n  androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException: " +
            "Response code: 403 https://abc.files.1drv.com/y4mXYZ/clip.mp4?download&psid=1&tempauth=v1.SECRET_TOKEN (more)"
        val redacted = redactUrls(line)
        assertFalse(redacted.contains("1drv.com"))
        assertFalse(redacted.contains("SECRET_TOKEN"))
        assertTrue(redacted.contains("Response code: 403 <url> (more)"))

        assertEquals("x tempauth=<redacted>&a=1", redactUrls("x tempauth=abc.def&a=1"))
        assertEquals("<url>", redactUrls("HTTP://Example.com/a?b"))
        assertEquals("no url here: onedrive://item/A", redactUrls("no url here: onedrive://item/A"))
    }
}
