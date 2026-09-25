package org.foxred.kage.core.mime

import java.time.Instant
import org.foxred.kage.core.account.*
import org.junit.Assert.*
import org.junit.Test

class AngusMimeCodecTest {
    private fun related(start: String = "; start=\"<root>\"") =
        ("Content-Type: multipart/related; boundary=x; type=\"text/html\"$start\r\n\r\n" +
                "--x\r\nContent-Type: text/plain\r\nContent-ID: <resource>\r\n\r\nauxiliary text\r\n" +
                "--x\r\nContent-Type: text/html\r\nContent-ID: <root>\r\n" +
                "Content-Disposition: attachment; filename=body.html\r\n\r\n<p>Root</p>\r\n--x--\r\n")
            .toByteArray()

    @Test
    fun relatedStartSelectsRootAndKeepsTextResourceOutOfBody() {
        val raw = related()
        val message = codec.decode(raw)
        assertEquals("<p>Root</p>", message.body.html)
        assertNull(message.body.text)
        val resource = message.attachments.single()
        assertEquals("resource", resource.contentId)
        assertTrue(resource.inline)
        assertEquals(
            "auxiliary text",
            codec.attachment(raw, resource.partId).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun relatedWithoutStartUsesFirstPartAndMissingRootFailsExplicitly() {
        val message =
            codec.decode(
                related("")
                    .toString(Charsets.UTF_8)
                    .replace("type=\"text/html\"", "type=\"text/plain\"")
                    .toByteArray()
            )
        assertEquals("auxiliary text", message.body.text)
        assertNull(message.body.html)
        assertEquals("root", message.attachments.single().contentId)
        val failure =
            assertThrows(MailFailure::class.java) { codec.decode(related("; start=\"<missing>\"")) }
        assertEquals(FailureKind.INVALID_MESSAGE, failure.kind)
    }

    @Test
    fun decodedEmptyBodyIsDistinctFromHeadersOnly() {
        val message =
            AngusMimeCodec()
                .decode("Subject: Empty\r\nContent-Type: text/plain\r\n\r\n".toByteArray())
        assertTrue(message.bodyDownloaded)
        assertEquals("", message.body.text)
    }

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
        assertEquals(sample().references, result.references)
        assertEquals(listOf(sample().inReplyTo), result.inReplyTo)
        assertEquals(sample().to, result.to)
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
    fun alternativesSelectLastSupportedRepresentationRatherThanDuplicatingBody() {
        val raw =
            "Content-Type: multipart/alternative; boundary=x\r\n\r\n--x\r\nContent-Type: text/plain\r\n\r\nold\r\n--x\r\nContent-Type: text/plain\r\n\r\nnew\r\n--x--\r\n"
                .toByteArray()
        assertEquals("new", codec.decode(raw).body.text)
    }

    @Test
    fun textAttachmentIsNotConcatenatedIntoMessageBody() {
        val encoded =
            codec.encode(
                sample()
                    .copy(
                        attachments =
                            listOf(
                                OutgoingAttachment(
                                    "note.txt",
                                    "text/plain",
                                    "secret attachment".toByteArray(),
                                )
                            )
                    )
            )
        val decoded = codec.decode(encoded)
        assertFalse(decoded.body.text!!.contains("secret attachment"))
        assertEquals(1, decoded.attachments.size)
    }

    @Test
    fun envelopePreservesReplyRoutingGroupsAndThreadHeaders() {
        val raw =
            "From: Author <author@example.net>\r\nSender: agent@example.net\r\nReply-To: replies@example.net\r\nTo: Reviewers: one@example.net, two@example.net;\r\nBcc: hidden@example.net\r\nMessage-ID: <child@example.net>\r\nReferences: <root@example.net> <parent@example.net>\r\nIn-Reply-To: <parent@example.net>\r\nDate: Fri, 25 Sep 2026 12:00:00 +0000\r\n\r\nbody"
                .toByteArray()
        val message = codec.decode(raw)
        assertEquals("agent@example.net", message.sender.single().address)
        assertEquals("replies@example.net", message.replyTo.single().address)
        assertEquals(listOf("one@example.net", "two@example.net"), message.to.map { it.address })
        assertEquals("hidden@example.net", message.bcc.single().address)
        assertEquals(listOf("<child@example.net>"), message.messageIds)
        assertEquals(listOf("<parent@example.net>"), message.inReplyTo)
        assertEquals(listOf("<root@example.net>", "<parent@example.net>"), message.references)
        assertEquals(Instant.parse("2026-09-25T12:00:00Z"), message.sentAt)
    }

    @Test
    fun unknownCharsetProducesExplicitFailureInsteadOfCorruptText() {
        val error =
            assertThrows(MailFailure::class.java) {
                codec.decode(
                    "Content-Type: text/plain; charset=not-a-real-charset\r\n\r\nbody".toByteArray()
                )
            }
        assertEquals(FailureKind.INVALID_MESSAGE, error.kind)
    }

    @Test
    fun injectedBoundariesMakeOutgoingBytesDeterministic() {
        val stable = AngusMimeCodec(boundary = { "test_$it" })
        assertArrayEquals(stable.encode(sample()), stable.encode(sample()))
        assertEquals(sample().subject, stable.decode(stable.encode(sample())).subject)
    }

    @Test
    fun encodedSizeLimitIsEnforcedDuringSerialization() {
        val failure =
            assertThrows(MailFailure::class.java) {
                AngusMimeCodec(maxBytes = 1024)
                    .encode(
                        sample()
                            .copy(
                                attachments =
                                    listOf(
                                        OutgoingAttachment(
                                            "bytes.bin",
                                            "application/octet-stream",
                                            ByteArray(800),
                                        )
                                    )
                            )
                    )
            }
        assertEquals(FailureKind.LIMIT_EXCEEDED, failure.kind)
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

    @Test
    fun malformedMultipartAndDeepNestingHaveTypedFailures() {
        val truncated =
            "Content-Type: multipart/mixed; boundary=x\r\n\r\n--x\r\nContent-Type: text/plain\r\n\r\ntext"
                .toByteArray()
        val malformed = assertThrows(MailFailure::class.java) { codec.decode(truncated) }
        assertEquals(FailureKind.INVALID_MESSAGE, malformed.kind)
        val withoutBoundary = "Content-Type: multipart/mixed\r\n\r\nbody".toByteArray()
        assertEquals(
            FailureKind.INVALID_MESSAGE,
            assertThrows(MailFailure::class.java) { codec.decode(withoutBoundary) }.kind,
        )
        val nested = codec.encode(sample())
        assertEquals(
            FailureKind.LIMIT_EXCEEDED,
            assertThrows(MailFailure::class.java) { AngusMimeCodec(maxDepth = 1).decode(nested) }
                .kind,
        )
    }
}
