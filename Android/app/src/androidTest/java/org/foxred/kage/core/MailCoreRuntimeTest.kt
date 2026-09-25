package org.foxred.kage.core

import org.foxred.kage.core.account.*
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.junit.Assert.*
import org.junit.Test

class MailCoreRuntimeTest {
    @Test
    fun demoAdaptersUseCoreContractsOnAndroid() {
        val store: MailStore = org.foxred.kage.core.demo.DemoMailStore()
        store.use {
            val authorization = Authorization("fixture-only")
            store.connect(
                Server("demo.invalid", 993, ServerProtocol.IMAP, username = "demo"),
                authorization,
            )
            val submission: MailSubmission =
                org.foxred.kage.core.demo.DemoMailSubmission({ store.append("INBOX", it) })
            submission.send(
                Server("demo.invalid", 465, ServerProtocol.SMTP, username = "demo"),
                authorization,
                OutgoingEmail(
                    "<runtime@demo.invalid>",
                    EmailAddress("from@demo.invalid"),
                    listOf(EmailAddress("to@demo.invalid")),
                    subject = "Demo runtime",
                    body = EmailBody("Body", null),
                ),
            )
            val headers = store.messages("INBOX", java.time.Instant.EPOCH)
            assertEquals("Demo runtime", headers.single().subject)
            assertFalse(headers.single().bodyDownloaded)
            assertEquals("Body", store.message(headers.single().identity!!).body.text)
        }
    }

    @Test
    fun mimeAndProviderClassesWorkOnAndroid() {
        val codec = AngusMimeCodec()
        val outgoing =
            OutgoingEmail(
                "<android@example.net>",
                EmailAddress("sender@example.net"),
                listOf(EmailAddress("to@example.net")),
                subject = "مرحبا שלום",
                body = EmailBody("Body", "<p>Body</p>"),
                attachments =
                    listOf(OutgoingAttachment("test.txt", "text/plain", "sample".toByteArray())),
            )
        val raw = codec.encode(outgoing)
        val decoded = codec.decode(raw)
        assertEquals(outgoing.subject, decoded.subject)
        assertArrayEquals(
            "sample".toByteArray(),
            codec.attachment(raw, decoded.attachments.single().partId),
        )
        AngusImapClient().close()
        assertNotNull(AngusSmtpClient())
    }
}
