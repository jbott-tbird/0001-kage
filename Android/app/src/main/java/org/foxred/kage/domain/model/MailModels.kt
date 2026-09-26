package org.foxred.kage.domain.model

/** Domain types contain no Android, Compose, or Room dependencies. */
data class Account(
    val id: String,
    val name: String,
    val address: String,
    val incoming: String = "imap.example.com",
    val outgoing: String = "smtp.example.com",
    val incomingPort: Int = 993,
    val outgoingPort: Int = 465,
    val security: String = "SSL/TLS",
    val outgoingSecurity: String = "SSL/TLS",
    val requireAuth: Boolean = true,
)

data class Folder(
    val id: String,
    val accountId: String,
    val name: String,
    val role: String,
    val parentId: String? = null,
    val serverUnreadCount: Int? = null,
    val serverTotalCount: Int? = null,
    val selectable: Boolean = true,
)

data class Attachment(
    val id: String,
    val messageId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val cached: Boolean = false,
    val asset: String = "sample-ticket.pdf",
    val localFile: String? = null,
)

data class Message(
    val id: String,
    val accountId: String,
    val folderId: String,
    val sender: String,
    val senderAddress: String,
    val to: String,
    val cc: String = "",
    val bcc: String = "",
    val subject: String,
    val body: String,
    val html: String? = null,
    val receivedAt: String,
    val isRead: Boolean = false,
    val isNew: Boolean = false,
    val flagged: Boolean = false,
    val pinned: Boolean = false,
    val draft: Boolean = false,
    val relatedGroup: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val preview: String = body.replace('\n', ' '),
    val bodyDownloaded: Boolean = true,
)

data class Preferences(
    val selectedFolder: String = "personal-inbox",
    val unified: Boolean = false,
    val threads: Boolean = false,
    val automaticAttachments: Boolean = false,
    val offline: Boolean = false,
    val started: Boolean = false,
)

data class Mailbox(
    val accounts: List<Account> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val messages: List<Message> = emptyList(),
    val preferences: Preferences = Preferences(),
)

data class MailFilter(
    val unread: Boolean = false,
    val flagged: Boolean = false,
    val pinned: Boolean = false,
    val attachments: Boolean = false,
) {
    val active
        get() = unread || flagged || pinned || attachments
}

enum class SearchScope {
    Account,
    AllAccounts,
}

data class MailQuery(
    val text: String = "",
    val scope: SearchScope = SearchScope.Account,
    val filter: MailFilter = MailFilter(),
)
