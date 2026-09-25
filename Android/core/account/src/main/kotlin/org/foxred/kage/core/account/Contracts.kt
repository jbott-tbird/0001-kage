package org.foxred.kage.core.account

import java.time.Instant

/** Blocking I/O boundary: callers use an I/O dispatcher, never the UI thread. */
interface MailStore : AutoCloseable {
    fun connect(server: Server, authorization: Authorization)

    fun mailboxes(): List<Mailbox>

    fun messages(mailbox: String, since: Instant, limit: Int = 100): List<Email>

    fun message(identity: MessageIdentity): Email

    fun attachment(identity: MessageIdentity, partId: String): ByteArray

    fun markRead(identity: MessageIdentity, read: Boolean)

    fun flag(identity: MessageIdentity, flagged: Boolean)

    fun move(identity: MessageIdentity, targetMailbox: String)
}

interface MimeCodec {
    fun decode(raw: ByteArray): Email

    fun encode(email: OutgoingEmail): ByteArray

    fun attachment(raw: ByteArray, partId: String): ByteArray
}

interface MailSubmission {
    fun send(server: Server, authorization: Authorization, email: OutgoingEmail)
}
