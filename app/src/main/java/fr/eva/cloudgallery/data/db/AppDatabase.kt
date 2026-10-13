package fr.eva.cloudgallery.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.eva.cloudgallery.data.db.dao.AlbumDao
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.dao.CloudFolderDao
import fr.eva.cloudgallery.data.db.dao.DedupDao
import fr.eva.cloudgallery.data.db.dao.DownloadTaskDao
import fr.eva.cloudgallery.data.db.dao.FavoriteDao
import fr.eva.cloudgallery.data.db.dao.MediaDao
import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.data.db.dao.PlaybackPositionDao
import fr.eva.cloudgallery.data.db.dao.SearchDao
import fr.eva.cloudgallery.data.db.dao.SyncCursorDao
import fr.eva.cloudgallery.data.db.dao.UserSettingDao
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.CloudFolderEntity
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaFtsEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.db.entity.PlaybackPositionEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.data.db.entity.UserSettingEntity

/**
 * Local database. Any schema change must bump [version] and provide a migration
 * (AutoMigration or a manual Migration): schemas are exported to app/schemas.
 */
@Database(
    entities = [
        MediaEntity::class,
        MediaSourceEntity::class,
        AlbumEntity::class,
        AlbumMediaEntity::class,
        FavoriteEntity::class,
        CachedFileEntity::class,
        DownloadTaskEntity::class,
        SyncCursorEntity::class,
        UserSettingEntity::class,
        PlaybackPositionEntity::class,
        CloudFolderEntity::class,
        MediaFtsEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun mediaSourceDao(): MediaSourceDao
    abstract fun albumDao(): AlbumDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun userSettingDao(): UserSettingDao
    abstract fun cachedFileDao(): CachedFileDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun cloudFolderDao(): CloudFolderDao
    abstract fun searchDao(): SearchDao
    abstract fun dedupDao(): DedupDao

    companion object {
        const val NAME = "cloudgallery.db"

        /**
         * v2: sources hidden (missingSince) instead of deleted, and MediaStore volume.
         * The MediaStore cursor is cleared to force a full rescan that fills in volumeName.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_source ADD COLUMN volumeName TEXT")
                db.execSQL("ALTER TABLE media_source ADD COLUMN missingSince INTEGER")
                db.execSQL("UPDATE sync_cursor SET cursor = NULL WHERE provider = 'mediastore'")
            }
        }

        /**
         * v3: OneDrive. OneDrive item id and cTag denormalized into `media` (thumbnails
         * without a join), and OneDrive folder table. No OneDrive data exists in v2: nothing to
         * copy over. The new `media` columns are recomputed by refreshSourceFlags.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN cloudItemId TEXT")
                db.execSQL("ALTER TABLE media ADD COLUMN cloudCTag TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_folder` (`itemId` TEXT NOT NULL, `parentId` TEXT, " +
                        "`name` TEXT NOT NULL, `isRoot` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`itemId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_folder_parentId` ON `cloud_folder` (`parentId`)")
            }
        }

        /**
         * v4: OneDrive content copied to the device (temporary originals, offline copies).
         * Content version (cTag) of cached files, and item id, version and attempt count of
         * downloads (safe resumption of a partial file). Existing rows: null columns (unknown
         * version, never considered up to date) and 0 attempts.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_file ADD COLUMN version TEXT")
                db.execSQL("ALTER TABLE download_task ADD COLUMN cloudItemId TEXT")
                db.execSQL("ALTER TABLE download_task ADD COLUMN cTag TEXT")
                db.execSQL("ALTER TABLE download_task ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5: deletion marker for OneDrive folders (`cloud_folder.deletedAt`). Existing rows:
         * folders present (null). Folders deleted before this version no longer have a row:
         * their hidden items are still detected as orphans.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_folder ADD COLUMN deletedAt INTEGER")
            }
        }

        /**
         * v6: deduplication and search. Signature of the local file when its hash was computed
         * (`media_source.hashSignature`, null: to be computed) and full-text search index
         * `media_fts` (empty: rebuilt by the data layer).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_source ADD COLUMN hashSignature TEXT")
                db.execSQL(MEDIA_FTS_CREATE_SQL)
            }
        }

        /**
         * v7: albums also hidden from Photos and Videos (`album.hideMedia`). Existing albums:
         * unaffected (0); `isHidden` already existed (hidden from Albums only).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album ADD COLUMN hideMedia INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Creation of `media_fts`, identical to the exported schema (app/schemas, version 6). */
        internal const val MEDIA_FTS_CREATE_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS `media_fts` USING FTS4(`name` TEXT NOT NULL, " +
                "`folders` TEXT NOT NULL, `dates` TEXT NOT NULL, tokenize=unicode61)"
    }
}
