package org.foxred.kage.core.account

import java.time.Instant

/** Domain vocabulary mirrors iOS Core/Account; no provider or persistence types escape here. */
data class EmailAddress(val address: String, val name: String = "") {
    init {
        require(address.isNotBlank() && !address.contains('\r') && !address.contains('\n'))
    }
}

enum class ConnectionSecurity {
    TLS,
    STARTTLS,
}

enum class ServerProtocol {
    IMAP,
    SMTP,
}

data class Server(
    val hostname: String,
    val port: Int,
    val protocol: ServerProtocol,
    val security: ConnectionSecurity = ConnectionSecurity.TLS,
    val username: String,
) {
    init {
        require(hostname.isNotBlank())
        require(port in 1..65535)
        require(username.isNotBlank())
    }
}

/** Intentionally not a data class: generated toString must never disclose a credential. */
class Authorization(val secret: String, val kind: Kind = Kind.APP_PASSWORD) {
    enum class Kind {
        APP_PASSWORD,
        OAUTH2,
    }

    init {
        require(secret.isNotBlank())
    }

    override fun toString() = "Authorization([redacted], $kind)"
}

data class Account(
    val id: String,
    val name: String,
    val identities: List<EmailAddress>,
    val incomingServer: Server,
    val outgoingServer: Server,
)

data class Mailbox(
    val name: String,
    val delimiter: Char,
    val selectable: Boolean,
    val attributes: Set<String> = emptySet(),
)

data class Folder(val accountId: String, val mailbox: Mailbox, val parentPath: String?)

data class MessageIdentity(val mailbox: String, val uidValidity: Long, val uid: Long) {
    init {
        require(uidValidity > 0 && uid > 0)
    }
}

data class EmailAttachment(
    val partId: String,
    val filename: String,
    val mediaType: String,
    val size: Long,
    val contentId: String? = null,
    val inline: Boolean = false,
)

data class EmailBody(val text: String?, val html: String?)

data class Email(
    val identity: MessageIdentity?,
    val messageId: String?,
    val subject: String,
    val from: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val receivedAt: Instant?,
    val body: EmailBody,
    val attachments: List<EmailAttachment>,
    val read: Boolean = false,
    val flagged: Boolean = false,
    val sender: List<EmailAddress> = emptyList(),
    val replyTo: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val sentAt: Instant? = null,
    val messageIds: List<String> = listOfNotNull(messageId),
    val threadIds: List<String> = emptyList(),
    val inReplyTo: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val blobId: String? = null,
)

data class OutgoingAttachment(val filename: String, val mediaType: String, val data: ByteArray)

data class OutgoingEmail(
    val messageId: String,
    val from: EmailAddress,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String,
    val body: EmailBody,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val sentAt: Instant = Instant.now(),
)

enum class FailureKind {
    CANCELLED,
    AUTHENTICATION,
    CONNECTION,
    PROTOCOL,
    INVALID_MESSAGE,
    LIMIT_EXCEEDED,
    UNCERTAIN_DELIVERY,
}

class MailFailure(val kind: FailureKind, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Cursor binds a bounded UID window to a mailbox generation and an exact sync cutoff. */
data class MessageCursor(
    val mailbox: String,
    val uidValidity: Long,
    val beforeUid: Long,
    val since: Instant,
) {
    init {
        require(uidValidity > 0 && beforeUid > 0)
    }
}

data class MessagePage(val messages: List<Email>, val next: MessageCursor?)

data class MailboxStatus(
    val uidValidity: Long,
    val uidNext: Long,
    val messageCount: Int,
    val unreadCount: Int,
)

data class Namespace(val prefix: String, val delimiter: Char, val shared: Boolean = false)
