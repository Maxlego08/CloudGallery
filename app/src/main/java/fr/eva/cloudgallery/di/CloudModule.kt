package fr.eva.cloudgallery.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.eva.cloudgallery.data.cloud.CloudSessionCoordinator
import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.data.cloud.offline.OfflineRepositoryImpl
import fr.eva.cloudgallery.data.cloud.precache.OneDrivePrecacheRepositoryImpl
import fr.eva.cloudgallery.data.cloud.precache.PrecacheCleaner
import fr.eva.cloudgallery.data.cloud.precache.WorkManagerPrecacheCleaner
import fr.eva.cloudgallery.data.cloud.sync.OneDriveSyncer
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.cloud.thumb.CoilCloudThumbnailCache
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.CloudTokenProvider
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.domain.repository.OneDrivePrecacheRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * OneDrive. Acyclic graph: the UI depends on [CloudSessionCoordinator], which depends on
 * [OneDriveSyncer] and [MsalAuthService]; the Graph client only depends on
 * [CloudTokenProvider] (MSAL), which depends neither on Graph nor on the sync.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {

    @Binds @Singleton
    abstract fun bindCloudAccountRepository(impl: CloudSessionCoordinator): CloudAccountRepository

    @Binds @Singleton
    abstract fun bindCloudTokenProvider(impl: MsalAuthService): CloudTokenProvider

    @Binds @Singleton
    abstract fun bindCloudMediaSyncer(impl: OneDriveSyncer): CloudMediaSyncer

    @Binds @Singleton
    abstract fun bindCloudThumbnailCache(impl: CoilCloudThumbnailCache): CloudThumbnailCache

    @Binds @Singleton
    abstract fun bindOfflineRepository(impl: OfflineRepositoryImpl): OfflineRepository

    @Binds @Singleton
    abstract fun bindOneDrivePrecacheRepository(impl: OneDrivePrecacheRepositoryImpl): OneDrivePrecacheRepository

    @Binds @Singleton
    abstract fun bindPrecacheCleaner(impl: WorkManagerPrecacheCleaner): PrecacheCleaner
}

@Module
@InstallIn(SingletonComponent::class)
object CloudNetworkModule {

    /**
     * HTTP client for Microsoft Graph. Injected via dagger.Lazy and created on first call, on the
     * I/O dispatcher. Redirects are followed; OkHttp strips the Authorization header on a
     * redirect to another host.
     */
    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}
