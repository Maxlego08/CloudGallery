package fr.eva.cloudgallery.data.cloud

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OneDrive data wipe counter, incremented by the purge (sign-out, purge of leftover data,
 * account change) BEFORE its transaction. Anything that writes OneDrive content (thumbnail,
 * original, offline copy) reads this number when the download starts and compares it when
 * saving: content obtained before a purge is never kept.
 */
@Singleton
class CloudDataGeneration @Inject constructor() {

    private val counter = AtomicLong(0)

    val value: Long get() = counter.get()

    fun bump() {
        counter.incrementAndGet()
    }
}
