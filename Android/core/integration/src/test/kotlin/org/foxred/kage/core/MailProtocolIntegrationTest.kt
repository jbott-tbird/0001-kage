package org.foxred.kage.core

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import java.time.Instant
import org.foxred.kage.core.account.*
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.junit.*
import org.junit.Assert.*

/** Local, non-forwarding mail servers. These tests never contact Gmail or real recipients. */
class MailProtocolIntegrationTest {
    private lateinit var mail: GreenMail

    @Before
    fun start() {
        mail =
            GreenMail(
                arrayOf(
                    ServerSetup(0, "127.0.0.1", "imaps"),
                    ServerSetup(0, "127.0.0.1", "smtps"),
                    ServerSetup(0, "127.0.0.1", "smtp"),
                )
            )
        mail.setUser("test@example.net", "test", "password")
        mail.setUser("hidden@example.net", "hidden", "password")
        mail.start()
    }

    @After
    fun stop() {
        mail.stop()
    }

    private fun incoming() =
        Server("localhost", mail.imaps.port, ServerProtocol.IMAP, username = "test")

    private fun outgoing() =
        Server("localhost", mail.smtps.port, ServerProtocol.SMTP, username = "test")

    private fun email() =
        OutgoingEmail(
            "<integration-1@example.net>",
            EmailAddress("test@example.net"),
            listOf(EmailAddress("test@example.net")),
            bcc = listOf(EmailAddress("hidden@example.net")),
            subject = "שלום test",
            body = EmailBody("Hello from the core", "<p>Hello</p>"),
            attachments =
                listOf(
                    OutgoingAttachment("sample.txt", "text/plain", "fixture bytes".toByteArray())
                ),
        )

    @Test
    fun tlsSubmissionAndUidReadingRoundTrip() {
        AngusSmtpClient().send(outgoing(), Authorization("password"), email())
        assertTrue(mail.waitForIncomingEmail(5000, 2))
        assertNull(mail.receivedMessages.first().getHeader("Bcc"))
        AngusImapClient().use { client ->
            client.connect(incoming(), Authorization("password"))
            assertTrue(client.mailboxes().any { it.name.equals("INBOX", true) })
            val headers = client.messages("INBOX", Instant.now().minusSeconds(86400))
            assertEquals(1, headers.size)
            val identity = headers.single().identity!!
            val message = client.message(identity)
            assertEquals(email().subject, message.subject)
            assertEquals(email().body.text, message.body.text)
            assertFalse(message.read)
            assertArrayEquals(
                "fixture bytes".toByteArray(),
                client.attachment(identity, message.attachments.single().partId),
            )
            client.markRead(identity, true)
            client.flag(identity, true)
            assertTrue(client.message(identity).read)
            assertTrue(client.message(identity).flagged)
            val failure =
                assertThrows(MailFailure::class.java) {
                    client.message(identity.copy(uidValidity = identity.uidValidity + 1))
                }
            assertEquals(FailureKind.PROTOCOL, failure.kind)
        }
    }

    @Test
    fun startTlsNeverFallsBackWhenServerDoesNotAdvertiseIt() {
        val failure =
            assertThrows(MailFailure::class.java) {
                AngusSmtpClient()
                    .send(
                        Server(
                            "localhost",
                            mail.smtp.port,
                            ServerProtocol.SMTP,
                            ConnectionSecurity.STARTTLS,
                            "test",
                        ),
                        Authorization("password"),
                        email(),
                    )
            }
        assertEquals(FailureKind.CONNECTION, failure.kind)
        assertEquals(0, mail.receivedMessages.size)
    }

    @Test
    fun wrongPasswordHasTypedAuthenticationFailure() {
        AngusImapClient().use { client ->
            val failure =
                assertThrows(MailFailure::class.java) {
                    client.connect(incoming(), Authorization("wrong"))
                }
            assertEquals(FailureKind.AUTHENTICATION, failure.kind)
        }
        val failure =
            assertThrows(MailFailure::class.java) {
                AngusSmtpClient().send(outgoing(), Authorization("wrong"), email())
            }
        assertEquals(FailureKind.AUTHENTICATION, failure.kind)
        assertEquals(0, mail.receivedMessages.size)
    }

    @Test
    fun hostnameMismatchIsRejectedBeforeAuthentication() {
        AngusImapClient().use { client ->
            val failure =
                assertThrows(MailFailure::class.java) {
                    client.connect(
                        incoming().copy(hostname = "127.0.0.1"),
                        Authorization("password"),
                    )
                }
            assertEquals(FailureKind.CONNECTION, failure.kind)
        }
    }
}
