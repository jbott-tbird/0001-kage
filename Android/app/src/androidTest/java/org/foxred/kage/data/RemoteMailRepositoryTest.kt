// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.foxred.kage.core.account.*
import org.foxred.kage.core.demo.DemoMailStore
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.testkit.ImapTranscript
import org.foxred.kage.core.testkit.LoopbackServer
import org.foxred.kage.core.testkit.testTlsContext
import org.foxred.kage.data.local.*
import org.foxred.kage.data.background.BackgroundMailRunner
import org.foxred.kage.data.background.BackgroundMailSettings
import org.foxred.kage.data.background.backgroundMailEligible
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.repository.sentCopyStatus
import org.foxred.kage.data.repository.sentCopyUploadNeedsReview
import org.foxred.kage.domain.model.SentCopyStatus
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.data.repository.RemoteMailRepository.OperationState
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.sync.AccountSessions
import org.foxred.kage.ui.emaildisplay.SafeMessageHtml
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import javax.net.ssl.SSLContext

/** Repository policy against real Room and in-memory servers; nothing leaves the device. */
@RunWith(AndroidJUnit4::class)
class RemoteMailRepositoryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val credentials by lazy { AndroidCredentialStore(context, "remote-repository-test") }
    private val codec = AngusMimeCodec()
    private val servers = mutableMapOf<String, DemoMailStore>()
    private var clock = 1_000L
    private val databases = mutableListOf<MailDatabase>()
    private val databaseName = "remote-repository-test"

    /** Tests may inspect complete fixtures; production observes one detail or list page. */
    private suspend fun RoomMailRepository.cachedMessages(): List<org.foxred.kage.domain.model.Message> {
        val folderIds = mailbox.first().folders.map { it.id }
        val result = mutableListOf<org.foxred.kage.domain.model.Message>()
        var cursor: org.foxred.kage.domain.model.MessagePageCursor? = null
        do {
            val page = messagePage(folderIds, cursor, oldestFirst = false, limit = 100)
            page.items.mapNotNullTo(result) { observeMessage(it.id).first() }
            cursor = page.next
        } while (cursor != null)
        return result
    }

    /** Fault-injecting view over a shared in-memory server; each session gets a new wrapper. */
    private inner class ScriptedStore(private val server: DemoMailStore) : MailStore by server {
        override fun connect(server: Server, authorization: Authorization) {
            if (offline) throw MailFailure(FailureKind.CONNECTION, "Fixture network is offline")
            connects++
            this.server.connect(server, authorization)
        }

        override fun messagePage(
            mailbox: String,
            since: Instant,
            cursor: MessageCursor?,
            limit: Int,
        ): MessagePage {
            pageCalls++
            failPageAt?.let { if (pageCalls >= it) throw MailFailure(FailureKind.CONNECTION, "Dropped") }
            val page = server.messagePage(mailbox, since, cursor, limit)
            afterPage?.invoke()
            return corruptPageAt?.takeIf { pageCalls == it }?.let {
                page.copy(
                    messages =
                        page.messages.mapIndexed { index, email ->
                            if (index == 0) email
                            else email.copy(identity = email.identity!!.copy(mailbox = "Elsewhere"))
                        }
                )
            } ?: page
        }

        override fun message(identity: MessageIdentity): Email {
            if (identity.uid == failBodyUid)
                throw MailFailure(FailureKind.CONNECTION, "Body fetch dropped")
            return server.message(identity)
        }

        override fun downloadAttachment(
            identity: MessageIdentity, partId: String, output: OutputStream,
        ): Long {
            if (failPart) {
                output.write("partial".toByteArray())
                throw MailFailure(FailureKind.CONNECTION, "Part stream dropped")
            }
            return server.downloadAttachment(identity, partId, output)
        }

        override fun changes(mailbox: String, uidValidity: Long, sinceModSeq: Long?): MailboxChanges? {
            if (!deltaEnabled) return null
            return if (sinceModSeq == null)
                MailboxChanges(uidValidity, 10, emptyList(), emptySet())
            else nextChanges ?: MailboxChanges(uidValidity, sinceModSeq, emptyList(), emptySet())
        }

        override fun cancel() {
            cancelled.countDown()
        }

        override fun close() = cancel()
    }

    private var offline = false
    private var connects = 0
    private var pageCalls = 0
    private var failPageAt: Int? = null
    private var failPart = false
    private var failBodyUid: Long? = null
    private var deltaEnabled = false
    private var nextChanges: MailboxChanges? = null
    private var corruptPageAt: Int? = null
    private var afterPage: (() -> Unit)? = null
    private var cancelled = CountDownLatch(1)

    /** The fixture server stays reachable for direct setup calls regardless of app sessions. */
    private fun server(accountId: String) =
        servers.getOrPut(accountId) { DemoMailStore() }.apply {
            connect(Server("fixture.invalid", 993, ServerProtocol.IMAP, username = "u"), Authorization.none())
        }

    private fun open(file: Boolean = false): MailDatabase =
        (if (file) Room.databaseBuilder(context, MailDatabase::class.java, databaseName)
            else Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java))
            .build()
            .also { databases += it }

    private fun repository(
        db: MailDatabase,
        store: (String) -> MailStore = { ScriptedStore(server(it)) },
    ): RemoteMailRepository {
        val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials, store)
        return RemoteMailRepository(db, sessions, credentials, DurableOutbox(db, context, codec), now = { Instant.ofEpochMilli(clock++) })
    }

    private fun account(id: String) =
        Account(
            id,
            "Account $id",
            listOf(EmailAddress("$id@fixture.invalid", "Person $id")),
            Server("imap.fixture.invalid", 993, ServerProtocol.IMAP, username = "$id-incoming"),
            Server(
                "smtp.fixture.invalid",
                587,
                ServerProtocol.SMTP,
                ConnectionSecurity.STARTTLS,
                "$id-outgoing",
            ),
        )

    private fun raw(n: Int, subject: String = "Message $n", html: String? = null) =
        codec.encode(
            OutgoingEmail(
                "<$n.${subject.hashCode()}@fixture.invalid>",
                EmailAddress("sender@fixture.invalid", "Sender"),
                listOf(EmailAddress("to@fixture.invalid")),
                subject = subject,
                body = EmailBody("Body $n", html),
                attachments =
                    listOf(OutgoingAttachment("part-$n.txt", "text/plain", "bytes $n".toByteArray())),
            )
        )

    private suspend fun ready(repo: RemoteMailRepository, id: String = "a") {
        repo.addAccount(account(id), Authorization("incoming-secret"), Authorization("outgoing-secret"))
        repo.refreshFolders(id)
    }

    @Test
    fun realMailStaysOutOfSnapshotButRemainsAvailableByPageAndDetail() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1, "Paged real message"))
        repo.syncMessages(inbox(), Instant.EPOCH)

        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        assertTrue(room.mailbox.first().messages.isEmpty())
        val page = room.messagePage(listOf(inbox()), cursor = null, oldestFirst = false, limit = 1)
        assertEquals("Paged real message", page.items.single().subject)
        repo.downloadBody(page.items.single().id)
        assertEquals("Body 1", room.observeMessage(page.items.single().id).first()?.body)
        assertEquals(1L, room.cacheCounts().cachedMessages)
    }

    @Test
    fun uncertainOutboxConfirmsOnlyWhenExactCopyExistsInSent() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val durable = DurableOutbox(db, context, codec)
        val queued = durable.enqueue("a", OutgoingEmail(
            "<reconcile@fixture.invalid>", EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Reconcile",
            body = EmailBody("Only once", null),
        ))
        durable.claimNext("a")
        durable.finish(queued.id, DurableOutbox.State.UNCERTAIN, "Connection lost")
        assertEquals(0, repo.reconcileSent("a"))
        assertEquals(DurableOutbox.State.UNCERTAIN, durable.entries("a").single().state)
        server("a").append("INBOX", durable.raw(queued))
        assertEquals(0, repo.reconcileSent("a"))
        server("a").append("Sent", durable.raw(queued), true)
        assertEquals(1, repo.reconcileSent("a"))
        assertEquals(DurableOutbox.State.SENT, durable.entries("a").single().state)
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(durable.entries("a").single()))
        assertEquals(0, repo.reconcileSent("a"))
        assertEquals(1, server("a").status("Sent").messageCount)
    }

    @Test
    fun smtpAcceptanceAndServerSentCopyStaySeparateUntilFound() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val durable = DurableOutbox(db, context, codec)
        val queued = durable.enqueue("a", OutgoingEmail(
            "<accepted@fixture.invalid>", EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Accepted",
            body = EmailBody("Accepted body", null),
        ))
        durable.claimNext("a")
        durable.finish(queued.id, DurableOutbox.State.SENT)
        assertEquals(0, repo.reconcileSent("a"))
        assertEquals(SentCopyStatus.PENDING, sentCopyStatus(durable.entries("a").single()))
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        assertEquals(SentCopyStatus.PENDING, room.outbox.first().single().sentCopyStatus)
        assertEquals(0, server("a").status("Sent").messageCount)
        val sentIdentity = server("a").append("Sent", durable.raw(queued), true)
        assertEquals(1, repo.reconcileSent("a"))
        val confirmed = durable.entries("a").single()
        assertEquals(DurableOutbox.State.SENT, confirmed.state)
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(confirmed))
        assertEquals(SentCopyStatus.CONFIRMED, room.outbox.first().single().sentCopyStatus)
        assertEquals(sentIdentity.uid, JSONObject(confirmed.envelopeJson).getLong("sentCopyUid"))
        assertEquals(0, repo.reconcileSent("a"))
        assertEquals(1, server("a").status("Sent").messageCount)
    }

    @Test
    fun explicitSentCopyUploadUsesSavedMimeAndDoesNotRepeatAppend() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val durable = DurableOutbox(db, context, codec)
        val queued = durable.enqueue("a", OutgoingEmail(
            "<manual-sent@fixture.invalid>", EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Manual Sent",
            body = EmailBody("Saved once", null),
        ))
        durable.claimNext("a")
        durable.finish(queued.id, DurableOutbox.State.SENT)

        assertTrue(repo.saveMissingSentCopy(queued.id))
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(durable.entries("a").single()))
        assertEquals(1, server("a").status("Sent").messageCount)
        assertTrue(repo.saveMissingSentCopy(queued.id))
        assertEquals(1, server("a").status("Sent").messageCount)
    }

    @Test
    fun failedPreAppendLookupLeavesSentCopyReadyForRetry() = runBlocking {
        val db = open()
        var lookups = 0
        var appends = 0
        val targetMessageId = "<pre-append-lookup@fixture.invalid>"
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun findByMessageId(mailbox: String, messageId: String): MessageIdentity? {
                    if (messageId == targetMessageId && ++lookups == 2)
                        throw MailFailure(FailureKind.CONNECTION, "Lookup failed before APPEND")
                    return server(id).findByMessageId(mailbox, messageId)
                }
                override fun append(mailbox: String, raw: ByteArray, read: Boolean,
                    draft: Boolean): MessageIdentity? {
                    appends++
                    return server(id).append(mailbox, raw, read, draft)
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        val durable = DurableOutbox(db, context, codec)
        val queued = durable.enqueue("a", OutgoingEmail(
            targetMessageId, EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Lookup retry",
            body = EmailBody("One copy", null),
        ))
        durable.claimNext("a")
        durable.finish(queued.id, DurableOutbox.State.SENT)

        assertTrue(runCatching { repo.saveMissingSentCopy(queued.id) }.isFailure)
        assertEquals(0, appends)
        assertFalse(sentCopyUploadNeedsReview(durable.entries("a").single()))
        assertEquals(SentCopyStatus.PENDING, sentCopyStatus(durable.entries("a").single()))

        assertTrue(repo.saveMissingSentCopy(queued.id))
        assertEquals(1, appends)
        assertEquals(1, server("a").status("Sent").messageCount)
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(durable.entries("a").single()))
    }

    @Test
    fun gmailSmtpNeverReceivesAnExtraSentAppend() = runBlocking {
        val db = open()
        val repo = repository(db)
        val gmail = account("gmail").copy(outgoingServer = Server("SMTP.GMAIL.COM.", 465,
            ServerProtocol.SMTP, ConnectionSecurity.TLS, "gmail@fixture.invalid"))
        repo.addAccount(gmail, Authorization("incoming-secret"),
            Authorization("outgoing-secret"))
        repo.refreshFolders("gmail")
        val durable = DurableOutbox(db, context, codec)
        val queued = durable.enqueue("gmail", OutgoingEmail(
            "<gmail-auto-file@fixture.invalid>", EmailAddress("gmail@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Auto filed",
            body = EmailBody("Gmail copy", null),
        ))
        durable.claimNext("gmail")
        durable.finish(queued.id, DurableOutbox.State.SENT)

        assertTrue(runCatching { repo.saveMissingSentCopy(queued.id) }.isFailure)
        assertEquals(0, server("gmail").status("Sent").messageCount)
        assertEquals(SentCopyStatus.PENDING, sentCopyStatus(durable.entries("gmail").single()))
    }

    @Test
    fun ambiguousSentCopyAppendRequiresLookupAndNeverRepeatsAutomatically() = runBlocking {
        val db = open()
        var appendCalls = 0
        var failAppend = true
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun append(mailbox: String, raw: ByteArray, read: Boolean,
                    draft: Boolean): MessageIdentity? {
                    appendCalls++
                    if (failAppend) throw MailFailure(FailureKind.CONNECTION, "APPEND response lost")
                    return server(id).append(mailbox, raw, read, draft)
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        val durable = DurableOutbox(db, context, codec)
        val queued = durable.enqueue("a", OutgoingEmail(
            "<uncertain-copy@fixture.invalid>", EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Review Sent",
            body = EmailBody("One copy", null),
        ))
        durable.claimNext("a")
        durable.finish(queued.id, DurableOutbox.State.SENT)

        assertTrue(runCatching { repo.saveMissingSentCopy(queued.id) }.isFailure)
        assertTrue(sentCopyUploadNeedsReview(durable.entries("a").single()))
        assertFalse(repo.saveMissingSentCopy(queued.id))
        assertEquals(1, appendCalls)

        failAppend = false
        assertTrue(repo.saveMissingSentCopy(queued.id, retryAfterReview = true))
        assertEquals(2, appendCalls)
        assertEquals(1, server("a").status("Sent").messageCount)
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(durable.entries("a").single()))
    }

    @Test
    fun sentCopyCommittedBeforeLostAppendResponseIsFoundWithoutSecondAppend() = runBlocking {
        val db = open()
        var appendCalls = 0
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun append(mailbox: String, raw: ByteArray, read: Boolean,
                    draft: Boolean): MessageIdentity? {
                    appendCalls++
                    server(id).append(mailbox, raw, read, draft)
                    throw MailFailure(FailureKind.CONNECTION, "APPEND response lost")
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        val durable = DurableOutbox(db, context, codec)
        val queued = durable.enqueue("a", OutgoingEmail(
            "<copy-accepted@fixture.invalid>", EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Accepted copy",
            body = EmailBody("One copy only", null),
        ))
        durable.claimNext("a")
        durable.finish(queued.id, DurableOutbox.State.SENT)

        assertTrue(runCatching { repo.saveMissingSentCopy(queued.id) }.isFailure)
        assertEquals(1, server("a").status("Sent").messageCount)
        assertTrue(repo.saveMissingSentCopy(queued.id, retryAfterReview = true))
        assertEquals(1, appendCalls)
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(durable.entries("a").single()))
    }

    private fun inbox(id: String = "a") = CoreRoomMapper.folderId(id, "INBOX")

    private suspend fun rows(db: MailDatabase, folderId: String): List<MessageEntity> {
        val result = mutableListOf<MessageEntity>()
        var afterId: String? = null
        while (true) {
            val page = db.remoteMailDao().messageIdsPage(folderId, afterId, 100)
            if (page.isEmpty()) break
            page.forEach { result += checkNotNull(db.remoteMailDao().message(it)) }
            afterId = page.last()
        }
        return result
    }

    @Before
    fun clean() {
        credentials.clear()
        context.deleteDatabase(databaseName)
    }

    @After
    fun close() {
        databases.forEach { it.close() }
        credentials.clear()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun failedOutgoingCredentialSaveRemovesPartialAccountSecrets() = runBlocking {
        val db = open()
        val failing = object : CredentialStore by credentials {
            override fun save(accountId: String, protocol: ServerProtocol,
                authorization: Authorization) {
                if (protocol == ServerProtocol.SMTP)
                    throw CredentialFailure(CredentialFailureReason.STORAGE_UNAVAILABLE)
                credentials.save(accountId, protocol, authorization)
            }
        }
        val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), failing,
            { id -> ScriptedStore(server(id)) })
        val repo = RemoteMailRepository(db, sessions, failing,
            DurableOutbox(db, context, codec))

        assertTrue(runCatching { repo.addAccount(account("a"),
            Authorization("incoming-secret"), Authorization("outgoing-secret")) }.isFailure)
        assertNull(db.remoteMailDao().account("a"))
        assertNull(credentials.authorization("a", ServerProtocol.IMAP))
        assertNull(credentials.authorization("a", ServerProtocol.SMTP))
        repository(db).addAccount(account("a"),
            Authorization("incoming-secret"), Authorization("outgoing-secret"))
        assertNotNull(db.remoteMailDao().account("a"))
    }

    @Test
    fun accountsAndFoldersPersistWithoutSecretsAndKeepLocalFolderState() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        fun Account.withoutServerIds() =
            copy(incomingServer = incomingServer.copy(id = "in"), outgoingServer = outgoingServer.copy(id = "out"))
        assertEquals(account("a").withoutServerIds(), repo.account("a")!!.withoutServerIds())
        assertEquals("incoming-secret", credentials.authorization("a", ServerProtocol.IMAP)!!.secret)
        assertEquals("outgoing-secret", credentials.authorization("a", ServerProtocol.SMTP)!!.secret)
        val stored = db.remoteMailDao().servers("a")
        assertEquals(listOf("a-incoming", "a-outgoing"), stored.map { it.username })
        assertTrue(stored.none { it.toString().contains("secret") })
        assertTrue(
            runCatching {
                    repo.addAccount(account("a"), Authorization("other"), Authorization("other"))
                }
                .isFailure
        )
        assertEquals("incoming-secret", credentials.authorization("a", ServerProtocol.IMAP)!!.secret)

        val folders = db.remoteMailDao().folders("a")
        assertEquals(
            listOf("inbox", "sent", "drafts", "archive", "trash", "junk"),
            folders.map { it.role },
        )
        db.remoteMailDao().saveFolders(listOf(folders.first().copy(lastVisitedUid = 42)))
        server("a").createMailbox("Projects/2026")
        server("a").deleteMailbox("Junk")
        repo.refreshFolders("a")
        val refreshed = db.remoteMailDao().folders("a").associateBy { it.remotePath }
        assertEquals(42L, refreshed.getValue("INBOX").lastVisitedUid)
        assertEquals(folders.first().id, refreshed.getValue("INBOX").id)
        assertNull(refreshed["Junk"])
        val nested = refreshed.getValue("Projects/2026")
        assertEquals("2026", nested.name)
        assertEquals(CoreRoomMapper.folderId("a", "Projects"), nested.parentId)
    }

    @Test
    fun missingInboxInServerFolderListCannotEraseCachedMail() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1))
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val folders = db.remoteMailDao().folders("a")
        val cached = rows(db, inbox())
        assertTrue(cached.single().bodyDownloaded)

        val incomplete = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun mailboxes(): List<Mailbox> = emptyList()
            }
        }
        val failure = runCatching { incomplete.refreshFolders("a") }.exceptionOrNull()
        assertEquals(FailureKind.PROTOCOL, (failure as MailFailure).kind)
        assertEquals(folders, db.remoteMailDao().folders("a"))
        assertEquals(cached, rows(db, inbox()))
    }

    @Test
    fun omittedFolderKeepsDownloadedMailUntilItReappears() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val sentId = CoreRoomMapper.folderId("a", "Sent")
        server("a").append("Sent", raw(1))
        repo.refreshVisibleFolder(sentId, Instant.EPOCH)
        val cached = rows(db, sentId)
        assertTrue(cached.single().bodyDownloaded)
        val original = checkNotNull(db.remoteMailDao().folder(sentId))

        val incomplete = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun mailboxes(): List<Mailbox> =
                    server(id).mailboxes().filterNot { it.name == "Sent" }
            }
        }
        incomplete.refreshFolders("a")
        val unavailable = checkNotNull(db.remoteMailDao().folder(sentId))
        assertNull(unavailable.remotePath)
        assertEquals(original.uidValidity, unavailable.uidValidity)
        assertEquals(cached, rows(db, sentId))

        repo.refreshFolders("a")
        assertEquals("Sent", db.remoteMailDao().folder(sentId)?.remotePath)
        assertEquals(cached, rows(db, sentId))
    }

    @Test
    fun offlineReopenRestoresLastFolderUnifiedChoiceAndDownloadedBody() = runBlocking {
        var db = open(file = true)
        val remote = repository(db)
        ready(remote)
        server("a").append("INBOX", raw(1))
        remote.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        room.updatePreferences(org.foxred.kage.domain.model.Preferences(
            selectedFolder = inbox(), unified = true, offline = true, started = true))
        val cached = rows(db, inbox()).single()
        assertTrue(cached.bodyDownloaded)

        db.close()
        db = open(file = true)
        val reopened = RoomMailRepository(db, context, DemoMail(context), credentials, repository(db))
        val state = reopened.mailbox.first()
        assertEquals(inbox(), state.preferences.selectedFolder)
        assertTrue(state.preferences.unified)
        assertTrue(state.preferences.offline)
        assertTrue(reopened.cachedMessages().single { it.id == cached.id }.bodyDownloaded)
    }

    @Test
    fun realComposeQueuesExactMimeAndBccWithoutSubmitting() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(directory, "queued-file-${System.nanoTime()}")
        file.writeText("queued attachment")
        try {
            val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
            val message = org.foxred.kage.domain.model.Message(
                "queued-message", "a", inbox(), "Account a", "a@fixture.invalid",
                "to@example.test", bcc = "hidden@example.test", subject = "Queued real message",
                body = "Queued body", html = "<p>Queued <strong>body</strong></p>",
                receivedAt = Instant.now().toString(), draft = true,
                attachments = listOf(org.foxred.kage.domain.model.Attachment(
                    "queued-part", "queued-message", "note.txt", "text/plain",
                    file.length(), cached = true, localFile = file.name)),
            )
            assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED,
                room.send(message))
            assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED,
                room.send(message))
            val entry = db.remoteMailDao().outbox("a").single()
            assertEquals(DurableOutbox.State.PENDING, entry.state)
            val queue = DurableOutbox(db, context, codec)
            assertEquals(listOf("to@example.test", "hidden@example.test"),
                queue.recipients(entry).map { it.address })
            val encoded = codec.decode(queue.raw(entry))
            assertEquals("Queued body", encoded.body.text)
            assertEquals(message.html, encoded.body.html)
            assertEquals("queued attachment", String(codec.attachment(queue.raw(entry),
                encoded.attachments.single().partId)))
            queue.claimNext("a")
            queue.finish(entry.id, DurableOutbox.State.UNCERTAIN,
                "Delivery result is unknown")
            assertEquals(org.foxred.kage.domain.repository.SendDisposition.UNCERTAIN,
                room.send(message))
            assertEquals(1, db.remoteMailDao().outbox("a").size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun shortenedLocalDraftAttachmentCannotBeOpenedSavedOrQueued() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(directory, "local-draft-${System.nanoTime()}")
        try {
            file.writeText("complete attachment")
            val attachment = org.foxred.kage.domain.model.Attachment(
                "local-draft-part", "local-draft-message", "note.txt", "text/plain",
                file.length(), cached = true, localFile = file.name)
            val draft = org.foxred.kage.domain.model.Message(
                "local-draft-message", "a", inbox(), "Account a", "a@fixture.invalid",
                "to@example.test", subject = "Local attachment", body = "Body",
                receivedAt = Instant.now().toString(), draft = true,
                attachments = listOf(attachment))
            room.saveDraft(draft)

            file.writeText("short")
            assertTrue(runCatching { room.cacheAttachment(attachment.id) }.isFailure)
            assertTrue(runCatching { room.saveDraft(draft) }.isFailure)
            assertTrue(runCatching { room.send(draft) }.isFailure)
            assertTrue(db.remoteMailDao().outbox("a").isEmpty())

            file.writeText("complete attachment")
            assertEquals(file.absolutePath, room.cacheAttachment(attachment.id))
            assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED,
                room.send(draft))
        } finally {
            file.delete()
        }
    }

    @Test
    fun realComposeQueuesCcAndBccOnlyMessages() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        val queue = DurableOutbox(db, context, codec)
        listOf(
            "cc" to "visible@example.test",
            "bcc" to "hidden@example.test",
        ).forEach { (kind, address) ->
            val message = org.foxred.kage.domain.model.Message(
                "only-$kind", "a", inbox(), "Account a", "a@fixture.invalid",
                to = "", cc = if (kind == "cc") address else "",
                bcc = if (kind == "bcc") address else "",
                subject = "Only $kind", body = "Body",
                receivedAt = Instant.now().toString(), draft = true,
            )
            assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED,
                room.send(message))
            val entry = db.remoteMailDao().outboxByMessageId("a", "<only-$kind@fixture.invalid>")!!
            assertEquals(listOf(address), queue.recipients(entry).map { it.address })
            val raw = queue.raw(entry).toString(Charsets.UTF_8)
            if (kind == "bcc") assertFalse(raw.contains(address))
            else assertTrue(raw.contains(address))
        }
    }

    @Test
    fun realReplyToGroupQueuesEachMemberAsRecipient() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        val reply = org.foxred.kage.domain.model.Message(
            "group-reply", "a", inbox(), "Account a", "a@fixture.invalid",
            to = "Support: one@example.test, two@example.test;",
            subject = "Re: Original", body = "Reply body",
            receivedAt = Instant.now().toString(), draft = true,
            inReplyTo = "<original@example.test>",
        )
        assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED,
            room.send(reply))
        val entry = db.remoteMailDao().outbox("a").single()
        assertEquals(listOf("one@example.test", "two@example.test"),
            DurableOutbox(db, context, codec).recipients(entry).map { it.address })
    }

    @Test
    fun savedReplyKeepsThreadHeadersInQueuedMime() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        val reply = org.foxred.kage.domain.model.Message(
            "reply-draft", "a", inbox(), "Account a", "a@fixture.invalid",
            "original@example.test", subject = "Re: Original", body = "Reply body",
            receivedAt = Instant.now().toString(), draft = true,
            inReplyTo = "<original@example.test>",
            references = listOf("<earlier@example.test>", "<original@example.test>"),
        )
        room.saveDraft(reply)
        val reopened = room.cachedMessages().single { it.id == reply.id }
        assertEquals(reply.inReplyTo, reopened.inReplyTo)
        assertEquals(reply.references, reopened.references)
        assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED,
            room.send(reopened))
        val entry = db.remoteMailDao().outbox("a").single()
        val encoded = codec.decode(DurableOutbox(db, context, codec).raw(entry))
        assertEquals(listOf("<original@example.test>"), encoded.inReplyTo)
        assertEquals(reply.references, encoded.references)
    }

    @Test
    fun localEditOfRemoteDraftSurvivesRefreshRemovalAndFolderLoss() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val identity = server("a").append("Drafts", raw(32, "Server draft"))
        val drafts = CoreRoomMapper.folderId("a", "Drafts")
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        repo.syncMessages(drafts, Instant.EPOCH)
        val header = room.cachedMessages().single { it.subject == "Server draft" }
        assertFalse(header.bodyDownloaded)
        assertTrue(runCatching { room.saveDraft(header.copy(subject = "Too early")) }.isFailure)
        repo.refreshVisibleFolder(drafts, Instant.EPOCH)
        val hydrated = room.cachedMessages().single { it.id == header.id }
        assertTrue(hydrated.bodyDownloaded)
        room.cacheAttachment(hydrated.attachments.single().id)
        val cached = room.cachedMessages().single { it.id == header.id }
        room.saveDraft(cached.copy(sender = "Account a", senderAddress = "a@fixture.invalid",
            subject = "Local edited draft", body = "Keep local edits"))
        assertEquals(identity.uid, db.remoteMailDao().message(header.id)?.uid)
        assertNotNull(db.remoteMailDao().attachments(header.id).single().partId)
        repo.syncMessages(drafts, Instant.EPOCH, full = true, reconcile = true)
        assertEquals("Keep local edits", db.remoteMailDao().message(header.id)?.body)
        server("a").delete(identity)
        repo.syncMessages(drafts, Instant.EPOCH, full = true, reconcile = true)
        val detached = checkNotNull(db.remoteMailDao().message(header.id))
        assertNull(detached.uid)
        assertEquals("Local edited draft", detached.subject)
        assertEquals("Keep local edits", detached.body)
        assertTrue(File(context.filesDir,
            "attachments/${db.remoteMailDao().attachments(header.id).single().localFile}").isFile)
        server("a").deleteMailbox("Drafts")
        repo.refreshFolders("a")
        assertNull(db.remoteMailDao().folder(drafts)?.remotePath)
        assertEquals("Keep local edits", db.remoteMailDao().message(header.id)?.body)
        server("a").createMailbox("Drafts")
        repo.refreshFolders("a")
        assertEquals("Drafts", db.remoteMailDao().folder(drafts)?.remotePath)
        assertEquals(1, repo.flushDrafts("a"))
        assertNotNull(db.remoteMailDao().message(header.id)?.uid)
        assertEquals(1, server("a").status("Drafts").messageCount)
    }

    @Test
    fun oldFolderBodyResponseCannotOverwriteAMovedMessage() = runBlocking {
        val db = open()
        ready(repository(db))
        server("a").append("INBOX", raw(1, "Original"))
        repository(db).syncMessages(inbox(), Instant.EPOCH)
        val source = rows(db, inbox()).single()
        assertFalse(source.bodyDownloaded)
        val archive = CoreRoomMapper.folderId("a", "Archive")
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email {
                    val response = server(id).message(identity)
                    runBlocking {
                        // Different folders may legitimately use the same UID and UIDVALIDITY.
                        db.remoteMailDao().relocate(source.id, archive,
                            identity.uidValidity, identity.uid)
                        val moved = checkNotNull(db.remoteMailDao().message(source.id))
                        db.remoteMailDao().saveMessage(moved.copy(subject = "Target copy"))
                    }
                    return response
                }
            }
        }

        repo.downloadBody(source.id)

        val moved = checkNotNull(db.remoteMailDao().message(source.id))
        assertEquals(archive, moved.folderId)
        assertEquals("Target copy", moved.subject)
        assertFalse(moved.bodyDownloaded)
    }

    @Test
    fun draftUploadContinuesBeyondOneMetadataPage() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val target = org.foxred.kage.domain.model.Message(
            "zz-paged-draft", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Upload after page boundary", body = "Keep body",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(target)
        val source = checkNotNull(db.remoteMailDao().message(target.id))
        val metadata = JSONObject(source.envelopeJson).put("localDraftDirty", false).toString()
        db.mailDao().saveMessages((0 until 64).map { index ->
            source.copy(id = "aa-paged-draft-${index.toString().padStart(3, '0')}",
                uidValidity = 1, uid = 1000L + index, envelopeJson = metadata)
        })

        assertEquals(1, repo.flushDrafts("a"))
        assertEquals(1, server("a").status("Drafts").messageCount)
        assertFalse(JSONObject(checkNotNull(db.remoteMailDao().message(target.id)).envelopeJson)
            .optBoolean("localDraftDirty"))
    }

    @Test
    fun draftServerScanFindsLocalUploadIdBeyondOneMetadataPage() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val target = org.foxred.kage.domain.model.Message(
            "zz-draft-upload", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Uploaded draft", body = "Local edits",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(target)
        val source = checkNotNull(db.remoteMailDao().message(target.id))
        val messageId = "<upload-after-page@fixture.invalid>"
        db.remoteMailDao().saveMessage(source.copy(envelopeJson =
            JSONObject(source.envelopeJson).put("draftUploadMessageId", messageId).toString()))
        val fillerMetadata = JSONObject(source.envelopeJson).put("localDraftDirty", false).toString()
        db.mailDao().saveMessages((0 until 64).map { index ->
            source.copy(id = "aa-draft-upload-${index.toString().padStart(3, '0')}",
                uidValidity = 1, uid = 1000L + index, envelopeJson = fillerMetadata)
        })
        val identity = server("a").append("Drafts", codec.encode(OutgoingEmail(
            messageId, EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("friend@example.test")), subject = "Uploaded draft",
            body = EmailBody("Server copy", null),
        )))

        val drafts = CoreRoomMapper.folderId("a", "Drafts")
        repo.syncMessages(drafts, Instant.EPOCH)
        assertNull(db.remoteMailDao().messageByUid(drafts, identity.uidValidity, identity.uid))
        assertNotNull(db.remoteMailDao().message(target.id))
    }

    @Test
    fun discardedServerDraftIsHiddenUntilQueuedUidDeletionFinishes() = runBlocking {
        val db = open(file = true)
        val repo = repository(db)
        ready(repo)
        val identity = server("a").append("Drafts", raw(33, "Discard me"))
        val drafts = CoreRoomMapper.folderId("a", "Drafts")
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        repo.syncMessages(drafts, Instant.EPOCH)
        val draft = room.cachedMessages().single { it.subject == "Discard me" }
        repo.markRead(draft.id, false)
        room.deleteDraft(draft.id)
        assertNull(db.remoteMailDao().message(draft.id))
        assertTrue(db.remoteMailDao().operations("a").none {
            it.kind == RemoteMailRepository.OperationKind.READ.name
        })
        val operation = db.remoteMailDao().operations("a")
            .single { it.kind == RemoteMailRepository.OperationKind.DELETE_DRAFT.name }
        assertEquals(OperationState.PENDING, operation.state)
        assertEquals(identity.uid, operation.uid)
        repo.syncMessages(drafts, Instant.EPOCH, full = true, reconcile = true)
        assertNull(db.remoteMailDao().message(draft.id))
        assertEquals(1, server("a").status("Drafts").messageCount)
        db.close()
        val reopened = open(file = true)
        val resumed = repository(reopened)
        assertEquals(OperationState.PENDING, reopened.remoteMailDao().operation(operation.id)?.state)
        resumed.flushOperations("a")
        assertEquals(OperationState.APPLIED, reopened.remoteMailDao().operation(operation.id)?.state)
        assertEquals(0, server("a").status("Drafts").messageCount)
        resumed.syncMessages(drafts, Instant.EPOCH, full = true, reconcile = true)
        assertNull(reopened.remoteMailDao().message(draft.id))
    }

    @Test
    fun editedServerDraftUploadsOneReplacementAndKeepsTheLocalRow() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val original = server("a").append("Drafts", raw(34, "Older server draft"))
        val drafts = CoreRoomMapper.folderId("a", "Drafts")
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        repo.refreshVisibleFolder(drafts, Instant.EPOCH)
        val initial = room.cachedMessages().single { it.subject == "Older server draft" }
        room.cacheAttachment(initial.attachments.single().id)
        val cached = room.cachedMessages().single { it.id == initial.id }
        room.saveDraft(cached.copy(sender = "Account a", senderAddress = "a@fixture.invalid",
            subject = "Edited server draft", body = "Replacement body",
            bcc = "hidden@example.test"))
        assertEquals(1, repo.flushDrafts("a"))
        val saved = checkNotNull(db.remoteMailDao().message(initial.id))
        assertNotEquals(original.uid, saved.uid)
        assertFalse(JSONObject(saved.envelopeJson).optBoolean("localDraftDirty"))
        assertNull(db.remoteMailDao().attachments(initial.id).single().partId)
        assertEquals(1, server("a").status("Drafts").messageCount)
        val remoteCopy = server("a").message(MessageIdentity("Drafts",
            checkNotNull(saved.uidValidity), checkNotNull(saved.uid)))
        assertEquals("Edited server draft", remoteCopy.subject)
        assertEquals("Replacement body", remoteCopy.body.text)
        assertEquals("hidden@example.test", remoteCopy.bcc.single().address)
        repo.syncMessages(drafts, Instant.EPOCH, full = true, reconcile = true)
        assertEquals(listOf(initial.id), db.remoteMailDao().messageIdsPage(drafts, null, 100))
        assertEquals("Edited server draft", db.remoteMailDao().message(initial.id)?.subject)
    }

    @Test
    fun partialRecipientStaysLocalUntilTheDraftCanBeSerialized() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "partial-draft", "a", "", "Account a", "a@fixture.invalid", "unfinished@",
            subject = "Partial recipient", body = "Keep editing",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        assertEquals(0, repo.flushDrafts("a"))
        assertEquals(0, server("a").status("Drafts").messageCount)
        assertEquals("unfinished@", db.remoteMailDao().message(draft.id)?.to)
        room.saveDraft(draft.copy(to = "ready@example.test"))
        assertEquals(1, repo.flushDrafts("a"))
        assertEquals(1, server("a").status("Drafts").messageCount)
        assertEquals("ready@example.test", db.remoteMailDao().message(draft.id)?.to)
    }

    @Test
    fun realDraftWithoutRecipientsCanSyncWithoutBecomingSendable() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "recipient-free-draft", "a", "", "Account a", "a@fixture.invalid", "",
            subject = "Address later", body = "A draft can start with no recipient",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        assertEquals(1, repo.flushDrafts("a"))
        val stored = server("a").messages("Drafts", Instant.EPOCH).single()
        assertTrue(stored.to.isEmpty())
        assertTrue(runCatching { room.send(draft) }.isFailure)
        assertEquals(0, server("a").status("Sent").messageCount)
    }

    @Test
    fun interruptedDraftAppendIsFoundAfterRoomReopenWithoutSecondAppend() = runBlocking {
        val db = open(file = true)
        var interruptAfterAppend = true
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun append(mailbox: String, raw: ByteArray, read: Boolean,
                    draft: Boolean): MessageIdentity? {
                    val result = server(id).append(mailbox, raw, read, draft)
                    if (interruptAfterAppend) {
                        interruptAfterAppend = false
                        throw MailFailure(FailureKind.CONNECTION, "Reply lost after APPEND")
                    }
                    return result
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "interrupted-upload", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Resume draft", body = "One copy only",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        assertEquals(0, repo.flushDrafts("a"))
        assertEquals(1, server("a").status("Drafts").messageCount)
        assertEquals("UNCERTAIN", JSONObject(checkNotNull(db.remoteMailDao().message(draft.id))
            .envelopeJson).getString("draftUploadPhase"))
        db.close()
        val reopened = open(file = true)
        val resumed = repository(reopened)
        assertEquals(1, resumed.flushDrafts("a"))
        assertEquals(1, server("a").status("Drafts").messageCount)
        val saved = checkNotNull(reopened.remoteMailDao().message(draft.id))
        assertNotNull(saved.uid)
        assertFalse(JSONObject(saved.envelopeJson).optBoolean("localDraftDirty"))
    }

    @Test
    fun discardingAfterLostAppendReplyAlsoDeletesTheAppendedCopy() = runBlocking {
        val db = open()
        var interruptAfterAppend = true
        var interruptAfterDelete = true
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun append(mailbox: String, raw: ByteArray, read: Boolean,
                    draft: Boolean): MessageIdentity? {
                    val result = server(id).append(mailbox, raw, read, draft)
                    if (interruptAfterAppend) {
                        interruptAfterAppend = false
                        throw MailFailure(FailureKind.CONNECTION, "Reply lost after APPEND")
                    }
                    return result
                }
                override fun findByMessageId(mailbox: String, messageId: String): MessageIdentity? {
                    if (offline) throw MailFailure(FailureKind.CONNECTION, "Fixture network is offline")
                    return server(id).findByMessageId(mailbox, messageId)
                }
                override fun delete(identity: MessageIdentity) {
                    server(id).delete(identity)
                    if (interruptAfterDelete) {
                        interruptAfterDelete = false
                        throw MailFailure(FailureKind.CONNECTION, "Reply lost after DELETE")
                    }
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "discard-interrupted", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Discard interrupted", body = "No ghost copy",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        assertEquals(0, repo.flushDrafts("a"))
        offline = true
        room.deleteDraft(draft.id)
        assertNull(db.remoteMailDao().message(draft.id))
        assertEquals(1, db.remoteMailDao().operations("a")
            .count { it.kind == RemoteMailRepository.OperationKind.DELETE_DRAFT_BY_ID.name })
        offline = false
        assertEquals(FailureKind.CONNECTION,
            (runCatching { repo.flushOperations("a") }.exceptionOrNull() as MailFailure).kind)
        assertEquals(0, server("a").status("Drafts").messageCount)
        repo.flushOperations("a")
        assertEquals(OperationState.APPLIED, db.remoteMailDao().operations("a")
            .single { it.kind == RemoteMailRepository.OperationKind.DELETE_DRAFT_BY_ID.name }.state)
        assertEquals(0, server("a").status("Drafts").messageCount)
    }

    @Test
    fun draftReplacementResumesAfterOldCopyWasDeletedBeforeItsReply() = runBlocking {
        val db = open()
        var interruptAfterDelete = true
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun delete(identity: MessageIdentity) {
                    server(id).delete(identity)
                    if (interruptAfterDelete) {
                        interruptAfterDelete = false
                        throw MailFailure(FailureKind.CONNECTION, "Reply lost after DELETE")
                    }
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        val original = server("a").append("Drafts", raw(35, "Replace once"))
        val drafts = CoreRoomMapper.folderId("a", "Drafts")
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        repo.refreshVisibleFolder(drafts, Instant.EPOCH)
        val source = room.cachedMessages().single { it.subject == "Replace once" }
        room.cacheAttachment(source.attachments.single().id)
        val cached = room.cachedMessages().single { it.id == source.id }
        room.saveDraft(cached.copy(sender = "Account a", senderAddress = "a@fixture.invalid",
            subject = "Replacement survived"))
        assertEquals(0, repo.flushDrafts("a"))
        assertEquals(1, server("a").status("Drafts").messageCount)
        assertEquals("APPENDED", JSONObject(checkNotNull(db.remoteMailDao().message(source.id))
            .envelopeJson).getString("draftUploadPhase"))
        assertEquals(1, repo.flushDrafts("a"))
        val saved = checkNotNull(db.remoteMailDao().message(source.id))
        assertNotEquals(original.uid, saved.uid)
        assertEquals(1, server("a").status("Drafts").messageCount)
        assertEquals("Replacement survived", server("a").message(MessageIdentity("Drafts",
            checkNotNull(saved.uidValidity), checkNotNull(saved.uid))).subject)
    }

    @Test
    fun uncertainDraftRequiresExplicitRetryBeforeASecondAppend() = runBlocking {
        val db = open()
        var rejectFirstAppend = true
        var appendCalls = 0
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun append(mailbox: String, raw: ByteArray, read: Boolean,
                    draft: Boolean): MessageIdentity? {
                    appendCalls++
                    if (rejectFirstAppend) {
                        rejectFirstAppend = false
                        throw MailFailure(FailureKind.CONNECTION, "Connection dropped before APPEND")
                    }
                    return server(id).append(mailbox, raw, read, draft)
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "manual-draft-retry", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Manual retry", body = "Preserve intent",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        assertEquals(0, repo.flushDrafts("a"))
        assertEquals("UNCERTAIN", room.cachedMessages().single { it.id == draft.id }
            .draftSyncState?.name)
        assertEquals(0, repo.flushDrafts("a"))
        assertEquals(1, appendCalls)
        room.saveDraft(draft.copy(subject = "Edited before retry", body = "Newest content"))
        assertEquals(1, repo.retryUncertainDraft(draft.id))
        assertEquals(2, appendCalls)
        assertEquals(1, server("a").status("Drafts").messageCount)
        assertFalse(JSONObject(checkNotNull(db.remoteMailDao().message(draft.id))
            .envelopeJson).optBoolean("localDraftDirty"))
        assertEquals("Edited before retry", server("a").messages("Drafts", Instant.EPOCH)
            .single().subject)
    }

    @Test
    fun confirmedSentCopyRemovesOnlyTheSubmittedDraftRevision() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "send-cleanup-draft", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Ready to send", body = "Submitted body",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        assertFalse(db.remoteMailDao().hasOutboxForDraft(draft.id))
        assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED, room.send(draft))
        assertTrue(db.remoteMailDao().hasOutboxForDraft(draft.id))
        assertEquals(0, repo.flushDrafts("a"))
        val durable = DurableOutbox(db, context, codec)
        val entry = durable.entries("a").single()
        assertNotEquals("", JSONObject(entry.envelopeJson).optString("draftRevision"))
        durable.claimNext("a")
        durable.finish(entry.id, DurableOutbox.State.SENT)
        server("a").append("Sent", durable.raw(entry), true)
        assertEquals(1, repo.reconcileSent("a"))
        assertNull(db.remoteMailDao().message(draft.id))
        assertNull(db.remoteMailDao().outboxEntry(entry.id)?.draftId)
        assertFalse(db.remoteMailDao().hasOutboxForDraft(draft.id))
        assertEquals(0, server("a").status("Drafts").messageCount)
    }

    @Test
    fun confirmedDraftCleanupContinuesBeyondTheFirstSentPage() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(65) { index ->
            db.remoteMailDao().saveOutbox(OutboxEntity(
                id = "sent-history-$index", accountId = "a", draftId = null,
                messageId = "<sent-history-$index@fixture.invalid>", rawMessagePath = "",
                envelopeJson = JSONObject().put("sentCopyState", SentCopyStatus.CONFIRMED.name)
                    .toString(),
                state = DurableOutbox.State.SENT, createdAt = index.toLong(),
                updatedAt = index.toLong(),
            ))
        }
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "later-sent-draft", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Later sent draft", body = "Submitted body",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED, room.send(draft))
        val durable = DurableOutbox(db, context, codec)
        val entry = checkNotNull(db.remoteMailDao().outboxByMessageId("a",
            "<later-sent-draft@fixture.invalid>"))
        durable.claimNext("a")
        durable.finish(entry.id, DurableOutbox.State.SENT)
        durable.confirmSentCopy(entry.id, MessageIdentity("Sent", 77, 43))

        assertEquals(0, repo.reconcileSent("a"))
        assertNull(db.remoteMailDao().message(draft.id))
        assertNull(db.remoteMailDao().outboxEntry(entry.id)?.draftId)
    }

    @Test
    fun laterEditsSurviveConfirmationOfAnOlderQueuedRevision() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "edited-after-queue", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Older send", body = "Older body",
            receivedAt = Instant.now().toString(), draft = true,
        )
        room.saveDraft(draft)
        room.send(draft)
        val durable = DurableOutbox(db, context, codec)
        val entry = durable.entries("a").single()
        room.saveDraft(draft.copy(subject = "Newer edit", body = "Keep me"))
        durable.claimNext("a")
        durable.finish(entry.id, DurableOutbox.State.SENT)
        server("a").append("Sent", durable.raw(entry), true)
        assertEquals(1, repo.reconcileSent("a"))
        assertEquals("Newer edit", db.remoteMailDao().message(draft.id)?.subject)
        assertNull(db.remoteMailDao().outboxEntry(entry.id)?.draftId)
        assertEquals(1, repo.flushDrafts("a"))
        assertEquals(1, server("a").status("Drafts").messageCount)
    }

    @Test
    fun sendingAnOpenedServerDraftSnapshotsItBeforeQueuing() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("Drafts", raw(36, "Opened server draft"))
        val drafts = CoreRoomMapper.folderId("a", "Drafts")
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        repo.refreshVisibleFolder(drafts, Instant.EPOCH)
        val loaded = room.cachedMessages().single { it.subject == "Opened server draft" }
        room.cacheAttachment(loaded.attachments.single().id)
        val cached = room.cachedMessages().single { it.id == loaded.id }
        assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED,
            room.send(cached.copy(sender = "Account a", senderAddress = "a@fixture.invalid")))
        val durable = DurableOutbox(db, context, codec)
        val entry = durable.entries("a").single()
        assertTrue(JSONObject(entry.envelopeJson).optString("draftRevision").isNotBlank())
        durable.claimNext("a")
        durable.finish(entry.id, DurableOutbox.State.SENT)
        server("a").append("Sent", durable.raw(entry), true)
        assertEquals(1, repo.reconcileSent("a"))
        assertNull(db.remoteMailDao().message(cached.id))
        repo.flushOperations("a")
        assertEquals(0, server("a").status("Drafts").messageCount)
    }

    @Test
    fun editingAnAlreadyQueuedDraftKeepsTheQueuedMimeUnchanged() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val draft = org.foxred.kage.domain.model.Message(
            "queued-edit", "a", "", "Account a", "a@fixture.invalid",
            "friend@example.test", subject = "Earlier content", body = "Queued body",
            receivedAt = Instant.now().toString(), draft = true,
        )
        assertEquals(org.foxred.kage.domain.repository.SendDisposition.QUEUED, room.send(draft))
        val durable = DurableOutbox(db, context, codec)
        val entry = durable.entries("a").single()
        assertTrue(runCatching {
            room.send(draft.copy(subject = "Newer content", body = "Keep new text"))
        }.exceptionOrNull()?.message.orEmpty().contains("earlier content"))
        assertEquals("Newer content", db.remoteMailDao().message(draft.id)?.subject)
        assertEquals("Earlier content", codec.decode(durable.raw(entry)).subject)
        assertEquals(1, durable.entries("a").size)
    }

    @Test
    fun remoteReplyToAndReferencesReachComposeModel() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        val original = MimeMessage(Session.getInstance(Properties()), raw(6).inputStream())
        original.setHeader("Reply-To", "alternate@example.test")
        original.setHeader("References", "<earlier@example.test>")
        val bytes = ByteArrayOutputStream().also { original.writeTo(it) }.toByteArray()
        server("a").append("INBOX", bytes)
        remote.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        val source = room.cachedMessages().single { it.subject == "Message 6" }
        assertEquals("alternate@example.test", source.replyToAddress)
        assertEquals("<6.${"Message 6".hashCode()}@fixture.invalid>", source.rfcMessageId)
        assertEquals(listOf("<earlier@example.test>"), source.references)
    }

    @Test
    fun removingAnotherAccountKeepsTheSelectedFolderAndUnifiedInbox() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote, "a")
        ready(remote, "b")
        ready(remote, "c")
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        room.updatePreferences(org.foxred.kage.domain.model.Preferences(
            selectedFolder = inbox("b"), unified = true, started = true))
        room.removeAccount("a")
        assertEquals(inbox("b"), room.mailbox.first().preferences.selectedFolder)

        room.updatePreferences(org.foxred.kage.domain.model.Preferences(
            selectedFolder = "unified", unified = true, started = true))
        room.removeAccount("b")
        assertEquals("unified", room.mailbox.first().preferences.selectedFolder)
        room.removeAccount("c")
        assertEquals("", room.mailbox.first().preferences.selectedFolder)
    }

    @Test
    fun pagedSyncStoresEachMessageOnceAndMergesServerState() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(5) { server("a").append("INBOX", raw(it + 1)) }
        assertEquals(5, repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2))
        assertEquals(3, pageCalls)
        val first = rows(db, inbox())
        assertEquals((1L..5L).toSet(), first.map { it.uid }.toSet())
        assertTrue(first.none { it.bodyDownloaded })

        pageCalls = 0
        assertEquals(0, repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2))
        assertEquals("A completed pass only pages newer UIDs", 0, pageCalls)
        server("a").append("INBOX", raw(6))
        assertEquals(1, repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2))
        assertEquals(6, rows(db, inbox()).size)

        val target = first.single { it.uid == 2L }
        repo.downloadBody(target.id)
        val downloaded = db.remoteMailDao().message(target.id)!!
        assertTrue(downloaded.bodyDownloaded)
        assertTrue(downloaded.body.contains("Body 2"))
        assertEquals(listOf("part-2.txt"), db.remoteMailDao().attachments(target.id).map { it.filename })
        val part = db.remoteMailDao().attachments(target.id).single()
        db.remoteMailDao().saveAttachments(listOf(part.copy(cached = true, localFile = "kept")))

        server("a").markRead(MessageIdentity("INBOX", downloaded.uidValidity!!, 2), true)
        repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2, full = true)
        val merged = db.remoteMailDao().message(target.id)!!
        assertTrue("Server read state is merged", merged.isRead)
        assertTrue("Envelope-only refresh keeps the downloaded body", merged.body.contains("Body 2"))
        assertEquals("kept", db.remoteMailDao().attachments(target.id).single().localFile)
        repo.downloadBody(target.id)
        assertEquals("kept", db.remoteMailDao().attachments(target.id).single().localFile)
        assertEquals(6, rows(db, inbox()).size)
    }

    @Test
    fun newSinceVisitRemainsIndependentFromUnreadAndResetsWithVisit() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1))
        repo.syncMessages(inbox(), Instant.EPOCH)
        val first = rows(db, inbox()).single()
        assertFalse(first.isNew)
        assertFalse(first.isRead)
        assertEquals(1L, db.remoteMailDao().folder(inbox())!!.lastVisitedUid)

        server("a").append("INBOX", raw(2))
        repo.syncMessages(inbox(), Instant.EPOCH)
        val second = rows(db, inbox()).single { it.uid == 2L }
        assertTrue(second.isNew)
        assertFalse(second.isRead)
        repo.markRead(second.id, true)
        assertTrue(db.remoteMailDao().message(second.id)!!.isNew)
        assertTrue(db.remoteMailDao().message(second.id)!!.isRead)
        repo.finishVisit(inbox())
        assertFalse(db.remoteMailDao().message(second.id)!!.isNew)
        assertTrue(db.remoteMailDao().message(second.id)!!.isRead)
        assertEquals(2L, db.remoteMailDao().folder(inbox())!!.lastVisitedUid)
    }

    @Test
    fun attachmentStreamPublishesOnlyCompletePrivateFileAndRetries() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(7, html = "<p>Rendered 7</p>"))
        repo.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        assertFalse(message.bodyDownloaded)
        repo.downloadBody(message.id)
        assertEquals("<p>Rendered 7</p>", db.remoteMailDao().message(message.id)!!.html)
        val part = db.remoteMailDao().attachments(message.id).single()
        val directory = File(context.cacheDir, "remote-part-test-${System.nanoTime()}")
        try {
            failPart = true
            val error = runCatching { repo.downloadAttachment(part.id, directory) }.exceptionOrNull()
            assertEquals(FailureKind.CONNECTION, (error as MailFailure).kind)
            assertFalse(File(directory, part.id).exists())
            assertFalse(db.mailDao().attachment(part.id)!!.cached)
            assertEquals("NOT_DOWNLOADED", db.mailDao().attachment(part.id)!!.downloadState)
            failPart = false
            assertTrue(runCatching {
                repo.downloadAttachment(part.id, directory) { throw CancellationException("Reader left") }
            }.exceptionOrNull() is CancellationException)
            assertFalse(File(directory, part.id).exists())
            assertEquals("NOT_DOWNLOADED", db.mailDao().attachment(part.id)!!.downloadState)
            val progress = mutableListOf<Long>()
            val path = repo.downloadAttachment(part.id, directory) { progress += it }
            assertEquals("bytes 7", File(path).readText())
            assertTrue(progress.isNotEmpty())
            assertEquals(File(path).length(), progress.last())
            assertTrue(db.mailDao().attachment(part.id)!!.cached)
            assertEquals("DOWNLOADED", db.mailDao().attachment(part.id)!!.downloadState)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun truncatedCachedRemotePartIsDownloadedAgainThroughRepositoryAndReader() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(8))
        repo.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        repo.downloadBody(message.id)
        val part = db.remoteMailDao().attachments(message.id).single()
        val directory = File(context.filesDir, "attachments")
        val target = File(directory, part.id)
        try {
            assertEquals("bytes 8", File(repo.downloadAttachment(part.id, directory)).readText())
            assertEquals(target.length(), db.mailDao().attachment(part.id)!!.downloadedBytes)

            target.writeText("x")
            failPart = true
            val failedRepair = runCatching { repo.downloadAttachment(part.id, directory) }
                .exceptionOrNull() as MailFailure
            assertEquals(FailureKind.CONNECTION, failedRepair.kind)
            assertFalse(db.mailDao().attachment(part.id)!!.cached)
            failPart = false
            assertEquals("bytes 8", File(repo.downloadAttachment(part.id, directory)).readText())

            target.writeText("x")
            val reader = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
            assertEquals("bytes 8", File(reader.cacheAttachment(part.id)).readText())
            assertEquals(target.length(), db.mailDao().attachment(part.id)!!.downloadedBytes)
        } finally {
            failPart = false
            target.delete()
        }
    }

    @Test
    fun fullStorageDuringAttachmentStreamKeepsRetryablePartAndRemovesPartialFile() = runBlocking {
        val db = open()
        val initial = repository(db)
        ready(initial)
        server("a").append("INBOX", raw(17))
        initial.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        initial.downloadBody(message.id)
        val part = db.remoteMailDao().attachments(message.id).single()
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun downloadAttachment(identity: MessageIdentity, partId: String,
                    output: OutputStream): Long {
                    output.write("partial".toByteArray())
                    throw IOException("part write failed", ErrnoException("write", OsConstants.ENOSPC))
                }
            }
        }
        val directory = File(context.cacheDir, "full-part-test-${System.nanoTime()}")
        try {
            val failure = runCatching { repo.downloadAttachment(part.id, directory) }
                .exceptionOrNull() as MailFailure
            assertEquals(FailureKind.LIMIT_EXCEEDED, failure.kind)
            assertFalse(File(directory, part.id).exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("part-") })
            assertEquals("NOT_DOWNLOADED", db.mailDao().attachment(part.id)!!.downloadState)
            assertEquals("bytes 17", File(initial.downloadAttachment(part.id, directory)).readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun oldFolderAttachmentStreamCannotPublishAfterAMove() = runBlocking {
        val db = open()
        val initial = repository(db)
        ready(initial)
        server("a").append("INBOX", raw(1))
        initial.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val source = rows(db, inbox()).single()
        val part = db.remoteMailDao().attachments(source.id).single()
        val archive = CoreRoomMapper.folderId("a", "Archive")
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun downloadAttachment(identity: MessageIdentity, partId: String,
                    output: OutputStream): Long {
                    val count = server(id).downloadAttachment(identity, partId, output)
                    runBlocking {
                        db.remoteMailDao().relocate(source.id, archive,
                            identity.uidValidity, identity.uid)
                    }
                    return count
                }
            }
        }
        val directory = File(context.cacheDir, "stale-part-test-${System.nanoTime()}")
        try {
            val failure = runCatching { repo.downloadAttachment(part.id, directory) }
                .exceptionOrNull() as MailFailure
            assertEquals(FailureKind.PROTOCOL, failure.kind)
            assertFalse(File(directory, part.id).exists())
            val retained = checkNotNull(db.remoteMailDao().attachments(source.id).singleOrNull())
            assertFalse(retained.cached)
            assertEquals("NOT_DOWNLOADED", retained.downloadState)
            assertEquals(archive, db.remoteMailDao().message(source.id)?.folderId)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun storedRemoteAttachmentIdCannotPublishOutsideItsPrivateDirectory() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(19))
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        val part = db.remoteMailDao().attachments(message.id).single()
        val token = System.nanoTime()
        val directory = File(context.cacheDir, "remote-path-test-$token")
        val sentinel = File(context.cacheDir, "remote-path-sentinel-$token")
        val escapedId = "../${sentinel.name}"
        sentinel.writeText("keep")
        try {
            db.remoteMailDao().saveAttachments(listOf(part.copy(id = escapedId)))

            val failure = runCatching { repo.downloadAttachment(escapedId, directory) }
                .exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("keep", sentinel.readText())
            assertFalse(db.mailDao().attachment(escapedId)!!.cached)
        } finally {
            directory.deleteRecursively()
            sentinel.delete()
        }
    }

    @Test
    fun attachmentCacheFileIsRemovedWhenItsRoomWriteFails() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(9))
        repo.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        repo.downloadBody(message.id)
        val part = db.remoteMailDao().attachments(message.id).single()
        val directory = File(context.cacheDir, "remote-part-room-failure-${System.nanoTime()}")
        val trigger = "fail_cached_part_${System.nanoTime()}"
        try {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE UPDATE ON attachments " +
                "WHEN NEW.cached = 1 BEGIN SELECT RAISE(ABORT, 'cache write failed'); END")
            assertNotNull(runCatching { repo.downloadAttachment(part.id, directory) }.exceptionOrNull())
            assertFalse(File(directory, part.id).exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("part-") })
            assertFalse(db.mailDao().attachment(part.id)!!.cached)
            assertEquals("NOT_DOWNLOADED", db.mailDao().attachment(part.id)!!.downloadState)

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
            assertEquals("bytes 9", File(repo.downloadAttachment(part.id, directory)).readText())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            directory.deleteRecursively()
        }
    }

    @Test
    fun automaticAttachmentPolicyWaitsUntilOnlineAndRunsSeparately() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        server("a").append("INBOX", raw(8))
        remote.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        remote.downloadBody(message.id)
        val part = db.remoteMailDao().attachments(message.id).single()
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        val offline = org.foxred.kage.domain.model.Preferences(
            selectedFolder = inbox(), started = true, automaticAttachments = true, offline = true)
        room.updatePreferences(offline)
        assertFalse(db.mailDao().attachment(part.id)!!.cached)
        room.updatePreferences(offline.copy(offline = false))
        assertFalse(db.mailDao().attachment(part.id)!!.cached)
        remote.cacheAutomaticAttachments()
        assertTrue(db.mailDao().attachment(part.id)!!.cached)
        assertEquals("bytes 8", File(context.filesDir, "attachments/${part.id}").readText())
        File(context.filesDir, "attachments/${part.id}").delete()
        Unit
    }

    @Test
    fun cachedMailInitializationLeavesRemoteAutomaticDownloadsForRefresh() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        server("a").append("INBOX", raw(18))
        remote.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        remote.downloadBody(message.id)
        val part = db.remoteMailDao().attachments(message.id).single()
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        room.updatePreferences(org.foxred.kage.domain.model.Preferences(
            selectedFolder = inbox(), started = true, automaticAttachments = true, offline = true))
        val preferences = checkNotNull(db.mailDao().getPreferences())
        db.mailDao().savePreferences(preferences.copy(offline = false))
        val file = File(context.filesDir, "attachments/${part.id}")
        file.delete()
        try {
            room.initialize()
            assertFalse(db.mailDao().attachment(part.id)!!.cached)
            assertFalse(file.exists())
            assertNotNull(room.observeMessage(message.id).first())

            remote.cacheAutomaticAttachments()
            assertTrue(db.mailDao().attachment(part.id)!!.cached)
            assertEquals("bytes 18", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun automaticCachingStopsRetryingAnUnavailableAccountButContinuesAnother() = runBlocking {
        val db = open()
        val base = repository(db)
        ready(base, "a")
        ready(base, "b")
        repeat(3) { server("a").append("INBOX", raw(30 + it)) }
        server("b").append("INBOX", raw(40))
        for (accountId in listOf("a", "b")) {
            base.syncMessages(inbox(accountId), Instant.EPOCH)
            rows(db, inbox(accountId)).forEach { base.downloadBody(it.id) }
        }
        val aParts = rows(db, inbox("a")).flatMap { db.remoteMailDao().attachments(it.id) }
        val bPart = db.remoteMailDao().attachments(rows(db, inbox("b")).single().id).single()
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, base)
        room.updatePreferences(org.foxred.kage.domain.model.Preferences(
            selectedFolder = inbox(), started = true, automaticAttachments = true, offline = true))
        db.mailDao().savePreferences(checkNotNull(db.mailDao().getPreferences()).copy(offline = false))
        var failedAttempts = 0
        val remote = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun downloadAttachment(identity: MessageIdentity, partId: String,
                    output: OutputStream): Long {
                    if (id == "a") {
                        failedAttempts++
                        throw MailFailure(FailureKind.CONNECTION, "Account a is unavailable")
                    }
                    return server(id).downloadAttachment(identity, partId, output)
                }
            }
        }
        val file = File(context.filesDir, "attachments/${bPart.id}")
        try {
            remote.cacheAutomaticAttachments()
            assertEquals(1, failedAttempts)
            assertTrue(aParts.all { db.mailDao().attachment(it.id)?.cached == false })
            assertTrue(db.mailDao().attachment(bPart.id)!!.cached)
            assertEquals("bytes 40", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun automaticRemoteAttachmentProjectionContinuesAfterOnePage() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        server("a").append("INBOX", raw(41))
        remote.syncMessages(inbox(), Instant.EPOCH)
        val messageId = rows(db, inbox()).single().id
        val ids = (0 until 65).map { "remote-auto-${it.toString().padStart(3, '0')}" }
        db.remoteMailDao().saveAttachments(ids.map { id ->
            AttachmentEntity(id, messageId, "part.txt", "text/plain", 1,
                cached = false, asset = "", partId = "part-$id")
        })

        val first = db.remoteMailDao().firstUncachedRemoteAttachmentPage(64)
        val next = db.remoteMailDao().nextUncachedRemoteAttachmentPage(first.last().id, 64)
        assertEquals(ids.take(64), first.map { it.id })
        assertEquals(ids.drop(64), next.map { it.id })
        assertTrue((first + next).all { it.accountId == "a" })
    }

    @Test
    fun inlineMimePartFlowsIntoCachedCidImage() = runBlocking {
        val db = open()
        val remote = repository(db)
        ready(remote)
        val raw = """
            From: Sender <sender@fixture.invalid>
            To: Person <a@fixture.invalid>
            Subject: Inline image
            Message-ID: <inline@fixture.invalid>
            MIME-Version: 1.0
            Content-Type: multipart/related; boundary="fixture-boundary"

            --fixture-boundary
            Content-Type: text/html; charset=UTF-8

            <p>Inline image <img src="cid:logo@fixture"></p>
            --fixture-boundary
            Content-Type: image/png
            Content-ID: <logo@fixture>
            Content-Disposition: inline; filename="logo.png"
            Content-Transfer-Encoding: base64

            AQIDBA==
            --fixture-boundary--
        """.trimIndent().replace("\n", "\r\n").toByteArray()
        server("a").append("INBOX", raw)
        remote.syncMessages(inbox(), Instant.EPOCH)
        val row = rows(db, inbox()).single()
        remote.downloadBody(row.id)
        val part = db.remoteMailDao().attachments(row.id).single()
        assertEquals("logo@fixture", part.contentId?.removeSurrounding("<", ">"))
        assertTrue(part.inline)
        assertEquals(listOf(part.id), remote.inlineImageAttachmentIds(row.id))
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, remote)
        val file = File(context.filesDir, "attachments/${part.id}")
        try {
            room.cacheAttachment(part.id)
            assertTrue(remote.inlineImageAttachmentIds(row.id).isEmpty())
            file.writeBytes(byteArrayOf(1))
            assertEquals(listOf(part.id), remote.inlineImageAttachmentIds(row.id))
            room.cacheAttachment(part.id)
            assertTrue(remote.inlineImageAttachmentIds(row.id).isEmpty())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
            val html = db.remoteMailDao().message(row.id)!!.html.orEmpty()
            val safe = SafeMessageHtml.render(context, html,
                listOf(db.mailDao().attachment(part.id)!!.domain()))
            assertTrue(safe.contains("data:image/png;base64,AQIDBA=="))
            assertFalse(safe.contains("cid:logo@fixture"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingListedParentsBecomeNonselectableDrawerContainers() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").createMailbox("Orphan/Deep/Leaf")
        repo.refreshFolders("a")
        val folders = db.remoteMailDao().folders("a").associateBy { it.remotePath }
        assertEquals(folders.getValue("Orphan").id, folders.getValue("Orphan/Deep").parentId)
        assertEquals(folders.getValue("Orphan/Deep").id, folders.getValue("Orphan/Deep/Leaf").parentId)
        assertFalse(CoreRoomMapper.mailbox(folders.getValue("Orphan")).selectable)
        assertFalse(CoreRoomMapper.mailbox(folders.getValue("Orphan/Deep")).selectable)
        assertTrue(CoreRoomMapper.mailbox(folders.getValue("Orphan/Deep/Leaf")).selectable)
        assertEquals("archive", CoreRoomMapper.folder("a", Mailbox("All Mail", '/', true,
            attributes = setOf("\\Archive"))).role)
        server("a").deleteMailbox("Orphan/Deep/Leaf")
        repo.refreshFolders("a")
        assertTrue(db.remoteMailDao().folders("a").none {
            it.remotePath?.startsWith("Orphan") == true
        })
    }

    @Test
    fun interruptedSyncResumesFromDurableCursorAfterRestart() = runBlocking {
        var db = open(file = true)
        ready(repository(db))
        repeat(7) { server("a").append("INBOX", raw(it + 1)) }
        failPageAt = 3
        val failure = runCatching { repository(db).syncMessages(inbox(), Instant.EPOCH, pageSize = 2) }
        assertEquals(FailureKind.CONNECTION, (failure.exceptionOrNull() as MailFailure).kind)
        assertEquals(setOf(4L, 5L, 6L, 7L), rows(db, inbox()).map { it.uid }.toSet())
        val interrupted = db.remoteMailDao().cursor(inbox())!!
        assertEquals(4L, interrupted.beforeUid)
        assertNull(interrupted.lastCompletedAt)

        db.close()
        db = open(file = true)
        failPageAt = null
        pageCalls = 0
        server("a").append("INBOX", raw(8))
        repository(db).syncMessages(inbox(), Instant.EPOCH, pageSize = 2)
        assertEquals("New UID window, then the two unfinished older pages", 3, pageCalls)
        val all = rows(db, inbox())
        assertEquals((1L..8L).toList(), all.mapNotNull { it.uid }.sorted())
        val complete = db.remoteMailDao().cursor(inbox())!!
        assertNull(complete.beforeUid)
        assertNotNull(complete.lastCompletedAt)
        assertEquals(9L, db.remoteMailDao().folder(inbox())!!.uidNext)
    }

    @Test
    fun incrementalSyncPassesItsSavedUidBoundaryToPaging() = runBlocking {
        val db = open()
        val observed = mutableListOf<MessageCursor>()
        val repo = repository(db) { id ->
            val fixture = server(id)
            object : MailStore by fixture {
                override fun messagePage(mailbox: String, since: Instant,
                    cursor: MessageCursor?, limit: Int): MessagePage {
                    cursor?.let(observed::add)
                    return fixture.messagePage(mailbox, since, cursor, limit)
                }
            }
        }
        ready(repo)
        repeat(20) { server("a").append("INBOX", raw(it + 1)) }
        repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 100)
        val floor = checkNotNull(db.remoteMailDao().folder(inbox())?.uidNext)
        server("a").append("INBOX", raw(21))
        observed.clear()

        repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 100)

        assertEquals(1, observed.size)
        assertEquals(floor, observed.single().atOrAboveUid)
        assertEquals(floor + 1, observed.single().beforeUid)
        assertEquals(21, rows(db, inbox()).size)
    }

    @Test
    fun rollingThirtyDayCutoffResumesEarlierCursorButWideningRangeRescans() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(7) { server("a").append("INBOX", raw(it + 1)) }
        val firstCutoff = Instant.now().minusSeconds(30L * 24 * 60 * 60)
        failPageAt = 3
        val failure = runCatching {
            repo.syncMessages(inbox(), firstCutoff, pageSize = 2)
        }.exceptionOrNull() as MailFailure
        assertEquals(FailureKind.CONNECTION, failure.kind)
        assertEquals(4L, db.remoteMailDao().cursor(inbox())!!.beforeUid)

        failPageAt = null
        pageCalls = 0
        val laterCutoff = firstCutoff.plusSeconds(60)
        repo.syncMessages(inbox(), laterCutoff, pageSize = 2)
        assertEquals(2, pageCalls)
        assertEquals(7, rows(db, inbox()).size)
        assertEquals(laterCutoff.toEpochMilli(),
            db.remoteMailDao().cursor(inbox())!!.sinceEpochMillis)

        pageCalls = 0
        repo.syncMessages(inbox(), firstCutoff, pageSize = 2)
        assertEquals(4, pageCalls)
    }

    @Test
    fun recentSyncResumesAfterAnEmptyDateFilteredPage() = runBlocking {
        val boundary = Instant.parse("2026-08-26T00:00:00Z")
        var serverNow = boundary.minusSeconds(1)
        servers["a"] = DemoMailStore(now = { serverNow })
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(5) { server("a").append("INBOX", raw(it + 1)) }
        serverNow = boundary.plusSeconds(1)
        repeat(2) { server("a").append("INBOX", raw(it + 6)) }
        failPageAt = 3

        val interrupted = runCatching {
            repo.syncMessages(inbox(), boundary, pageSize = 2)
        }.exceptionOrNull() as MailFailure
        assertEquals(FailureKind.CONNECTION, interrupted.kind)
        assertEquals(4L, db.remoteMailDao().cursor(inbox())?.beforeUid)
        assertEquals(setOf(6L, 7L), rows(db, inbox()).mapNotNull { it.uid }.toSet())

        failPageAt = null
        pageCalls = 0
        repo.syncMessages(inbox(), boundary, pageSize = 2)
        assertEquals(2, pageCalls)
        assertNull(db.remoteMailDao().cursor(inbox())?.beforeUid)
        assertEquals(setOf(6L, 7L), rows(db, inbox()).mapNotNull { it.uid }.toSet())
    }

    @Test
    fun fullHistoryResumesAfterRecentRefreshWithoutReplayingCommittedPages() = runBlocking {
        var db = open(file = true)
        ready(repository(db))
        repeat(7) { server("a").append("INBOX", raw(it + 1)) }
        failPageAt = 3
        val interrupted = runCatching {
            repository(db).downloadHistory(inbox(), pageSize = 2)
        }.exceptionOrNull() as MailFailure
        assertEquals(FailureKind.CONNECTION, interrupted.kind)
        val checkpoint = checkNotNull(db.remoteMailDao().historyCursor(inbox()))
        assertEquals(4L, checkpoint.beforeUid)
        assertEquals(4, checkpoint.scannedMessages)
        assertEquals(4, rows(db, inbox()).size)

        db.close()
        db = open(file = true)
        failPageAt = null
        repository(db).syncMessages(inbox(), Instant.now().minusSeconds(30L * 86400),
            pageSize = 2)
        assertEquals(4L, db.remoteMailDao().historyCursor(inbox())?.beforeUid)
        val recentCursor = checkNotNull(db.remoteMailDao().cursor(inbox()))
        pageCalls = 0
        val result = repository(db).downloadHistory(inbox(), pageSize = 2)
        assertEquals(2, pageCalls)
        assertEquals(7, result.scannedMessages)
        assertTrue(result.scanComplete)
        assertEquals(0, result.remainingBodies)
        assertEquals(7, rows(db, inbox()).size)
        assertTrue(rows(db, inbox()).all { it.bodyDownloaded })
        assertTrue(rows(db, inbox()).all { row ->
            db.remoteMailDao().attachments(row.id).single().cached == false
        })
        assertEquals(recentCursor, db.remoteMailDao().cursor(inbox()))
    }

    @Test
    fun historyStartsANewScanAfterMailboxGenerationChangesAcrossRestart() = runBlocking {
        var db = open(file = true)
        ready(repository(db))
        server("a").append("INBOX", raw(1, "Old generation one"))
        server("a").append("INBOX", raw(2, "Old generation two"))
        failPageAt = 2
        assertEquals(FailureKind.CONNECTION,
            (runCatching { repository(db).downloadHistory(inbox(), pageSize = 1) }
                .exceptionOrNull() as MailFailure).kind)
        val oldGeneration = checkNotNull(db.remoteMailDao().historyCursor(inbox()))
        assertEquals(1, oldGeneration.scannedMessages)
        db.close()

        server("a").deleteMailbox("INBOX")
        server("a").createMailbox("INBOX")
        server("a").append("INBOX", raw(3, "Replacement generation"))
        db = open(file = true)
        failPageAt = null
        val resumed = repository(db).downloadHistory(inbox(), pageSize = 1)

        assertTrue(resumed.scanComplete)
        assertEquals(1, resumed.scannedMessages)
        assertEquals(0, resumed.remainingBodies)
        val replacement = rows(db, inbox()).single()
        assertEquals("Replacement generation", replacement.subject)
        assertTrue(replacement.bodyDownloaded)
        assertNotEquals(oldGeneration.uidValidity,
            db.remoteMailDao().historyCursor(inbox())?.uidValidity)
    }

    @Test
    fun startupRemovesOnlyUnreferencedRemotePartFiles() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1))
        repo.downloadHistory(inbox(), pageSize = 1)
        val row = rows(db, inbox()).single()
        val part = db.remoteMailDao().attachments(row.id).single()
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val kept = File(directory, part.id)
        val orphan = File(directory, "remote-part-orphan-${System.nanoTime()}")
        val imported = File(directory, "imported-${System.nanoTime()}")
        try {
            kept.writeText("kept")
            orphan.writeText("orphan")
            imported.writeText("imported")
            db.remoteMailDao().saveAttachments(listOf(part.copy(
                cached = true, localFile = kept.name)))

            RoomMailRepository(db, context, DemoMail(context), credentials, repo).initialize()

            assertTrue(kept.isFile)
            assertFalse(orphan.exists())
            assertTrue(imported.isFile)
        } finally {
            kept.delete()
            orphan.delete()
            imported.delete()
        }
    }

    @Test
    fun fullHistoryReconcilesAPlaceholderBeyondTheFirstBoundedChunk() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val targetMessageId = "<1.${"Message 1".hashCode()}@fixture.invalid>"
        val placeholders = (0..256).map { index ->
            val id = "history-placeholder-${index.toString().padStart(3, '0')}"
            MessageEntity(id, "a", inbox(), "Sender", "sender@example.test",
                "to@example.test", "", "", "Local placeholder", "", null,
                "2026-01-01T00:00:00Z", false, false, false, false, false, null,
                envelopeJson = JSONObject().put("messageId",
                    if (index == 256) targetMessageId else "<other-$index@fixture.invalid>")
                    .toString())
        }
        db.mailDao().saveMessages(placeholders)
        server("a").append("INBOX", raw(1))

        val progress = repo.downloadHistory(inbox(), pageSize = 1)

        assertTrue(progress.scanComplete)
        assertEquals(1L, db.remoteMailDao().message("history-placeholder-256")?.uid)
        assertEquals(1, rows(db, inbox()).count { it.uid != null })
        assertEquals(256, rows(db, inbox()).count { it.uid == null })
    }

    @Test
    fun recentFullPassSettlesOnlyPlaceholdersPresentWhenItStarted() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        fun placeholder(id: String) = MessageEntity(id, "a", inbox(), "Sender",
            "sender@example.test", "to@example.test", "", "", "Move placeholder", "",
            null, "2026-01-01T00:00:00Z", false, false, false, false, false, null,
            envelopeJson = JSONObject().put("messageId", "<$id@fixture.invalid>").toString())
        db.mailDao().saveMessages((0 until 65).map { placeholder("settled-before-$it") })
        server("a").append("INBOX", raw(1))
        afterPage = {
            afterPage = null
            runBlocking { db.mailDao().saveMessages(listOf(placeholder("settled-during"))) }
        }

        repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 32, full = true)

        assertNull(db.remoteMailDao().message("settled-before-0"))
        assertNull(db.remoteMailDao().message("settled-before-64"))
        assertNotNull(db.remoteMailDao().message("settled-during"))
        assertEquals(1, rows(db, inbox()).count { it.uid != null })
        assertEquals(1, rows(db, inbox()).count { it.uid == null })
    }

    @Test
    fun cancelledHistoryKeepsItsLastCommittedPageAndLimitsEachFetch() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(19) { server("a").append("INBOX", raw(it + 1)) }
        val requestedLimits = mutableListOf<Int>()
        val bounded = repository(db) { id ->
            object : MailStore by server(id) {
                override fun messagePage(mailbox: String, since: Instant,
                    cursor: MessageCursor?, limit: Int): MessagePage {
                    requestedLimits += limit
                    if (requestedLimits.size == 3)
                        throw CancellationException("History paused")
                    return server(id).messagePage(mailbox, since, cursor, limit)
                }
                override fun close() = Unit
            }
        }
        val cancelled = runCatching { bounded.downloadHistory(inbox(), pageSize = 3) }
            .exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertEquals(6, db.remoteMailDao().historyCursor(inbox())?.scannedMessages)
        assertEquals(6, rows(db, inbox()).size)
        assertTrue(requestedLimits.all { it <= 3 })

        val resumed = repo.downloadHistory(inbox(), pageSize = 3)
        assertTrue(resumed.scanComplete)
        assertEquals(19, resumed.scannedMessages)
        assertEquals(19, rows(db, inbox()).size)
        assertEquals(0, resumed.remainingBodies)
    }

    @Test
    fun completedHistoryCanBeCheckedAgainForNewServerMessages() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1))
        assertEquals(1, repo.downloadHistory(inbox(), pageSize = 2).scannedMessages)
        server("a").append("INBOX", raw(2))
        assertEquals(1, repo.downloadHistory(inbox(), pageSize = 2).scannedMessages)
        val checked = repo.downloadHistory(inbox(), pageSize = 2,
            restartCompleted = true)
        assertTrue(checked.scanComplete)
        assertEquals(2, checked.scannedMessages)
        assertEquals(2, rows(db, inbox()).size)
    }

    @Test
    fun historyDoesNotAdvanceRecentSyncUidBoundary() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val cutoff = Instant.now().minusSeconds(30L * 86400)
        server("a").append("INBOX", raw(1))
        repo.syncMessages(inbox(), cutoff, pageSize = 2)
        assertEquals(2L, db.remoteMailDao().folder(inbox())?.uidNext)

        server("a").append("INBOX", raw(2))
        repo.downloadHistory(inbox(), pageSize = 2)
        assertEquals(2L, db.remoteMailDao().folder(inbox())?.uidNext)
        pageCalls = 0
        repo.syncMessages(inbox(), cutoff, pageSize = 2)
        assertTrue("Recent sync must still page the new UID", pageCalls > 0)
        assertEquals(3L, db.remoteMailDao().folder(inbox())?.uidNext)
        assertEquals(2, rows(db, inbox()).size)
    }

    @Test
    fun recentRefreshCanRunBetweenCommittedHistoryPages() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(5) { server("a").append("INBOX", raw(it + 1)) }
        val firstPageCommitted = CountDownLatch(1)
        val continueHistory = CountDownLatch(1)
        val backfill = async(Dispatchers.Default) {
            repo.downloadHistory(inbox(), pageSize = 2) { progress ->
                if (progress.scannedMessages == 2 && !progress.scanComplete) {
                    firstPageCommitted.countDown()
                    check(continueHistory.await(10, TimeUnit.SECONDS))
                }
            }
        }
        assertTrue(firstPageCommitted.await(10, TimeUnit.SECONDS))
        try {
            repo.syncMessages(inbox(), Instant.now().minusSeconds(30L * 86400), pageSize = 2)
        } finally {
            continueHistory.countDown()
        }
        assertTrue(backfill.await().scanComplete)
        assertNotNull(db.remoteMailDao().cursor(inbox()))
        assertEquals(5, rows(db, inbox()).size)
    }

    @Test
    fun historyReportsLowStorageAndResumesCommittedBodies() = runBlocking {
        val db = open()
        var diskFull = true
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun message(identity: MessageIdentity): Email {
                    if (diskFull && identity.uid == 3L)
                        throw SQLiteFullException("Fixture storage is full")
                    return server(id).message(identity)
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        val failure = runCatching { repo.downloadHistory(inbox(), pageSize = 2) }
            .exceptionOrNull() as MailFailure
        assertEquals(FailureKind.LIMIT_EXCEEDED, failure.kind)
        assertEquals(2L, db.remoteMailDao().historyCursor(inbox())?.beforeUid)
        assertEquals(2, rows(db, inbox()).size)

        diskFull = false
        val finished = repo.downloadHistory(inbox(), pageSize = 2)
        assertTrue(finished.scanComplete)
        assertEquals(0, finished.remainingBodies)
        assertTrue(rows(db, inbox()).all { it.bodyDownloaded })
    }

    @Test
    fun expungedBodyDoesNotBlockTheRestOfFullHistory() = runBlocking {
        val db = open()
        var expungeOnce = true
        val repo = repository(db) { id ->
            object : MailStore by server(id) {
                override fun message(identity: MessageIdentity): Email {
                    if (identity.uid == 3L && expungeOnce) {
                        expungeOnce = false
                        server(id).delete(identity)
                        throw MailFailure(FailureKind.PROTOCOL, "Message no longer exists")
                    }
                    return server(id).message(identity)
                }
                override fun close() = Unit
            }
        }
        ready(repo)
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        val result = repo.downloadHistory(inbox(), pageSize = 2)
        assertTrue(result.scanComplete)
        assertEquals(0, result.remainingBodies)
        assertEquals(setOf(1L, 2L), rows(db, inbox()).mapNotNull { it.uid }.toSet())
        assertTrue(rows(db, inbox()).all { it.bodyDownloaded })
    }

    @Test
    fun historyVanishedCheckCannotDeleteAMessageMovedWhileChecking() = runBlocking {
        val db = open()
        ready(repository(db))
        server("a").append("INBOX", raw(1))
        val archive = CoreRoomMapper.folderId("a", "Archive")
        var movedId: String? = null
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email =
                    throw MailFailure(FailureKind.PROTOCOL, "Body vanished")
                override fun exists(identity: MessageIdentity): Boolean {
                    runBlocking {
                        val row = checkNotNull(db.remoteMailDao().messageByUid(inbox(),
                            identity.uidValidity, identity.uid))
                        movedId = row.id
                        db.remoteMailDao().relocate(row.id, archive,
                            identity.uidValidity, identity.uid)
                    }
                    return false
                }
            }
        }

        assertTrue(repo.downloadHistory(inbox(), pageSize = 1).scanComplete)
        assertEquals(archive, db.remoteMailDao().message(checkNotNull(movedId))?.folderId)
    }

    @Test
    fun fullScanRemovesExpungesOnlyAfterResumedPassCompletes() = runBlocking {
        var db = open(file = true)
        var repo = repository(db)
        ready(repo)
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2)
        val original = rows(db, inbox()).associateBy { it.uid }
        repo.downloadBody(original.getValue(2L).id)
        val identity = MessageIdentity("INBOX", original.getValue(1L).uidValidity!!, 1)
        server("a").move(identity, "Trash")
        server("a").markRead(identity.copy(uid = 2), true)
        pageCalls = 0
        failPageAt = 2
        val interrupted = runCatching {
            repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2, full = true)
        }.exceptionOrNull()
        assertEquals(FailureKind.CONNECTION, (interrupted as MailFailure).kind)
        assertEquals(3, rows(db, inbox()).size)
        val checkpoint = db.remoteMailDao().cursor(inbox())!!
        assertNotNull(checkpoint.fullPassId)
        assertEquals(2L, checkpoint.beforeUid)

        db.close()
        db = open(file = true)
        repo = repository(db)
        failPageAt = null
        repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2, full = true)
        assertEquals(setOf(2L, 3L), rows(db, inbox()).mapNotNull { it.uid }.toSet())
        assertTrue(db.remoteMailDao().message(original.getValue(2L).id)!!.isRead)
        assertTrue(db.remoteMailDao().message(original.getValue(2L).id)!!.bodyDownloaded)
        assertNull(db.remoteMailDao().cursor(inbox())!!.fullPassId)
    }

    @Test
    fun visibleThirtyDayRefreshResumesBodyHydrationAfterConnectionLoss() = runBlocking {
        var db = open(file = true)
        ready(repository(db))
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        var failUid: Long? = 2
        val fetched = mutableListOf<Long>()
        val store: (String) -> MailStore = { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email {
                    fetched += identity.uid
                    if (identity.uid == failUid)
                        throw MailFailure(FailureKind.CONNECTION, "Body fetch interrupted")
                    return server(id).message(identity)
                }
            }
        }
        var repo = repository(db, store)
        val failure = runCatching { repo.refreshVisibleFolder(inbox(), Instant.EPOCH) }
            .exceptionOrNull() as MailFailure
        assertEquals(FailureKind.CONNECTION, failure.kind)
        assertEquals(listOf(3L, 2L), fetched)
        assertEquals(3, rows(db, inbox()).size)
        assertNull(db.remoteMailDao().cursor(inbox())!!.beforeUid)
        assertTrue(rows(db, inbox()).single { it.uid == 3L }.bodyDownloaded)

        db.close()
        db = open(file = true)
        failUid = null
        repo = repository(db, store)
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertEquals(listOf(3L, 2L, 2L, 1L), fetched)
        assertTrue(rows(db, inbox()).all { it.bodyDownloaded })
        val attachments = db.remoteMailDao().attachments(rows(db, inbox()).first().id)
        assertEquals(1, attachments.size)
        assertTrue("Attachment bytes remain on demand", attachments.none { it.cached })
    }

    @Test
    fun lowStorageDuringBodyHydrationKeepsHeadersAndRetries() = runBlocking {
        val db = open()
        ready(repository(db))
        server("a").append("INBOX", raw(1))
        var storageFull = true
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email {
                    if (storageFull) throw IllegalStateException(
                        "Room write failed", SQLiteFullException("No room for message body"))
                    return server(id).message(identity)
                }
            }
        }
        val failure = runCatching { repo.refreshVisibleFolder(inbox(), Instant.EPOCH) }
            .exceptionOrNull() as MailFailure
        assertEquals(FailureKind.LIMIT_EXCEEDED, failure.kind)
        assertFalse(rows(db, inbox()).single().bodyDownloaded)
        assertNull(db.remoteMailDao().cursor(inbox())!!.beforeUid)
        storageFull = false
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertTrue(rows(db, inbox()).single().bodyDownloaded)
    }

    @Test
    fun bodyFetchForAnotherUidCannotReplaceTheRequestedMessage() = runBlocking {
        val db = open()
        ready(repository(db))
        repeat(2) { server("a").append("INBOX", raw(it + 1)) }
        repository(db).syncMessages(inbox(), Instant.EPOCH)
        val original = rows(db, inbox()).associateBy { it.uid }
        val wrongBody = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email =
                    server(id).message(identity.copy(uid = 2L))
            }
        }

        val failure = runCatching { wrongBody.downloadBody(original.getValue(1L).id) }
            .exceptionOrNull() as MailFailure
        assertEquals(FailureKind.PROTOCOL, failure.kind)
        assertFalse(db.remoteMailDao().message(original.getValue(1L).id)!!.bodyDownloaded)
        assertFalse(db.remoteMailDao().message(original.getValue(2L).id)!!.bodyDownloaded)

        repository(db).downloadBody(original.getValue(1L).id)
        assertTrue(db.remoteMailDao().message(original.getValue(1L).id)!!.bodyDownloaded)
    }

    @Test
    fun oversizedBodyDoesNotStopOtherRecentBodies() = runBlocking {
        val db = open()
        ready(repository(db))
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email {
                    if (identity.uid == 2L)
                        throw MailFailure(FailureKind.LIMIT_EXCEEDED, "Body exceeds limit")
                    return server(id).message(identity)
                }
            }
        }
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertEquals(setOf(1L, 3L), rows(db, inbox())
            .filter { it.bodyDownloaded }.mapNotNull { it.uid }.toSet())
        assertEquals(3, rows(db, inbox()).size)
    }

    @Test
    fun malformedBodyDoesNotStopOtherRecentBodies() = runBlocking {
        val db = open()
        ready(repository(db))
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email {
                    if (identity.uid == 2L)
                        throw MailFailure(FailureKind.INVALID_MESSAGE, "Malformed MIME")
                    return server(id).message(identity)
                }
            }
        }
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertEquals(setOf(1L, 3L), rows(db, inbox())
            .filter { it.bodyDownloaded }.mapNotNull { it.uid }.toSet())
        assertEquals(3, rows(db, inbox()).size)
    }

    @Test
    fun expungedBeforeBodyDownloadDoesNotStopOtherRecentBodies() = runBlocking {
        val db = open()
        ready(repository(db))
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email {
                    if (identity.uid == 2L) {
                        server(id).delete(identity)
                        throw MailFailure(FailureKind.PROTOCOL, "Message was expunged")
                    }
                    return server(id).message(identity)
                }
            }
        }
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertEquals(setOf(1L, 3L), rows(db, inbox()).mapNotNull { it.uid }.toSet())
        assertTrue(rows(db, inbox()).all { it.bodyDownloaded })
    }

    @Test
    fun recentVanishedCheckCannotDeleteAMessageMovedWhileChecking() = runBlocking {
        val db = open()
        ready(repository(db))
        server("a").append("INBOX", raw(1))
        repository(db).syncMessages(inbox(), Instant.EPOCH)
        val source = rows(db, inbox()).single()
        val archive = CoreRoomMapper.folderId("a", "Archive")
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email =
                    throw MailFailure(FailureKind.PROTOCOL, "Body vanished")
                override fun exists(identity: MessageIdentity): Boolean {
                    runBlocking {
                        db.remoteMailDao().relocate(source.id, archive,
                            identity.uidValidity, identity.uid)
                    }
                    return false
                }
            }
        }

        repo.syncMessages(inbox(), Instant.EPOCH, full = true, hydrateBodies = true)
        assertEquals(archive, db.remoteMailDao().message(source.id)?.folderId)
    }

    @Test
    fun accountRemovalInterruptsBodyHydrationWithoutRecreatingMail() = runBlocking {
        val db = open()
        ready(repository(db))
        server("a").append("INBOX", raw(1))
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun message(identity: MessageIdentity): Email {
                    entered.countDown()
                    check(released.await(5, TimeUnit.SECONDS)) { "Body fetch was not cancelled" }
                    throw MailFailure(FailureKind.CANCELLED, "Body fetch cancelled")
                }
                override fun cancel() { released.countDown() }
                override fun close() { released.countDown() }
            }
        }
        val refresh = async(Dispatchers.Default) {
            runCatching { repo.refreshVisibleFolder(inbox(), Instant.EPOCH) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repo.removeAccount("a")
        assertEquals(FailureKind.CANCELLED,
            (withTimeout(5_000) { refresh.await() }.exceptionOrNull() as MailFailure).kind)
        assertNull(db.remoteMailDao().account("a"))
        assertTrue(rows(db, inbox()).isEmpty())
    }

    @Test
    fun backgroundRefreshAlertsForCommittedMailEvenIfBodyFetchFails() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        var alerts = 0
        val runner = BackgroundMailRunner(room, repo, db.remoteMailDao(),
            notify = { _, _ -> alerts++ })
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = true, offline = false)
        try {
            val first = checkNotNull(server("a").append("INBOX", raw(1)))
            runner.run(context)
            assertEquals(first.uid, BackgroundMailSettings.lastNotifiedUid(context, inbox()))
            assertEquals(0, alerts)

            val second = checkNotNull(server("a").append("INBOX", raw(2)))
            failBodyUid = second.uid
            assertNotNull(runCatching { runner.run(context) }.exceptionOrNull())
            assertEquals(second.uid, BackgroundMailSettings.lastNotifiedUid(context, inbox()))
            assertEquals(1, alerts)

            failBodyUid = null
            runner.run(context)
            assertEquals(1, alerts)
            assertTrue(rows(db, inbox()).first { it.uid == second.uid }.bodyDownloaded)
        } finally {
            failBodyUid = null
            BackgroundMailSettings.setEnabled(context, false,
                hasEligibleAccount = true, offline = false)
        }
    }

    @Test
    fun failedNotificationKeepsCommittedUidCheckpointAndDoesNotAlertAgain() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        var notifyAttempts = 0
        var rejectNotification = true
        val runner = BackgroundMailRunner(room, repo, db.remoteMailDao(),
            notify = { _, _ ->
                notifyAttempts++
                if (rejectNotification) throw SecurityException("Notification permission changed")
            })
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = true, offline = false)
        try {
            val baseline = checkNotNull(server("a").append("INBOX", raw(1)))
            assertTrue(runner.run(context))
            assertEquals(baseline.uid, BackgroundMailSettings.lastNotifiedUid(context, inbox()))

            val next = checkNotNull(server("a").append("INBOX", raw(2)))
            assertTrue(runCatching { runner.run(context) }.exceptionOrNull() is SecurityException)
            assertEquals(next.uid, BackgroundMailSettings.lastNotifiedUid(context, inbox()))
            assertEquals(1, notifyAttempts)

            rejectNotification = false
            assertTrue(runner.run(context))
            assertEquals(1, notifyAttempts)
        } finally {
            BackgroundMailSettings.setEnabled(context, false,
                hasEligibleAccount = true, offline = false)
        }
    }

    @Test
    fun removedAccountCannotOverwriteBackgroundNotificationCheckpoint() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        var alerts = 0
        val runner = BackgroundMailRunner(room, repo, db.remoteMailDao(),
            notify = { _, _ -> alerts++ })
        BackgroundMailSettings.setEnabled(context, false,
            hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true,
            hasEligibleAccount = true, offline = false)
        try {
            val first = checkNotNull(server("a").append("INBOX", raw(1)))
            assertTrue(runner.run(context))
            assertEquals(first.uid, BackgroundMailSettings.lastNotifiedUid(context, inbox()))

            server("a").append("INBOX", raw(2))
            afterPage = {
                afterPage = null
                runBlocking { db.remoteMailDao().removeAccount("a") }
            }
            assertFalse(runner.run(context))
            assertNull(db.remoteMailDao().account("a"))
            assertEquals(first.uid, BackgroundMailSettings.lastNotifiedUid(context, inbox()))
            assertEquals(0, alerts)
        } finally {
            afterPage = null
            BackgroundMailSettings.setEnabled(context, false,
                hasEligibleAccount = false, offline = false)
        }
    }

    @Test
    fun backgroundNotificationBaselineResetsWhenMailboxGenerationChanges() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        var alerts = 0
        val runner = BackgroundMailRunner(room, repo, db.remoteMailDao(),
            notify = { _, _ -> alerts++ })
        BackgroundMailSettings.setEnabled(context, false,
            hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true,
            hasEligibleAccount = true, offline = false)
        try {
            repeat(3) { server("a").append("INBOX", raw(it + 1)) }
            assertTrue(runner.run(context))
            val oldGeneration = checkNotNull(db.remoteMailDao().folder(inbox())?.uidValidity)
            assertEquals(3L, BackgroundMailSettings.lastNotifiedUid(context, inbox()))

            server("a").deleteMailbox("INBOX")
            server("a").createMailbox("INBOX")
            server("a").append("INBOX", raw(4))
            assertTrue(runner.run(context))
            val newGeneration = checkNotNull(db.remoteMailDao().folder(inbox())?.uidValidity)
            assertNotEquals(oldGeneration, newGeneration)
            assertEquals(1L, BackgroundMailSettings.lastNotifiedUid(context, inbox()))
            assertEquals(0, alerts)

            server("a").append("INBOX", raw(5))
            assertTrue(runner.run(context))
            assertEquals(2L, BackgroundMailSettings.lastNotifiedUid(context, inbox()))
            assertEquals(1, alerts)
        } finally {
            BackgroundMailSettings.setEnabled(context, false,
                hasEligibleAccount = true, offline = false)
        }
    }

    @Test
    fun queuedBackgroundRunDoesNotCrossAReenabledOptIn() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        var alerts = 0
        val runner = BackgroundMailRunner(room, repo, db.remoteMailDao(),
            notify = { _, _ -> alerts++ })
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = false, offline = false)
        val stoppedToken = checkNotNull(BackgroundMailSettings.runToken(context))
        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = false, offline = false)
        try {
            server("a").append("INBOX", raw(1))
            assertFalse(runner.run(context, stoppedToken))
            assertTrue(rows(db, inbox()).isEmpty())
            assertNull(BackgroundMailSettings.lastNotifiedUid(context, inbox()))
            assertEquals(0, alerts)
        } finally {
            BackgroundMailSettings.setEnabled(context, false,
                hasEligibleAccount = false, offline = false)
        }
    }

    @Test
    fun backgroundRefreshLeavesGoogleAccountsForForegroundAuthorization() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo, "a")
        ready(repo, "b")
        val google = checkNotNull(db.remoteMailDao().account("a"))
        assertEquals(1, db.remoteMailDao().updateAccount(
            google.copy(oauthConfigurationJson = "{}")))
        server("a").append("INBOX", raw(1))
        val passwordMail = checkNotNull(server("b").append("INBOX", raw(2)))
        val room = RoomMailRepository(db, context, DemoMail(context), credentials, repo)
        val accounts = room.mailbox.first().accounts.associateBy { it.id }
        assertFalse(backgroundMailEligible(accounts.getValue("a")))
        assertTrue(backgroundMailEligible(accounts.getValue("b")))

        BackgroundMailSettings.setEnabled(context, false, hasEligibleAccount = false, offline = false)
        BackgroundMailSettings.setEnabled(context, true, hasEligibleAccount = true, offline = false)
        try {
            assertTrue(BackgroundMailRunner(room, repo, db.remoteMailDao()).run(context))
            assertTrue(rows(db, inbox("a")).isEmpty())
            assertTrue(rows(db, inbox("b")).single().bodyDownloaded)
            assertNull(BackgroundMailSettings.lastNotifiedUid(context, inbox("a")))
            assertEquals(passwordMail.uid,
                BackgroundMailSettings.lastNotifiedUid(context, inbox("b")))
        } finally {
            BackgroundMailSettings.setEnabled(context, false,
                hasEligibleAccount = true, offline = false)
        }
    }

    @Test
    fun thirtyDayBoundaryIncludesExactStartAndKeepsOlderDownloadedMail() = runBlocking {
        val boundary = Instant.parse("2026-08-26T00:00:00Z")
        var serverTime = boundary.minusMillis(1)
        servers["a"] = DemoMailStore(now = { serverTime })
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1, "Before boundary"))
        serverTime = boundary
        server("a").append("INBOX", raw(2, "At boundary"))
        serverTime = boundary.plusSeconds(1)
        server("a").append("INBOX", raw(3, "After boundary"))

        repo.refreshVisibleFolder(inbox(), boundary)
        assertEquals(setOf("At boundary", "After boundary"),
            rows(db, inbox()).map { it.subject }.toSet())
        assertTrue(rows(db, inbox()).all { it.bodyDownloaded })

        repo.refreshVisibleFolder(inbox(), boundary.minusSeconds(1), full = true)
        val older = rows(db, inbox()).single { it.subject == "Before boundary" }
        assertTrue(older.bodyDownloaded)
        val drafts = db.remoteMailDao().folderByRole("a", "drafts")!!
        db.remoteMailDao().saveMessage(older.copy(
            id = "local-draft", folderId = drafts.id, uid = null, uidValidity = null, draft = true))
        val atBoundary = rows(db, inbox()).single { it.subject == "At boundary" }
        repo.flag(atBoundary.id, true)
        repo.syncMessages(inbox(), boundary, full = true)
        assertTrue("Downloaded content survives a narrower window",
            db.remoteMailDao().message(older.id)!!.bodyDownloaded)
        assertTrue(db.remoteMailDao().message(older.id)!!.body.contains("Body 1"))
        assertNotNull(db.remoteMailDao().message("local-draft"))
        assertEquals(OperationState.PENDING, db.remoteMailDao().operations("a").single().state)
    }

    @Test
    fun recentFullPassCleansSeveralUidPagesWithoutDiscardingDownloadedHistory() = runBlocking {
        val cutoff = Instant.parse("2026-08-26T00:00:00Z")
        var serverTime = cutoff.minusSeconds(86_400)
        servers["a"] = DemoMailStore(now = { serverTime })
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1, "Older history"))
        serverTime = cutoff
        repeat(6) { server("a").append("INBOX", raw(it + 2)) }
        repo.downloadHistory(inbox(), pageSize = 2)
        repo.syncMessages(inbox(), cutoff, pageSize = 2, full = true)
        val old = rows(db, inbox()).single { it.uid == 1L }
        val validity = checkNotNull(old.uidValidity)

        (2L..5L).forEach { server("a").move(MessageIdentity("INBOX", validity, it), "Trash") }
        repo.syncMessages(inbox(), cutoff, pageSize = 2, full = true)

        assertEquals(setOf(1L, 6L, 7L), rows(db, inbox()).mapNotNull { it.uid }.toSet())
        assertTrue(db.remoteMailDao().message(old.id)!!.body.contains("Body 1"))
        assertNull(db.remoteMailDao().cursor(inbox())?.beforeUid)
    }

    @Test
    fun visibleRefreshAppliesQresyncFlagsAndVanishedWithoutFullEnvelopeScan() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        deltaEnabled = true
        repeat(3) { server("a").append("INBOX", raw(it + 1)) }
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val old = rows(db, inbox()).associateBy { it.uid }
        assertEquals(10L, db.remoteMailDao().cursor(inbox())!!.highestModSeq)
        val validity = old.getValue(1L).uidValidity!!
        server("a").markRead(MessageIdentity("INBOX", validity, 2), true)
        server("a").move(MessageIdentity("INBOX", validity, 1), "Trash")
        nextChanges = MailboxChanges(validity, 11,
            listOf(MailboxFlagChange(2, read = true, flagged = false)), setOf(1))
        pageCalls = 0
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertEquals(0, pageCalls)
        assertNull(db.remoteMailDao().message(old.getValue(1L).id))
        assertTrue(db.remoteMailDao().message(old.getValue(2L).id)!!.isRead)
        assertEquals(11L, db.remoteMailDao().cursor(inbox())!!.highestModSeq)

        repo.markRead(old.getValue(3L).id, true)
        nextChanges = MailboxChanges(validity, 12,
            listOf(MailboxFlagChange(3, read = false, flagged = false)), emptySet())
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertTrue("Queued intent wins over QRESYNC", db.remoteMailDao().message(old.getValue(3L).id)!!.isRead)
    }

    @Test
    fun visibleRefreshFallsBackToFullScanWhenQresyncIsUnavailable() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(2) { server("a").append("INBOX", raw(it + 1)) }
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val original = rows(db, inbox()).associateBy { it.uid }
        val validity = original.getValue(1L).uidValidity!!
        server("a").move(MessageIdentity("INBOX", validity, 1), "Trash")
        server("a").markRead(MessageIdentity("INBOX", validity, 2), true)
        pageCalls = 0
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertTrue("Fallback scans UID pages", pageCalls > 0)
        assertEquals(listOf(2L), rows(db, inbox()).mapNotNull { it.uid })
        assertTrue(db.remoteMailDao().message(original.getValue(2L).id)!!.isRead)
        assertNull(db.remoteMailDao().cursor(inbox())!!.highestModSeq)
    }

    @Test
    fun rejectedPageRollsBackWithoutAdvancingCursor() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        repeat(4) { server("a").append("INBOX", raw(it + 1)) }
        corruptPageAt = 2
        assertTrue(runCatching { repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2) }.isFailure)
        assertEquals(setOf(3L, 4L), rows(db, inbox()).map { it.uid }.toSet())
        assertEquals(3L, db.remoteMailDao().cursor(inbox())!!.beforeUid)
        corruptPageAt = null
        repo.syncMessages(inbox(), Instant.EPOCH, pageSize = 2)
        assertEquals(setOf(1L, 2L, 3L, 4L), rows(db, inbox()).map { it.uid }.toSet())
    }

    @Test
    fun queuedIntentWinsUntilServerConfirmsAndSurvivesRestart() = runBlocking {
        var db = open(file = true)
        var repo = repository(db)
        ready(repo)
        repeat(2) { server("a").append("INBOX", raw(it + 1)) }
        repo.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single { it.uid == 1L }
        val identity = MessageIdentity("INBOX", message.uidValidity!!, 1)

        repo = repository(db)
        offline = true
        repo.markRead(message.id, false)
        repo.markRead(message.id, true)
        repo.flag(message.id, true)
        assertEquals("Repeated intent is coalesced", 2, db.remoteMailDao().operations("a").size)
        assertTrue(db.remoteMailDao().message(message.id)!!.let { it.isRead && it.flagged })
        val offlineFailure = runCatching { repo.flushOperations("a") }.exceptionOrNull()
        assertEquals(FailureKind.CONNECTION, (offlineFailure as MailFailure).kind)
        assertTrue(db.remoteMailDao().operations("a").all { it.state == OperationState.PENDING })

        offline = false
        repo.syncMessages(inbox(), Instant.EPOCH, full = true)
        assertTrue(
            "Server state must not overwrite queued intent",
            db.remoteMailDao().message(message.id)!!.let { it.isRead && it.flagged },
        )
        assertFalse(server("a").message(identity).read)

        db.close()
        db = open(file = true)
        repo = repository(db)
        assertEquals(RemoteMailRepository.FlushResult(2, 0), repo.flushOperations("a"))
        assertTrue(server("a").message(identity).let { it.read && it.flagged })
        assertTrue(db.remoteMailDao().operations("a").all { it.state == OperationState.APPLIED })
        repo.syncMessages(inbox(), Instant.EPOCH, full = true)
        assertTrue(db.remoteMailDao().message(message.id)!!.isRead)
        assertTrue("Confirmed intent is purged by a later pass", db.remoteMailDao().operations("a").isEmpty())
    }

    @Test
    fun offlineActionsReplayInOrderAcrossBoundedPages() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val identity = server("a").append("INBOX", raw(801, "Queued flag target"))
        repeat(130) { number ->
            db.remoteMailDao().saveOperation(PendingOperationEntity(
                id = "paged-operation-${number.toString().padStart(3, '0')}",
                accountId = "a", messageId = null, mailbox = "INBOX",
                uidValidity = identity.uidValidity, uid = identity.uid,
                kind = RemoteMailRepository.OperationKind.READ.name,
                desiredValue = number % 2 == 0, targetMailbox = null,
                state = if (number % 2 == 0) OperationState.PENDING else OperationState.IN_FLIGHT,
                createdAt = 1_000L + number / 2, updatedAt = 1_000L + number / 2,
            ))
        }

        assertEquals(RemoteMailRepository.FlushResult(130, 0), repo.flushOperations("a"))
        assertFalse(server("a").message(identity).read)
        assertTrue(db.remoteMailDao().operations("a").all { it.state == OperationState.APPLIED })
    }

    @Test
    fun visibleRefreshReplaysOfflineActionsBeforeMergingServerFlags() = runBlocking {
        val db = open()
        var repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1))
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        val identity = MessageIdentity("INBOX", message.uidValidity!!, message.uid!!)

        repo.markRead(message.id, true)
        repo.flag(message.id, true)
        repo = repository(db)
        offline = true
        assertEquals(FailureKind.CONNECTION,
            (runCatching { repo.refreshVisibleFolder(inbox(), Instant.EPOCH) }
                .exceptionOrNull() as MailFailure).kind)
        assertTrue(db.remoteMailDao().operations("a").all { it.state == OperationState.PENDING })

        offline = false
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertTrue(server("a").message(identity).let { it.read && it.flagged })
        assertTrue(db.remoteMailDao().operations("a").all { it.state == OperationState.APPLIED })
        assertTrue(db.remoteMailDao().message(message.id)!!.let { it.isRead && it.flagged })
    }

    @Test
    fun rejectedReadAndFlagActionsReconcileFromServerOnRefresh() = runBlocking {
        val db = open()
        val initial = repository(db)
        ready(initial)
        server("a").append("INBOX", raw(1))
        initial.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun markRead(identity: MessageIdentity, read: Boolean) {
                    throw MailFailure(FailureKind.PROTOCOL, "Read rejected")
                }
                override fun flag(identity: MessageIdentity, flagged: Boolean) {
                    throw MailFailure(FailureKind.PROTOCOL, "Flag rejected")
                }
            }
        }
        repo.markRead(message.id, true)
        repo.flag(message.id, true)
        assertTrue(db.remoteMailDao().message(message.id)!!.let { it.isRead && it.flagged })
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        assertFalse(db.remoteMailDao().message(message.id)!!.isRead)
        assertFalse(db.remoteMailDao().message(message.id)!!.flagged)
        assertTrue(db.remoteMailDao().operations("a").all { it.state == OperationState.FAILED })
    }

    @Test
    fun stalePageFetchedBeforeConfirmationCannotRevertIntent() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1))
        repo.syncMessages(inbox(), Instant.EPOCH)
        val message = rows(db, inbox()).single()
        repo.markRead(message.id, true)
        // The page is read while the server is still unread; the operation is confirmed before
        // that page commits, as a concurrent flush would do between network read and commit.
        afterPage = {
            afterPage = null
            server("a").markRead(MessageIdentity("INBOX", message.uidValidity!!, 1), true)
            runBlocking {
                val op = db.remoteMailDao().operations("a").single()
                db.remoteMailDao().saveOperation(op.copy(state = OperationState.APPLIED, updatedAt = clock++))
            }
            server("a").markRead(MessageIdentity("INBOX", message.uidValidity!!, 1), false)
        }
        repo.syncMessages(inbox(), Instant.EPOCH, full = true)
        assertTrue(db.remoteMailDao().message(message.id)!!.isRead)
    }

    @Test
    fun moveUsesPlaceholderUntilTargetCopyConfirmsIt() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1, "Keep"))
        server("a").append("INBOX", raw(2, "Rejected"))
        server("a").createMailbox("Temporary")
        repo.refreshFolders("a")
        repo.syncMessages(inbox(), Instant.EPOCH)
        val archive = CoreRoomMapper.folderId("a", "Archive")
        val keep = rows(db, inbox()).single { it.subject == "Keep" }
        val rejected = rows(db, inbox()).single { it.subject == "Rejected" }
        db.mailDao().pin(keep.id, true)

        repo.move(keep.id, archive)
        repo.move(rejected.id, CoreRoomMapper.folderId("a", "Temporary"))
        assertTrue(db.remoteMailDao().message(keep.id)!!.let { it.folderId == archive && it.uid == null })
        assertTrue(runCatching { repo.markRead(keep.id, true) }.isFailure)
        repo.syncMessages(inbox(), Instant.EPOCH, full = true)
        assertTrue("Queued moves are not re-added to the source", rows(db, inbox()).isEmpty())

        server("a").deleteMailbox("Temporary")
        val interrupted = db.remoteMailDao().operations("a").single { it.messageId == rejected.id }
        db.remoteMailDao().saveOperation(interrupted.copy(state = OperationState.IN_FLIGHT))
        assertEquals(RemoteMailRepository.FlushResult(1, 1), repo.flushOperations("a"))
        val restored = db.remoteMailDao().message(rejected.id)!!
        assertEquals(inbox(), restored.folderId)
        assertEquals(rejected.uid, restored.uid)
        assertEquals(
            OperationState.FAILED,
            db.remoteMailDao().operations("a").single { it.messageId == rejected.id }.state,
        )

        repo.syncMessages(archive, Instant.EPOCH)
        val archived = rows(db, archive)
        assertEquals(1, archived.size)
        assertNotNull(archived.single().uid)
        assertTrue("Local pin follows the confirmed copy", archived.single().pinned)
        assertEquals(keep.id, archived.single().id)
        repo.syncMessages(inbox(), Instant.EPOCH, full = true)
        assertEquals(listOf("Rejected"), rows(db, inbox()).map { it.subject })
    }

    @Test
    fun confirmedMoveKeepsDownloadedBodyAndAttachmentFile() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").append("INBOX", raw(1))
        repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        val source = rows(db, inbox()).single()
        val part = db.remoteMailDao().attachments(source.id).single()
        val directory = File(context.cacheDir, "moved-part-test-${System.nanoTime()}")
        try {
            val path = repo.downloadAttachment(part.id, directory)
            val archive = CoreRoomMapper.folderId("a", "Archive")
            repo.move(source.id, archive)
            assertEquals(RemoteMailRepository.FlushResult(1, 0), repo.flushOperations("a"))
            repo.syncMessages(archive, Instant.EPOCH)

            val confirmed = rows(db, archive).single()
            assertEquals(source.id, confirmed.id)
            assertEquals(source.body, confirmed.body)
            assertTrue(confirmed.bodyDownloaded)
            val retainedPart = db.remoteMailDao().attachments(source.id).single()
            assertEquals(part.id, retainedPart.id)
            assertTrue(retainedPart.cached)
            assertEquals("bytes 1", File(path).readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun interruptedCopyFallbackFinishesSourceWithoutCopyingTwiceAfterRestart() = runBlocking {
        var db = open(file = true)
        ready(repository(db))
        server("a").append("INBOX", raw(1))
        repository(db).syncMessages(inbox(), Instant.EPOCH)
        val source = rows(db, inbox()).single()
        val identity = MessageIdentity("INBOX", source.uidValidity!!, source.uid!!)
        var moveCalls = 0
        val store: (String) -> MailStore = { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun supports(capability: String): Boolean =
                    if (capability == "MOVE") false else server(id).supports(capability)

                override fun move(identity: MessageIdentity, targetMailbox: String) {
                    moveCalls++
                    if (moveCalls > 1) error("Interrupted COPY must not be replayed")
                    server(id).append(targetMailbox, raw(1))
                    throw MailFailure(FailureKind.CONNECTION, "Disconnected after COPY")
                }
            }
        }
        var repo = repository(db, store)
        repo.move(source.id, CoreRoomMapper.folderId("a", "Archive"))
        assertEquals(FailureKind.CONNECTION,
            (runCatching { repo.flushOperations("a") }.exceptionOrNull() as MailFailure).kind)
        assertEquals(1, moveCalls)
        assertTrue(server("a").exists(identity))
        assertEquals(1, server("a").status("Archive").messageCount)
        val checkpoint = db.remoteMailDao().operations("a").single()
        assertEquals(1L, checkpoint.moveTargetUidNext)
        assertNotNull(checkpoint.moveSourceMessageId)

        db.close()
        db = open(file = true)
        repo = repository(db, store)
        assertEquals(RemoteMailRepository.FlushResult(1, 0), repo.flushOperations("a"))
        assertFalse(server("a").exists(identity))
        assertEquals(1, server("a").status("Archive").messageCount)
        assertEquals(1, moveCalls)
        assertEquals(OperationState.APPLIED, db.remoteMailDao().operations("a").single().state)
    }

    @Test
    fun retryIgnoresMatchingCopyThatPredatesMoveBoundary() = runBlocking {
        val db = open()
        ready(repository(db))
        server("a").append("INBOX", raw(1))
        server("a").append("Archive", raw(1))
        repository(db).syncMessages(inbox(), Instant.EPOCH)
        val source = rows(db, inbox()).single()
        var calls = 0
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun supports(capability: String): Boolean =
                    if (capability == "MOVE") false else server(id).supports(capability)

                override fun move(identity: MessageIdentity, targetMailbox: String) {
                    calls++
                    if (calls == 1) throw MailFailure(FailureKind.CONNECTION, "Before COPY")
                    server(id).move(identity, targetMailbox)
                }
            }
        }
        repo.move(source.id, CoreRoomMapper.folderId("a", "Archive"))
        assertEquals(FailureKind.CONNECTION,
            (runCatching { repo.flushOperations("a") }.exceptionOrNull() as MailFailure).kind)
        assertEquals(2L, db.remoteMailDao().operations("a").single().moveTargetUidNext)
        assertEquals(RemoteMailRepository.FlushResult(1, 0), repo.flushOperations("a"))
        assertEquals(2, calls)
        assertEquals(2, server("a").status("Archive").messageCount)
    }

    @Test
    fun moveRetriesAfterConnectionFailsBeforeTargetCheckpoint() = runBlocking {
        val db = open()
        ready(repository(db))
        server("a").append("INBOX", raw(1))
        repository(db).syncMessages(inbox(), Instant.EPOCH)
        val source = rows(db, inbox()).single()
        var statusCalls = 0
        val repo = repository(db) { id ->
            object : MailStore by ScriptedStore(server(id)) {
                override fun status(mailbox: String): MailboxStatus {
                    if (mailbox == "Archive" && ++statusCalls == 1)
                        throw MailFailure(FailureKind.CONNECTION, "Before checkpoint")
                    return server(id).status(mailbox)
                }
            }
        }
        repo.move(source.id, CoreRoomMapper.folderId("a", "Archive"))
        assertEquals(FailureKind.CONNECTION,
            (runCatching { repo.flushOperations("a") }.exceptionOrNull() as MailFailure).kind)
        assertNull(db.remoteMailDao().operations("a").single().moveTargetUidNext)
        assertEquals(RemoteMailRepository.FlushResult(1, 0), repo.flushOperations("a"))
        assertEquals(1, server("a").status("Archive").messageCount)
    }

    @Test
    fun uidValidityChangeDropsStaleRowsAndFailsOldIntent() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        server("a").createMailbox("Project")
        repeat(2) { server("a").append("Project", raw(it + 1)) }
        repo.refreshFolders("a")
        val project = CoreRoomMapper.folderId("a", "Project")
        repo.syncMessages(project, Instant.EPOCH)
        val old = rows(db, project)
        assertEquals(2, old.size)
        repo.markRead(old.first().id, true)

        server("a").deleteMailbox("Project")
        server("a").createMailbox("Project")
        server("a").append("Project", raw(3))
        repo.syncMessages(project, Instant.EPOCH)
        val fresh = rows(db, project)
        assertEquals(1, fresh.size)
        assertNotEquals(old.first().uidValidity, fresh.single().uidValidity)
        val queued = db.remoteMailDao().operations("a").single()
        assertNull("Queued intent keeps identity after cache reset", queued.messageId)
        assertEquals(RemoteMailRepository.FlushResult(0, 1), repo.flushOperations("a"))
        assertEquals(OperationState.FAILED, db.remoteMailDao().operations("a").single().state)
    }

    @Test
    fun accountsAreIsolatedAndRemovalCancelsInFlightWork() = runBlocking {
        val db = open()
        val entered = CountDownLatch(1)
        val repo =
            repository(db) { id ->
                if (id == "a")
                    object : MailStore by ScriptedStore(server(id)) {
                        override fun connect(server: Server, authorization: Authorization) {
                            this@RemoteMailRepositoryTest.server(id).connect(server, authorization)
                        }

                        override fun status(mailbox: String): MailboxStatus {
                            if (mailbox != "Archive") return server(id).status(mailbox)
                            entered.countDown()
                            check(cancelled.await(10, TimeUnit.SECONDS))
                            throw MailFailure(FailureKind.CANCELLED, "Cancelled")
                        }

                        override fun cancel() {
                            cancelled.countDown()
                        }

                        override fun close() = cancel()
                    }
                else ScriptedStore(server(id))
            }
        ready(repo, "a")
        ready(repo, "b")
        server("a").append("INBOX", raw(1, "Same"))
        server("b").append("INBOX", raw(1, "Same"))
        repo.syncMessages(inbox("a"), Instant.EPOCH)
        repo.syncMessages(inbox("b"), Instant.EPOCH)
        assertNotEquals(rows(db, inbox("a")).single().id, rows(db, inbox("b")).single().id)
        assertEquals("a", rows(db, inbox("a")).single().accountId)
        val queued = DurableOutbox(db, context, codec).enqueue(
            "a", OutgoingEmail("<cleanup@example.test>", EmailAddress("a@example.test"),
                listOf(EmailAddress("b@example.test")), subject = "Cleanup", body = EmailBody("body", null)),
        )
        assertTrue(java.io.File(queued.rawMessagePath).exists())

        val blocked = async(Dispatchers.Default) {
            runCatching { repo.syncMessages(CoreRoomMapper.folderId("a", "Archive"), Instant.EPOCH) }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repo.removeAccount("a")
        val outcome = withTimeout(10_000) { blocked.await() }
        assertEquals(FailureKind.CANCELLED, (outcome.exceptionOrNull() as MailFailure).kind)
        assertNull(db.remoteMailDao().account("a"))
        assertTrue(rows(db, inbox("a")).isEmpty())
        assertNull(credentials.authorization("a", ServerProtocol.IMAP))
        assertTrue(db.remoteMailDao().outbox("a").isEmpty())
        assertFalse(java.io.File(queued.rawMessagePath).exists())
        assertEquals(1, rows(db, inbox("b")).size)
        assertNotNull(credentials.authorization("b", ServerProtocol.IMAP))
    }

    @Test
    fun failedAccountDeleteRestoresCredentialsAndQueuedDelivery() = runBlocking {
        val db = open()
        val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials,
            { id -> ScriptedStore(server(id)) })
        val durable = DurableOutbox(db, context, codec)
        var submissions = 0
        val repo = RemoteMailRepository(db, sessions, credentials, durable,
            transportFactory = {
                object : RawMailSubmission {
                    override fun cancel() = Unit
                    override fun sendRaw(server: Server, authorization: Authorization,
                        raw: ByteArray, recipients: List<EmailAddress>) {
                        submissions++
                    }
                }
            })
        ready(repo)
        val queued = repo.queueOutgoing("a", OutgoingEmail(
            "<remove-retry@fixture.invalid>", EmailAddress("a@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = "Keep queued mail",
            body = EmailBody("Still sendable", null),
        ))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_account_delete BEFORE DELETE ON accounts " +
                "BEGIN SELECT RAISE(ABORT, 'Delete denied'); END")
        assertTrue(runCatching { repo.removeAccount("a") }.isFailure)
        assertNotNull(db.remoteMailDao().account("a"))
        assertEquals("incoming-secret", credentials.authorization("a", ServerProtocol.IMAP)?.secret)
        assertEquals("outgoing-secret", credentials.authorization("a", ServerProtocol.SMTP)?.secret)
        assertTrue(java.io.File(queued.rawMessagePath).exists())
        repo.flushOutgoing("a")
        assertEquals(DurableOutbox.State.SENT,
            db.remoteMailDao().outboxEntry(queued.id)?.state)
        assertEquals(1, submissions)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_account_delete")
        repo.removeAccount("a")
        assertNull(db.remoteMailDao().account("a"))
        assertNull(credentials.authorization("a", ServerProtocol.IMAP))
        assertFalse(java.io.File(queued.rawMessagePath).exists())
    }

    @Test
    fun startupRemovesOrphanOutboxFilesAfterInterruptedAccountCleanup() = runBlocking {
        var db = open(file = true)
        var repo = repository(db)
        ready(repo, "a")
        ready(repo, "b")
        fun outgoing(id: String) = OutgoingEmail("<$id@fixture.invalid>",
            EmailAddress("sender@fixture.invalid"),
            listOf(EmailAddress("to@fixture.invalid")), subject = id,
            body = EmailBody("body", null))
        val orphan = repo.queueOutgoing("a", outgoing("orphan"))
        val retained = repo.queueOutgoing("b", outgoing("retained"))
        val temporary = java.io.File(orphan.rawMessagePath).resolveSibling("interrupted.tmp")
        temporary.writeText("interrupted")
        assertTrue(java.io.File(orphan.rawMessagePath).exists())
        assertTrue(java.io.File(retained.rawMessagePath).exists())
        db.remoteMailDao().removeAccount("a")
        db.close()

        db = open(file = true)
        repo = repository(db)
        RoomMailRepository(db, context, DemoMail(context), credentials, repo).initialize()
        assertFalse(java.io.File(orphan.rawMessagePath).exists())
        assertFalse(temporary.exists())
        assertTrue(java.io.File(retained.rawMessagePath).exists())
        assertNotNull(db.remoteMailDao().outboxEntry(retained.id))
        repo.removeAccount("b")
        assertFalse(java.io.File(retained.rawMessagePath).exists())
    }

    @Test
    fun coroutineCancellationInterruptsBlockingCall() = runBlocking {
        val db = open()
        val entered = CountDownLatch(1)
        val repo =
            repository(db) { id ->
                object : MailStore by ScriptedStore(server(id)) {
                    override fun connect(server: Server, authorization: Authorization) {
                        this@RemoteMailRepositoryTest.server(id).connect(server, authorization)
                    }

                    override fun mailboxes(): List<Mailbox> {
                        entered.countDown()
                        check(cancelled.await(10, TimeUnit.SECONDS)) { "Store was not cancelled" }
                        throw MailFailure(FailureKind.CANCELLED, "Cancelled")
                    }

                    override fun cancel() {
                        cancelled.countDown()
                    }

                    override fun close() = cancel()
                }
            }
        repo.addAccount(account("a"), Authorization("incoming-secret"), Authorization("outgoing-secret"))
        val job = async(Dispatchers.Default) { runCatching { repo.refreshFolders("a") } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("Cancellation reached the store", cancelled.await(10, TimeUnit.SECONDS))
        assertTrue(db.remoteMailDao().folders("a").isEmpty())
    }

    @Test
    fun visibleRefreshTimeoutClosesStalledStoreAndNextAttemptReconnects() = runBlocking {
        val db = open()
        ready(repository(db))
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var created = 0
        val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials, factory = { id ->
            created++
            val attempt = created
            object : MailStore by ScriptedStore(server(id)) {
                override fun status(mailbox: String): MailboxStatus {
                    if (attempt == 1) {
                        entered.countDown()
                        check(released.await(5, TimeUnit.SECONDS)) { "Timed out store was not closed" }
                        throw MailFailure(FailureKind.CANCELLED, "Interrupted")
                    }
                    return server(id).status(mailbox)
                }

                override fun cancel() { released.countDown() }
                override fun close() { released.countDown() }
            }
        })
        val repo = RemoteMailRepository(db, sessions, credentials,
            DurableOutbox(db, context, codec), refreshTimeoutMillis = 500)
        val failure = runCatching {
            repo.refreshVisibleFolder(inbox(), Instant.EPOCH)
        }.exceptionOrNull() as MailFailure
        assertTrue(failure.kind == FailureKind.CONNECTION || failure.kind == FailureKind.CANCELLED)
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(released.await(1, TimeUnit.SECONDS))
        assertEquals(0, repo.refreshVisibleFolder(inbox(), Instant.EPOCH))
        assertEquals(2, created)
        sessions.closeAll()
    }

    @Test
    fun cancelledIdleReconnectsAndUsesNoopFallback() = runBlocking {
        val db = open()
        ready(repository(db))
        val idling = CountDownLatch(1)
        val cancelledIdle = CountDownLatch(1)
        var created = 0
        val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials, factory = { id ->
            created++
            val attempt = created
            object : MailStore by ScriptedStore(server(id)) {
                override fun supports(capability: String): Boolean =
                    capability == "IDLE" && attempt == 1

                override fun awaitChange(mailbox: String) {
                    idling.countDown()
                    check(cancelledIdle.await(5, TimeUnit.SECONDS)) { "IDLE was not interrupted" }
                    throw MailFailure(FailureKind.CANCELLED, "IDLE interrupted")
                }

                override fun poll(mailbox: String): MailboxStatus = server(id).poll(mailbox)
                override fun cancel() { cancelledIdle.countDown() }
                override fun close() { cancelledIdle.countDown() }
            }
        })
        val waiting = async(Dispatchers.Default) {
            runCatching { sessions.withStore("a") { it.awaitChange("INBOX") } }
        }
        assertTrue(idling.await(2, TimeUnit.SECONDS))
        waiting.cancel()
        withTimeout(2_000) { waiting.join() }
        val status = sessions.withStore("a") { store ->
            if (store.supports("IDLE")) store.awaitChange("INBOX")
            store.poll("INBOX")
        }
        assertEquals(server("a").status("INBOX").uidValidity, status.uidValidity)
        assertEquals(2, created)
        sessions.closeAll()
    }

    @Test
    fun missingCredentialsFailBeforeNetworkWithoutChangingRows() = runBlocking {
        val db = open()
        val repo = repository(db)
        ready(repo)
        val before = db.remoteMailDao().folders("a")
        credentials.removeAccount("a")
        connects = 0
        val failure =
            runCatching { repository(db).refreshFolders("a") }.exceptionOrNull() as MailFailure
        assertEquals(FailureKind.AUTHENTICATION, failure.kind)
        assertEquals(0, connects)
        assertEquals(before, db.remoteMailDao().folders("a"))
    }

    @Test
    fun realAngusSessionPersistsControlledServerFolderAndCursor() = runBlocking {
        val original = SSLContext.getDefault()
        val tls = testTlsContext(
            InstrumentationRegistry.getInstrumentation().context.assets.open("localhost.p12")
        )
        SSLContext.setDefault(tls)
        try {
            val transcript = ImapTranscript()
            LoopbackServer(tls, transcript::serve).use { server ->
                val db = open()
                val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials,
                    factory = { AngusImapClient() })
                val repo = RemoteMailRepository(db, sessions, credentials, DurableOutbox(db, context, codec))
                val configured = account("wire").copy(
                    incomingServer = Server("localhost", server.port, ServerProtocol.IMAP, username = "user")
                )
                repo.addAccount(configured, Authorization("password"), Authorization("password"))
                repo.refreshFolders("wire")
                assertEquals(listOf("INBOX"), db.remoteMailDao().folders("wire").map { it.remotePath })
                assertEquals(0, repo.syncMessages(inbox("wire"), Instant.EPOCH))
                assertEquals(77L, db.remoteMailDao().folder(inbox("wire"))!!.uidValidity)
                assertNull(db.remoteMailDao().cursor(inbox("wire"))!!.beforeUid)
                sessions.closeAll()
                server.awaitCompletion()
                assertTrue(transcript.commands.contains("LIST"))
                assertTrue(transcript.commands.contains("EXAMINE") || transcript.commands.contains("SELECT"))
            }
        } finally {
            SSLContext.setDefault(original)
        }
    }

    @Test
    fun realAngusSessionUsesQresyncAfterDurableBaseline() = runBlocking {
        val original = SSLContext.getDefault()
        val tls = testTlsContext(
            InstrumentationRegistry.getInstrumentation().context.assets.open("localhost.p12")
        )
        SSLContext.setDefault(tls)
        try {
            val transcript = ImapTranscript(additionalCapabilities = "ENABLE CONDSTORE QRESYNC")
            LoopbackServer(tls, transcript::serve).use { server ->
                val db = open()
                val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials,
                    factory = { AngusImapClient() })
                val repo = RemoteMailRepository(db, sessions, credentials,
                    DurableOutbox(db, context, codec))
                val configured = account("wire").copy(
                    incomingServer = Server("localhost", server.port, ServerProtocol.IMAP, username = "user")
                )
                repo.addAccount(configured, Authorization("password"), Authorization("password"))
                repo.refreshFolders("wire")
                repo.refreshVisibleFolder(inbox("wire"), Instant.EPOCH)
                assertEquals(10L, db.remoteMailDao().cursor(inbox("wire"))!!.highestModSeq)
                repo.refreshVisibleFolder(inbox("wire"), Instant.EPOCH)
                assertTrue(transcript.sawQresync)
                assertEquals(10L, db.remoteMailDao().cursor(inbox("wire"))!!.highestModSeq)
                sessions.closeAll()
                server.awaitCompletion()
            }
        } finally {
            SSLContext.setDefault(original)
        }
    }
}
