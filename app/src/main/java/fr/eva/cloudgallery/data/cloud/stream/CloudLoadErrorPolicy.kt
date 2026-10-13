package fr.eva.cloudgallery.data.cloud.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import fr.eva.cloudgallery.domain.model.CloudStreamException

/**
 * Retries for OneDrive streaming:
 * - 401/403/404/410 status from the download host: URL forgotten, then default policy
 *   (the next attempt obtains a new URL);
 * - sign-in required, item deleted or Graph throttling: immediate failure;
 * - other errors: Media3's default policy.
 */
@OptIn(UnstableApi::class)
class CloudLoadErrorPolicy(private val resolver: OneDriveStreamResolver) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val causes = causeChain(loadErrorInfo.exception).toList()
        val status = causes.firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }
        val failure = causes.firstNotNullOfOrNull { (it as? CloudStreamException)?.failure }
        return when (streamRetryDecision(status, failure)) {
            StreamRetry.INVALIDATE_URL_THEN_DEFAULT -> {
                resolver.invalidate()
                super.getRetryDelayMsFor(loadErrorInfo)
            }
            StreamRetry.FATAL -> C.TIME_UNSET
            StreamRetry.DEFAULT -> super.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
