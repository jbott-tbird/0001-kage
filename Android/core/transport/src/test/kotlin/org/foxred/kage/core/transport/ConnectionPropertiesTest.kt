// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.transport

import org.foxred.kage.core.account.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionPropertiesTest {
    @Test
    fun startTlsCannotFallBackToPlaintext() {
        val p =
            connectionProperties(
                Server("localhost", 587, ServerProtocol.SMTP, ConnectionSecurity.STARTTLS, "user"),
                Authorization("secret"),
            )
        assertEquals("true", p.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", p.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertFalse(p.toString().contains("secret"))
        assertFalse(Authorization("secret").toString().contains("secret"))
    }

    @Test
    fun oauthUsesBuiltinMechanismWithoutAndroidSasl() {
        val p =
            connectionProperties(
                Server(
                    "imap.gmail.com",
                    993,
                    ServerProtocol.IMAP,
                    username = "user",
                    authenticationType = AuthenticationType.OAUTH2,
                ),
                Authorization("token", Authorization.Kind.OAUTH2),
            )
        assertEquals("XOAUTH2", p.getProperty("mail.imap.auth.mechanisms"))
        assertEquals("true", p.getProperty("mail.imap.ssl.enable"))
        assertEquals("true", p.getProperty("mail.imap.peek"))
    }

    @Test
    fun expiredTokensAndMismatchedCredentialsAreRejectedBeforeNetworking() {
        val server =
            Server(
                "imap.gmail.com",
                993,
                ServerProtocol.IMAP,
                username = "user",
                authenticationType = AuthenticationType.OAUTH2,
            )
        val error =
            assertThrows(MailFailure::class.java) {
                connectionProperties(
                    server,
                    Authorization("expired", Authorization.Kind.OAUTH2, java.time.Instant.EPOCH),
                )
            }
        assertEquals(FailureKind.AUTHENTICATION, error.kind)
        assertThrows(IllegalArgumentException::class.java) {
            connectionProperties(server, Authorization("password"))
        }
    }

    @Test
    fun unauthenticatedSubmissionStillRequiresTls() {
        val properties =
            connectionProperties(
                Server(
                    "localhost",
                    465,
                    ServerProtocol.SMTP,
                    username = "",
                    authenticationType = AuthenticationType.NONE,
                ),
                Authorization.none(),
            )
        assertEquals("false", properties.getProperty("mail.smtp.auth"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.enable"))
    }
}
