package fr.eva.cloudgallery

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import fr.eva.cloudgallery.data.cloud.offline.OfflineThumbnailFetcher
import fr.eva.cloudgallery.data.cloud.stream.RedactingMedia3Logger
import fr.eva.cloudgallery.data.cloud.sync.BackgroundSyncScheduler
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailFetcher
import fr.eva.cloudgallery.data.cloud.thumb.ThumbnailCacheLimit
import fr.eva.cloudgallery.data.search.SearchIndexer
import fr.eva.cloudgallery.ui.image.buildAppImageLoader
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import javax.inject.Inject

@HiltAndroidApp
class CloudGalleryApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject
    lateinit var syncCoordinator: MediaSyncCoordinator

    /**
     * Miniatures OneDrive pour Coil. Fabrique légère : le client Graph, OkHttp et MSAL ne sont
     * créés qu'au premier téléchargement, sur le dispatcher d'E/S (dagger.Lazy).
     */
    @Inject
    lateinit var cloudThumbnails: CloudThumbnailFetcher.Factory

    /** Miniatures tirées des copies hors ligne (fichiers de l'appli, lus sur le dispatcher d'E/S). */
    @Inject
    lateinit var offlineThumbnails: OfflineThumbnailFetcher.Factory

    /** Plafond du cache disque Coil (réglage), lu dès le démarrage. */
    @Inject
    lateinit var thumbnailCacheLimit: ThumbnailCacheLimit

    /** Workers WorkManager créés par Hilt (initialiseur par défaut retiré du manifeste). */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Synchro OneDrive périodique tenue à jour d'après le réglage et le compte. */
    @Inject
    lateinit var backgroundSync: BackgroundSyncScheduler

    /** Index de recherche tenu à jour d'après la base (toutes les écritures, quelle qu'en soit l'origine). */
    @Inject
    lateinit var searchIndexer: SearchIndexer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Journal Media3 sans URL (une URL de téléchargement OneDrive vaut un jeton), quel que
        // soit le build : aucune entrée de logcat ne doit la contenir.
        RedactingMedia3Logger.install()
        thumbnailCacheLimit.preload()
        backgroundSync.start()
        searchIndexer.start()
        // Synchro incrémentale MediaStore -> Room à chaque retour de l'appli au premier plan.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncCoordinator.onForeground()
            }
        })
    }

    override fun newImageLoader(context: Context): ImageLoader =
        buildAppImageLoader(context, diskCacheMaxBytes = thumbnailCacheLimit::forDiskCacheOpening) {
            cloudThumbnails.register(this)
            offlineThumbnails.register(this)
        }
}
