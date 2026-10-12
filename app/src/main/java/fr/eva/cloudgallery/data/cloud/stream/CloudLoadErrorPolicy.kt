package fr.eva.cloudgallery.data.cloud.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import fr.eva.cloudgallery.domain.model.CloudStreamException

/**
 * Nouvelles tentatives du streaming OneDrive :
 * - statut 401/403/404/410 de l'hôte de téléchargement : URL oubliée, puis politique par défaut
 *   (la tentative suivante obtient une nouvelle URL) ;
 * - reconnexion nécessaire, élément supprimé ou limitation Graph : échec immédiat ;
 * - autres erreurs : politique par défaut de Media3.
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
