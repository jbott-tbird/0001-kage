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
    fun unicodeMailboxNamesRoundTripThroughListAppendAndRename() {
        AngusImapClient().use { client ->
            client.connect(incoming(), Authorization("password"))
            client.createMailbox("项目")
            client.createMailbox("项目/草稿")
            client.append("项目/草稿", org.foxred.kage.core.mime.AngusMimeCodec().encode(email()))
            assertTrue(client.mailboxes().any { it.name == "项目/草稿" })
            assertEquals(email().subject, client.messages("项目/草稿", Instant.EPOCH).single().subject)
            client.renameMailbox("项目/草稿", "项目/已发送")
            assertEquals(1, client.status("项目/已发送").messageCount)
            client.deleteMailbox("项目/已发送")
            client.deleteMailbox("项目")
        }
    }

    @Test
    fun rawMessageStreamPreservesMimeAndDoesNotMarkRead() {
        val raw = org.foxred.kage.core.mime.AngusMimeCodec().encode(email())
        lateinit var identity: MessageIdentity
        AngusImapClient().use { client ->
            client.connect(incoming(), Authorization("password"))
            client.append("INBOX", raw)
            identity = client.messages("INBOX", Instant.EPOCH).single().identity!!
            val output = java.io.ByteArrayOutputStream()
            assertEquals(raw.size.toLong(), client.downloadRawMessage(identity, output))
            assertArrayEquals(raw, output.toByteArray())
            assertFalse(client.message(identity).read)
        }
        AngusImapClient(maxPartBytes = 64).use { client ->
            client.connect(incoming(), Authorization("password"))
            val output = java.io.ByteArrayOutputStream()
            val failure =
                assertThrows(MailFailure::class.java) {
                    client.downloadRawMessage(identity, output)
                }
            assertEquals(FailureKind.LIMIT_EXCEEDED, failure.kind)
            assertTrue(output.size() <= 64)
        }
    }

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
            assertFalse(headers.single().bodyDownloaded)
            val identity = headers.single().identity!!
            val message = client.message(identity)
            assertTrue(message.bodyDownloaded)
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
    fun uidPagesContinueWithoutDuplicatesAndIgnoreLaterArrivals() {
        repeat(7) { i ->
            AngusSmtpClient()
                .send(
                    outgoing(),
                    Authorization("password"),
                    email()
                        .copy(
                            messageId = "<page-$i@example.net>",
                            subject = "Page $i",
                            bcc = emptyList(),
                        ),
                )
        }
        AngusImapClient().use { client ->
            client.connect(incoming(), Authorization("password"))
            val since = Instant.now().minusSeconds(86400)
            val first = client.messagePage("INBOX", since, limit = 2)
            assertEquals(2, first.messages.size)
            assertNotNull(first.next)
            AngusSmtpClient()
                .send(
                    outgoing(),
                    Authorization("password"),
                    email()
                        .copy(messageId = "<late@example.net>", subject = "Late", bcc = emptyList()),
                )
            val seen = first.messages.toMutableList()
            var next = first.next
            while (next != null) {
                val page = client.messagePage("INBOX", since, next, 2)
                assertTrue(page.messages.size <= 2)
                seen += page.messages
                next = page.next
            }
            assertEquals(7, seen.size)
            assertEquals(7, seen.map { it.identity }.distinct().size)
            assertFalse(seen.any { it.subject == "Late" })
            val reset =
                assertThrows(MailFailure::class.java) {
                    client.messagePage(
                        "INBOX",
                        since,
                        first.next!!.copy(uidValidity = first.next!!.uidValidity + 1),
                        2,
                    )
                }
            assertEquals(FailureKind.PROTOCOL, reset.kind)
            val empty = client.messagePage("INBOX", Instant.now().plusSeconds(86400), limit = 2)
            assertTrue(empty.messages.isEmpty())
            assertNotNull(empty.next)
        }
    }

    @Test
    fun messageBodyDoesNotConsumeLargeAttachmentsAndDownloadIsBounded() {
        val payload = ByteArray(100_000) { (it % 127).toByte() }
        AngusSmtpClient()
            .send(
                outgoing(),
                Authorization("password"),
                email()
                    .copy(
                        attachments =
                            listOf(
                                OutgoingAttachment("large.bin", "application/octet-stream", payload)
                            )
                    ),
            )
        AngusImapClient(64).use { client ->
            client.connect(incoming(), Authorization("password"))
            val identity =
                client.messages("INBOX", Instant.now().minusSeconds(86400)).single().identity!!
            val message = client.message(identity)
            assertTrue(message.bodyDownloaded)
            assertEquals(email().body.text, message.body.text)
            val output = java.io.ByteArrayOutputStream()
            val failure =
                assertThrows(MailFailure::class.java) {
                    client.downloadAttachment(identity, message.attachments.single().partId, output)
                }
            assertEquals(FailureKind.LIMIT_EXCEEDED, failure.kind)
            assertTrue(output.size() <= 64)
        }
        AngusImapClient().use { client ->
            client.connect(incoming(), Authorization("password"))
            val identity =
                client.messages("INBOX", Instant.now().minusSeconds(86400)).single().identity!!
            val attachment = client.message(identity).attachments.single()
            val output = java.io.ByteArrayOutputStream()
            assertEquals(
                payload.size.toLong(),
                client.downloadAttachment(identity, attachment.partId, output),
            )
            assertArrayEquals(payload, output.toByteArray())
            assertFalse(client.message(identity).read)
        }
    }

    @Test
    fun folderLifecycleAndAppendUseTheSameCoreContracts() {
        AngusImapClient().use { client ->
            client.connect(incoming(), Authorization("password"))
            assertTrue(client.supports("IMAP4rev1"))
            assertTrue(client.namespaces().isNotEmpty())
            client.createMailbox("Draft Test")
            client.subscribe("Draft Test", true)
            val raw = org.foxred.kage.core.mime.AngusMimeCodec().encode(email())
            client.append("Draft Test", raw, true)
            assertEquals(1, client.status("Draft Test").messageCount)
            assertEquals(0, client.status("Draft Test").unreadCount)
            assertEquals(1, client.poll("Draft Test").messageCount)
            client.renameMailbox("Draft Test", "Draft Renamed")
            assertTrue(client.mailboxes().any { it.name == "Draft Renamed" })
            val header = client.messages("Draft Renamed", Instant.EPOCH).single()
            assertEquals(email().messageId, header.messageId)
            client.subscribe("Draft Renamed", false)
            client.deleteMailbox("Draft Renamed")
            assertFalse(client.mailboxes().any { it.name == "Draft Renamed" })
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

    @Test
    fun movePreservesUnrelatedMessagesAndCanReconnectAfterCancellation() {
        repeat(2) { i ->
            AngusSmtpClient()
                .send(
                    outgoing(),
                    Authorization("password"),
                    email().copy(subject = "Message $i", bcc = emptyList()),
                )
        }
        AngusImapClient().use { client ->
            client.connect(incoming(), Authorization("password"))
            assertTrue(client.supports("MOVE"))
            client.createMailbox("Archive Test")
            val chosen = client.messages("INBOX", Instant.EPOCH).first()
            client.move(chosen.identity!!, "Archive Test")
            assertEquals(1, client.status("INBOX").messageCount)
            assertEquals(
                chosen.subject,
                client.messages("Archive Test", Instant.EPOCH).single().subject,
            )
            client.cancel()
            client.connect(incoming(), Authorization("password"))
            assertEquals(1, client.status("INBOX").messageCount)
        }
    }
}
