// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.data.repository.RemoteMailRepository.OperationState
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.sync.AccountSessions
import org.foxred.kage.ui.emaildisplay.SafeMessageHtml
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

    private fun inbox(id: String = "a") = CoreRoomMapper.folderId(id, "INBOX")

    private suspend fun rows(db: MailDatabase, folderId: String) =
        db.remoteMailDao().messageIds(folderId).map { db.remoteMailDao().message(it)!! }

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
    fun automaticAttachmentPolicyWaitsUntilOnline() = runBlocking {
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
        assertTrue(db.mailDao().attachment(part.id)!!.cached)
        assertEquals("bytes 8", File(context.filesDir, "attachments/${part.id}").readText())
        File(context.filesDir, "attachments/${part.id}").delete()
        Unit
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
        try {
            room.cacheAttachment(part.id)
            assertTrue(remote.inlineImageAttachmentIds(row.id).isEmpty())
            val html = db.remoteMailDao().message(row.id)!!.html.orEmpty()
            val safe = SafeMessageHtml.render(context, html,
                listOf(db.mailDao().attachment(part.id)!!.domain()))
            assertTrue(safe.contains("data:image/png;base64,AQIDBA=="))
            assertFalse(safe.contains("cid:logo@fixture"))
        } finally {
            File(context.filesDir, "attachments/${part.id}").delete()
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
    fun moveUsesPlaceholderUntilTargetCopyReplacesIt() = runBlocking {
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
        assertNull(db.remoteMailDao().message(keep.id))
        repo.syncMessages(inbox(), Instant.EPOCH, full = true)
        assertEquals(listOf("Rejected"), rows(db, inbox()).map { it.subject })
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
        val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials) { id ->
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
        }
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
        val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials) { id ->
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
        }
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
                val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials) { AngusImapClient() }
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
                val sessions = AccountSessions(RemoteMailRepository.incomingServer(db), credentials) { AngusImapClient() }
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
