package org.foxred.kage.core

import org.foxred.kage.core.account.*
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.junit.Assert.*
import org.junit.Test

class MailCoreRuntimeTest {
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
