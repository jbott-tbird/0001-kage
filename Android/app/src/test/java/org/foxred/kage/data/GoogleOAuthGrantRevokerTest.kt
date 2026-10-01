// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.data.security.GoogleOAuthGrantRevoker
import org.junit.Assert.*
import org.junit.Test

class GoogleOAuthGrantRevokerTest {
    @Test fun postsEncodedAccessTokenAndDoesNotFollowRedirects() {
        val request = FakeConnection(200)
        GoogleOAuthGrantRevoker { request }.revoke("access +/secret")
        assertEquals("POST", request.requestMethod)
        assertFalse(request.instanceFollowRedirects)
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8",
            request.getRequestProperty("Content-Type"))
        assertEquals("token=access+%2B%2Fsecret", request.body.toString("UTF-8"))
        assertTrue(request.disconnected)
    }

    @Test fun alreadyInvalidTokenCountsAsRevokedButOtherFailuresKeepTheGrant() {
        val invalid = FakeConnection(400, """{"error":"invalid_token"}""")
        GoogleOAuthGrantRevoker { invalid }.revoke("refresh")
        assertTrue(invalid.disconnected)

        val malformed = FakeConnection(400, """{"error":"invalid_request"}""")
        val rejected = runCatching {
            GoogleOAuthGrantRevoker { malformed }.revoke("refresh")
        }.exceptionOrNull() as MailFailure
        assertEquals(FailureKind.AUTHENTICATION, rejected.kind)

        val unavailable = FakeConnection(503)
        val failure = runCatching {
            GoogleOAuthGrantRevoker { unavailable }.revoke("refresh")
        }.exceptionOrNull() as MailFailure
        assertEquals(FailureKind.CONNECTION, failure.kind)
        assertFalse(failure.message.orEmpty().contains("refresh"))
        assertTrue(unavailable.disconnected)
    }

    private class FakeConnection(
        private val code: Int,
        private val error: String = "",
    ) : HttpsURLConnection(URL("https://oauth2.googleapis.com/revoke")) {
        val body = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream() = body
        override fun getResponseCode() = code
        override fun getErrorStream(): InputStream? =
            if (error.isEmpty()) null else ByteArrayInputStream(error.toByteArray())
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
