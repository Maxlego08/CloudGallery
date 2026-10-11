package fr.eva.cloudgallery.data.cloud

import fr.eva.cloudgallery.BuildConfig
import fr.eva.cloudgallery.data.cloud.auth.isValidClientId
import fr.eva.cloudgallery.data.cloud.auth.isValidSignatureHash
import fr.eva.cloudgallery.data.cloud.auth.msalRedirectUri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disponibilité de OneDrive dans ce build : l'identifiant d'application Entra et l'empreinte
 * de signature viennent de local.properties (BuildConfig). Absents ou invalides, toute la
 * fonction est masquée (aucune entrée de menu inopérante). Aucun accès disque ni MSAL ici.
 */
@Singleton
class CloudFeature @Inject constructor() {

    val clientId: String = BuildConfig.MSAL_CLIENT_ID

    val enabled: Boolean = isValidClientId(clientId) && isValidSignatureHash(BuildConfig.MSAL_SIGNATURE_HASH)

    /** URI de redirection encodée, identique à celle enregistrée dans Entra. */
    val redirectUri: String
        get() = msalRedirectUri(BuildConfig.APPLICATION_ID, BuildConfig.MSAL_SIGNATURE_HASH)
}
