package org.foxred.kage.core.account

import java.time.Instant

data class MessageIdentity(val mailbox: String, val uidValidity: Long, val uid: Long) {
    init {
        require(uidValidity > 0 && uid > 0)
    }
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
