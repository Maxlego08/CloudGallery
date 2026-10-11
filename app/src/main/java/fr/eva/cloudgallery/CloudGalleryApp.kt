package fr.eva.cloudgallery

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import fr.eva.cloudgallery.ui.image.buildAppImageLoader
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import javax.inject.Inject

@HiltAndroidApp
class CloudGalleryApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var syncCoordinator: MediaSyncCoordinator

    override fun onCreate() {
        super.onCreate()
        // Synchro incrémentale MediaStore -> Room à chaque retour de l'appli au premier plan.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                syncCoordinator.onForeground()
            }
        })
    }

    override fun newImageLoader(context: Context): ImageLoader = buildAppImageLoader(context)
}
