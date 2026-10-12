package fr.eva.cloudgallery

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import fr.eva.cloudgallery.data.cloud.offline.OfflineThumbnailFetcher
import fr.eva.cloudgallery.data.cloud.stream.RedactingMedia3Logger
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailFetcher
import fr.eva.cloudgallery.ui.image.buildAppImageLoader
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import javax.inject.Inject

@HiltAndroidApp
class CloudGalleryApp : Application(), SingletonImageLoader.Factory {

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

    override fun onCreate() {
        super.onCreate()
        // Journal Media3 sans URL (une URL de téléchargement OneDrive vaut un jeton), quel que
        // soit le build : aucune entrée de logcat ne doit la contenir.
        RedactingMedia3Logger.install()
        // Synchro incrémentale MediaStore -> Room à chaque retour de l'appli au premier plan.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncCoordinator.onForeground()
            }
        })
    }

    override fun newImageLoader(context: Context): ImageLoader =
        buildAppImageLoader(context) {
            cloudThumbnails.register(this)
            offlineThumbnails.register(this)
        }
}
