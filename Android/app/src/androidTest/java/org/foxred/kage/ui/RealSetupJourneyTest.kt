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
    private var rejectSmtp = true

    @Before fun start() {
        credentials.clear()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        val remote = RemoteMailRepository(db,
            AccountSessions(RemoteMailRepository.incomingServer(db), credentials) { DemoMailStore() },
            credentials, DurableOutbox(db, context, AngusMimeCodec()))
        val setup = RealAccountSetup(remote, { DemoMailStore() }, { _, _ ->
            if (rejectSmtp) throw MailFailure(FailureKind.AUTHENTICATION, "Wrong SMTP app password")
        })
        vm = MailViewModel(RoomMailRepository(db, context, DemoMail(context), credentials, remote), setup)
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
        try {
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription("Open account drawer")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Inbox did not open: ${compose.onRoot().printToString()}", timeout)
        }
        compose.onNodeWithContentDescription("Open account drawer").assertExists()
        kotlinx.coroutines.runBlocking {
            val realId = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }.id
            vm.repository.resetDemo()
            assertNotNull(db.remoteMailDao().account(realId))
            assertEquals("example-app-password", credentials.authorization(realId,
                org.foxred.kage.core.account.ServerProtocol.IMAP)?.secret)
            vm.repository.removeAccount(realId)
            assertNull(db.remoteMailDao().account(realId))
            assertNull(credentials.authorization(realId,
                org.foxred.kage.core.account.ServerProtocol.IMAP))
        }
    }

    private fun runBlockingAccountMode(): String? = kotlinx.coroutines.runBlocking {
        val id = vm.mailbox.value.accounts.single { it.address == "kage.test-only.setup@gmail.com" }.id
        db.remoteMailDao().account(id)?.mode
    }
}
