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
 * Appels Microsoft Graph utilisés par CloudGallery (lecture seule, OneDrive personnel).
 * Les liens nextLink / deltaLink sont utilisés tels quels, jamais reconstruits.
 */
@Singleton
class GraphApi @Inject constructor(
    private val http: GraphHttpClient,
) {

    /** Profil : nom affiché et adresse. */
    suspend fun me(): MeDto = getJson("$GRAPH_BASE/me?\$select=displayName,mail,userPrincipalName", MeDto.serializer())

    /** Lecteur OneDrive : identifiant stable (liaison du curseur) et quota. */
    suspend fun drive(): DriveDto = getJson("$GRAPH_BASE/me/drive?\$select=id,driveType,quota", DriveDto.serializer())

    /** Une page de la requête delta ([url] : delta initial, nextLink, deltaLink ou URL de reprise). */
    suspend fun deltaPage(url: String): DeltaPage = getJson(url, DeltaPage.serializer())

    /** Delta initial sur tout le lecteur (énumération complète), avec la liste de champs [DELTA_SELECT]. */
    fun initialDeltaUrl(): String = buildInitialDeltaUrl()

    /**
     * Contenu de la miniature [size] (taille standard ou personnalisée `c{L}x{H}[_crop]`) de
     * l'élément [itemId] : `GET /me/drive/items/{id}/thumbnails/0/{size}/content`. Graph répond
     * par une redirection vers une URL de miniature, suivie par OkHttp sans l'en-tête
     * Authorization (autre hôte). [read] reçoit le corps et son type MIME (réponse fermée ensuite).
     * Peu de nouvelles tentatives : une miniature est redemandée au prochain affichage.
     */
    suspend fun <T> thumbnailContent(
        itemId: String,
        size: String,
        read: suspend (body: BufferedSource, mimeType: String?) -> T,
    ): T = http.get(thumbnailContentUrl(itemId, size), accept = ACCEPT_IMAGE, maxRetries = THUMBNAIL_MAX_RETRIES) { response ->
        val body = response.body
        if (body.contentLength() > THUMBNAIL_MAX_BYTES) {
            throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Miniature trop volumineuse"))
        }
        read(body.source(), body.contentType()?.let { "${it.type}/${it.subtype}" })
    }

    /**
     * Taille, version et URL de téléchargement temporaire (`@microsoft.graph.downloadUrl`) d'un
     * élément. L'URL pré-authentifiée vaut un jeton : elle n'est ni journalisée ni persistée, et
     * une réponse illisible est signalée sans son contenu (qui pourrait la contenir).
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
                ?: throw CloudException(CloudErrorCode.SERVER, IllegalStateException("URL de téléchargement absente"))
            CloudDownloadInfo(
                itemId = dto.id,
                sizeBytes = dto.size ?: 0L,
                cTag = dto.cTag,
                eTag = dto.eTag,
                driveId = dto.parentReference?.driveId,
                url = url,
            )
        }

    /** Lien web (`webUrl`) d'un élément ou dossier OneDrive, ouvert dans l'appli OneDrive ou le navigateur. */
    suspend fun webUrl(itemId: String): String {
        val url = "${downloadInfoUrl(itemId)}?\$select=webUrl"
        return getJson(url, WebUrlDto.serializer()).webUrl
            ?.takeIf { it.startsWith("https://") }
            ?: throw CloudException(CloudErrorCode.SERVER, IllegalStateException("Lien web absent"))
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

        /** Demandée juste avant une lecture : quelques nouvelles tentatives seulement. */
        private const val DOWNLOAD_INFO_MAX_RETRIES = 2

        /** Une miniature pèse quelques dizaines à centaines de Ko : au-delà, réponse inattendue. */
        private const val THUMBNAIL_MAX_BYTES = 16L * 1024 * 1024
    }
}

/** URL du contenu d'une miniature ; l'identifiant et la taille sont encodés comme segments de chemin. */
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
 * URL de `GET /me/drive/items/{id}` (l'identifiant est encodé comme un segment de chemin). Sans
 * `$select` : l'annotation `@microsoft.graph.downloadUrl` fait partie de la réponse complète d'un
 * fichier, alors qu'avec `$select` OneDrive personnel peut ne pas la renvoyer (originaux, lecture
 * vidéo et téléchargements tous en échec sur appareil).
 */
internal fun downloadInfoUrl(itemId: String): String =
    "${GraphApi.GRAPH_BASE}/me/drive/items".toHttpUrl().newBuilder()
        .addPathSegment(itemId)
        .build()
        .toString()

/**
 * URL de téléchargement utilisable : HTTPS uniquement (elle transporte une autorisation
 * temporaire), et jamais l'hôte Microsoft Graph (le jeton Bearer n'y est jamais joint, mais un
 * lien Graph n'a rien à faire ici).
 */
internal fun isDownloadUrlAcceptable(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps && !isTrustedGraphUrl(parsed)
}

/**
 * URL du delta initial (tout le lecteur). `$select` et les virgules restent littéraux, comme
 * dans la documentation Microsoft Graph ([DELTA_SELECT] ne contient que des lettres et des virgules).
 */
internal fun buildInitialDeltaUrl(): String =
    "${GraphApi.GRAPH_BASE}/me/drive/root/delta".toHttpUrl().newBuilder()
        .addEncodedQueryParameter("\$select", DELTA_SELECT)
        .build()
        .toString()
