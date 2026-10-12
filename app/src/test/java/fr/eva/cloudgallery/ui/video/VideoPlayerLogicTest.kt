package fr.eva.cloudgallery.ui.video

import androidx.media3.common.PlaybackException
import fr.eva.cloudgallery.domain.model.CloudStreamException
import fr.eva.cloudgallery.domain.model.CloudStreamFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VideoPlayerLogicTest {

    @Test
    fun `codec and container errors are unsupported format`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        ).forEach { code ->
            assertEquals("code $code", PlaybackErrorKind.UNSUPPORTED_FORMAT, VideoPlayerLogic.classifyError(code))
        }
    }

    @Test
    fun `malformed container and decoding failure are corrupted file`() {
        assertEquals(
            PlaybackErrorKind.CORRUPTED_FILE,
            VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            PlaybackErrorKind.CORRUPTED_FILE,
            VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
    }

    @Test
    fun `missing file and permission errors are not accessible`() {
        assertEquals(
            PlaybackErrorKind.FILE_NOT_ACCESSIBLE,
            VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
        assertEquals(
            PlaybackErrorKind.FILE_NOT_ACCESSIBLE,
            VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
        )
    }

    @Test
    fun `other io errors map to io`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        ).forEach { code ->
            assertEquals("code $code", PlaybackErrorKind.IO, VideoPlayerLogic.classifyError(code))
        }
    }

    @Test
    fun `connection failures map to network`() {
        assertEquals(
            PlaybackErrorKind.NETWORK,
            VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            PlaybackErrorKind.NETWORK,
            VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
    }

    @Test
    fun `bad http status after url refresh is server or link`() {
        assertEquals(
            PlaybackErrorKind.SERVER_OR_LINK,
            VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
    }

    @Test
    fun `cloud failure in causes wins over error code`() {
        // Echec du résolveur : Media3 le rapporte en ERROR_CODE_IO_UNSPECIFIED (2000).
        val code = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        assertEquals(PlaybackErrorKind.AUTH_REQUIRED, VideoPlayerLogic.classifyError(code, CloudStreamFailure.AUTH_REQUIRED))
        assertEquals(PlaybackErrorKind.CLOUD_THROTTLED, VideoPlayerLogic.classifyError(code, CloudStreamFailure.THROTTLED))
        assertEquals(PlaybackErrorKind.CLOUD_NOT_FOUND, VideoPlayerLogic.classifyError(code, CloudStreamFailure.NOT_FOUND))
        assertEquals(PlaybackErrorKind.NETWORK, VideoPlayerLogic.classifyError(code, CloudStreamFailure.NETWORK))
        assertEquals(PlaybackErrorKind.SERVER_OR_LINK, VideoPlayerLogic.classifyError(code, CloudStreamFailure.SERVER))
        assertEquals(PlaybackErrorKind.IO, VideoPlayerLogic.classifyError(code, null))
    }

    @Test
    fun `cloud failure is found anywhere in the cause chain`() {
        val root = CloudStreamException(CloudStreamFailure.NOT_FOUND, RuntimeException("graph"))
        val wrapped = IOException("loader", IllegalStateException("source", root))
        assertEquals(
            CloudStreamFailure.NOT_FOUND,
            VideoPlayerLogic.cloudFailureOf(generateSequence<Throwable>(wrapped) { it.cause }),
        )
        assertNull(VideoPlayerLogic.cloudFailureOf(generateSequence<Throwable>(IOException("x")) { it.cause }))
        assertNull(VideoPlayerLogic.cloudFailureOf(emptySequence()))
    }

    @Test
    fun `unavailable source explains why`() {
        assertEquals(
            PlaybackErrorKind.AUTH_REQUIRED,
            VideoPlayerLogic.unavailableError(UnavailableReason.SIGNED_OUT, cloudConfigured = true),
        )
        assertEquals(
            PlaybackErrorKind.CLOUD_ONLY,
            VideoPlayerLogic.unavailableError(UnavailableReason.SIGNED_OUT, cloudConfigured = false),
        )
        assertEquals(
            PlaybackErrorKind.FILE_NOT_ACCESSIBLE,
            VideoPlayerLogic.unavailableError(UnavailableReason.NO_SOURCE, cloudConfigured = true),
        )
    }

    @Test
    fun `unknown codes map to other`() {
        assertEquals(PlaybackErrorKind.OTHER, VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals(PlaybackErrorKind.OTHER, VideoPlayerLogic.classifyError(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertEquals(PlaybackErrorKind.OTHER, VideoPlayerLogic.classifyError(123_456))
    }

    @Test
    fun `error actions`() {
        assertTrue(VideoPlayerLogic.canOpenExternally(PlaybackErrorKind.UNSUPPORTED_FORMAT))
        assertFalse(VideoPlayerLogic.canOpenExternally(PlaybackErrorKind.FILE_NOT_ACCESSIBLE))
        assertFalse(VideoPlayerLogic.canOpenExternally(PlaybackErrorKind.IO))
        assertTrue(VideoPlayerLogic.canRetry(PlaybackErrorKind.IO))
        assertFalse(VideoPlayerLogic.canRetry(PlaybackErrorKind.CLOUD_ONLY))
        assertTrue(VideoPlayerLogic.canRetry(PlaybackErrorKind.NETWORK))
        assertTrue(VideoPlayerLogic.canRetry(PlaybackErrorKind.SERVER_OR_LINK))
        assertTrue(VideoPlayerLogic.canRetry(PlaybackErrorKind.CLOUD_THROTTLED))
        assertFalse(VideoPlayerLogic.canRetry(PlaybackErrorKind.AUTH_REQUIRED))
        assertFalse(VideoPlayerLogic.canRetry(PlaybackErrorKind.CLOUD_NOT_FOUND))
        assertFalse(VideoPlayerLogic.canOpenExternally(PlaybackErrorKind.NETWORK))
        assertFalse(VideoPlayerLogic.canOpenExternally(PlaybackErrorKind.AUTH_REQUIRED))
    }

    @Test
    fun `sign in action only when the account is needed`() {
        assertTrue(VideoPlayerLogic.needsSignIn(PlaybackErrorKind.AUTH_REQUIRED))
        PlaybackErrorKind.entries.filter { it != PlaybackErrorKind.AUTH_REQUIRED }.forEach {
            assertFalse(it.name, VideoPlayerLogic.needsSignIn(it))
        }
        assertTrue(VideoPlayerLogic.isCloudError(PlaybackErrorKind.CLOUD_NOT_FOUND))
        assertFalse(VideoPlayerLogic.isCloudError(PlaybackErrorKind.CORRUPTED_FILE))
    }

    @Test
    fun `resume position ignores start and end`() {
        assertEquals(0L, VideoPlayerLogic.resumePosition(savedMs = 500, durationMs = 60_000))
        assertEquals(30_000L, VideoPlayerLogic.resumePosition(savedMs = 30_000, durationMs = 60_000))
        assertEquals(0L, VideoPlayerLogic.resumePosition(savedMs = 59_000, durationMs = 60_000))
        assertEquals(30_000L, VideoPlayerLogic.resumePosition(savedMs = 30_000, durationMs = 0))
    }

    @Test
    fun `seek target is clamped`() {
        assertEquals(0L, VideoPlayerLogic.seekTarget(5_000, -10_000, 60_000))
        assertEquals(60_000L, VideoPlayerLogic.seekTarget(55_000, 10_000, 60_000))
        assertEquals(25_000L, VideoPlayerLogic.seekTarget(15_000, 10_000, 60_000))
        assertEquals(25_000L, VideoPlayerLogic.seekTarget(15_000, 10_000, 0))
    }

    @Test
    fun `double tap zones are thirds`() {
        assertEquals(-1, VideoPlayerLogic.doubleTapZone(10f, 900f))
        assertEquals(0, VideoPlayerLogic.doubleTapZone(450f, 900f))
        assertEquals(1, VideoPlayerLogic.doubleTapZone(890f, 900f))
        assertEquals(0, VideoPlayerLogic.doubleTapZone(10f, 0f))
    }

    @Test
    fun `speed labels`() {
        assertEquals("1x", VideoPlayerLogic.speedLabel(1f))
        assertEquals("2x", VideoPlayerLogic.speedLabel(2f))
        assertEquals("0.25x", VideoPlayerLogic.speedLabel(0.25f))
        assertEquals("0.5x", VideoPlayerLogic.speedLabel(0.5f))
        assertEquals("1.5x", VideoPlayerLogic.speedLabel(1.5f))
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), VideoPlayerLogic.PLAYBACK_SPEEDS)
    }

    @Test
    fun `aspect ratio`() {
        assertEquals(16f / 9f, VideoPlayerLogic.aspectRatio(1920, 1080)!!, 0.0001f)
        assertNull(VideoPlayerLogic.aspectRatio(0, 1080))
    }
}
