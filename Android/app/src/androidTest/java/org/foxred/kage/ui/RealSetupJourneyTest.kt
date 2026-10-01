// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.*
import java.time.Instant
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.foxred.kage.core.demo.DemoMailStore
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.local.CoreRoomMapper
import org.foxred.kage.data.local.MessageEntity
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.security.GoogleAuthorizationGateway
import org.foxred.kage.data.security.GoogleAuthorizationStep
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.data.setup.RealAccountSetup
import org.foxred.kage.data.sync.AccountSessions
import org.foxred.kage.ui.navigation.KageApp
import org.foxred.kage.ui.theme.KageTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

class RealSetupJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val credentials by lazy { AndroidCredentialStore(context, "real-ui-test") }
    private val google = object : GoogleAuthorizationGateway {
        override val configured = true
        override val configuration = OAuthConfiguration("client-id",
            URI("https://accounts.google.com/o/oauth2/v2/auth"),
            URI("https://oauth2.googleapis.com/token"),
            URI("org.foxred.kage:/oauth/google"), listOf("https://mail.google.com/"))
        var nextStep: GoogleAuthorizationStep? = null
        var completionCalls = 0
        val clearedTokens = mutableListOf<String>()
        override suspend fun authorize(email: String?, selectAccount: Boolean) =
            checkNotNull(nextStep) { "No Google test grant was configured" }
        override fun complete(intent: Intent): Authorization {
            completionCalls++
            error("A canceled consent screen must not complete")
        }
        override suspend fun clearCachedToken(token: String) {
            clearedTokens += token
        }
    }
    private lateinit var db: MailDatabase
    private lateinit var vm: MailViewModel
    private lateinit var remote: RemoteMailRepository
    private val server = DemoMailStore()
    private var rejectSmtp = true
    private var failPage = true
    @Volatile private var failPageAccountId: String? = null
    private var failBody = true
    @Volatile private var holdBody = false
    private val bodyEntered = CountDownLatch(1)
    private val releaseBody = CountDownLatch(1)
    @Volatile private var holdPages = false
    private val pageEntered = CountDownLatch(1)
    private val resumePages = CountDownLatch(1)
    private val pageCalls = AtomicInteger()
    private val statusCalls = AtomicInteger()
    private val draftAppendEntered = CountDownLatch(1)
    @Volatile private var holdHistoryPages = false
    private val historyPageCalls = AtomicInteger()
    private val firstHistoryPageEntered = CountDownLatch(1)
    private val secondHistoryPageEntered = CountDownLatch(1)
    private val releaseFirstHistoryPage = CountDownLatch(1)
    private val releaseSecondHistoryPage = CountDownLatch(1)
    @Volatile private var holdAutomaticAttachment = false
    private val automaticAttachmentEntered = CountDownLatch(1)
    private val releaseAutomaticAttachment = CountDownLatch(1)

    /** Real mail is observed through bounded pages and per-message detail. */
    private suspend fun cachedRealMessages(): List<org.foxred.kage.domain.model.Message> {
        val realIds = db.mailDao().realAccountIds().toSet()
        val folderIds = vm.mailbox.value.folders.filter { it.accountId in realIds }.map { it.id }
        val messages = mutableListOf<org.foxred.kage.domain.model.Message>()
        var cursor: org.foxred.kage.domain.model.MessagePageCursor? = null
        do {
            val page = vm.repository.messagePage(folderIds, cursor, oldestFirst = false, limit = 100)
            page.items.mapNotNullTo(messages) { vm.repository.observeMessage(it.id).first() }
            cursor = page.next
        } while (cursor != null)
        return messages
    }

    @Before fun start() {
        credentials.clear()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        server.connect(Server("fixture.invalid", 993, ServerProtocol.IMAP, username = "real"), Authorization.none())
        server.createMailbox("Orphan/Deep/Leaf")
        server.createMailbox("Empty")
        server.close()
        remote = RemoteMailRepository(db,
            AccountSessions(RemoteMailRepository.incomingServer(db), credentials, factory = { accountId ->
                object : MailStore by server {
                    override fun status(mailbox: String): MailboxStatus {
                        statusCalls.incrementAndGet()
                        return server.status(mailbox)
                    }
                    override fun append(mailbox: String, raw: ByteArray, read: Boolean,
                        draft: Boolean): MessageIdentity? {
                        if (draft) draftAppendEntered.countDown()
                        return server.append(mailbox, raw, read, draft)
                    }
                    override fun messagePage(
                        mailbox: String, since: Instant, cursor: MessageCursor?, limit: Int,
                    ): MessagePage {
                        pageCalls.incrementAndGet()
                        if (holdHistoryPages && since == Instant.EPOCH) {
                            when (historyPageCalls.incrementAndGet()) {
                                1 -> {
                                    firstHistoryPageEntered.countDown()
                                    check(releaseFirstHistoryPage.await(15, TimeUnit.SECONDS))
                                }
                                2 -> {
                                    secondHistoryPageEntered.countDown()
                                    check(releaseSecondHistoryPage.await(5, TimeUnit.SECONDS))
                                }
                            }
                        }
                        if (holdPages) {
                            pageEntered.countDown()
                            check(resumePages.await(5, TimeUnit.SECONDS))
                        }
                        if (failPage || failPageAccountId == accountId)
                            throw MailFailure(FailureKind.CONNECTION, "Fixture connection lost")
                        return server.messagePage(mailbox, since, cursor, limit)
                    }
                    override fun message(identity: MessageIdentity): Email {
                        if (holdBody) {
                            bodyEntered.countDown()
                            check(releaseBody.await(15, TimeUnit.SECONDS))
                        }
                        if (failBody) throw MailFailure(FailureKind.CONNECTION, "Fixture body fetch lost")
                        return server.message(identity)
                    }
                    override fun downloadAttachment(identity: MessageIdentity, partId: String,
                        output: java.io.OutputStream): Long {
                        if (holdAutomaticAttachment) {
                            automaticAttachmentEntered.countDown()
                            check(releaseAutomaticAttachment.await(15, TimeUnit.SECONDS))
                        }
                        return server.downloadAttachment(identity, partId, output)
                    }
                    override fun cancel() {
                        resumePages.countDown()
                        releaseFirstHistoryPage.countDown()
                        releaseAutomaticAttachment.countDown()
                        releaseBody.countDown()
                    }
                    override fun close() = Unit
                }
            }),
            credentials, DurableOutbox(db, context, AngusMimeCodec()))
        val setup = RealAccountSetup(remote, { server }, { _, _ ->
            if (rejectSmtp) throw MailFailure(FailureKind.AUTHENTICATION, "Wrong SMTP app password")
        })
        vm = MailViewModel(RoomMailRepository(db, context, DemoMail(context), credentials, remote),
            setup, remote, google)
        compose.setContent { KageTheme { KageApp(vm) } }
        compose.waitUntil(10000) { vm.ready.value }
    }

    @Test fun unifiedRefreshHydratesEachRealInboxAndSuppressesDuplicateCall() {
        failPage = false
        failBody = false
        kotlinx.coroutines.runBlocking {
            for (id in listOf("first", "second")) addRealAccount(id)
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: Unified fixture\r\nMessage-ID: <unified@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(org.foxred.kage.domain.model.Preferences(
                selectedFolder = "unified", unified = true, started = true))
        }
        compose.waitUntil(5000) {
            vm.mailbox.value.folders.count { it.role == "inbox" &&
                it.accountId in setOf("first", "second") } == 2
        }
        holdPages = true
        vm.refreshFolder("unified")
        assertTrue(pageEntered.await(5, TimeUnit.SECONDS))
        vm.refreshFolder("unified")
        resumePages.countDown()
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().count { it.accountId in setOf("first", "second") &&
                    it.bodyDownloaded } == 2
            }
        }
        assertEquals(2, pageCalls.get())
    }

    @Test fun appOpenCachesRecentBodiesFromBothAccountsWithOneInboxSelected() {
        failPage = false
        failBody = false
        kotlinx.coroutines.runBlocking {
            addRealAccount("first")
            addRealAccount("second")
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: Startup fixture\r\nMessage-ID: <startup@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(org.foxred.kage.domain.model.Preferences(
                selectedFolder = CoreRoomMapper.folderId("first", "INBOX"), started = true))
        }
        vm.viewModelScope.cancel()
        vm = MailViewModel(RoomMailRepository(db, context, DemoMail(context), credentials, remote),
            remote = remote)
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().count { it.accountId in setOf("first", "second") &&
                    it.subject == "Startup fixture" && it.bodyDownloaded } == 2
            }
        }
        assertEquals(CoreRoomMapper.folderId("first", "INBOX"),
            vm.mailbox.value.preferences.selectedFolder)
        assertEquals(2, pageCalls.get())
    }

    @Test fun stoppedAppDoesNotStartHistoryUntilForegroundResume() {
        failPage = false
        vm.onAppStopped()
        kotlinx.coroutines.runBlocking { addRealAccount("history-foreground") }
        val folderId = CoreRoomMapper.folderId("history-foreground", "INBOX")
        compose.waitForIdle()
        val before = pageCalls.get()

        vm.downloadHistory(folderId)
        compose.waitForIdle()
        assertEquals(before, pageCalls.get())
        assertFalse(vm.historyDownload.value.running)
        assertEquals("Return to Kage to download mail history",
            vm.historyDownload.value.error)

        vm.onAppResumed()
        vm.downloadHistory(folderId)
        compose.waitUntil(10000) {
            vm.historyDownload.value.progress?.scanComplete == true &&
                !vm.historyDownload.value.running
        }
        assertTrue(pageCalls.get() > before)
    }

    @Test fun enablingAutomaticAttachmentsDoesNotWaitForRemoteStream() {
        failPage = false
        failBody = false
        val folderId = CoreRoomMapper.folderId("auto-ui", "INBOX")
        kotlinx.coroutines.runBlocking {
            addRealAccount("auto-ui")
            server.append("INBOX", """
                From: Sender <sender@example.test>
                To: Reader <auto-ui@example.test>
                Subject: Automatic attachment fixture
                Message-ID: <auto-ui@example.test>
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="auto-boundary"

                --auto-boundary
                Content-Type: text/plain; charset=UTF-8

                Fixture body
                --auto-boundary
                Content-Type: application/octet-stream
                Content-Disposition: attachment; filename="auto.txt"
                Content-Transfer-Encoding: base64

                YXV0bw==
                --auto-boundary--
            """.trimIndent().replace("\n", "\r\n").toByteArray())
            vm.repository.updatePreferences(org.foxred.kage.domain.model.Preferences(
                selectedFolder = folderId, started = true))
        }
        vm.refreshFolder(folderId)
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any {
                    it.accountId == "auto-ui" && it.subject == "Automatic attachment fixture" &&
                        it.bodyDownloaded && it.attachments.isNotEmpty()
                }
            }
        }
        val partId = kotlinx.coroutines.runBlocking {
            val message = cachedRealMessages().single { it.subject == "Automatic attachment fixture" }
            db.remoteMailDao().attachments(message.id).single().id
        }
        val file = java.io.File(context.filesDir, "attachments/$partId")
        try {
            holdAutomaticAttachment = true
            kotlinx.coroutines.runBlocking {
                vm.repository.updatePreferences(vm.mailbox.value.preferences.copy(
                    automaticAttachments = true))
            }
            assertTrue(automaticAttachmentEntered.await(5, TimeUnit.SECONDS))
            assertTrue(vm.ready.value)
            assertFalse(kotlinx.coroutines.runBlocking { db.mailDao().attachment(partId)!!.cached })
            val beforeRefresh = statusCalls.get()
            holdAutomaticAttachment = false
            vm.refreshFolder(folderId)
            compose.waitUntil(5000) { statusCalls.get() > beforeRefresh }
            compose.waitUntil(10000) {
                kotlinx.coroutines.runBlocking { db.mailDao().attachment(partId)!!.cached }
            }
            assertEquals("auto", file.readText())
        } finally {
            releaseAutomaticAttachment.countDown()
            file.delete()
        }
    }

    @Test fun pausingHistoryResumesDeferredAutomaticAttachmentCaching() {
        failPage = false
        failBody = false
        val folderId = CoreRoomMapper.folderId("history-auto", "INBOX")
        kotlinx.coroutines.runBlocking {
            addRealAccount("history-auto")
            server.append("INBOX", """
                From: Sender <sender@example.test>
                To: Reader <history-auto@example.test>
                Subject: Paused history attachment
                Message-ID: <history-auto@example.test>
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="history-auto-boundary"

                --history-auto-boundary
                Content-Type: text/plain; charset=UTF-8

                Fixture body
                --history-auto-boundary
                Content-Type: application/octet-stream
                Content-Disposition: attachment; filename="history-auto.txt"
                Content-Transfer-Encoding: base64

                YXV0bw==
                --history-auto-boundary--
            """.trimIndent().replace("\n", "\r\n").toByteArray())
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = folderId, started = true))
        }
        vm.refreshFolder(folderId)
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.subject == "Paused history attachment" &&
                    it.bodyDownloaded && it.attachments.isNotEmpty() }
            }
        }
        val partId = kotlinx.coroutines.runBlocking {
            val message = cachedRealMessages().single { it.subject == "Paused history attachment" }
            db.remoteMailDao().attachments(message.id).single().id
        }
        val file = java.io.File(context.filesDir, "attachments/$partId")
        try {
            holdHistoryPages = true
            vm.downloadHistory(folderId)
            assertTrue(firstHistoryPageEntered.await(5, TimeUnit.SECONDS))
            kotlinx.coroutines.runBlocking {
                vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                    automaticAttachments = true))
            }
            compose.waitUntil(5000) {
                vm.mailbox.value.preferences.automaticAttachments &&
                    vm.historyDownload.value.running
            }
            assertFalse(kotlinx.coroutines.runBlocking { db.mailDao().attachment(partId)!!.cached })

            vm.pauseHistory()
            compose.waitUntil(10000) {
                kotlinx.coroutines.runBlocking { db.mailDao().attachment(partId)!!.cached }
            }
            assertEquals("auto", file.readText())
        } finally {
            releaseFirstHistoryPage.countDown()
            file.delete()
        }
    }

    @Test fun stoppedAppDefersOptionalAttachmentCachingUntilResume() {
        failPage = false
        failBody = false
        val folderId = CoreRoomMapper.folderId("background-auto", "INBOX")
        kotlinx.coroutines.runBlocking {
            addRealAccount("background-auto")
            server.append("INBOX", """
                From: Sender <sender@example.test>
                To: Reader <background-auto@example.test>
                Subject: Background attachment fixture
                Message-ID: <background-auto@example.test>
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="background-auto-boundary"

                --background-auto-boundary
                Content-Type: text/plain; charset=UTF-8

                Fixture body
                --background-auto-boundary
                Content-Type: application/octet-stream
                Content-Disposition: attachment; filename="background-auto.txt"
                Content-Transfer-Encoding: base64

                YXV0bw==
                --background-auto-boundary--
            """.trimIndent().replace("\n", "\r\n").toByteArray())
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = folderId, started = true))
        }
        vm.refreshFolder(folderId)
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.subject == "Background attachment fixture" &&
                    it.bodyDownloaded && it.attachments.isNotEmpty() }
            }
        }
        val partId = kotlinx.coroutines.runBlocking {
            val message = cachedRealMessages().single { it.subject == "Background attachment fixture" }
            db.remoteMailDao().attachments(message.id).single().id
        }
        val file = java.io.File(context.filesDir, "attachments/$partId")
        try {
            holdAutomaticAttachment = true
            vm.onAppStopped()
            kotlinx.coroutines.runBlocking {
                vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                    automaticAttachments = true))
            }
            compose.waitUntil(5000) { vm.mailbox.value.preferences.automaticAttachments }
            assertFalse(automaticAttachmentEntered.await(1, TimeUnit.SECONDS))
            assertFalse(kotlinx.coroutines.runBlocking { db.mailDao().attachment(partId)!!.cached })

            holdPages = true
            vm.onAppResumed()
            vm.refreshRecentInboxes()
            assertTrue(pageEntered.await(5, TimeUnit.SECONDS))
            assertFalse(automaticAttachmentEntered.await(1, TimeUnit.SECONDS))
            resumePages.countDown()
            assertTrue(automaticAttachmentEntered.await(5, TimeUnit.SECONDS))
            releaseAutomaticAttachment.countDown()
            compose.waitUntil(10000) {
                kotlinx.coroutines.runBlocking { db.mailDao().attachment(partId)!!.cached }
            }
            assertEquals("auto", file.readText())
        } finally {
            resumePages.countDown()
            releaseAutomaticAttachment.countDown()
            file.delete()
        }
    }

    @Test fun addingAccountWhileOnlineRefreshesItsInboxWithoutSwitchingFolders() {
        failPage = false
        failBody = false
        val selected = CoreRoomMapper.folderId("first", "INBOX")
        kotlinx.coroutines.runBlocking {
            addRealAccount("first")
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: Added account fixture\r\nMessage-ID: <added@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = selected, started = true))
            vm.refreshFolder(selected)
        }
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.accountId == "first" && it.bodyDownloaded }
            }
        }

        kotlinx.coroutines.runBlocking { addRealAccount("second") }
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.accountId == "second" &&
                    it.subject == "Added account fixture" && it.bodyDownloaded }
            }
        }
        assertEquals(selected, vm.mailbox.value.preferences.selectedFolder)
    }

    @Test fun failedFirstAccountDoesNotPreventTheSecondRecentInboxFromLoading() {
        failPage = false
        failBody = false
        failPageAccountId = "first"
        kotlinx.coroutines.runBlocking {
            addRealAccount("first")
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: Two account fixture\r\n" +
                    "Message-ID: <two-account@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.error.value = null
            addRealAccount("second")
        }
        compose.waitUntil(10000) {
            vm.error.value != null && kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.accountId == "second" &&
                    it.subject == "Two account fixture" && it.bodyDownloaded }
            }
        }
        assertFalse(kotlinx.coroutines.runBlocking {
            cachedRealMessages().any { it.accountId == "first" &&
                it.subject == "Two account fixture" }
        })
    }

    @Test fun stoppingAppCancelsForegroundRefreshAndResumeCanRetry() {
        val folderId = CoreRoomMapper.folderId("stopped-refresh", "INBOX")
        kotlinx.coroutines.runBlocking {
            addRealAccount("stopped-refresh")
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: Stopped refresh fixture\r\n" +
                    "Message-ID: <stopped-refresh@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = folderId, started = true))
        }
        compose.waitUntil(5000) { vm.mailbox.value.folders.any { it.id == folderId } }
        failPage = false
        failBody = false
        holdPages = true
        vm.refreshFolder(folderId)
        assertTrue(pageEntered.await(5, TimeUnit.SECONDS))

        vm.onAppStopped()
        assertTrue(resumePages.await(5, TimeUnit.SECONDS))
        assertFalse(vm.folderSync.value.loading)
        val stoppedCalls = pageCalls.get()
        vm.refreshRecentInboxes()
        vm.refreshFolder(folderId)
        compose.waitForIdle()
        assertEquals(stoppedCalls, pageCalls.get())

        holdPages = false
        vm.onAppResumed()
        vm.refreshFolder(folderId)
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.subject == "Stopped refresh fixture" &&
                    it.bodyDownloaded }
            }
        }
    }

    @Test fun stoppingAppCancelsManualAttachmentAndResumeCanRetry() {
        val accountId = "stopped-part"
        val folderId = CoreRoomMapper.folderId(accountId, "INBOX")
        failPage = false
        failBody = false
        kotlinx.coroutines.runBlocking {
            addRealAccount(accountId)
            server.append("INBOX", AngusMimeCodec().encode(OutgoingEmail(
                "<stopped-part@example.test>", EmailAddress("sender@example.test"),
                listOf(EmailAddress("reader@example.test")), subject = "Stopped part fixture",
                body = EmailBody("Body", null), attachments = listOf(
                    OutgoingAttachment("part.txt", "text/plain", "manual".toByteArray())))))
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = folderId, started = true))
        }
        vm.refreshFolder(folderId)
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.subject == "Stopped part fixture" &&
                    it.bodyDownloaded && it.attachments.isNotEmpty() }
            }
        }
        val partId = kotlinx.coroutines.runBlocking {
            val message = cachedRealMessages().single { it.subject == "Stopped part fixture" }
            db.remoteMailDao().attachments(message.id).single().id
        }
        val readyPath = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val file = java.io.File(context.filesDir, "attachments/$partId")
        try {
            holdAutomaticAttachment = true
            vm.downloadAttachment(partId) { readyPath.set(it) }
            assertTrue(automaticAttachmentEntered.await(5, TimeUnit.SECONDS))
            vm.onAppStopped()
            assertTrue(releaseAutomaticAttachment.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5000) { vm.attachmentTransfers.value[partId]?.loading != true }
            assertNull(readyPath.get())
            assertFalse(kotlinx.coroutines.runBlocking { db.mailDao().attachment(partId)!!.cached })

            holdAutomaticAttachment = false
            vm.onAppResumed()
            vm.downloadAttachment(partId) { readyPath.set(it) }
            compose.waitUntil(10000) { readyPath.get() != null }
            assertEquals("manual", file.readText())
        } finally {
            releaseAutomaticAttachment.countDown()
            file.delete()
        }
    }

    @Test fun stoppingAppCancelsReaderBodyAndResumeCanRetry() {
        val accountId = "stopped-body"
        val folderId = CoreRoomMapper.folderId(accountId, "INBOX")
        failPage = false
        kotlinx.coroutines.runBlocking {
            addRealAccount(accountId)
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: Stopped body fixture\r\n" +
                    "Message-ID: <stopped-body@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = folderId, started = true))
        }
        vm.refreshFolder(folderId)
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().any { it.subject == "Stopped body fixture" &&
                    !it.bodyDownloaded }
            }
        }
        compose.waitUntil(5000) {
            vm.folderSync.value.folderId == folderId && !vm.folderSync.value.loading
        }
        val messageId = kotlinx.coroutines.runBlocking {
            cachedRealMessages().single { it.subject == "Stopped body fixture" }.id
        }
        try {
            holdBody = true
            failBody = false
            vm.viewModelScope.launch { vm.loadBody(messageId) }
            assertTrue(bodyEntered.await(5, TimeUnit.SECONDS))
            vm.onAppStopped()
            assertTrue(releaseBody.await(5, TimeUnit.SECONDS))
            assertFalse(vm.messageLoad.value.loading)
            assertFalse(kotlinx.coroutines.runBlocking {
                db.remoteMailDao().message(messageId)!!.bodyDownloaded
            })

            holdBody = false
            vm.onAppResumed()
            vm.viewModelScope.launch { vm.loadBody(messageId) }
            compose.waitUntil(10000) {
                kotlinx.coroutines.runBlocking {
                    db.remoteMailDao().message(messageId)!!.bodyDownloaded
                }
            }
        } finally {
            releaseBody.countDown()
        }
    }

    @Test fun stoppedMailboxChangesDeferAutomaticDraftSyncUntilResume() {
        val accountId = "stopped-draft"
        val draftId = "stopped-draft-message"
        vm.onAppStopped()
        kotlinx.coroutines.runBlocking {
            addRealAccount(accountId)
            vm.repository.saveDraft(org.foxred.kage.domain.model.Message(
                draftId, accountId, "", accountId, "$accountId@example.test",
                "reader@example.test", subject = "Stopped draft fixture", body = "Body",
                receivedAt = Instant.now().toString(), draft = true))
        }
        compose.waitUntil(5000) { vm.mailbox.value.accounts.any { it.id == accountId } }
        assertFalse(draftAppendEntered.await(500, TimeUnit.MILLISECONDS))
        assertTrue(kotlinx.coroutines.runBlocking {
            JSONObject(db.remoteMailDao().message(draftId)!!.envelopeJson)
                .optBoolean("localDraftDirty")
        })

        vm.onAppResumed()
        assertTrue(draftAppendEntered.await(5, TimeUnit.SECONDS))
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                !JSONObject(db.remoteMailDao().message(draftId)!!.envelopeJson)
                    .optBoolean("localDraftDirty")
            }
        }
    }

    @Test fun acceptedSendOffersAnExplicitSentCopyForOtherSmtpServers() {
        failPage = false
        failBody = false
        compose.onNodeWithText("Explore the demo inbox").performClick()
        val queued = kotlinx.coroutines.runBlocking {
            addRealAccount("sent-ui")
            val durable = DurableOutbox(db, context, AngusMimeCodec())
            val entry = durable.enqueue("sent-ui", OutgoingEmail(
                "<sent-copy-ui@example.test>", EmailAddress("sent-ui@example.test"),
                listOf(EmailAddress("friend@example.test")), subject = "Sent copy UI",
                body = EmailBody("Saved once", null),
            ))
            durable.claimNext("sent-ui")
            durable.finish(entry.id, DurableOutbox.State.SENT)
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = checkNotNull(remote.inboxFolder("sent-ui")), started = true))
            entry
        }
        compose.waitUntil(10000) {
            vm.outbox.value.any { it.id == queued.id } &&
                vm.mailbox.value.preferences.selectedFolder == CoreRoomMapper.folderId("sent-ui", "INBOX")
        }
        compose.onNodeWithContentDescription("Inbox options").performClick()
        compose.onNodeWithText("Outbox (1)").performClick()
        compose.onNodeWithText("Save copy to Sent").performClick()
        compose.onNodeWithText("This action does not resend the message.", substring = true)
            .assertExists()
        compose.onNodeWithText("Save copy", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) {
            server.status("Sent").messageCount == 1 &&
                vm.outbox.value.any { it.id == queued.id &&
                    it.sentCopyStatus == org.foxred.kage.domain.model.SentCopyStatus.CONFIRMED }
        }
    }

    @Test fun realComposeQueuesMailWhileOfflineWithoutSubmittingSmtp() {
        failPage = false
        failBody = false
        compose.onNodeWithText("Explore the demo inbox").performClick()
        kotlinx.coroutines.runBlocking {
            addRealAccount("compose-ui")
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = checkNotNull(remote.inboxFolder("compose-ui")),
                offline = true, started = true))
        }
        compose.waitUntil(10000) {
            vm.mailbox.value.preferences.offline &&
                vm.mailbox.value.preferences.selectedFolder ==
                    CoreRoomMapper.folderId("compose-ui", "INBOX")
        }
        compose.onNodeWithContentDescription("Compose a message").performClick()
        compose.onNodeWithText("To").performTextInput("Friend <friend@example.test>")
        compose.onNodeWithText("Subject").performTextInput("Offline real Outbox")
        androidx.test.espresso.Espresso.onView(
            androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Message")
        ).perform(androidx.test.espresso.action.ViewActions.replaceText("Queued body"))
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(10000) {
            vm.outbox.value.any { it.accountId == "compose-ui" &&
                it.status == org.foxred.kage.domain.model.OutboxStatus.QUEUED }
        }
        val entry = kotlinx.coroutines.runBlocking {
            db.remoteMailDao().outbox("compose-ui").single()
        }
        assertEquals(DurableOutbox.State.PENDING, entry.state)
        val encoded = kotlinx.coroutines.runBlocking {
            val codec = AngusMimeCodec()
            codec.decode(DurableOutbox(db, context, codec).raw(entry))
        }
        assertEquals("friend@example.test", encoded.to.single().address)
        assertEquals("Friend", encoded.to.single().name)
        assertEquals("Queued body", encoded.body.text)
        assertEquals("Friend <friend@example.test>",
            kotlinx.coroutines.runBlocking { db.remoteMailDao().message(entry.draftId!!) }?.to)
    }

    @Test fun uncertainDraftExplainsTheServerRiskBeforeManualRetry() {
        failPage = false
        failBody = false
        compose.onNodeWithText("Explore the demo inbox").performClick()
        kotlinx.coroutines.runBlocking {
            addRealAccount("draft-ui")
            val draft = org.foxred.kage.domain.model.Message(
                "draft-ui-uncertain", "draft-ui", "", "draft-ui", "draft-ui@example.test",
                "friend@example.test", subject = "Needs draft review", body = "Saved locally",
                receivedAt = Instant.now().toString(), draft = true,
            )
            vm.repository.saveDraft(draft)
            val row = checkNotNull(db.remoteMailDao().message(draft.id))
            db.remoteMailDao().saveMessage(row.copy(envelopeJson = JSONObject(row.envelopeJson)
                .put("draftUploadMessageId", "<draft-ui@example.test>")
                .put("draftUploadPhase", "UNCERTAIN")
                .put("draftUploadError", "APPEND reply was lost")
                .toString()))
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = CoreRoomMapper.folderId("draft-ui", "Drafts"), started = true))
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Needs draft review").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Needs draft review").performClick()
        compose.onNodeWithText("Check and retry draft sync").assertExists().performClick()
        compose.onNodeWithText("Retry draft sync?").assertExists()
        compose.onNodeWithText("retrying could create a second copy", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun fullHistoryControlsShowCompletionAndOfferAnotherScan() {
        failPage = false
        failBody = false
        compose.onNodeWithText("Explore the demo inbox").performClick()
        kotlinx.coroutines.runBlocking {
            addRealAccount("history-ui")
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: History UI fixture\r\nMessage-ID: <history-ui@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = CoreRoomMapper.folderId("history-ui", "INBOX"), started = true))
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Download full folder history")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Download full folder history").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Full history downloaded", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Check folder history again").assertExists()
    }

    @Test fun pausingAndRestartingHistoryKeepsTheNewRunVisible() {
        failPage = false
        failBody = false
        compose.onNodeWithText("Explore the demo inbox").performClick()
        val folderId = CoreRoomMapper.folderId("history-race", "INBOX")
        kotlinx.coroutines.runBlocking {
            addRealAccount("history-race")
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: History race fixture\r\n" +
                    "Message-ID: <history-race@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(vm.repository.mailbox.first().preferences.copy(
                selectedFolder = folderId, started = true))
        }
        compose.waitUntil(5000) { vm.mailbox.value.folders.any { it.id == folderId } }
        holdHistoryPages = true
        vm.downloadHistory(folderId)
        assertTrue(firstHistoryPageEntered.await(5, TimeUnit.SECONDS))
        vm.pauseHistory()
        vm.downloadHistory(folderId)
        assertTrue(secondHistoryPageEntered.await(5, TimeUnit.SECONDS))
        compose.waitForIdle()
        assertTrue(vm.historyDownload.value.running)
        assertEquals(folderId, vm.historyDownload.value.folderId)
        releaseSecondHistoryPage.countDown()
        compose.waitUntil(10000) { !vm.historyDownload.value.running }
        assertNull(vm.historyDownload.value.error)
    }

    @Test fun unifiedRefreshRestartsWhenAnInboxAppearsDuringThePass() {
        failPage = false
        failBody = false
        kotlinx.coroutines.runBlocking {
            addRealAccount("first")
            server.append("INBOX", (
                "From: sender@example.test\r\nTo: reader@example.test\r\n" +
                    "Subject: New inbox fixture\r\nMessage-ID: <new-inbox@example.test>\r\n\r\nBody"
                ).toByteArray())
            vm.repository.updatePreferences(org.foxred.kage.domain.model.Preferences(
                selectedFolder = "unified", unified = true, started = true))
        }
        compose.waitUntil(5000) {
            vm.mailbox.value.folders.any { it.role == "inbox" && it.accountId == "first" }
        }
        holdPages = true
        vm.refreshFolder("unified")
        assertTrue(pageEntered.await(5, TimeUnit.SECONDS))
        kotlinx.coroutines.runBlocking { addRealAccount("second") }
        compose.waitUntil(5000) {
            vm.mailbox.value.folders.any { it.role == "inbox" && it.accountId == "second" }
        }
        vm.refreshFolder("unified")
        resumePages.countDown()
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().count { it.accountId in setOf("first", "second") &&
                    it.bodyDownloaded } == 2
            }
        }
    }

    private suspend fun addRealAccount(id: String) {
        remote.addAccount(Account(
            id, id,
            listOf(EmailAddress("$id@example.test")),
            Server("fixture.invalid", 993, ServerProtocol.IMAP, username = id),
            Server("fixture.invalid", 465, ServerProtocol.SMTP, username = id),
        ), Authorization("password"), Authorization("password"))
        remote.refreshFolders(id)
    }

    @After fun stop() {
        releaseFirstHistoryPage.countDown()
        releaseSecondHistoryPage.countDown()
        releaseAutomaticAttachment.countDown()
        vm.viewModelScope.cancel()
        db.close()
        credentials.clear()
    }

    // Gmail-domain identities below exercise provider auto-detection with mocked mail clients.
    // They are test-only strings; no live Google sign-in or delivery occurs.
    @Test fun failedValidationStaysOnSetupThenRetryOpensRealInbox() {
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Email address").performTextInput("kage.test-only.setup@gmail.com")
        compose.onNodeWithText("Set up with an app password").performClick()
        compose.onNodeWithText("App password").performTextInput("example-app-password")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("IMAP").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Incoming IMAP server").assertExists()
        compose.onNodeWithText("Connect account").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("SMTP sign-in failed", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(vm.mailbox.value.accounts.none { it.address == "kage.test-only.setup@gmail.com" })
        rejectSmtp = false
        compose.onNodeWithText("Connect account").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.accounts.any { it.address == "kage.test-only.setup@gmail.com" } &&
                vm.mailbox.value.preferences.selectedFolder.startsWith("remote-folder-")
        }
        compose.onNodeWithText("Finish").performClick()
        assertEquals("REAL", runBlockingAccountMode())
        assertEquals("inbox", vm.mailbox.value.folders.single {
            it.id == vm.mailbox.value.preferences.selectedFolder
        }.role)
        try {
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription("Open account drawer")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Inbox did not open: ${compose.onRoot().printToString()}", timeout)
        }
        compose.onNodeWithContentDescription("Open account drawer").assertExists()
        compose.onNodeWithContentDescription("Open account drawer").performClick()
        compose.onNodeWithText("Empty").assertExists()
        compose.onNodeWithContentDescription("Expand Orphan").performScrollTo().performClick()
        compose.waitForIdle()
        try {
            compose.onNodeWithContentDescription("Expand Deep").performScrollTo().performClick()
        } catch (failure: Throwable) {
            throw AssertionError("Nested folder did not expand: ${compose.onRoot().printToString()}", failure)
        }
        compose.onNodeWithText("Leaf").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) {
            compose.onAllNodesWithContentDescription("Close navigation menu")
                .fetchSemanticsNodes().isEmpty()
        }
        assertEquals("inbox", vm.mailbox.value.folders.single {
            it.id == vm.mailbox.value.preferences.selectedFolder
        }.role)
        server.connect(Server("fixture.invalid", 993, ServerProtocol.IMAP, username = "real"), Authorization.none())
        server.append("INBOX", AngusMimeCodec().encode(OutgoingEmail(
            "<real-ui@example.test>", EmailAddress("sender@example.test"),
            listOf(EmailAddress("kage.test-only.setup@gmail.com")), subject = "Real inbox header",
            body = EmailBody("Stored on the fixture server", "<p>Exclusive formatted phrase</p>"),
            attachments = listOf(OutgoingAttachment("part-ui.txt", "text/plain",
                "Reader attachment".toByteArray())),
        )))
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("Refresh mailbox").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Refresh mailbox").performClick()
        try {
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("Fixture connection lost").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: Throwable) {
            throw AssertionError("No refresh error: ${vm.folderSync.value}; ${compose.onRoot().printToString()}", timeout)
        }
        failPage = false
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10000) {
            vm.messages.value.any { it.subject == "Real inbox header" }
        }
        compose.onNodeWithText("Real inbox header").assertExists()
        kotlinx.coroutines.runBlocking {
            vm.repository.updatePreferences(vm.mailbox.value.preferences.copy(offline = true))
        }
        compose.waitUntil(5000) { vm.mailbox.value.preferences.offline }
        compose.onNodeWithText("Real inbox header").performClick()
        compose.onNodeWithText("Body unavailable offline. Reconnect to download it.").assertExists()
        kotlinx.coroutines.runBlocking {
            vm.repository.updatePreferences(vm.mailbox.value.preferences.copy(offline = false))
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Fixture body fetch lost").fetchSemanticsNodes().isNotEmpty()
        }
        failBody = false
        compose.onNodeWithText("Retry message").performClick()
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking {
                cachedRealMessages().single { it.subject == "Real inbox header" }.bodyDownloaded
            }
        }
        compose.onNodeWithText("Show plain text").performClick()
        compose.onNodeWithText("Stored on the fixture server").assertExists()
        compose.onNodeWithContentDescription("Find in message").performClick()
        compose.onNodeWithText("Find in this message").performTextInput("Exclusive formatted phrase")
        compose.onNodeWithText("1 / 1").assertExists()
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.onNodeWithText("part-ui.txt").assertExists()
        val attachmentId = kotlinx.coroutines.runBlocking {
            cachedRealMessages().single { it.subject == "Real inbox header" }.attachments.single().id
        }
        kotlinx.coroutines.runBlocking { vm.repository.cacheAttachment(attachmentId) }
        compose.waitUntil(10000) {
            kotlinx.coroutines.runBlocking { db.mailDao().attachment(attachmentId)?.cached == true }
        }
        val cachedPart = java.io.File(context.filesDir, "attachments/$attachmentId")
        assertTrue(cachedPart.isFile)
        kotlinx.coroutines.runBlocking {
            vm.repository.updatePreferences(vm.mailbox.value.preferences.copy(offline = true))
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Real inbox header").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Real inbox header").performClick()
        compose.onNodeWithText("Show plain text").performClick()
        compose.onNodeWithText("Stored on the fixture server").assertExists()
        compose.onNodeWithText("Available offline", substring = true).assertExists()
        kotlinx.coroutines.runBlocking {
            val realId = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }.id
            val row = cachedRealMessages().single { it.subject == "Real inbox header" }
            vm.repository.markRead(row.id, true)
            assertEquals("READ", db.remoteMailDao().operations(realId).single().kind)
            vm.repository.resetDemo()
            assertNotNull(db.remoteMailDao().account(realId))
            assertEquals("example-app-password", credentials.authorization(realId,
                org.foxred.kage.core.account.ServerProtocol.IMAP)?.secret)
            vm.repository.removeAccount(realId)
            assertNull(db.remoteMailDao().account(realId))
            assertFalse(cachedPart.exists())
            assertNull(credentials.authorization(realId,
                org.foxred.kage.core.account.ServerProtocol.IMAP))
        }
    }

    @Test fun googleGrantCreatesARealAccountOnlyAfterBothServersValidate() {
        rejectSmtp = true
        google.nextStep = GoogleAuthorizationStep.Granted(Authorization("google-access",
            Authorization.Kind.OAUTH2, Instant.now().plusSeconds(3000)))
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Email address").performTextInput("kage.test-only.setup@gmail.com")
        compose.onNodeWithText("Continue with Google").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("SMTP sign-in failed", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(vm.mailbox.value.accounts.none { it.address == "kage.test-only.setup@gmail.com" })

        assertEquals(listOf("google-access"), google.clearedTokens)

        rejectSmtp = false
        compose.onNodeWithText("Continue with Google").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.accounts.any { it.address == "kage.test-only.setup@gmail.com" }
        }
        val account = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }
        assertTrue(account.usesOAuth)
        assertEquals(Authorization.Kind.OAUTH2,
            credentials.authorization(account.id, ServerProtocol.IMAP)?.kind)
        assertNull(credentials.authorization(account.id, ServerProtocol.IMAP)?.refreshToken)
    }

    @Test fun canceledGoogleConsentKeepsSetupOpenAndAllowsRetry() {
        val testContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(testContext, GoogleConsentCancelActivity::class.java)
        val pending = PendingIntent.getActivity(testContext, 42, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        google.nextStep = GoogleAuthorizationStep.Consent(pending)
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Email address").performTextInput("kage.test-only.setup@gmail.com")
        compose.onNodeWithText("Continue with Google").performClick()
        try {
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("Google sign-in was canceled")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: Throwable) {
            throw AssertionError("No cancellation message: ${compose.onRoot().printToString()}", timeout)
        }
        assertTrue(vm.mailbox.value.accounts.none { it.address == "kage.test-only.setup@gmail.com" })
        assertEquals(0, google.completionCalls)

        rejectSmtp = false
        google.nextStep = GoogleAuthorizationStep.Granted(Authorization("retry-access",
            Authorization.Kind.OAUTH2, Instant.now().plusSeconds(3000)))
        compose.onNodeWithText("Continue with Google").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.accounts.any { it.address == "kage.test-only.setup@gmail.com" }
        }
    }

    @Test fun settingsMovesAppPasswordAccountToGoogleWithoutLosingCachedMail() {
        rejectSmtp = false
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Email address").performTextInput("kage.test-only.setup@gmail.com")
        compose.onNodeWithText("Set up with an app password").performClick()
        compose.onNodeWithText("App password").performTextInput("first-password")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Connect account").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Finish").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Finish").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.accounts.any { it.address == "kage.test-only.setup@gmail.com" } &&
                compose.onAllNodesWithContentDescription("Open account drawer")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        val before = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }
        val folderId = kotlinx.coroutines.runBlocking { remote.inboxFolder(before.id)!! }
        kotlinx.coroutines.runBlocking {
            db.mailDao().saveMessages(listOf(MessageEntity(
                "cached-before-google", before.id, folderId,
                "Sender", "sender@example.test", "kage.test-only.setup@gmail.com", "", "",
                "Keep cached mail", "Cached body", null, "2026-01-01T00:00:00Z",
                false, false, false, false, false, null,
            )))
        }
        google.nextStep = GoogleAuthorizationStep.Granted(Authorization("google-access",
            Authorization.Kind.OAUTH2, Instant.now().plusSeconds(3000)))
        compose.onNodeWithContentDescription("Open account drawer").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("kage.test-only.setup@gmail.com", substring = true).performClick()
        compose.onNodeWithText("Use Google sign-in").performClick()
        compose.onNodeWithText("Continue with Google").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.accounts.any { it.id == before.id && it.usesOAuth }
        }
        assertNotNull(kotlinx.coroutines.runBlocking {
            db.mailDao().message("cached-before-google")
        })
        assertEquals("google-access",
            credentials.authorization(before.id, ServerProtocol.IMAP)?.secret)
        assertNull(credentials.authorization(before.id, ServerProtocol.IMAP)?.refreshToken)
    }

    @Test fun settingsReplacesAppPasswordWithoutDeletingCachedMail() {
        rejectSmtp = false
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Email address").performTextInput("kage.test-only.setup@gmail.com")
        compose.onNodeWithText("Set up with an app password").performClick()
        compose.onNodeWithText("App password").performTextInput("first-password")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Connect account").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Finish").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Finish").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.accounts.any { it.address == "kage.test-only.setup@gmail.com" } &&
                compose.onAllNodesWithContentDescription("Open account drawer")
                    .fetchSemanticsNodes().isNotEmpty()
        }
        val accountId = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }.id
        val folderId = kotlinx.coroutines.runBlocking { remote.inboxFolder(accountId)!! }
        kotlinx.coroutines.runBlocking {
            db.mailDao().saveMessages(listOf(MessageEntity(
                "cached-before-password-change", accountId, folderId,
                "Sender", "sender@example.test", "kage.test-only.setup@gmail.com", "", "",
                "Keep cached mail", "Cached body", null, "2026-01-01T00:00:00Z",
                false, false, false, false, false, null,
            )))
        }
        compose.onNodeWithContentDescription("Open account drawer").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("kage.test-only.setup@gmail.com", substring = true).performClick()
        compose.onNodeWithText("Update app password").performClick()
        compose.onNodeWithText("New app password").performTextInput("replacement-password")
        rejectSmtp = true
        compose.onNodeWithText("Save app password").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("SMTP sign-in failed", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("first-password", credentials.authorization(accountId, ServerProtocol.IMAP)?.secret)
        rejectSmtp = false
        compose.onNodeWithText("Save app password").performClick()
        compose.waitUntil(10000) {
            credentials.authorization(accountId, ServerProtocol.IMAP)?.secret == "replacement-password"
        }
        assertEquals("replacement-password",
            credentials.authorization(accountId, ServerProtocol.SMTP)?.secret)
        assertEquals("cached-before-password-change", kotlinx.coroutines.runBlocking {
            db.mailDao().message("cached-before-password-change")?.id
        })
        assertEquals(folderId, kotlinx.coroutines.runBlocking { remote.inboxFolder(accountId) })
    }

    private fun runBlockingAccountMode(): String? = kotlinx.coroutines.runBlocking {
        val id = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }.id
        db.remoteMailDao().account(id)?.mode
    }
}
