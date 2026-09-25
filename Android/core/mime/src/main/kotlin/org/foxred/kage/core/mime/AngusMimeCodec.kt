package org.foxred.kage.core.mime

import jakarta.activation.DataHandler
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Properties
import org.foxred.kage.core.account.*

class AngusMimeCodec(private val maxBytes: Int = 25 * 1024 * 1024, private val maxDepth: Int = 32) :
    MimeCodec {
    init {
        require(maxBytes > 0 && maxDepth > 0)
    }

    private fun parse(raw: ByteArray): MimeMessage {
        if (raw.size > maxBytes)
            throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Message exceeds size limit")
        return MimeMessage(Session.getInstance(Properties()), raw.inputStream())
    }

    override fun decode(raw: ByteArray): Email {
        val message = parse(raw)
        val texts = mutableListOf<String>()
        val html = mutableListOf<String>()
        val attachments = mutableListOf<EmailAttachment>()
        fun visit(part: Part, path: String, depth: Int) {
            if (depth > maxDepth)
                throw MailFailure(FailureKind.LIMIT_EXCEEDED, "MIME nesting exceeds limit")
            val type = ContentType(part.contentType).baseType.lowercase()
            val cid = part.getHeader("Content-ID")?.firstOrNull()?.trim('<', '>')
            if (
                part.disposition.equals(Part.ATTACHMENT, true) ||
                    part.fileName != null ||
                    (!part.isMimeType("multipart/*") &&
                        !part.isMimeType("text/plain") &&
                        !part.isMimeType("text/html"))
            ) {
                attachments +=
                    EmailAttachment(
                        path,
                        part.fileName?.let { MimeUtility.decodeText(it) } ?: "attachment",
                        type,
                        part.size.toLong(),
                        cid,
                        part.disposition.equals(Part.INLINE, true),
                    )
            } else if (part.isMimeType("multipart/*")) {
                val multipart = part.content as Multipart
                for (i in 0 until multipart.count) visit(
                    multipart.getBodyPart(i),
                    "$path.${i + 1}",
                    depth + 1,
                )
            } else if (part.isMimeType("text/html")) html += part.content as String
            else if (part.isMimeType("text/plain")) texts += part.content as String
        }
        visit(message, "1", 0)
        fun addresses(values: Array<Address>?) =
            values.orEmpty().map {
                val a = it as InternetAddress
                EmailAddress(a.address, a.personal ?: "")
            }
        return Email(
            null,
            message.messageID,
            message.subject ?: "",
            addresses(message.from),
            addresses(message.getRecipients(Message.RecipientType.TO)),
            addresses(message.getRecipients(Message.RecipientType.CC)),
            (message.receivedDate ?: message.sentDate)?.toInstant(),
            EmailBody(
                texts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                html.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ),
            attachments,
        )
    }

    override fun attachment(raw: ByteArray, partId: String): ByteArray {
        val indices =
            partId.split('.').map {
                it.toIntOrNull() ?: throw IllegalArgumentException("Invalid part ID")
            }
        require(indices.firstOrNull() == 1 && indices.size <= maxDepth + 1)
        var part: Part = parse(raw)
        for (index in indices.drop(1)) {
            val multipart =
                part.content as? Multipart ?: throw IllegalArgumentException("Not a multipart")
            require(index in 1..multipart.count)
            part = multipart.getBodyPart(index - 1)
        }
        return part.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > maxBytes)
                    throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Attachment exceeds limit")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }

    override fun encode(email: OutgoingEmail): ByteArray {
        fun safe(value: String): String {
            require(!value.contains('\r') && !value.contains('\n')) { "Invalid header" }
            return value
        }
        fun address(a: EmailAddress) =
            InternetAddress(safe(a.address), safe(a.name), "UTF-8").apply { validate() }
        require(email.to.isNotEmpty() || email.cc.isNotEmpty() || email.bcc.isNotEmpty())
        val msg = MimeMessage(Session.getInstance(Properties()))
        msg.setFrom(address(email.from))
        msg.setRecipients(Message.RecipientType.TO, email.to.map(::address).toTypedArray())
        msg.setRecipients(Message.RecipientType.CC, email.cc.map(::address).toTypedArray())
        // Bcc is deliberately carried only by the SMTP envelope.
        msg.setSubject(safe(email.subject), "UTF-8")
        msg.sentDate = Date.from(email.sentAt)
        fun body(): MimeBodyPart =
            MimeBodyPart().apply {
                if (email.body.html != null && email.body.text != null) {
                    val alt = MimeMultipart("alternative")
                    alt.addBodyPart(MimeBodyPart().apply { setText(email.body.text, "UTF-8") })
                    alt.addBodyPart(
                        MimeBodyPart().apply { setText(email.body.html, "UTF-8", "html") }
                    )
                    setContent(alt)
                } else if (email.body.html != null) setText(email.body.html, "UTF-8", "html")
                else setText(email.body.text ?: "", "UTF-8")
            }
        val mixed = MimeMultipart("mixed")
        mixed.addBodyPart(body())
        email.attachments.forEach { attachment ->
            require(attachment.data.size <= maxBytes)
            mixed.addBodyPart(
                MimeBodyPart().apply {
                    dataHandler =
                        DataHandler(
                            ByteArrayDataSource(attachment.data, safe(attachment.mediaType))
                        )
                    fileName = safe(attachment.filename)
                    disposition = Part.ATTACHMENT
                }
            )
        }
        msg.setContent(mixed)
        email.inReplyTo?.let { msg.setHeader("In-Reply-To", safe(it)) }
        if (email.references.isNotEmpty())
            msg.setHeader("References", email.references.joinToString(" ") { safe(it) })
        msg.saveChanges()
        msg.setHeader("Message-ID", safe(email.messageId))
        return ByteArrayOutputStream()
            .also { msg.writeTo(it) }
            .toByteArray()
            .also {
                if (it.size > maxBytes)
                    throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Encoded message exceeds limit")
            }
    }
}
