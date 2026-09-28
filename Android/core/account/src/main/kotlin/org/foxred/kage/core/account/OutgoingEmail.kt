package org.foxred.kage.core.account

import java.time.Instant

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
