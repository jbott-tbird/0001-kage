// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

import java.io.OutputStream
import java.time.Instant

/** Blocking I/O boundary: callers use an I/O dispatcher, never the UI thread. */
interface MailStore : AutoCloseable {
    /** Thread-safe interruption; the cancelled session must be reconnected. */
    fun cancel()

    fun connect(server: Server, authorization: Authorization)

    fun mailboxes(): List<Mailbox>

    fun supports(capability: String): Boolean

    fun namespaces(): List<Namespace>

    fun status(mailbox: String): MailboxStatus

    /** Check one stable UID without downloading its body. */
    fun exists(identity: MessageIdentity): Boolean

    fun createMailbox(mailbox: String)

    fun renameMailbox(mailbox: String, target: String)

    fun deleteMailbox(mailbox: String)

    fun subscribe(mailbox: String, subscribed: Boolean)

    /** Wait for a server change or cancellation; caller refreshes state after return. */
    fun awaitChange(mailbox: String)

    fun poll(mailbox: String): MailboxStatus

    /** Optional QRESYNC changes since a durable token; null also requests an initial token. */
    fun changes(mailbox: String, uidValidity: Long, sinceModSeq: Long?): MailboxChanges? = null

    /** Server-confirmed APPEND; identity may be unavailable when UIDPLUS is not supported. */
    fun append(
        mailbox: String, raw: ByteArray, read: Boolean = false, draft: Boolean = false,
    ): MessageIdentity?

    /** Exact Message-ID lookup for reconciling an uncertain submission with Sent. */
    fun findByMessageId(mailbox: String, messageId: String): MessageIdentity?

    /** Convenience first window; sync consumers must continue messagePage until next is null. */
    fun messages(mailbox: String, since: Instant, limit: Int = 100): List<Email> =
        messagePage(mailbox, since, null, limit).messages

    fun messagePage(
        mailbox: String,
        since: Instant,
        cursor: MessageCursor? = null,
        limit: Int = 100,
    ): MessagePage

    fun message(identity: MessageIdentity): Email

    /** Stream the complete server MIME source, without marking it read. Caller owns the sink. */
    fun downloadRawMessage(identity: MessageIdentity, output: OutputStream): Long

    fun attachment(identity: MessageIdentity, partId: String): ByteArray

    /**
     * Writes decoded attachment bytes incrementally. Caller owns output and removes it on failure.
     */
    fun downloadAttachment(identity: MessageIdentity, partId: String, output: OutputStream): Long

    fun markRead(identity: MessageIdentity, read: Boolean)

    fun flag(identity: MessageIdentity, flagged: Boolean)

    fun move(identity: MessageIdentity, targetMailbox: String)

    /** Permanently remove one UID; implementations must never issue unrestricted EXPUNGE. */
    fun delete(identity: MessageIdentity)
}

interface MimeCodec {
    fun decode(raw: ByteArray): Email

    fun encode(email: OutgoingEmail): ByteArray

    /** Unsent drafts may have no recipients; preserve Bcc in their private server copy. */
    fun encodeDraft(email: OutgoingEmail): ByteArray

    fun attachment(raw: ByteArray, partId: String): ByteArray
}

interface MailSubmission {
    /**
     * Interrupt current network submission; after DATA the delivery result may remain uncertain.
     */
    fun cancel()

    fun send(server: Server, authorization: Authorization, email: OutgoingEmail)
}

/** Submits a previously encoded MIME file with its separately stored SMTP envelope. */
interface RawMailSubmission {
    /** Cancel an active or immediately upcoming call to [sendRaw]. */
    fun cancel()

    fun sendRaw(
        server: Server,
        authorization: Authorization,
        raw: ByteArray,
        recipients: List<EmailAddress>,
    )

    /** Signal before the first possible SMTP envelope command. Opaque transports signal early. */
    fun sendRaw(
        server: Server,
        authorization: Authorization,
        raw: ByteArray,
        recipients: List<EmailAddress>,
        onSubmissionStart: () -> Unit,
    ) {
        onSubmissionStart()
        sendRaw(server, authorization, raw, recipients)
    }
}
