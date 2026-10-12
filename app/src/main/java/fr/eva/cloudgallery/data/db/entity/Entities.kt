package fr.eva.cloudgallery.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind

/**
 * Média logique. Les colonnes `hasLocal`, `hasCloud`, `hasOffline`, `primaryUri`, `cloudItemId`
 * et `cloudCTag` sont dénormalisées depuis `media_source` (sources visibles uniquement) pour que
 * la grille n'ait aucune jointure à faire.
 */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["dateTaken", "id"]),
        Index(value = ["mediaType", "dateTaken"]),
        Index(value = ["displayName"]),
        Index(value = ["contentHash"]),
    ],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaType: MediaType,
    val displayName: String,
    val mimeType: String?,
    /** Date de prise de vue en ms (EXIF/MediaStore), à défaut date d'ajout. */
    val dateTaken: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val orientation: Int,
    val primaryUri: String?,
    val hasLocal: Boolean,
    val hasCloud: Boolean,
    val hasOffline: Boolean,
    /** Empreinte de contenu (ex. SHA-1/QuickXorHash) lorsqu'elle est connue. Sert au dédoublonnage. */
    val contentHash: String? = null,
    /** Identifiant de l'élément OneDrive visible (miniatures Graph), null sans source OneDrive. */
    val cloudItemId: String? = null,
    /** cTag de cet élément OneDrive : change avec le contenu (clé de cache des miniatures). */
    val cloudCTag: String? = null,
)

@Entity(
    tableName = "media_source",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["kind", "externalId"], unique = true),
        Index(value = ["mediaId"]),
        Index(value = ["bucketKey"]),
    ],
)
data class MediaSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val kind: SourceKind,
    /** _ID MediaStore (LOCAL), id d'élément OneDrive (ONEDRIVE) ou chemin de cache (OFFLINE_COPY). */
    val externalId: String,
    val uri: String?,
    /** Clé de dossier : "local:<BUCKET_ID>" ou "onedrive:<parentId>". */
    val bucketKey: String?,
    val bucketName: String?,
    val relativePath: String?,
    val sizeBytes: Long,
    val dateModified: Long,
    /** GENERATION_MODIFIED MediaStore, ou cTag OneDrive. */
    val generation: Long = 0,
    val eTag: String? = null,
    val cTag: String? = null,
    val sha1Hash: String? = null,
    val quickXorHash: String? = null,
    /** VOLUME_NAME MediaStore (ex. « external_primary » ou volume amovible) ; null si inconnu. */
    val volumeName: String? = null,
    /**
     * Instant (ms) depuis lequel la source n'est plus visible dans MediaStore (corbeille, volume
     * démonté, accès partiel, ré-identification en attente). Null = visible. Une source absente
     * est masquée mais conservée, avec favoris et albums, jusqu'à confirmation de sa disparition.
     */
    val missingSince: Long? = null,
)

@Entity(
    tableName = "album",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index(value = ["type"]),
    ],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AlbumType,
    /** bucketKey pour les albums-dossiers, null pour les albums virtuels. */
    val sourceKey: String?,
    val relativePath: String?,
    val coverMediaId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sortIndex: Int = 0,
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    /** Nom défini par l'utilisateur (prioritaire sur le nom de dossier). */
    val customName: String? = null,
)

@Entity(
    tableName = "album_media",
    primaryKeys = ["albumId", "mediaId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["mediaId"])],
)
data class AlbumMediaEntity(
    val albumId: Long,
    val mediaId: Long,
    val addedAt: Long,
)

@Entity(
    tableName = "favorite",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FavoriteEntity(
    @PrimaryKey val mediaId: Long,
    val addedAt: Long,
)

enum class CacheKind { THUMBNAIL, PREVIEW, ORIGINAL, OFFLINE }

@Entity(
    tableName = "cached_file",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["mediaId", "kind"], unique = true),
        Index(value = ["lastAccessAt"]),
    ],
)
data class CachedFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val sourceId: Long?,
    val kind: CacheKind,
    val path: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val lastAccessAt: Long,
    /** Fichier conservé explicitement hors connexion : jamais supprimé par le nettoyage LRU. */
    val pinned: Boolean,
    /**
     * Version du contenu copié (cTag OneDrive) : une copie dont la version diffère de celle de
     * l'élément OneDrive est périmée. Null si inconnue (jamais considérée comme à jour).
     */
    val version: String? = null,
)

