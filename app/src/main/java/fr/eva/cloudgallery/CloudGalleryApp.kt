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
import fr.eva.cloudgallery.data.cloud.precache.OneDrivePrecacheScheduler
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
     * OneDrive thumbnails for Coil. Lightweight factory: the Graph client, OkHttp and MSAL are
     * only created on the first download, on the I/O dispatcher (dagger.Lazy).
     */
    @Inject
    lateinit var cloudThumbnails: CloudThumbnailFetcher.Factory

    /** Thumbnails taken from offline copies (app files, read on the I/O dispatcher). */
    @Inject
    lateinit var offlineThumbnails: OfflineThumbnailFetcher.Factory

    /** Coil disk cache limit (setting), read at startup. */
    @Inject
    lateinit var thumbnailCacheLimit: ThumbnailCacheLimit

    /** WorkManager workers created by Hilt (default initializer removed from the manifest). */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Periodic OneDrive sync kept up to date based on the setting and the account. */
    @Inject
    lateinit var backgroundSync: BackgroundSyncScheduler

    /** Search index kept up to date from the database (all writes, whatever their origin). */
    @Inject
    lateinit var searchIndexer: SearchIndexer

    /** Caching of all of OneDrive kept up to date (or cleaned up) after each sync. */
    @Inject
    lateinit var precacheScheduler: OneDrivePrecacheScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Media3 logging without URLs (a OneDrive download URL is as good as a token), whatever
        // the build: no logcat entry may contain one.
        RedactingMedia3Logger.install()
        thumbnailCacheLimit.preload()
        backgroundSync.start()
        searchIndexer.start()
        precacheScheduler.start()
        // Incremental MediaStore -> Room sync each time the app returns to the foreground.
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
