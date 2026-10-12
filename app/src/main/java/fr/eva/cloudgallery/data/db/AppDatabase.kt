package fr.eva.cloudgallery.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.eva.cloudgallery.data.db.dao.AlbumDao
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.dao.CloudFolderDao
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
        CloudFolderEntity::class,
        MediaFtsEntity::class,
    ],
    version = 6,
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

        /**
         * v3 : OneDrive. Identifiant et cTag de l'élément OneDrive dénormalisés dans `media`
         * (miniatures sans jointure), et table des dossiers OneDrive. Aucune donnée OneDrive
         * n'existe en v2 : rien à recopier. Les nouvelles colonnes de `media` sont recalculées
         * par refreshSourceFlags.
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
         * v4 : contenus OneDrive copiés sur l'appareil (originaux temporaires, copies hors ligne).
         * Version du contenu (cTag) des fichiers en cache, et identifiant, version et nombre de
         * tentatives des téléchargements (reprise sûre d'un fichier partiel). Lignes existantes :
         * colonnes nulles (version inconnue, jamais considérée à jour) et 0 tentative.
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
         * v5 : marqueur de suppression des dossiers OneDrive (`cloud_folder.deletedAt`). Lignes
         * existantes : dossiers présents (null). Les dossiers supprimés avant cette version n'ont
         * plus de ligne : leurs éléments masqués restent détectés comme orphelins.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_folder ADD COLUMN deletedAt INTEGER")
            }
        }

        /**
         * v6 : dédoublonnage et recherche. Signature du fichier local au calcul de son empreinte
         * (`media_source.hashSignature`, null : à calculer) et index de recherche plein texte
         * `media_fts` (vide : reconstruit par la couche données).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_source ADD COLUMN hashSignature TEXT")
                db.execSQL(MEDIA_FTS_CREATE_SQL)
            }
        }

        /** Création de `media_fts`, identique au schéma exporté (app/schemas, version 6). */
        internal const val MEDIA_FTS_CREATE_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS `media_fts` USING FTS4(`name` TEXT NOT NULL, " +
                "`folders` TEXT NOT NULL, `dates` TEXT NOT NULL, tokenize=unicode61)"
    }
}
