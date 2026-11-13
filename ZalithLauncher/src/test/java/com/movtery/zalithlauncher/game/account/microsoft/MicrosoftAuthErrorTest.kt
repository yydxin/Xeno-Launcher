package com.movtery.zalithlauncher.game.account.microsoft

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrosoftAuthErrorTest {

    @Test
    fun `xerr codes map to friendly statuses`() {
        assertEquals(XboxLoginException.ExceptionStatus.BANNED, xerrStatus("2148916227"))
        assertEquals(XboxLoginException.ExceptionStatus.RESTRICTED, xerrStatus("2148916229"))
        assertEquals(XboxLoginException.ExceptionStatus.UNREGISTERED, xerrStatus("2148916233"))
        assertEquals(XboxLoginException.ExceptionStatus.NOT_ACCEPTED_SERVICE, xerrStatus("2148916234"))
        assertEquals(XboxLoginException.ExceptionStatus.BLOCKED_REGION, xerrStatus("2148916235"))
        assertEquals(XboxLoginException.ExceptionStatus.REQUIRES_PROOF_OF_AGE, xerrStatus("2148916236"))
        assertEquals(XboxLoginException.ExceptionStatus.REACHED_PLAYTIME_LIMIT, xerrStatus("2148916237"))
        assertEquals(XboxLoginException.ExceptionStatus.UNDERAGE, xerrStatus("2148916238"))
        assertNull(xerrStatus(null))
        assertNull(xerrStatus("999"))
    }

    @Test
    fun `server summary extracts error fields and truncates`() {
        val body = buildJsonObject {
            put("error", "invalid_request")
            put("error_description", "AADSTS900144: The request body must contain client_id")
        }
        val summary = microsoftErrorSummary(body)
        assertTrue(summary!!.contains("AADSTS900144"))
        assertTrue(summary.length <= 200)

        val long = buildJsonObject { put("Message", "x".repeat(260)) }
        assertEquals(200, microsoftErrorSummary(long)!!.length)

        assertNull(microsoftErrorSummary(null))
        assertNull(microsoftErrorSummary(buildJsonObject { }))
    }

    @Test
    fun `client id configured check rejects missing and placeholders`() {
        assertFalse(isMicrosoftAuthConfigured(""))
        assertFalse(isMicrosoftAuthConfigured("   "))
        assertFalse(isMicrosoftAuthConfigured("YOUR_CLIENT_ID"))
        assertTrue(isMicrosoftAuthConfigured("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `step exception preserves status and summary for every http class`() {
        listOf(400, 401, 403, 429, 500).forEach { status ->
            val error = MicrosoftAuthStepException(
                step = MicrosoftAuthStep.MINECRAFT_LOGIN,
                httpStatus = status,
                serverSummary = "server said $status"
            )
            assertEquals(status, error.httpStatus)
            assertEquals("server said $status", error.serverSummary)
            assertEquals(MicrosoftAuthStep.MINECRAFT_LOGIN, error.step)
        }
    }
}
