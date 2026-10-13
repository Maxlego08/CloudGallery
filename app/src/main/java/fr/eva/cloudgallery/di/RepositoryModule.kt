package fr.eva.cloudgallery.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.eva.cloudgallery.data.local.MediaStoreIndexer
import fr.eva.cloudgallery.data.local.MediaStoreWriter
import fr.eva.cloudgallery.data.prefs.SettingsRepositoryImpl
import fr.eva.cloudgallery.data.repository.AlbumRepositoryImpl
import fr.eva.cloudgallery.data.repository.FavoritesRepositoryImpl
import fr.eva.cloudgallery.data.repository.MediaRepositoryImpl
import fr.eva.cloudgallery.data.repository.PlaybackPositionRepositoryImpl
import fr.eva.cloudgallery.data.search.SearchHistoryStore
import fr.eva.cloudgallery.data.storage.SpaceSaverRepositoryImpl
import fr.eva.cloudgallery.data.storage.StorageRepositoryImpl
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import fr.eva.cloudgallery.domain.repository.FavoritesRepository
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.LocalMediaWriter
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.PlaybackPositionRepository
import fr.eva.cloudgallery.domain.repository.SearchHistoryRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.domain.repository.SpaceSaverRepository
import fr.eva.cloudgallery.domain.repository.StorageRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository

    @Binds @Singleton
    abstract fun bindAlbumRepository(impl: AlbumRepositoryImpl): AlbumRepository

    @Binds @Singleton
    abstract fun bindLocalMediaIndexer(impl: MediaStoreIndexer): LocalMediaIndexer

    @Binds @Singleton
    abstract fun bindLocalMediaWriter(impl: MediaStoreWriter): LocalMediaWriter

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindPlaybackPositionRepository(impl: PlaybackPositionRepositoryImpl): PlaybackPositionRepository

    @Binds @Singleton
    abstract fun bindStorageRepository(impl: StorageRepositoryImpl): StorageRepository

    @Binds @Singleton
    abstract fun bindSearchHistoryRepository(impl: SearchHistoryStore): SearchHistoryRepository

    @Binds @Singleton
    abstract fun bindSpaceSaverRepository(impl: SpaceSaverRepositoryImpl): SpaceSaverRepository
}
