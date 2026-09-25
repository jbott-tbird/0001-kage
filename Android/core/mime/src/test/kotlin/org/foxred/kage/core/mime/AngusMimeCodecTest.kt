package org.foxred.kage.core.mime

import java.time.Instant
import org.foxred.kage.core.account.*
import org.junit.Assert.*
import org.junit.Test

class AngusMimeCodecTest {
    private val codec = AngusMimeCodec()

    private fun sample() =
        OutgoingEmail(
            "<test-42@example.net>",
            EmailAddress("sender@example.net", "روك"),
            listOf(EmailAddress("to@example.net")),
            bcc = listOf(EmailAddress("hidden@example.net")),
            subject = "שלום — café",
            body = EmailBody("Hello\nمرحبا", "<p dir=rtl>שלום</p>"),
            attachments =
                listOf(
                    OutgoingAttachment(
                        "résumé.bin",
                        "application/octet-stream",
                        byteArrayOf(0, 1, -1, 13, 10),
                    )
                ),
            inReplyTo = "<original@example.net>",
            references = listOf("<original@example.net>"),
            sentAt = Instant.parse("2026-09-25T12:00:00Z"),
        )

    @Test
    fun roundTripPreservesUnicodeAlternativesAttachmentAndHeaders() {
        val raw = codec.encode(sample())
        val result = codec.decode(raw)
        assertEquals(sample().subject, result.subject)
        assertEquals(sample().from, result.from.single())
        assertEquals(sample().body.text, result.body.text)
        assertEquals(sample().body.html, result.body.html)
        assertEquals(sample().messageId, result.messageId)
        assertEquals(sample().sentAt, result.receivedAt)
        assertEquals("résumé.bin", result.attachments.single().filename)
        assertArrayEquals(
            sample().attachments.single().data,
            codec.attachment(raw, result.attachments.single().partId),
        )
        assertFalse(raw.toString(Charsets.UTF_8).contains("hidden@example.net"))
        assertTrue(raw.toString(Charsets.UTF_8).contains("In-Reply-To: <original@example.net>"))
    }

    @Test
    fun decodesFoldedEncodedHeaderAndQuotedPrintable() {
        val raw =
            "From: =?UTF-8?B?Um9j?= <roc@example.net>\r\nSubject: =?UTF-8?Q?caf=C3=A9?=\r\n =?UTF-8?Q?_review?=\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\ncaf=C3=A9\r\n"
                .toByteArray()
        val result = codec.decode(raw)
        assertEquals("café review", result.subject)
        assertEquals("café\r\n", result.body.text)
        assertEquals("Roc", result.from.single().name)
    }

    @Test
    fun missingHeadersRemainReadable() {
        val result = codec.decode("\r\nhello".toByteArray())
        assertEquals("", result.subject)
        assertTrue(result.from.isEmpty())
        assertEquals("hello", result.body.text)
    }

    @Test
    fun inlineCidIsAnAttachmentWithoutLosingHtml() {
        val raw =
            "Content-Type: multipart/related; boundary=x\r\n\r\n--x\r\nContent-Type: text/html\r\n\r\n<img src=cid:logo>\r\n--x\r\nContent-Type: image/png\r\nContent-ID: <logo>\r\nContent-Disposition: inline\r\nContent-Transfer-Encoding: base64\r\n\r\nAAEC\r\n--x--\r\n"
                .toByteArray()
        val result = codec.decode(raw)
        assertEquals("logo", result.attachments.single().contentId)
        assertTrue(result.attachments.single().inline)
        assertArrayEquals(
            byteArrayOf(0, 1, 2),
            codec.attachment(raw, result.attachments.single().partId),
        )
    }

    @Test
    fun limitsRejectOversizedInput() {
        val error = assertThrows(MailFailure::class.java) { AngusMimeCodec(8).decode(ByteArray(9)) }
        assertEquals(FailureKind.LIMIT_EXCEEDED, error.kind)
    }

    @Test
    fun cannotInjectHeaders() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(sample().copy(subject = "Hi\r\nBcc: bad@example.net"))
        }
    }

    @Test
    fun invalidPartDoesNotReturnWholeMessage() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.attachment(codec.encode(sample()), "0.1")
        }
    }
}
