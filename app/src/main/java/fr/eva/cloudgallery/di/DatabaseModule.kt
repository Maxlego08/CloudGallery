package fr.eva.cloudgallery.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.eva.cloudgallery.data.db.AppDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()

    @Provides fun mediaDao(db: AppDatabase) = db.mediaDao()
    @Provides fun mediaSourceDao(db: AppDatabase) = db.mediaSourceDao()
    @Provides fun albumDao(db: AppDatabase) = db.albumDao()
    @Provides fun favoriteDao(db: AppDatabase) = db.favoriteDao()
    @Provides fun syncCursorDao(db: AppDatabase) = db.syncCursorDao()
    @Provides fun playbackPositionDao(db: AppDatabase) = db.playbackPositionDao()
    @Provides fun userSettingDao(db: AppDatabase) = db.userSettingDao()
    @Provides fun cachedFileDao(db: AppDatabase) = db.cachedFileDao()
    @Provides fun downloadTaskDao(db: AppDatabase) = db.downloadTaskDao()
    @Provides fun cloudFolderDao(db: AppDatabase) = db.cloudFolderDao()
}
