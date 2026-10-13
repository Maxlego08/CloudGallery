package fr.eva.cloudgallery.data.cloud

import fr.eva.cloudgallery.BuildConfig
import fr.eva.cloudgallery.data.cloud.auth.isValidClientId
import fr.eva.cloudgallery.data.cloud.auth.isValidSignatureHash
import fr.eva.cloudgallery.data.cloud.auth.msalRedirectUri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OneDrive availability in this build: the Entra application id and the signature hash come
 * from local.properties (BuildConfig). If missing or invalid, the whole feature is hidden (no
 * dead menu entry). No disk or MSAL access here.
 */
@Singleton
class CloudFeature @Inject constructor() {

    val clientId: String = BuildConfig.MSAL_CLIENT_ID

    val enabled: Boolean = isValidClientId(clientId) && isValidSignatureHash(BuildConfig.MSAL_SIGNATURE_HASH)

    /** Encoded redirect URI, identical to the one registered in Entra. */
    val redirectUri: String
        get() = msalRedirectUri(BuildConfig.APPLICATION_ID, BuildConfig.MSAL_SIGNATURE_HASH)
}
