package fr.eva.cloudgallery.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind

/**
 * Logical media item. The `hasLocal`, `hasCloud`, `hasOffline`, `primaryUri`, `cloudItemId` and
 * `cloudCTag` columns are denormalized from `media_source` (visible sources only) so that the
 * grid needs no join.
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
    /** Capture date in ms (EXIF/MediaStore), falling back to the date added. */
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
    /** Content hash (e.g. SHA-1/QuickXorHash) when known. Used for deduplication. */
    val contentHash: String? = null,
    /** Id of the visible OneDrive item (Graph thumbnails), null without a OneDrive source. */
    val cloudItemId: String? = null,
    /** cTag of that OneDrive item: changes with the content (thumbnail cache key). */
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
    /** MediaStore _ID (LOCAL), OneDrive item id (ONEDRIVE) or cache path (OFFLINE_COPY). */
    val externalId: String,
    val uri: String?,
    /** Folder key: "local:<BUCKET_ID>" or "onedrive:<parentId>". */
    val bucketKey: String?,
    val bucketName: String?,
    val relativePath: String?,
    val sizeBytes: Long,
    val dateModified: Long,
    /** MediaStore GENERATION_MODIFIED, or OneDrive cTag. */
    val generation: Long = 0,
    val eTag: String? = null,
    val cTag: String? = null,
    val sha1Hash: String? = null,
    val quickXorHash: String? = null,
    /**
     * Local file whose [quickXorHash] was computed by the app: signature of the file at that time
     * (size, modification date, MediaStore generation, see `localHashSignature`). A signature
     * different from the current state makes the hash stale. Null: never computed (always null
     * for OneDrive, whose hash comes from Graph).
     */
    val hashSignature: String? = null,
    /** MediaStore VOLUME_NAME (e.g. "external_primary" or a removable volume); null if unknown. */
    val volumeName: String? = null,
    /**
     * Instant (ms) since which the source is no longer visible in MediaStore (trash, unmounted
     * volume, partial access, pending re-identification). Null = visible. A missing source is
     * hidden but kept, with its favorites and albums, until its disappearance is confirmed.
     */
    val missingSince: Long? = null,
)

/**
 * Full-text search index (FTS4, unicode61 tokenizer: case and accents ignored) of a logical
 * media item: [rowId] = `media.id`. Kept up to date by the data layer (no foreign key possible
 * on a virtual table): file name, folder names of its visible sources, date labels (year, month
 * spelled out...).
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "media_fts")
data class MediaFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val name: String,
    val folders: String,
    val dates: String,
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
    /** bucketKey for folder albums, null for virtual albums. */
    val sourceKey: String?,
    val relativePath: String?,
    val coverMediaId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sortIndex: Int = 0,
    val isPinned: Boolean = false,
    /** Hidden from the Albums tab (user choice, "Hide albums"). */
    val isHidden: Boolean = false,
    /** User-defined name (takes precedence over the folder name). */
    val customName: String? = null,
    /** The album's media are also hidden from Photos and Videos (v7, with [isHidden]). */
    @ColumnInfo(defaultValue = "0") val hideMedia: Boolean = false,
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
    /** File explicitly kept offline: never deleted by the LRU cleanup. */
    val pinned: Boolean,
    /**
     * Version of the copied content (OneDrive cTag): a copy whose version differs from the
     * OneDrive item's is stale. Null if unknown (never considered up to date).
     */
    val version: String? = null,
)

/**
 * State of an offline copy to download (stored by name, TEXT column: adding a value does not
 * change the schema). PAUSED: stopped by the user (Task Manager, force stop), resumed only on
 * their request.
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
    /** Name of an error code (DownloadError), never free text or a URL. */
    val error: String? = null,
    /** OneDrive item id at enqueue time. */
    val cloudItemId: String? = null,
    /** Content version (cTag) the partial `.part` file belongs to. */
    val cTag: String? = null,
    /** Attempts already made (retries after an error). */
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
)

/**
 * Known OneDrive folder (all folders of the drive, returned by the delta query). Used for the
 * name and path of OneDrive albums and to hide the content of a deleted folder.
 */
@Entity(
    tableName = "cloud_folder",
    indices = [Index(value = ["parentId"])],
)
data class CloudFolderEntity(
    /** OneDrive item id of the folder. */
    @PrimaryKey val itemId: String,
    /** Parent folder (null for the root or if unknown). */
    val parentId: String?,
    val name: String,
    /** Drive root (`root` facet). */
    val isRoot: Boolean,
    val updatedAt: Long,
    /**
     * Deletion marker (v5): folder deleted on OneDrive, kept as long as its subtree contains
     * hidden items (restore from the recycle bin possible).
     * Null: folder present. A marked row is never shown.
     */
    val deletedAt: Long? = null,
)

/**
 * Sync cursor per provider ("mediastore", "onedrive"). For OneDrive: `cursor` = deltaLink,
 * `version` = "<drive.id>|<$select version>" (see OneDriveLogic).
 */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val provider: String,
    /** MediaStore generation or Microsoft Graph deltaLink. */
    val cursor: String?,
    /** MediaStore version (getVersion): if it changes, the generation is no longer comparable. */
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

/** Grid row (projection of `media` + favorite). */
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

/** Album list row with statistics and cover. */
data class AlbumRow(
    val id: Long,
    val name: String,
    val customName: String?,
    val type: AlbumType,
    val sourceKey: String?,
    val relativePath: String?,
    val isPinned: Boolean,
    val isHidden: Boolean,
    val hideMedia: Boolean,
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
