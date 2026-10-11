package fr.eva.cloudgallery.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.eva.cloudgallery.data.db.dao.AlbumDao
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.dao.DownloadTaskDao
import fr.eva.cloudgallery.data.db.dao.FavoriteDao
import fr.eva.cloudgallery.data.db.dao.MediaDao
import fr.eva.cloudgallery.data.db.dao.MediaSourceDao
import fr.eva.cloudgallery.data.db.dao.PlaybackPositionDao
import fr.eva.cloudgallery.data.db.dao.SyncCursorDao
import fr.eva.cloudgallery.data.db.dao.UserSettingDao
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.data.db.entity.PlaybackPositionEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.data.db.entity.UserSettingEntity

/**
 * Base locale. Toute évolution du schéma doit incrémenter [version] et fournir une
 * migration (AutoMigration ou Migration manuelle) : les schémas sont exportés dans app/schemas.
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
    ],
    version = 2,
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

    companion object {
        const val NAME = "cloudgallery.db"

        /**
         * v2 : sources masquées (missingSince) au lieu d'être supprimées, et volume MediaStore.
         * Le curseur MediaStore est effacé pour forcer une relecture complète qui renseigne volumeName.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_source ADD COLUMN volumeName TEXT")
                db.execSQL("ALTER TABLE media_source ADD COLUMN missingSince INTEGER")
                db.execSQL("UPDATE sync_cursor SET cursor = NULL WHERE provider = 'mediastore'")
            }
        }
    }
}
