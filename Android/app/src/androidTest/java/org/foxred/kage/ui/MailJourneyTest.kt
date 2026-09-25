package org.foxred.kage.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.cancel
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.ui.navigation.KageApp
import org.foxred.kage.ui.theme.KageTheme
import org.junit.*

class MailJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: MailDatabase
    private lateinit var vm: MailViewModel

    @Before
    fun start() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        vm = MailViewModel(RoomMailRepository(db, context, DemoMail(context)))
        compose.setContent { KageTheme { KageApp(vm) } }
        compose.waitUntil(10000) { vm.ready.value }
    }

    @After
    fun stop() {
        vm.viewModelScope.cancel()
        db.close()
    }

    @Test
    fun setupIsPrefilledAndCreatesPopulatedMailbox() {
        compose.onNodeWithText("Get started").performClick()
        compose.onAllNodesWithText("skye@example.net").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Planned for v2.0").assertExists()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.mailbox.value.accounts.size == 4 }
        compose.onNodeWithText("Finish").performScrollTo().performClick()
        compose.onAllNodesWithText("skye@example.net").onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("Filter messages").assertExists()
        Assert.assertTrue(
            vm.mailbox.value.messages.any {
                it.accountId !in listOf("personal", "work", "community")
            }
        )
    }

    @Test
    fun drawerFiltersAndDraftSavingWork() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        compose.onNodeWithContentDescription("Filter messages").performClick()
        listOf("Unread", "Flagged", "Pinned", "Has attachments").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Flagged").performClick()
        pressBack()
        compose.onNodeWithText("Clear filters").assertExists().performClick()
        compose.onNodeWithContentDescription("Open account drawer").performClick()
        compose.onNodeWithText("Mailboxes").assertExists()
        compose.onNodeWithText("Drafts").performClick()
        compose.onNodeWithContentDescription("Compose a message").performClick()
        compose.onNodeWithText("To").performTextInput("friend@example.net")
        compose.onNodeWithText("Subject").performTextInput("Native draft test")
        compose
            .onNodeWithText("Message", substring = false)
            .performTextInput("Persist this draft through Room.")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.messages.any { it.subject == "Native draft test" }
        }
        compose.onNodeWithText("Native draft test").performClick()
        compose.onNodeWithText("Persist this draft through Room.").assertExists()
    }
}
