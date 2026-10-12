package fr.eva.cloudgallery.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migrations validées contre les schémas exportés (app/schemas, assets androidTest). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun SupportSQLiteDatabase.insertLocalMedia() {
        execSQL(
            "INSERT INTO media (id, mediaType, displayName, mimeType, dateTaken, dateAdded, dateModified, width, height, " +
                "durationMs, sizeBytes, orientation, primaryUri, hasLocal, hasCloud, hasOffline, contentHash) VALUES " +
                "(1, 'IMAGE', 'IMG_1.jpg', 'image/jpeg', 10, 10, 10, 100, 80, 0, 5, 0, " +
                "'content://media/external/images/media/7', 1, 0, 0, NULL)",
        )
        execSQL("INSERT INTO favorite (mediaId, addedAt) VALUES (1, 99)")
    }

    private fun SupportSQLiteDatabase.assertMigratedTo3() {
        query("SELECT displayName, primaryUri, hasLocal, cloudItemId, cloudCTag FROM media WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("IMG_1.jpg", c.getString(0))
            assertEquals("content://media/external/images/media/7", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertTrue(c.isNull(3))
            assertTrue(c.isNull(4))
        }
        query("SELECT COUNT(*) FROM media_source WHERE mediaId = 1 AND kind = 'LOCAL'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        query("SELECT COUNT(*) FROM favorite WHERE mediaId = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // Nouvelle table vide, utilisable.
        execSQL("INSERT INTO cloud_folder (itemId, parentId, name, isRoot, updatedAt) VALUES ('ROOT', NULL, 'root', 1, 0)")
        query("SELECT name FROM cloud_folder WHERE itemId = 'ROOT'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("root", c.getString(0))
        }
    }

    @Test
    fun migrate2To3_keepsDataAndAddsCloudColumns() {
        helper.createDatabase(TEST_DB, 2).apply {
            insertLocalMedia()
            execSQL(
                "INSERT INTO media_source (id, mediaId, kind, externalId, uri, bucketKey, bucketName, relativePath, " +
                    "sizeBytes, dateModified, generation, volumeName, missingSince) VALUES " +
                    "(1, 1, 'LOCAL', 'image:7', 'content://media/external/images/media/7', 'local:3', 'Camera', " +
                    "'DCIM/Camera/', 5, 10, 7, 'external_primary', NULL)",
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            db.assertMigratedTo3()
            db.query("SELECT volumeName FROM media_source WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("external_primary", c.getString(0))
            }
        }
    }

    @Test
    fun migrate1To3_chainsMigrations() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertLocalMedia()
            execSQL(
                "INSERT INTO media_source (id, mediaId, kind, externalId, uri, bucketKey, bucketName, relativePath, " +
                    "sizeBytes, dateModified, generation) VALUES " +
                    "(1, 1, 'LOCAL', 'image:7', 'content://media/external/images/media/7', 'local:3', 'Camera', " +
                    "'DCIM/Camera/', 5, 10, 7)",
            )
            execSQL("INSERT INTO sync_cursor (provider, cursor, version, lastSyncAt, lastFullSyncAt) VALUES ('mediastore', '42', 'v', 1, 1)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).use { db ->
            db.assertMigratedTo3()
            db.query("SELECT missingSince FROM media_source WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
            // MIGRATION_1_2 force une relecture complète de MediaStore.
            db.query("SELECT cursor FROM sync_cursor WHERE provider = 'mediastore'").use { c ->
                assertTrue(c.moveToFirst())
                assertNull(c.getString(0))
            }
        }
    }

    /** Ligne de cache et tâche de téléchargement telles qu'en v3 (avant les colonnes de version). */
    private fun SupportSQLiteDatabase.insertV3CacheRows() {
        execSQL(
            "INSERT INTO cached_file (id, mediaId, sourceId, kind, path, sizeBytes, createdAt, lastAccessAt, pinned) VALUES " +
                "(1, 1, NULL, 'ORIGINAL', 'a_b.jpg', 5, 1, 2, 0)",
        )
        execSQL(
            "INSERT INTO download_task (id, mediaId, sourceId, state, bytesDownloaded, bytesTotal, keepOffline, createdAt, updatedAt, error) VALUES " +
                "(1, 1, NULL, 'QUEUED', 0, 5, 1, 1, 1, NULL)",
        )
    }

    private fun SupportSQLiteDatabase.assertMigratedTo4() {
        query("SELECT path, sizeBytes, pinned, version FROM cached_file WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("a_b.jpg", c.getString(0))
            assertEquals(5L, c.getLong(1))
            assertEquals(0, c.getInt(2))
            // Version inconnue : jamais considérée à jour.
            assertTrue(c.isNull(3))
        }
        query("SELECT state, bytesTotal, cloudItemId, cTag, attempts FROM download_task WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("QUEUED", c.getString(0))
            assertEquals(5L, c.getLong(1))
            assertTrue(c.isNull(2))
            assertTrue(c.isNull(3))
            assertEquals(0, c.getInt(4))
        }
        // Valeur par défaut de la nouvelle colonne pour une insertion qui ne la précise pas.
        execSQL(
            "INSERT INTO download_task (id, mediaId, sourceId, state, bytesDownloaded, bytesTotal, keepOffline, createdAt, updatedAt) VALUES " +
                "(2, 1, NULL, 'QUEUED', 0, 5, 1, 1, 1)",
        )
        query("SELECT attempts FROM download_task WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun migrate3To4_keepsRowsAndAddsVersionColumns() {
        helper.createDatabase(TEST_DB, 3).apply {
            insertLocalMedia()
            insertV3CacheRows()
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4).use { db ->
            db.assertMigratedTo4()
            db.query("SELECT displayName, hasLocal FROM media WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("IMG_1.jpg", c.getString(0))
                assertEquals(1, c.getInt(1))
            }
        }
    }

    @Test
    fun migrate2To4_chainsMigrations() {
        helper.createDatabase(TEST_DB, 2).apply {
            insertLocalMedia()
            execSQL(
                "INSERT INTO media_source (id, mediaId, kind, externalId, uri, bucketKey, bucketName, relativePath, " +
                    "sizeBytes, dateModified, generation, volumeName, missingSince) VALUES " +
                    "(1, 1, 'LOCAL', 'image:7', 'content://media/external/images/media/7', 'local:3', 'Camera', " +
                    "'DCIM/Camera/', 5, 10, 7, 'external_primary', NULL)",
            )
            insertV3CacheRows()
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).use { db ->
            db.assertMigratedTo3()
            db.assertMigratedTo4()
        }
    }

    @Test
    fun migrate1To4_chainsMigrations() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertLocalMedia()
            execSQL(
                "INSERT INTO media_source (id, mediaId, kind, externalId, uri, bucketKey, bucketName, relativePath, " +
                    "sizeBytes, dateModified, generation) VALUES " +
                    "(1, 1, 'LOCAL', 'image:7', 'content://media/external/images/media/7', 'local:3', 'Camera', " +
                    "'DCIM/Camera/', 5, 10, 7)",
            )
            insertV3CacheRows()
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        ).use { db ->
            db.assertMigratedTo3()
            db.assertMigratedTo4()
        }
    }

    /** Dossier OneDrive tel qu'en v4 (avant le marqueur de suppression). */
    private fun SupportSQLiteDatabase.insertV4Folder() {
        execSQL("INSERT INTO cloud_folder (itemId, parentId, name, isRoot, updatedAt) VALUES ('PH', 'ROOT', 'Photos', 0, 7)")
    }

    private fun SupportSQLiteDatabase.assertMigratedTo5() {
        // Dossier existant : présent (aucun marqueur).
        query("SELECT name, parentId, deletedAt FROM cloud_folder WHERE itemId = 'PH'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Photos", c.getString(0))
            assertEquals("ROOT", c.getString(1))
            assertTrue(c.isNull(2))
        }
        execSQL("UPDATE cloud_folder SET deletedAt = 42 WHERE itemId = 'PH'")
        query("SELECT deletedAt FROM cloud_folder WHERE itemId = 'PH'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
        }
    }

    @Test
    fun migrate4To5_addsFolderTombstoneColumn() {
        helper.createDatabase(TEST_DB, 4).apply {
            insertLocalMedia()
            insertV4Folder()
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5).use { db ->
            db.assertMigratedTo5()
            db.query("SELECT displayName FROM media WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("IMG_1.jpg", c.getString(0))
            }
        }
    }

    @Test
    fun migrate3To5_chainsMigrations() {
        helper.createDatabase(TEST_DB, 3).apply {
            insertLocalMedia()
            insertV3CacheRows()
            insertV4Folder()
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).use { db ->
            db.assertMigratedTo4()
            db.assertMigratedTo5()
        }
    }

    @Test
    fun migrate2To5_chainsMigrations() {
        helper.createDatabase(TEST_DB, 2).apply {
            insertLocalMedia()
            insertV3CacheRows()
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        ).use { db ->
            db.assertMigratedTo3()
            db.assertMigratedTo4()
            db.insertV4Folder()
            db.assertMigratedTo5()
        }
    }

    @Test
    fun migrate1To5_chainsMigrations() {
        helper.createDatabase(TEST_DB, 1).apply {
            insertLocalMedia()
            insertV3CacheRows()
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
        ).use { db ->
            db.assertMigratedTo3()
            db.assertMigratedTo4()
            db.insertV4Folder()
            db.assertMigratedTo5()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
