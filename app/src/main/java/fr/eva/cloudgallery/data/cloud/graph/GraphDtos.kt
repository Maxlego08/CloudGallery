package fr.eva.cloudgallery.data.cloud.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * Microsoft Graph responses (personal OneDrive). All fields are optional: facets only appear
 * when they apply, and a deleted item has only `id` and `deleted`.
 * Dates remain ISO 8601 strings, parsed by the sync logic.
 */

/** Lenient parsing: unknown fields ignored, unexpected nulls replaced by the default. */
internal val GraphJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Delta query page: `nextLink` while pages remain, `deltaLink` on the last one. */
@Serializable
data class DeltaPage(
    val value: List<DriveItemDto> = emptyList(),
    @SerialName("@odata.nextLink") val nextLink: String? = null,
    @SerialName("@odata.deltaLink") val deltaLink: String? = null,
)

@Serializable
data class DriveItemDto(
    val id: String,
    val name: String? = null,
    val size: Long? = null,
    val eTag: String? = null,
    val cTag: String? = null,
    val createdDateTime: String? = null,
    val lastModifiedDateTime: String? = null,
    val fileSystemInfo: FileSystemInfoDto? = null,
    val file: FileFacetDto? = null,
    val folder: FolderFacetDto? = null,
    @SerialName("package") val pkg: JsonObject? = null,
    val image: ImageFacetDto? = null,
    val photo: PhotoFacetDto? = null,
    val video: VideoFacetDto? = null,
    val parentReference: ItemReferenceDto? = null,
    val deleted: DeletedFacetDto? = null,
    val root: JsonObject? = null,
    val remoteItem: JsonObject? = null,
)

@Serializable
data class FileSystemInfoDto(
    val createdDateTime: String? = null,
    val lastModifiedDateTime: String? = null,
)

@Serializable
data class FileFacetDto(
    val mimeType: String? = null,
    val hashes: HashesDto? = null,
)

@Serializable
data class HashesDto(
    val quickXorHash: String? = null,
    val sha1Hash: String? = null,
)

@Serializable
data class FolderFacetDto(val childCount: Int? = null)

@Serializable
data class PhotoFacetDto(val takenDateTime: String? = null)

@Serializable
data class ImageFacetDto(
    val width: Int? = null,
    val height: Int? = null,
)

/** Video facet: [duration] in milliseconds. */
@Serializable
data class VideoFacetDto(
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** Parent of an item. `path` is never returned by delta: track items by id. */
@Serializable
data class ItemReferenceDto(
    val id: String? = null,
    val driveId: String? = null,
    val driveType: String? = null,
    val name: String? = null,
)

@Serializable
data class DeletedFacetDto(val state: String? = null)

@Serializable
data class MeDto(
    val displayName: String? = null,
    val mail: String? = null,
    val userPrincipalName: String? = null,
)

@Serializable
data class DriveDto(
    val id: String? = null,
    val driveType: String? = null,
    val quota: QuotaDto? = null,
)

@Serializable
data class QuotaDto(
    val total: Long? = null,
    val used: Long? = null,
    val remaining: Long? = null,
    val state: String? = null,
)

/**
 * Response of `GET /me/drive/items/{id}?$select=…,@microsoft.graph.downloadUrl`. Plain class
 * (not a data class): [toString] never contains the pre-authenticated URL.
 */
@Serializable
class DownloadInfoDto(
    val id: String,
    val size: Long? = null,
    val cTag: String? = null,
    val eTag: String? = null,
    val parentReference: ItemReferenceDto? = null,
    @SerialName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null,
) {
    override fun toString(): String = "DownloadInfoDto(id=$id, size=$size)"
}

/** Web link of an item (`$select=webUrl`). */
@Serializable
data class WebUrlDto(val webUrl: String? = null)

@Serializable
data class GraphErrorEnvelope(val error: GraphErrorDto? = null)

@Serializable
data class GraphErrorDto(
    val code: String? = null,
    val message: String? = null,
)
