package org.foxred.kage.core.mime

import jakarta.mail.Address
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import org.foxred.kage.core.account.*

/** Provider-only mapping reused for stored RFC822 bytes and live IMAP messages. */
object AngusEnvelopeReader {
    fun read(message: Message): Email {
        fun addresses(values: Array<out Address>?): List<EmailAddress> =
            values.orEmpty().flatMap { value ->
                val address =
                    value as? InternetAddress
                        ?: throw MailFailure(
                            FailureKind.INVALID_MESSAGE,
                            "Unsupported address representation",
                        )
                if (address.isGroup) addresses(address.getGroup(false))
                else listOf(EmailAddress(address.address, address.personal ?: ""))
            }
        fun headerAddresses(name: String): List<EmailAddress> =
            message.getHeader(name)?.let {
                addresses(InternetAddress.parseHeader(it.joinToString(","), false))
            } ?: emptyList()
        fun ids(name: String): List<String> =
            message.getHeader(name).orEmpty().flatMap { value ->
                Regex("<[^>]+>")
                    .findAll(value)
                    .map { it.value }
                    .toList()
                    .ifEmpty { value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
            }
        val messageIds = ids("Message-ID")
        return Email(
            null,
            messageIds.firstOrNull(),
            message.subject ?: "",
            addresses(message.from),
            addresses(message.getRecipients(Message.RecipientType.TO)),
            addresses(message.getRecipients(Message.RecipientType.CC)),
            (message.receivedDate ?: message.sentDate)?.toInstant(),
            EmailBody(null, null),
            emptyList(),
            message.isSet(Flags.Flag.SEEN),
            message.isSet(Flags.Flag.FLAGGED),
            headerAddresses("Sender"),
            headerAddresses("Reply-To"),
            addresses(message.getRecipients(Message.RecipientType.BCC)),
            message.sentDate?.toInstant(),
            messageIds,
            emptyList(),
            ids("In-Reply-To"),
            ids("References"),
        )
    }
}
