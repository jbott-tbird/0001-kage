package org.foxred.kage.core.mime

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import org.foxred.kage.core.account.*

/**
 * Provider adapter helper shared by raw-MIME and lazy IMAP readers; never exposed by domain
 * contracts.
 */
class AngusPartReader(
    private val maxBytes: Int = 25 * 1024 * 1024,
    private val maxDepth: Int = 32,
    private val maxParts: Int = 1000,
) {
    init {
        require(maxBytes > 0 && maxDepth > 0 && maxParts > 0)
    }

    data class Content(val body: EmailBody, val attachments: List<EmailAttachment>)

    fun read(root: Part): Content {
        val attachments = mutableListOf<EmailAttachment>()
        var remaining = maxBytes.toLong()
        var visited = 0
        fun text(part: Part): String {
            val out = ByteArrayOutputStream()
            val count = copy(part, out, remaining)
            remaining -= count
            val charsetName = ContentType(part.contentType).getParameter("charset") ?: "US-ASCII"
            val charset =
                try {
                    Charset.forName(MimeUtility.javaCharset(charsetName))
                } catch (e: Exception) {
                    throw MailFailure(
                        FailureKind.INVALID_MESSAGE,
                        "Unsupported message character set",
                        e,
                    )
                }
            return out.toByteArray().toString(charset)
        }
        fun visit(part: Part, path: String, depth: Int): EmailBody {
            if (++visited > maxParts || depth > maxDepth)
                throw MailFailure(FailureKind.LIMIT_EXCEEDED, "MIME structure exceeds limit")
            val type = ContentType(part.contentType).baseType.lowercase()
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
                        part.getHeader("Content-ID")?.firstOrNull()?.trim('<', '>'),
                        part.disposition.equals(Part.INLINE, true),
                    )
                return EmailBody(null, null)
            }
            if (part.isMimeType("multipart/*")) {
                // IMAP's multipart object uses BODYSTRUCTURE; it does not materialize leaf
                // contents.
                if (ContentType(part.contentType).getParameter("boundary").isNullOrBlank())
                    throw MailFailure(FailureKind.INVALID_MESSAGE, "Multipart boundary is missing")
                val multipart = part.content as Multipart
                if (multipart is MimeMultipart && !multipart.isComplete)
                    throw MailFailure(FailureKind.INVALID_MESSAGE, "Multipart message is truncated")
                if (multipart.count > maxParts - visited)
                    throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Too many MIME parts")
                val children =
                    (0 until multipart.count).map {
                        visit(multipart.getBodyPart(it), "$path.${it + 1}", depth + 1)
                    }
                fun combine(values: List<String>): String? =
                    if (part.isMimeType("multipart/alternative")) values.lastOrNull()
                    else values.takeIf { it.isNotEmpty() }?.joinToString("\n")
                return EmailBody(
                    combine(children.mapNotNull { it.text }),
                    combine(children.mapNotNull { it.html }),
                )
            }
            return if (part.isMimeType("text/html")) EmailBody(null, text(part))
            else EmailBody(text(part), null)
        }
        return Content(visit(root, "1", 0), attachments)
    }

    fun attachment(root: Part, partId: String, output: OutputStream): Long {
        val indices =
            partId.split('.').map {
                it.toIntOrNull() ?: throw IllegalArgumentException("Invalid part ID")
            }
        require(indices.firstOrNull() == 1 && indices.size <= maxDepth + 1)
        var part = root
        for (index in indices.drop(1)) {
            val multi =
                part.content as? Multipart ?: throw IllegalArgumentException("Not a multipart")
            require(index in 1..multi.count)
            part = multi.getBodyPart(index - 1)
        }
        return copy(part, output, maxBytes.toLong())
    }

    private fun copy(part: Part, output: OutputStream, limit: Long): Long =
        part.inputStream.use { input ->
            var copied = 0L
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted)
                    throw java.io.InterruptedIOException("MIME read interrupted")
                val n = input.read(buffer)
                if (n < 0) break
                if (copied + n > limit)
                    throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Decoded content exceeds limit")
                output.write(buffer, 0, n)
                copied += n
            }
            copied
        }
}
