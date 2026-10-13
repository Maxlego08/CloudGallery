package fr.eva.cloudgallery.data.cloud.auth

import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException

/** Réduit une exception MSAL à sa famille (voir [classifyMsalError]). */
internal fun Throwable.msalKind(): MsalExceptionKind = when (this) {
    is MsalUserCancelException -> MsalExceptionKind.USER_CANCEL
    is MsalUiRequiredException -> MsalExceptionKind.UI_REQUIRED
    is MsalDeclinedScopeException -> MsalExceptionKind.DECLINED_SCOPE
    is MsalClientException -> MsalExceptionKind.CLIENT
    is MsalServiceException -> MsalExceptionKind.SERVICE
    else -> MsalExceptionKind.OTHER
}

/** Classe un échec quelconque levé par MSAL. */
internal fun classifyMsalError(error: Throwable): MsalFailure =
    classifyMsalError(error.msalKind(), (error as? MsalException)?.errorCode)
