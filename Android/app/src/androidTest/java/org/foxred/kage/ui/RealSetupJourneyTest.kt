package org.foxred.kage.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.*
import java.time.Instant
import org.foxred.kage.core.demo.DemoMailStore
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.security.AndroidCredentialStore
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

class RealSetupJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val credentials by lazy { AndroidCredentialStore(context, "real-ui-test") }
    private lateinit var db: MailDatabase
    private lateinit var vm: MailViewModel
    private val server = DemoMailStore()
    private var rejectSmtp = true
    private var failPage = true
    private var failBody = true

    @Before fun start() {
        credentials.clear()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        server.connect(Server("fixture.invalid", 993, ServerProtocol.IMAP, username = "real"), Authorization.none())
        server.createMailbox("Orphan/Deep/Leaf")
        server.createMailbox("Empty")
        server.close()
        val remote = RemoteMailRepository(db,
            AccountSessions(RemoteMailRepository.incomingServer(db), credentials) {
                object : MailStore by server {
                    override fun messagePage(
                        mailbox: String, since: Instant, cursor: MessageCursor?, limit: Int,
                    ): MessagePage {
                        if (failPage) throw MailFailure(FailureKind.CONNECTION, "Fixture connection lost")
                        return server.messagePage(mailbox, since, cursor, limit)
                    }
                    override fun message(identity: MessageIdentity): Email {
                        if (failBody) throw MailFailure(FailureKind.CONNECTION, "Fixture body fetch lost")
                        return server.message(identity)
                    }
                }
            },
            credentials, DurableOutbox(db, context, AngusMimeCodec()))
        val setup = RealAccountSetup(remote, { server }, { _, _ ->
            if (rejectSmtp) throw MailFailure(FailureKind.AUTHENTICATION, "Wrong SMTP app password")
        })
        vm = MailViewModel(RoomMailRepository(db, context, DemoMail(context), credentials, remote), setup, remote)
        compose.setContent { KageTheme { KageApp(vm) } }
        compose.waitUntil(10000) { vm.ready.value }
    }

    @After fun stop() {
        vm.viewModelScope.cancel()
        db.close()
        credentials.clear()
    }

    @Test fun failedValidationStaysOnSetupThenRetryOpensRealInbox() {
        compose.onNodeWithText("Connect a real account").performClick()
        compose.onNodeWithText("Email address").performTextInput("kage.test-only.setup@gmail.com")
        compose.onNodeWithText("App password").performTextInput("example-app-password")
        compose.onNodeWithText("IMAP: imap.gmail.com:993 · SMTP: smtp.gmail.com:465")
            .assertExists()
        compose.onNodeWithText("Connect account").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("SMTP sign-in failed", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(vm.mailbox.value.accounts.none { it.address == "kage.test-only.setup@gmail.com" })
        rejectSmtp = false
        compose.onNodeWithText("Connect account").performScrollTo().performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.accounts.any { it.address == "kage.test-only.setup@gmail.com" } &&
                vm.mailbox.value.preferences.selectedFolder.startsWith("remote-folder-")
        }
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
            vm.mailbox.value.messages.any { it.subject == "Real inbox header" }
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
            vm.mailbox.value.messages.single { it.subject == "Real inbox header" }.bodyDownloaded
        }
        compose.onNodeWithText("Show plain text").performClick()
        compose.onNodeWithText("Stored on the fixture server").assertExists()
        compose.onNodeWithContentDescription("Find in message").performClick()
        compose.onNodeWithText("Find in this message").performTextInput("Exclusive formatted phrase")
        compose.onNodeWithText("1 / 1").assertExists()
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.onNodeWithText("part-ui.txt").assertExists()
        kotlinx.coroutines.runBlocking {
            val id = vm.mailbox.value.messages.single { it.subject == "Real inbox header" }
                .attachments.single().id
            vm.repository.cacheAttachment(id)
        }
        compose.waitUntil(10000) {
            val id = vm.mailbox.value.messages.single { it.subject == "Real inbox header" }
                .attachments.single().id
            kotlinx.coroutines.runBlocking { db.mailDao().attachment(id)?.cached == true }
        }
        val cachedPart = java.io.File(context.filesDir, "attachments/" +
            vm.mailbox.value.messages.single { it.subject == "Real inbox header" }
                .attachments.single().id)
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
            val row = vm.mailbox.value.messages.single { it.subject == "Real inbox header" }
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

    private fun runBlockingAccountMode(): String? = kotlinx.coroutines.runBlocking {
        val id = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }.id
        db.remoteMailDao().account(id)?.mode
    }
}
