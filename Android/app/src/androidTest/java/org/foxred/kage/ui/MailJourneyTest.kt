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
        compose.onNodeWithContentDescription("Collapse Projects").performScrollTo().performClick()
        compose.onNodeWithText("Design", substring = false).assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand Projects").performClick()
        compose.onNodeWithText("Design", substring = false).assertExists()
        compose.onNodeWithText("Drafts").performScrollTo().performClick()
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

    @Test
    fun selectionMarksOnlyChosenMessagesAndSortCanBeReversed() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        val before = vm.mailbox.value.messages.filter { it.folderId == "personal-inbox" }
        val newest = before.maxBy { it.receivedAt }
        val oldest = before.minBy { it.receivedAt }
        compose.onNodeWithContentDescription("Inbox options").performClick()
        compose.onNodeWithText("Oldest first").performClick()
        compose.onNodeWithText(oldest.subject).assertIsDisplayed()
        compose.onNodeWithContentDescription("Inbox options").performClick()
        compose.onNodeWithText("Newest first").performClick()
        compose.onNodeWithText(newest.subject).assertIsDisplayed()
        compose.onNodeWithContentDescription("Inbox options").performClick()
        compose.onNodeWithText("Select messages").performClick()
        compose.onNodeWithContentDescription("Select ${newest.subject}").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Mark read").performClick()
        compose.waitUntil(10000) { vm.mailbox.value.messages.first { it.id == newest.id }.isRead }
        val after = vm.mailbox.value.messages.associateBy { it.id }
        before
            .filter { it.id != newest.id }
            .forEach { original ->
                Assert.assertEquals(original.isRead, after.getValue(original.id).isRead)
            }
        Assert.assertEquals(newest.isNew, after.getValue(newest.id).isNew)
    }

    @Test
    fun allAccountSearchShowsAccountAndFolderForResults() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        compose.onNodeWithContentDescription("Search messages").performClick()
        compose.onNodeWithText("Search mail").performTextInput("Lighthouse booking confirmed")
        compose.onNodeWithText("All accounts").performClick()
        compose.onNodeWithText("3 results").assertIsDisplayed()
        compose.onNodeWithText("rhea@example.com · Inbox").assertIsDisplayed()
        compose.onNodeWithText("rhea@example.org · Inbox").assertIsDisplayed()
        compose.onNodeWithText("rhea@community.example.net · Inbox").assertIsDisplayed()
    }
}
