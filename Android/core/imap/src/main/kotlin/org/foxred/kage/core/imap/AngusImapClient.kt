package org.foxred.kage.core.imap

import jakarta.mail.*
import jakarta.mail.Folder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Date
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.transport.connectionProperties

/** One serialized session per account; folder selection never leaks across callers. */
class AngusImapClient(private val codec: MimeCodec = AngusMimeCodec()) : MailStore {
    private var store: IMAPStore? = null

    @Synchronized
    override fun connect(server: Server, authorization: Authorization) {
        require(server.protocol == ServerProtocol.IMAP)
        close()
        val candidate =
            Session.getInstance(connectionProperties(server, authorization)).getStore("imap")
                as IMAPStore
        try {
            candidate.connect(server.hostname, server.port, server.username, authorization.secret)
            store = candidate
        } catch (e: Exception) {
            runCatching { candidate.close() }
            throw failure(e)
        }
    }

    private fun connected(): IMAPStore =
        store?.takeIf { it.isConnected }
            ?: throw MailFailure(FailureKind.CONNECTION, "IMAP session is disconnected")

    @Synchronized
    override fun mailboxes(): List<Mailbox> =
        try {
            connected().defaultFolder.list("*").map {
                val f = it as IMAPFolder
                Mailbox(
                    f.fullName,
                    f.separator,
                    f.type and Folder.HOLDS_MESSAGES != 0,
                    f.attributes.toSet(),
                )
            }
        } catch (e: Exception) {
            throw failure(e)
        }

    private fun <T> folder(name: String, write: Boolean = false, block: (IMAPFolder) -> T): T {
        val f = connected().getFolder(name) as IMAPFolder
        try {
            f.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
            return block(f)
        } catch (e: Exception) {
            throw failure(e)
        } finally {
            if (f.isOpen) runCatching { f.close(false) }
        }
    }

    private fun identified(f: IMAPFolder, id: MessageIdentity): Message {
        if (f.uidValidity != id.uidValidity)
            throw MailFailure(FailureKind.PROTOCOL, "Mailbox identity changed; refresh required")
        return f.getMessageByUID(id.uid)
            ?: throw MailFailure(FailureKind.PROTOCOL, "Message no longer exists")
    }

    private fun raw(message: Message): ByteArray =
        ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()

    private fun envelope(f: IMAPFolder, m: Message): Email {
        fun addresses(a: Array<Address>?) =
            a.orEmpty().map {
                val v = it as InternetAddress
                EmailAddress(v.address, v.personal ?: "")
            }
        return Email(
            MessageIdentity(f.fullName, f.uidValidity, f.getUID(m)),
            m.getHeader("Message-ID")?.firstOrNull(),
            m.subject ?: "",
            addresses(m.from),
            addresses(m.getRecipients(Message.RecipientType.TO)),
            addresses(m.getRecipients(Message.RecipientType.CC)),
            (m.receivedDate ?: m.sentDate)?.toInstant(),
            EmailBody(null, null),
            emptyList(),
            m.isSet(Flags.Flag.SEEN),
            m.isSet(Flags.Flag.FLAGGED),
        )
    }

    @Synchronized
    override fun messages(mailbox: String, since: Instant, limit: Int): List<Email> {
        require(limit in 1..1000)
        return folder(mailbox) { f ->
            val messages =
                f.search(ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)))
                    .takeLast(limit)
                    .toTypedArray()
            f.fetch(
                messages,
                FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                },
            )
            messages.map { envelope(f, it) }.reversed()
        }
    }

    @Synchronized
    override fun message(identity: MessageIdentity): Email =
        folder(identity.mailbox) { f ->
            val m = identified(f, identity)
            codec
                .decode(raw(m))
                .copy(
                    identity = identity,
                    read = m.isSet(Flags.Flag.SEEN),
                    flagged = m.isSet(Flags.Flag.FLAGGED),
                )
        }

    @Synchronized
    override fun attachment(identity: MessageIdentity, partId: String): ByteArray =
        folder(identity.mailbox) { f -> codec.attachment(raw(identified(f, identity)), partId) }

    @Synchronized
    override fun markRead(identity: MessageIdentity, read: Boolean) {
        folder(identity.mailbox, true) { f ->
            identified(f, identity).setFlag(Flags.Flag.SEEN, read)
        }
    }

    @Synchronized
    override fun flag(identity: MessageIdentity, flagged: Boolean) {
        folder(identity.mailbox, true) { f ->
            identified(f, identity).setFlag(Flags.Flag.FLAGGED, flagged)
        }
    }

    @Synchronized
    override fun move(identity: MessageIdentity, targetMailbox: String) {
        folder(identity.mailbox, true) { f ->
            // Never emulate MOVE with an unrestricted EXPUNGE: other deleted messages may belong to
            // another client.
            if (!connected().hasCapability("MOVE"))
                throw MailFailure(FailureKind.PROTOCOL, "Server does not support safe MOVE")
            f.moveMessages(arrayOf(identified(f, identity)), connected().getFolder(targetMailbox))
        }
    }

    @Synchronized
    override fun close() {
        val current = store
        store = null
        if (current != null) runCatching { current.close() }
    }

    private fun failure(e: Exception): MailFailure =
        when (e) {
            is MailFailure -> e
            is AuthenticationFailedException ->
                MailFailure(FailureKind.AUTHENTICATION, "IMAP authentication failed", e)
            else -> MailFailure(FailureKind.CONNECTION, "IMAP operation failed", e)
        }
}
