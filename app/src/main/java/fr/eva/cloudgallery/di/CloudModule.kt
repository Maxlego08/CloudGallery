package fr.eva.cloudgallery.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.eva.cloudgallery.data.cloud.CloudSessionCoordinator
import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.data.cloud.sync.OneDriveSyncer
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.cloud.thumb.CoilCloudThumbnailCache
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.CloudTokenProvider
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * OneDrive. Graphe sans cycle : l'UI dépend de [CloudSessionCoordinator], qui dépend de
 * [OneDriveSyncer] et de [MsalAuthService] ; le client Graph ne dépend que de
 * [CloudTokenProvider] (MSAL), qui ne dépend ni de Graph ni de la synchronisation.
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
}

@Module
@InstallIn(SingletonComponent::class)
object CloudNetworkModule {

    /**
     * Client HTTP de Microsoft Graph. Injecté via dagger.Lazy et créé au premier appel, sur le
     * dispatcher d'E/S. Les redirections sont suivies ; OkHttp retire l'en-tête Authorization
     * lors d'une redirection vers un autre hôte.
     */
    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}
