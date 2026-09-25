package org.foxred.kage.core.demo

import java.io.OutputStream
import java.time.Instant
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.mime.BoundedOutputStream

/**
 * One account's in-memory mailbox. No network, credentials retained, or delivery to real
 * recipients.
 */
class DemoMailStore(
    private val codec: MimeCodec = AngusMimeCodec(),
    private val now: () -> Instant = Instant::now,
    private val maxBytes: Int = 25 * 1024 * 1024,
) : MailStore {
    private data class Stored(val raw: ByteArray, val email: Email)

    private data class Box(
        val generation: Long,
        var next: Long = 1,
        var subscribed: Boolean = true,
        val messages: MutableMap<Long, Stored> = linkedMapOf(),
    )

    private val boxes = linkedMapOf<String, Box>()
    private var generation = 1L
    @Volatile private var connected = false

    init {
        require(maxBytes > 0)
        listOf("INBOX", "Sent", "Drafts", "Archive", "Trash", "Junk").forEach {
            boxes[it] = Box(generation++)
        }
    }

    private fun fail(message: String): Nothing = throw MailFailure(FailureKind.PROTOCOL, message)

    private fun checkConnected() {
        if (!connected) throw MailFailure(FailureKind.CONNECTION, "Demo session is disconnected")
    }

    private fun box(name: String): Box {
        checkConnected()
        return boxes[name] ?: fail("Mailbox does not exist")
    }

    private fun stored(id: MessageIdentity): Stored {
        val box = box(id.mailbox)
        if (box.generation != id.uidValidity) fail("Mailbox identity changed; refresh required")
        return box.messages[id.uid] ?: fail("Message no longer exists")
    }

    @Synchronized
    override fun connect(server: Server, authorization: Authorization) {
        require(server.protocol == ServerProtocol.IMAP)
        connected = true
    }

    override fun cancel() {
        connected = false
    }

    override fun close() {
        cancel()
    }

    @Synchronized
    override fun supports(capability: String): Boolean {
        checkConnected()
        return capability.uppercase() in setOf("IMAP4REV1", "UIDPLUS", "MOVE")
    }

    @Synchronized
    override fun namespaces(): List<Namespace> {
        checkConnected()
        return listOf(Namespace("", '/'))
    }

    @Synchronized
    override fun mailboxes(): List<Mailbox> {
        checkConnected()
        return boxes.map { (name, box) ->
            val role = name.takeIf { it in listOf("Sent", "Drafts", "Archive", "Trash", "Junk") }
            Mailbox(
                name,
                '/',
                true,
                role?.let { setOf("\\$it") } ?: emptySet(),
                isSubscribed = box.subscribed,
                unreadEmails = box.messages.values.count { !it.email.read },
                totalEmails = box.messages.size,
            )
        }
    }

    @Synchronized
    override fun status(mailbox: String): MailboxStatus =
        box(mailbox).let {
            MailboxStatus(
                it.generation,
                it.next,
                it.messages.size,
                it.messages.values.count { m -> !m.email.read },
            )
        }

    @Synchronized
    override fun createMailbox(mailbox: String) {
        checkConnected()
        require(mailbox.isNotBlank())
        if (mailbox in boxes) fail("Mailbox already exists")
        boxes[mailbox] = Box(generation++)
    }

    @Synchronized
    override fun renameMailbox(mailbox: String, target: String) {
        box(mailbox)
        require(target.isNotBlank())
        if (target == mailbox || target.startsWith("$mailbox/")) fail("Invalid mailbox rename")
        val names = boxes.keys.filter { it == mailbox || it.startsWith("$mailbox/") }
        val mapping = names.associateWith { target + it.removePrefix(mailbox) }
        if (mapping.values.any { it in boxes }) fail("Target mailbox already exists")
        mapping.forEach { (old, new) ->
            val moved = boxes.remove(old)!!
            moved.messages.replaceAll { uid, message ->
                message.copy(
                    email =
                        message.email.copy(identity = MessageIdentity(new, moved.generation, uid))
                )
            }
            boxes[new] = moved
        }
    }

    @Synchronized
    override fun deleteMailbox(mailbox: String) {
        box(mailbox)
        if (boxes.keys.any { it.startsWith("$mailbox/") }) fail("Mailbox has children")
        boxes.remove(mailbox)
    }

    @Synchronized
    override fun subscribe(mailbox: String, subscribed: Boolean) {
        box(mailbox).subscribed = subscribed
    }

    override fun awaitChange(mailbox: String) {
        fail("Demo server does not support IDLE; use polling")
    }

    @Synchronized override fun poll(mailbox: String): MailboxStatus = status(mailbox)

    @Synchronized
    override fun append(mailbox: String, raw: ByteArray, read: Boolean): MessageIdentity {
        val target = box(mailbox)
        if (raw.size > maxBytes)
            throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Message exceeds limit")
        val decoded = codec.decode(raw)
        val identity = MessageIdentity(mailbox, target.generation, target.next++)
        target.messages[identity.uid] =
            Stored(raw.copyOf(), decoded.copy(identity = identity, receivedAt = now(), read = read))
        return identity
    }

    @Synchronized
    override fun messagePage(
        mailbox: String,
        since: Instant,
        cursor: MessageCursor?,
        limit: Int,
    ): MessagePage {
        require(limit in 1..1000)
        require(cursor == null || (cursor.mailbox == mailbox && cursor.since == since))
        val box = box(mailbox)
        if (cursor != null && cursor.uidValidity != box.generation)
            fail("Mailbox identity changed; restart pagination")
        val before = cursor?.beforeUid ?: box.next
        val lower = maxOf(1, before - limit)
        val messages =
            box.messages
                .filterKeys { it in lower until before }
                .values
                .map { it.email }
                .filter { it.receivedAt?.isBefore(since) == false }
                .sortedByDescending { it.identity!!.uid }
                .map {
                    it.copy(
                        body = EmailBody(null, null),
                        attachments = emptyList(),
                        bodyDownloaded = false,
                    )
                }
        return MessagePage(
            messages,
            if (lower > 1) MessageCursor(mailbox, box.generation, lower, since) else null,
        )
    }

    @Synchronized override fun message(identity: MessageIdentity): Email = stored(identity).email

    @Synchronized
    override fun downloadRawMessage(identity: MessageIdentity, output: OutputStream): Long {
        val bounded = BoundedOutputStream(output, maxBytes.toLong())
        bounded.write(stored(identity).raw)
        return bounded.count
    }

    @Synchronized
    override fun attachment(identity: MessageIdentity, partId: String): ByteArray =
        codec.attachment(stored(identity).raw, partId)

    @Synchronized
    override fun downloadAttachment(
        identity: MessageIdentity,
        partId: String,
        output: OutputStream,
    ): Long {
        val bounded = BoundedOutputStream(output, maxBytes.toLong())
        bounded.write(attachment(identity, partId))
        return bounded.count
    }

    @Synchronized
    override fun markRead(identity: MessageIdentity, read: Boolean) {
        val item = stored(identity)
        box(identity.mailbox).messages[identity.uid] =
            item.copy(email = item.email.copy(read = read))
    }

    @Synchronized
    override fun flag(identity: MessageIdentity, flagged: Boolean) {
        val item = stored(identity)
        box(identity.mailbox).messages[identity.uid] =
            item.copy(email = item.email.copy(flagged = flagged))
    }

    @Synchronized
    override fun move(identity: MessageIdentity, targetMailbox: String) {
        val item = stored(identity)
        val target = box(targetMailbox)
        if (identity.mailbox == targetMailbox) return
        val next = MessageIdentity(targetMailbox, target.generation, target.next++)
        target.messages[next.uid] = item.copy(email = item.email.copy(identity = next))
        box(identity.mailbox).messages.remove(identity.uid)
    }
}
