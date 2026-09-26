package org.foxred.kage.core.account

import java.net.URI
import java.time.Instant
import java.util.UUID

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

enum class AuthenticationType {
    PASSWORD,
    OAUTH2,
    NONE,
}

sealed interface DeletePolicy {
    data object Never : DeletePolicy

    data object OnDelete : DeletePolicy

    data object MarkAsRead : DeletePolicy

    data class After(val days: Int = 7) : DeletePolicy {
        init {
            require(days >= 0)
        }
    }
}

data class OAuthConfiguration(
    val clientId: String,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val redirectUri: URI,
    val scopes: List<String>,
)

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
    val authenticationType: AuthenticationType = AuthenticationType.PASSWORD,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(hostname.isNotBlank())
        require(port in 1..65535)
        require(username.isNotBlank() || authenticationType == AuthenticationType.NONE)
    }
}

/** Intentionally not a data class: generated toString must never disclose a credential. */
class Authorization(
    val secret: String,
    val kind: Kind = Kind.APP_PASSWORD,
    val expiresAt: Instant? = null,
    val refreshToken: String? = null,
) {
    enum class Kind {
        APP_PASSWORD,
        OAUTH2,
        NONE,
    }

    init {
        require(secret.isNotBlank() || kind == Kind.NONE)
    }

    fun isExpired(now: Instant = Instant.now()): Boolean =
        expiresAt?.let { !it.isAfter(now) } ?: false

    companion object {
        fun none() = Authorization("", Kind.NONE)
    }

    override fun toString() = "Authorization([redacted], $kind)"
}

data class Account(
    val id: String,
    val name: String,
    val identities: List<EmailAddress>,
    val incomingServer: Server,
    val outgoingServer: Server,
    val deletePolicy: DeletePolicy = DeletePolicy.Never,
    val avatarColor: String = "user-blue",
    val authConfig: OAuthConfiguration? = null,
) {
    val servers: List<Server>
        get() = listOf(incomingServer, outgoingServer)

    val emailAddress: EmailAddress?
        get() = identities.firstOrNull()

    fun server(protocol: ServerProtocol): Server? = servers.firstOrNull { it.protocol == protocol }
}

enum class MailboxRole {
    INBOX,
    SENT,
    DRAFTS,
    ARCHIVE,
    TRASH,
    JUNK,
    ALL,
    IMPORTANT,
}

/** Unknown rights remain null until the server supplies them, rather than inventing permissions. */
data class MailboxRights(
    val mayReadItems: Boolean? = null,
    val mayAddItems: Boolean? = null,
    val mayRemoveItems: Boolean? = null,
    val maySetSeen: Boolean? = null,
    val maySetKeywords: Boolean? = null,
    val mayCreateChild: Boolean? = null,
    val mayRename: Boolean? = null,
    val mayDelete: Boolean? = null,
)

data class Mailbox(
    val name: String,
    val delimiter: Char,
    val selectable: Boolean,
    val attributes: Set<String> = emptySet(),
    val isSubscribed: Boolean = false,
    val unreadEmails: Int? = null,
    val totalEmails: Int? = null,
    val rights: MailboxRights = MailboxRights(),
    val id: String? = null,
) {
    val role: MailboxRole?
        get() =
            if (name.equals("INBOX", true)) MailboxRole.INBOX
            else
                MailboxRole.entries.firstOrNull { candidate ->
                    attributes.any { it.equals("\\" + candidate.name, true) }
                }
}

data class Folder(
    val accountId: String,
    val mailbox: Mailbox,
    val parentPath: String?,
    val subfolders: List<Folder> = emptyList(),
    val id: String = "$accountId:${mailbox.name}",
) {
    val path: String
        get() = mailbox.name

    val name: String
        get() = mailbox.name.substringAfterLast(mailbox.delimiter)

    val unreadEmails: Int?
        get() = mailbox.unreadEmails

    val totalEmails: Int?
        get() = mailbox.totalEmails

    fun aggregatedUnreadCount(): Int =
        (unreadEmails ?: 0) + subfolders.sumOf { it.aggregatedUnreadCount() }
}

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

/** Attachments live on Email so metadata remains available before fetching body content. */
data class EmailBody(val text: String?, val html: String?) {
    val preview: String?
        get() = text?.replace(Regex("\\s+"), " ")?.trim()?.take(200)
}

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
    val bodyDownloaded: Boolean = false,
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

data class MailboxFlagChange(val uid: Long, val read: Boolean, val flagged: Boolean)

/** A CONDSTORE/QRESYNC checkpoint; null from [MailStore.changes] means scan the mailbox. */
data class MailboxChanges(
    val uidValidity: Long,
    val highestModSeq: Long,
    val flags: List<MailboxFlagChange>,
    val vanishedUids: Set<Long>,
)

data class Namespace(val prefix: String, val delimiter: Char, val shared: Boolean = false)
