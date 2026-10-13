package fr.eva.cloudgallery.data.cloud.graph

import fr.eva.cloudgallery.data.cloud.sync.DELTA_SELECT
import fr.eva.cloudgallery.domain.model.CloudDownloadInfo
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Microsoft Graph calls used by CloudGallery (read-only, personal OneDrive).
 * nextLink / deltaLink links are used as is, never rebuilt.
 */
@Singleton
class GraphApi @Inject constructor(
    private val http: GraphHttpClient,
) {

    /** Profile: display name and address. */
    suspend fun me(): MeDto = getJson("$GRAPH_BASE/me?\$select=displayName,mail,userPrincipalName", MeDto.serializer())

    /** OneDrive drive: stable identifier (cursor binding) and quota. */
    suspend fun drive(): DriveDto = getJson("$GRAPH_BASE/me/drive?\$select=id,driveType,quota", DriveDto.serializer())

    /** One page of the delta query ([url]: initial delta, nextLink, deltaLink or resume URL). */
    suspend fun deltaPage(url: String): DeltaPage = getJson(url, DeltaPage.serializer())

    /** Initial delta over the whole drive (full enumeration), with the [DELTA_SELECT] field list. */
    fun initialDeltaUrl(): String = buildInitialDeltaUrl()

    /**
     * Content of thumbnail [size] (standard size or custom `c{W}x{H}[_crop]`) of item [itemId]:
     * `GET /me/drive/items/{id}/thumbnails/0/{size}/content`. Graph responds with a redirect to a
     * thumbnail URL, which OkHttp follows without the Authorization header (different host).
     * [read] receives the body and its MIME type (the response is closed afterwards).
     * Few retries: a thumbnail is requested again the next time it is displayed.
     */
    suspend fun <T> thumbnailContent(
        itemId: String,
        size: String,
        read: suspend (body: BufferedSource, mimeType: String?) -> T,
    ): T = http.get(thumbnailContentUrl(itemId, size), accept = ACCEPT_IMAGE, maxRetries = THUMBNAIL_MAX_RETRIES) { response ->
        val body = response.body
        if (body.contentLength() > THUMBNAIL_MAX_BYTES) {
            throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Thumbnail too large"))
        }
        read(body.source(), body.contentType()?.let { "${it.type}/${it.subtype}" })
    }

    /**
     * Size, version and temporary download URL (`@microsoft.graph.downloadUrl`) of an item. The
     * pre-authenticated URL is as good as a token: it is neither logged nor persisted, and an
     * unreadable response is reported without its content (which might contain it).
     */
    suspend fun downloadInfo(itemId: String): CloudDownloadInfo =
        http.get(downloadInfoUrl(itemId), maxRetries = DOWNLOAD_INFO_MAX_RETRIES) { response ->
            val dto = try {
                GraphJson.decodeFromString(DownloadInfoDto.serializer(), response.body.string())
            } catch (_: SerializationException) {
                throw CloudException(CloudErrorCode.SERVER)
            } catch (_: IllegalArgumentException) {
                throw CloudException(CloudErrorCode.SERVER)
            }
            val url = dto.downloadUrl?.takeIf { isDownloadUrlAcceptable(it) }
                ?: throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Missing download URL"))
            CloudDownloadInfo(
                itemId = dto.id,
                sizeBytes = dto.size ?: 0L,
                cTag = dto.cTag,
                eTag = dto.eTag,
                driveId = dto.parentReference?.driveId,
                url = url,
            )
        }

    /** Web link (`webUrl`) of a OneDrive item or folder, opened in the OneDrive app or the browser. */
    suspend fun webUrl(itemId: String): String {
        val url = "${downloadInfoUrl(itemId)}?\$select=webUrl"
        return getJson(url, WebUrlDto.serializer()).webUrl
            ?.takeIf { it.startsWith("https://") }
            ?: throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Missing web link"))
    }

    private suspend fun <T> getJson(url: String, deserializer: DeserializationStrategy<T>): T =
        http.get(url) { response ->
            val body = response.body.string()
            try {
                GraphJson.decodeFromString(deserializer, body)
            } catch (e: SerializationException) {
                throw CloudException(CloudErrorCode.SERVER, e)
            } catch (e: IllegalArgumentException) {
                throw CloudException(CloudErrorCode.SERVER, e)
            }
        }

    companion object {
        const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
        private const val ACCEPT_IMAGE = "image/*"
        private const val THUMBNAIL_MAX_RETRIES = 1

        /** Requested right before playback: only a few retries. */
        private const val DOWNLOAD_INFO_MAX_RETRIES = 2

        /** A thumbnail weighs tens to hundreds of KB: anything beyond is an unexpected response. */
        private const val THUMBNAIL_MAX_BYTES = 16L * 1024 * 1024
    }
}

/** URL of a thumbnail's content; the identifier and size are encoded as path segments. */
internal fun thumbnailContentUrl(itemId: String, size: String): String =
    "${GraphApi.GRAPH_BASE}/me/drive/items".toHttpUrl().newBuilder()
        .addPathSegment(itemId)
        .addPathSegment("thumbnails")
        .addPathSegment("0")
        .addPathSegment(size)
        .addPathSegment("content")
        .build()
        .toString()

/**
 * URL of `GET /me/drive/items/{id}` (the identifier is encoded as a path segment). No
 * `$select`: the `@microsoft.graph.downloadUrl` annotation is part of a file's full response,
 * whereas with `$select` personal OneDrive may not return it (originals, video playback and
 * downloads all failing on device).
 */
internal fun downloadInfoUrl(itemId: String): String =
    "${GraphApi.GRAPH_BASE}/me/drive/items".toHttpUrl().newBuilder()
        .addPathSegment(itemId)
        .build()
        .toString()

/**
 * Usable download URL: HTTPS only (it carries a temporary authorization), and never the
 * Microsoft Graph host (the Bearer token is never attached to it, but a Graph link has no
 * business here).
 */
internal fun isDownloadUrlAcceptable(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps && !isTrustedGraphUrl(parsed)
}

/**
 * Initial delta URL (whole drive). `$select` and the commas stay literal, as in the Microsoft
 * Graph documentation ([DELTA_SELECT] contains only letters and commas).
 */
internal fun buildInitialDeltaUrl(): String =
    "${GraphApi.GRAPH_BASE}/me/drive/root/delta".toHttpUrl().newBuilder()
        .addEncodedQueryParameter("\$select", DELTA_SELECT)
        .build()
        .toString()
