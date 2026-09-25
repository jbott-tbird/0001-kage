package org.foxred.kage.core.mime

import jakarta.activation.DataHandler
import jakarta.mail.*
import jakarta.mail.internet.*
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Properties
import org.foxred.kage.core.account.*

class AngusMimeCodec(
    private val maxBytes: Int = 25 * 1024 * 1024,
    private val maxDepth: Int = 32,
    private val boundary: (String) -> String = { "kage_" + java.util.UUID.randomUUID().toString() },
) : MimeCodec {
    init {
        require(maxBytes > 0 && maxDepth > 0)
    }

    private fun parse(raw: ByteArray): MimeMessage {
        if (raw.size > maxBytes)
            throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Message exceeds size limit")
        return MimeMessage(Session.getInstance(Properties()), raw.inputStream())
    }

    override fun decode(raw: ByteArray): Email =
        try {
            val message = parse(raw)
            val content = AngusPartReader(maxBytes, maxDepth).read(message)
            AngusEnvelopeReader.read(message)
                .copy(body = content.body, attachments = content.attachments, bodyDownloaded = true)
        } catch (failure: MailFailure) {
            throw failure
        } catch (failure: Exception) {
            throw MailFailure(
                FailureKind.INVALID_MESSAGE,
                "Message MIME could not be decoded",
                failure,
            )
        }

    override fun attachment(raw: ByteArray, partId: String): ByteArray =
        ByteArrayOutputStream()
            .also { AngusPartReader(maxBytes, maxDepth).attachment(parse(raw), partId, it) }
            .toByteArray()

    override fun encode(email: OutgoingEmail): ByteArray {
        fun safe(value: String): String {
            require(!value.contains('\r') && !value.contains('\n')) { "Invalid header" }
            return value
        }
        fun address(a: EmailAddress) =
            InternetAddress(safe(a.address), safe(a.name), "UTF-8").apply { validate() }
        require(email.to.isNotEmpty() || email.cc.isNotEmpty() || email.bcc.isNotEmpty())
        if (email.attachments.sumOf { it.data.size.toLong() } > maxBytes)
            throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Attachments exceed message limit")
        val usedBoundaries = mutableSetOf<String>()
        fun multipart(subtype: String): MimeMultipart {
            val value = boundary(subtype)
            require(value.matches(Regex("[A-Za-z0-9_=-]{1,70}")) && usedBoundaries.add(value)) {
                "Invalid or duplicate MIME boundary"
            }
            return object : MimeMultipart(subtype) {
                init {
                    contentType =
                        ContentType(
                                "multipart",
                                subtype,
                                ParameterList().apply { set("boundary", value) },
                            )
                            .toString()
                }
            }
        }
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
                    val alt = multipart("alternative")
                    alt.addBodyPart(MimeBodyPart().apply { setText(email.body.text, "UTF-8") })
                    alt.addBodyPart(
                        MimeBodyPart().apply { setText(email.body.html, "UTF-8", "html") }
                    )
                    setContent(alt)
                } else if (email.body.html != null) setText(email.body.html, "UTF-8", "html")
                else setText(email.body.text ?: "", "UTF-8")
            }
        val mixed = multipart("mixed")
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
        val out =
            object : ByteArrayOutputStream() {
                private fun checkSize(addition: Int) {
                    if (count.toLong() + addition > maxBytes)
                        throw MailFailure(
                            FailureKind.LIMIT_EXCEEDED,
                            "Encoded message exceeds limit",
                        )
                }

                override fun write(value: Int) {
                    checkSize(1)
                    super.write(value)
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    checkSize(length)
                    super.write(bytes, offset, length)
                }
            }
        msg.writeTo(out)
        return out.toByteArray()
    }
}
