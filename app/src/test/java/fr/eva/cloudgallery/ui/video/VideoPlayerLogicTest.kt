package fr.eva.cloudgallery.ui.video

import androidx.media3.common.PlaybackException
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSourceInfo
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        ).forEach { code ->
            assertEquals("code $code", PlaybackErrorKind.IO, VideoPlayerLogic.classifyError(code))
        }
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

    @Test
    fun `cloud only media has no playback source yet`() {
        val details = MediaDetails(
            id = 1, type = MediaType.VIDEO, displayName = "a.mp4", mimeType = "video/mp4",
            dateTaken = 0, dateAdded = 0, dateModified = 0, width = 1920, height = 1080,
            durationMs = 1000, sizeBytes = 1, orientation = 0, uri = null, isFavorite = false,
            sources = emptyList(),
        )
        assertEquals(PlaybackSource.Unavailable, PlaybackSourceResolver.resolve(details))
    }

    @Test
    fun `onedrive source alone is never played`() {
        val details = MediaDetails(
            id = 1, type = MediaType.VIDEO, displayName = "a.mp4", mimeType = "video/mp4",
            dateTaken = 0, dateAdded = 0, dateModified = 0, width = 1920, height = 1080,
            durationMs = 1000, sizeBytes = 1, orientation = 0, uri = null, isFavorite = false,
            sources = listOf(
                MediaSourceInfo(
                    id = 1, kind = SourceKind.ONEDRIVE, externalId = "ITEM!1", uri = null, bucketKey = "onedrive:P",
                    bucketName = "Photos", relativePath = "OneDrive/Photos/", sizeBytes = 1, dateModified = 0,
                ),
            ),
            cloudThumb = CloudThumbRef("ITEM!1", "ctag"),
        )
        assertTrue(details.hasCloud)
        assertFalse(details.hasLocal)
        assertEquals(PlaybackSource.Unavailable, PlaybackSourceResolver.resolve(details))
    }
}
