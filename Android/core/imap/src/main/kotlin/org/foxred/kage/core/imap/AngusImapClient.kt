package org.foxred.kage.core.imap

import jakarta.mail.*
import jakarta.mail.Folder
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Properties
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusEnvelopeReader
import org.foxred.kage.core.mime.AngusPartReader
import org.foxred.kage.core.transport.ConnectionControl
import org.foxred.kage.core.transport.connectionProperties

/** One serialized session per account; folder selection never leaks across callers. */
class AngusImapClient(
    private val maxPartBytes: Int = 25 * 1024 * 1024,
    private val timeoutMillis: Int = 15000,
) : MailStore {
    @Volatile private var control = ConnectionControl()

    override fun cancel() {
        control.cancel()
    }

    private val partReader = AngusPartReader(maxPartBytes)
    private var store: IMAPStore? = null

    @Synchronized
    override fun connect(server: Server, authorization: Authorization) {
        require(server.protocol == ServerProtocol.IMAP)
        close()
        control = ConnectionControl()
        val properties = connectionProperties(server, authorization, timeoutMillis)
        control.install(properties, "imap")
        val candidate = Session.getInstance(properties).getStore("imap") as IMAPStore
        try {
            candidate.connect(
                server.hostname,
                server.port,
                server.username.takeUnless { authorization.kind == Authorization.Kind.NONE },
                authorization.secret.takeUnless { authorization.kind == Authorization.Kind.NONE },
            )
            store = candidate
        } catch (e: Exception) {
            val error = failure(e)
            control.cancel()
            runCatching { candidate.close() }
            throw error
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
                    isSubscribed = f.isSubscribed,
                )
            }
        } catch (e: Exception) {
            throw failure(e)
        }

    @Synchronized
    override fun supports(capability: String): Boolean {
        require(capability.matches(Regex("[A-Za-z0-9=._-]+")))
        return connected().hasCapability(capability)
    }

    @Synchronized
    override fun namespaces(): List<Namespace> =
        try {
            connected().personalNamespaces.map { Namespace(it.fullName, it.separator) } +
                connected().sharedNamespaces.map { Namespace(it.fullName, it.separator, true) }
        } catch (e: Exception) {
            throw failure(e)
        }

    @Synchronized
    override fun status(mailbox: String): MailboxStatus =
        folder(mailbox) { f ->
            MailboxStatus(f.uidValidity, f.uidNext, f.messageCount, f.unreadMessageCount)
        }

    private fun mailboxAction(name: String, operation: (Folder) -> Boolean) {
        require(name.isNotBlank())
        try {
            if (!operation(connected().getFolder(name)))
                throw MailFailure(FailureKind.PROTOCOL, "Mailbox operation was not accepted")
        } catch (e: Exception) {
            throw failure(e)
        }
    }

    @Synchronized
    override fun createMailbox(mailbox: String) =
        mailboxAction(mailbox) { it.create(Folder.HOLDS_MESSAGES) }

    @Synchronized
    override fun renameMailbox(mailbox: String, target: String) {
        require(target.isNotBlank())
        mailboxAction(mailbox) { it.renameTo(connected().getFolder(target)) }
    }

    @Synchronized
    override fun deleteMailbox(mailbox: String) = mailboxAction(mailbox) { it.delete(false) }

    @Synchronized
    override fun subscribe(mailbox: String, subscribed: Boolean) {
        mailboxAction(mailbox) {
            it.isSubscribed = subscribed
            true
        }
    }

    @Synchronized
    override fun awaitChange(mailbox: String) {
        if (!supports("IDLE"))
            throw MailFailure(FailureKind.PROTOCOL, "Server does not support IDLE; use polling")
        folder(mailbox) { it.idle(true) }
    }

    @Synchronized
    override fun poll(mailbox: String): MailboxStatus =
        folder(mailbox) { f ->
            f.doCommand {
                it.noop()
                null
            }
            MailboxStatus(f.uidValidity, f.uidNext, f.messageCount, f.unreadMessageCount)
        }

    @Synchronized
    override fun append(mailbox: String, raw: ByteArray, read: Boolean): MessageIdentity? {
        if (raw.size > maxPartBytes)
            throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Draft exceeds message limit")
        try {
            val message = MimeMessage(Session.getInstance(Properties()), raw.inputStream())
            message.setFlag(Flags.Flag.SEEN, read)
            val target = connected().getFolder(mailbox) as IMAPFolder
            val result = target.appendUIDMessages(arrayOf(message))?.firstOrNull()
            return result?.let { MessageIdentity(mailbox, it.uidvalidity, it.uid) }
        } catch (e: Exception) {
            throw failure(e)
        }
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

    private fun envelope(f: IMAPFolder, m: Message): Email =
        AngusEnvelopeReader.read(m)
            .copy(identity = MessageIdentity(f.fullName, f.uidValidity, f.getUID(m)))

    @Synchronized
    override fun messagePage(
        mailbox: String,
        since: Instant,
        cursor: MessageCursor?,
        limit: Int,
    ): MessagePage {
        require(limit in 1..1000)
        require(cursor == null || (cursor.mailbox == mailbox && cursor.since == since))
        return folder(mailbox) { f ->
            if (cursor != null && f.uidValidity != cursor.uidValidity)
                throw MailFailure(
                    FailureKind.PROTOCOL,
                    "Mailbox identity changed; restart pagination",
                )
            val before = cursor?.beforeUid ?: f.uidNext
            if (before < 1)
                throw MailFailure(FailureKind.PROTOCOL, "Server did not provide UIDNEXT")
            if (before == 1L) return@folder MessagePage(emptyList(), null)
            val lower = maxOf(1, before - limit)
            // A bounded UID range remains stable through expunges and arrivals. Empty pages may
            // have a cursor.
            val messages = f.getMessagesByUID(lower, before - 1).filterNotNull().toTypedArray()
            f.fetch(
                messages,
                FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                    listOf("Message-ID", "Sender", "Reply-To", "References", "In-Reply-To")
                        .forEach { add(it) }
                },
            )
            val result =
                messages
                    .map { envelope(f, it) }
                    .filter { it.receivedAt?.isBefore(since) == false }
                    .sortedByDescending { it.identity!!.uid }
            MessagePage(
                result,
                if (lower > 1) MessageCursor(mailbox, f.uidValidity, lower, since) else null,
            )
        }
    }

    @Synchronized
    override fun message(identity: MessageIdentity): Email =
        folder(identity.mailbox) { f ->
            val m = identified(f, identity)
            f.fetch(arrayOf(m), FetchProfile().apply { add(FetchProfile.Item.CONTENT_INFO) })
            val content = partReader.read(m)
            envelope(f, m)
                .copy(body = content.body, attachments = content.attachments, bodyDownloaded = true)
        }

    @Synchronized
    override fun attachment(identity: MessageIdentity, partId: String): ByteArray =
        ByteArrayOutputStream().also { downloadAttachment(identity, partId, it) }.toByteArray()

    @Synchronized
    override fun downloadAttachment(
        identity: MessageIdentity,
        partId: String,
        output: OutputStream,
    ): Long =
        folder(identity.mailbox) { f ->
            partReader.attachment(identified(f, identity), partId, output)
        }

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
        // Check before selecting a folder; capability discovery must not open a second session.
        // Never emulate MOVE with unrestricted EXPUNGE of another client's deleted messages.
        if (!supports("MOVE"))
            throw MailFailure(FailureKind.PROTOCOL, "Server does not support safe MOVE")
        folder(identity.mailbox, true) { f ->
            f.moveMessages(arrayOf(identified(f, identity)), connected().getFolder(targetMailbox))
        }
    }

    override fun close() {
        cancel()
        synchronized(this) {
            val current = store
            store = null
            if (current != null) runCatching { current.close() }
        }
    }

    private fun failure(e: Exception): MailFailure =
        when {
            control.cancelled -> MailFailure(FailureKind.CANCELLED, "IMAP operation cancelled", e)
            e is MailFailure -> e
            e is AuthenticationFailedException ->
                MailFailure(FailureKind.AUTHENTICATION, "IMAP authentication failed", e)
            else -> MailFailure(FailureKind.CONNECTION, "IMAP operation failed", e)
        }
}
