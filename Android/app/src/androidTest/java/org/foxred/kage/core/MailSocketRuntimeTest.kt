package org.foxred.kage.core

import androidx.test.platform.app.InstrumentationRegistry
import javax.net.ssl.SSLContext
import org.foxred.kage.core.account.*
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.foxred.kage.core.testkit.*
import org.junit.*
import org.junit.Assert.*

class MailSocketRuntimeTest {
    private lateinit var original: SSLContext
    private lateinit var tls: SSLContext

    @Before
    fun trustOnlyGeneratedTestIdentity() {
        original = SSLContext.getDefault()
        tls =
            testTlsContext(
                InstrumentationRegistry.getInstrumentation().context.assets.open("localhost.p12")
            )
        SSLContext.setDefault(tls)
    }

    @After
    fun restoreTrust() {
        SSLContext.setDefault(original)
    }

    @Test
    fun tlsImapAuthenticationListAndStatusWorkOnAndroid() {
        val transcript = ImapTranscript(fragmented = true)
        LoopbackServer(tls, transcript::serve).use { server ->
            AngusImapClient().use { client ->
                client.connect(
                    Server("localhost", server.port, ServerProtocol.IMAP, username = "user"),
                    Authorization("password"),
                )
                assertEquals("INBOX", client.mailboxes().single().name)
                assertEquals(77L, client.status("INBOX").uidValidity)
            }
            server.awaitCompletion()
        }
    }

    @Test
    fun smtpStartTlsAndEnvelopeSubmissionWorkOnAndroid() {
        val transcript = SmtpTranscript(tls)
        LoopbackServer(handler = transcript::serve).use { server ->
            AngusSmtpClient()
                .send(
                    Server(
                        "localhost",
                        server.port,
                        ServerProtocol.SMTP,
                        ConnectionSecurity.STARTTLS,
                        "user",
                    ),
                    Authorization("password"),
                    OutgoingEmail(
                        "<android-socket@example.net>",
                        EmailAddress("from@example.net"),
                        listOf(EmailAddress("to@example.net")),
                        subject = "Android network test",
                        body = EmailBody(".dot\r\nBody", null),
                    ),
                )
            server.awaitCompletion()
            assertTrue(transcript.commands.contains("DATA"))
            assertTrue(transcript.commands.contains("..dot"))
        }
    }
}
