// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core

import java.io.File
import org.foxred.kage.core.account.AuthenticationType
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.ConnectionSecurity
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.foxred.kage.core.testkit.ImapTranscript
import org.foxred.kage.core.testkit.LoopbackServer
import org.foxred.kage.core.testkit.SmtpTranscript
import org.foxred.kage.core.testkit.testTlsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Exercises the actual SASL mechanism with a local TLS server; no Google account is required. */
class OAuthProtocolIntegrationTest {
    private val token = "local-oauth-access-token"

    private fun tls() = testTlsContext(
        File(System.getProperty("greenmail.tls.keystore.file")).inputStream())

    @Test
    fun imapAuthenticatesWithXoauth2WithoutRecordingTheToken() {
        val transcript = ImapTranscript(oauthToken = token)
        LoopbackServer(tls(), transcript::serve).use { server ->
            AngusImapClient().use { client ->
                client.connect(Server("localhost", server.port, ServerProtocol.IMAP,
                    ConnectionSecurity.TLS, "user", AuthenticationType.OAUTH2),
                    Authorization(token, Authorization.Kind.OAUTH2))
                assertEquals("INBOX", client.mailboxes().single().name)
            }
            server.awaitCompletion()
        }
        assertFalse(transcript.commands.joinToString().contains(token))
    }

    @Test
    fun smtpAuthenticatesWithXoauth2BeforeSendingAnyMessage() {
        val transcript = SmtpTranscript(startTls = tls(), oauthToken = token)
        LoopbackServer(handler = transcript::serve).use { server ->
            AngusSmtpClient().verifyConnection(
                Server("localhost", server.port, ServerProtocol.SMTP,
                    ConnectionSecurity.STARTTLS, "user", AuthenticationType.OAUTH2),
                Authorization(token, Authorization.Kind.OAUTH2),
            )
            server.awaitCompletion()
        }
        assertFalse(transcript.commands.any { it.startsWith("MAIL FROM:") })
        assertFalse(transcript.commands.joinToString().contains(token))
    }
}