/**
 * Etat d'une copie hors ligne à télécharger (stocké par son nom, colonne TEXT : ajouter une valeur
 * ne change pas le schéma). PAUSED : arrêtée par l'utilisateur (Gestionnaire de tâches, arrêt
 * forcé), reprise uniquement à sa demande.
 */
enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELLED, PAUSED }

@Entity(
    tableName = "download_task",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["mediaId"]), Index(value = ["state"])],
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val sourceId: Long?,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val keepOffline: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Nom d'un code d'erreur (DownloadError), jamais de texte libre ni d'URL. */
    val error: String? = null,
    /** Identifiant de l'élément OneDrive au moment de la mise en file. */
    val cloudItemId: String? = null,
    /** Version du contenu (cTag) à laquelle appartient le fichier partiel `.part`. */
    val cTag: String? = null,
    /** Tentatives déjà faites (reprises après erreur). */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
)

/**
 * Dossier OneDrive connu (tous les dossiers du lecteur, renvoyés par la requête delta). Sert au
 * nom et au chemin des albums OneDrive et au masquage du contenu d'un dossier supprimé.
 */
@Entity(
    tableName = "cloud_folder",
    indices = [Index(value = ["parentId"])],
)
data class CloudFolderEntity(
    /** Identifiant d'élément OneDrive du dossier. */
    @PrimaryKey val itemId: String,
    /** Dossier parent (null pour la racine ou si inconnu). */
    val parentId: String?,
    val name: String,
    /** Racine du lecteur (facette `root`). */
    val isRoot: Boolean,
    val updatedAt: Long,
    /**
     * Marqueur de suppression (v5) : dossier supprimé sur OneDrive, gardé tant que son
     * sous-arbre contient des éléments masqués (restauration possible depuis la corbeille).
     * Null : dossier présent. Une ligne marquée n'est jamais montrée.
     */
    val deletedAt: Long? = null,
)

/**
 * Curseur de synchronisation par fournisseur ("mediastore", "onedrive"). Pour OneDrive :
 * `cursor` = deltaLink, `version` = « <drive.id>|<version de $select> » (voir OneDriveLogic).
 */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val provider: String,
    /** Génération MediaStore ou deltaLink Microsoft Graph. */
    val cursor: String?,
    /** Version MediaStore (getVersion) : si elle change, la génération n'est plus comparable. */
    val version: String?,
    val lastSyncAt: Long,
    val lastFullSyncAt: Long,
    val lastError: String? = null,
)

@Entity(tableName = "user_settings")
data class UserSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(
    tableName = "playback_position",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaybackPositionEntity(
    @PrimaryKey val mediaId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** Ligne de grille (projection de `media` + favori). */
data class MediaRow(
    val id: Long,
    val mediaType: MediaType,
    val mimeType: String?,
    val dateTaken: Long,
    val dateModified: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val primaryUri: String?,
    val hasLocal: Boolean,
    val hasCloud: Boolean,
    val hasOffline: Boolean,
    @ColumnInfo(name = "isFavorite") val isFavorite: Boolean,
    val cloudItemId: String?,
    val cloudCTag: String?,
)

/** Ligne de liste d'albums avec statistiques et couverture. */
data class AlbumRow(
    val id: Long,
    val name: String,
    val customName: String?,
    val type: AlbumType,
    val sourceKey: String?,
    val relativePath: String?,
    val isPinned: Boolean,
    val sortIndex: Int,
    val mediaCount: Int,
    val coverMediaId: Long?,
    val coverUri: String?,
    val coverType: MediaType?,
    val coverDateModified: Long?,
    val coverCloudItemId: String?,
    val coverCloudCTag: String?,
    val latestDate: Long?,
)
