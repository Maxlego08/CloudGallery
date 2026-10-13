package fr.eva.cloudgallery.data.cloud.auth

import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.microsoft.identity.client.exception.MsalUserCancelException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAuthLogicTest {

    @Test
    fun redirectUri_encodesSignatureHash() {
        assertEquals(
            "msauth://fr.eva.cloudgallery/AAAAAAAAAAAAAAAAAAAAAAAA%2B%2F8%3D",
            msalRedirectUri("fr.eva.cloudgallery", "AAAAAAAAAAAAAAAAAAAAAAAA+/8="),
        )
    }

    @Test
    fun clientId_validation() {
        assertTrue(isValidClientId("12345678-90ab-cdef-1234-567890ABCDEF"))
        assertFalse(isValidClientId(""))
        assertFalse(isValidClientId("   "))
        assertFalse(isValidClientId("12345678-90ab-cdef-1234-567890abcde"))
        assertFalse(isValidClientId("12345678-90ab-cdef-1234-567890abcdeg"))
        assertFalse(isValidClientId("1234567890abcdef1234567890abcdef"))
    }

    @Test
    fun signatureHash_validation() {
        assertTrue(isValidSignatureHash("AAAAAAAAAAAAAAAAAAAAAAAA+/8="))
        assertFalse(isValidSignatureHash(""))
        assertFalse(isValidSignatureHash("AAAAAAAAAAAAAAAAAAAAAAAA+/8"))
        assertFalse(isValidSignatureHash("AAAAAAAAAAAAAAAAAAAAAAAA%2B%2F8%3D"))
        assertFalse(isValidSignatureHash("msal-not-configured"))
    }

    @Test
    fun configJson_singleAccountPersonalMicrosoftAccount() {
        val redirect = msalRedirectUri("fr.eva.cloudgallery", "AAAAAAAAAAAAAAAAAAAAAAAA+/8=")
        val json = Json.parseToJsonElement(buildMsalConfigJson("client-id", redirect)).jsonObject

        assertEquals("client-id", json.getValue("client_id").jsonPrimitive.content)
        assertEquals("SINGLE", json.getValue("account_mode").jsonPrimitive.content)
        assertEquals("DEFAULT", json.getValue("authorization_user_agent").jsonPrimitive.content)
        assertEquals(redirect, json.getValue("redirect_uri").jsonPrimitive.content)
        assertFalse(json.getValue("broker_redirect_uri_registered").jsonPrimitive.boolean)

        val authorities = json.getValue("authorities").jsonArray
        assertEquals(1, authorities.size)
        val authority = authorities[0].jsonObject
        assertEquals("AAD", authority.getValue("type").jsonPrimitive.content)
        assertTrue(authority.getValue("default").jsonPrimitive.boolean)
        val audience = authority.getValue("audience").jsonObject
        assertEquals("PersonalMicrosoftAccount", audience.getValue("type").jsonPrimitive.content)
        assertEquals("consumers", audience.getValue("tenant_id").jsonPrimitive.content)
    }

    @Test
    fun graphScopes_readOnlyWithoutReservedScopes() {
        assertEquals(listOf("Files.Read", "User.Read"), GRAPH_SCOPES)
        assertTrue(GRAPH_SCOPES.none { it in setOf("openid", "profile", "offline_access") })
    }

    @Test
    fun tokenFreshness_respectsSkew() {
        val now = 1_000_000_000L
        val skew = 5 * 60_000L
        assertTrue(isTokenFresh(expiresAtMs = now + skew + 1, nowMs = now))
        assertFalse(isTokenFresh(expiresAtMs = now + skew, nowMs = now))
        assertFalse(isTokenFresh(expiresAtMs = now + 60_000L, nowMs = now))
        assertFalse(isTokenFresh(expiresAtMs = now - 1, nowMs = now))
        assertFalse(isTokenFresh(expiresAtMs = 0L, nowMs = now))
        assertTrue(isTokenFresh(expiresAtMs = now + 2, nowMs = now, skewMs = 1))
    }

    @Test
    fun classify_offlineAndDuplicateAreTransient_notReauth() {
        listOf("device_network_not_available", "io_error", "duplicate_command").forEach { code ->
            assertEquals(code, MsalFailure.TRANSIENT, classifyMsalError(MsalExceptionKind.CLIENT, code))
        }
        listOf("service_not_available", "request_timeout").forEach { code ->
            assertEquals(code, MsalFailure.TRANSIENT, classifyMsalError(MsalExceptionKind.SERVICE, code))
        }
        assertEquals(CloudErrorCode.NETWORK, MsalFailure.TRANSIENT.toErrorCode())
    }

    @Test
    fun classify_uiRequiredAndNoAccount() {
        assertEquals(MsalFailure.UI_REQUIRED, classifyMsalError(MsalExceptionKind.UI_REQUIRED, "invalid_grant"))
        assertEquals(MsalFailure.UI_REQUIRED, classifyMsalError(MsalExceptionKind.UI_REQUIRED, null))
        assertEquals(MsalFailure.NO_ACCOUNT, classifyMsalError(MsalExceptionKind.CLIENT, "no_current_account"))
        assertEquals(MsalFailure.NO_ACCOUNT, classifyMsalError(MsalExceptionKind.CLIENT, "current_account_mismatch"))
        assertEquals(CloudErrorCode.AUTH_REQUIRED, MsalFailure.UI_REQUIRED.toErrorCode())
        assertEquals(CloudErrorCode.AUTH_REQUIRED, MsalFailure.NO_ACCOUNT.toErrorCode())
    }

    @Test
    fun classify_configurationErrors() {
        listOf(
            "redirect_uri_validation_error",
            "app_manifest_validation_error",
            "multiple_apps_listening_url_scheme",
            "unknown_authority",
            "json_parse_failure",
        ).forEach { code ->
            assertEquals(code, MsalFailure.CONFIG, classifyMsalError(MsalExceptionKind.CLIENT, code))
        }
        listOf("unauthorized_client", "invalid_scope", "invalid_instance", "invalid_request").forEach { code ->
            assertEquals(code, MsalFailure.CONFIG, classifyMsalError(MsalExceptionKind.SERVICE, code))
        }
    }

    @Test
    fun classify_cancelDeclinedBrowserServerUnknown() {
        assertEquals(MsalFailure.CANCELLED, classifyMsalError(MsalExceptionKind.USER_CANCEL, null))
        assertNull(MsalFailure.CANCELLED.toErrorCode())
        assertEquals(MsalFailure.DECLINED, classifyMsalError(MsalExceptionKind.DECLINED_SCOPE, null))
        assertEquals(MsalFailure.DECLINED, classifyMsalError(MsalExceptionKind.SERVICE, "access_denied"))
        assertEquals(MsalFailure.NO_BROWSER, classifyMsalError(MsalExceptionKind.CLIENT, "chrome_not_installed"))
        assertEquals(MsalFailure.SERVER, classifyMsalError(MsalExceptionKind.SERVICE, "unknown_error"))
        assertEquals(MsalFailure.UNKNOWN, classifyMsalError(MsalExceptionKind.CLIENT, "unknown_error"))
        assertEquals(MsalFailure.UNKNOWN, classifyMsalError(MsalExceptionKind.CLIENT, null))
        assertEquals(MsalFailure.UNKNOWN, classifyMsalError(MsalExceptionKind.OTHER, "io_error"))
    }

    @Test
    fun classify_realMsalExceptions() {
        assertEquals(MsalFailure.TRANSIENT, classifyMsalError(MsalClientException("device_network_not_available")))
        assertEquals(MsalFailure.NO_ACCOUNT, classifyMsalError(MsalClientException(MsalClientException.NO_CURRENT_ACCOUNT)))
        assertEquals(MsalFailure.UI_REQUIRED, classifyMsalError(MsalUiRequiredException(MsalUiRequiredException.INVALID_GRANT)))
        assertEquals(MsalFailure.CANCELLED, classifyMsalError(MsalUserCancelException()))
        assertEquals(
            MsalFailure.TRANSIENT,
            classifyMsalError(MsalServiceException(MsalServiceException.SERVICE_NOT_AVAILABLE, "unavailable", null)),
        )
        assertEquals(MsalFailure.UNKNOWN, classifyMsalError(IllegalArgumentException("other")))
    }
}
