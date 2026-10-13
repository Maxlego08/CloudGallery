package fr.eva.cloudgallery.data.cloud.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * Réponses Microsoft Graph (OneDrive personnel). Tous les champs sont facultatifs : les facettes
 * n'apparaissent que lorsqu'elles s'appliquent, et un élément supprimé n'a que `id` et `deleted`.
 * Les dates restent des chaînes ISO 8601, analysées par la logique de synchronisation.
 */

/** Lecture tolérante : champs inconnus ignorés, valeurs nulles inattendues remplacées par le défaut. */
internal val GraphJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Page de la requête delta : `nextLink` tant qu'il reste des pages, `deltaLink` sur la dernière. */
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

/** Facette vidéo : [duration] en millisecondes. */
@Serializable
data class VideoFacetDto(
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** Parent d'un élément. `path` n'est jamais renvoyé par delta : suivre les éléments par id. */
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
 * Réponse de `GET /me/drive/items/{id}?$select=…,@microsoft.graph.downloadUrl`. Classe ordinaire
 * (pas une data class) : [toString] ne contient jamais l'URL pré-authentifiée.
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

/** Lien web d'un élément (`$select=webUrl`). */
@Serializable
data class WebUrlDto(val webUrl: String? = null)

@Serializable
data class GraphErrorEnvelope(val error: GraphErrorDto? = null)

@Serializable
data class GraphErrorDto(
    val code: String? = null,
    val message: String? = null,
)
