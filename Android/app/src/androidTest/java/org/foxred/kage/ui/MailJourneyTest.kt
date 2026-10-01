// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.local.OutboxEntity
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.ui.navigation.KageApp
import org.foxred.kage.ui.theme.KageTheme
import org.junit.*

class MailJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var db: MailDatabase
    private lateinit var vm: MailViewModel

    @Before
    fun start() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        vm =
            MailViewModel(
                RoomMailRepository(
                    db,
                    context,
                    DemoMail(context),
                    org.foxred.kage.data.security.AndroidCredentialStore(context, "repository-test"),
                )
            )
        restoration.setContent { KageTheme { KageApp(vm) } }
        compose.waitUntil(10000) { vm.ready.value }
    }

    @After
    fun stop() {
        vm.viewModelScope.cancel()
        db.close()
    }

    @Test
    fun setupRequiresEmailAndCreatesPopulatedMailbox() {
        compose.onNodeWithText("Set up a demo account").performClick()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Email address").performTextInput("my-address@example.net")
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Planned for v2.0").assertExists()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(10000) { vm.mailbox.value.accounts.size == 4 }
        Assert.assertFalse(
            vm.mailbox.value.accounts.first { it.address == "my-address@example.net" }.requireAuth
        )
        compose.onNodeWithText("Finish").performScrollTo().performClick()
        compose.onAllNodesWithText("my-address@example.net").onFirst().assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Expand Travel").performScrollTo().performClick()
        compose.onNodeWithText("Upcoming trips", substring = false).assertExists()
        compose.onNodeWithContentDescription("Collapse Travel").performClick()
        compose.onNodeWithText("Upcoming trips", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Drafts").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Compose a message").performClick()
        compose.onNodeWithText("To").performTextInput("friend@example.net")
        compose.onNodeWithText("Subject").performTextInput("Native draft test")
        compose.onNodeWithText("Cc", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Bcc", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Add Cc / Bcc").performClick()
        compose.onNodeWithText("Cc", substring = false).performTextInput("copy@example.net")
        compose.onNodeWithText("Bcc", substring = false).performTextInput("private@example.net")
        compose.onNodeWithText("Hide Cc / Bcc").performClick()
        compose.onNodeWithText("Cc", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Show Cc / Bcc · recipients added").assertExists()
        onView(withContentDescription("Message")).perform(replaceText("Persist this draft through Room."))
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.messages.any { it.subject == "Native draft test" }
        }
        compose.runOnIdle {
            val saved = vm.mailbox.value.messages.single { it.subject == "Native draft test" }
            Assert.assertEquals("copy@example.net", saved.cc)
            Assert.assertEquals("private@example.net", saved.bcc)
            Assert.assertEquals("Persist this draft through Room.", saved.body)
            Assert.assertNotNull(saved.html)
        }
        compose.onNodeWithText("Native draft test").performClick()
        compose.onNodeWithText("Cc", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Show Cc / Bcc · recipients added").performClick()
        compose.onNodeWithText("copy@example.net").assertExists()
        compose.onNodeWithText("private@example.net").assertExists()
        onView(withContentDescription("Message")).check(
            androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.withText(
                    org.hamcrest.Matchers.startsWith("Persist this draft through Room."))))
    }

    @Test
    fun outboxPagerReachesOlderFailedSend() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        kotlinx.coroutines.runBlocking {
            val dao = db.remoteMailDao()
            fun row(id: String, state: String, copyState: String) = OutboxEntity(
                id = id, accountId = "personal", draftId = null,
                messageId = "<$id@example.test>", rawMessagePath = "/unused/$id.eml",
                envelopeJson = """{"to":[{"address":"$id@example.test"}],"cc":[],"bcc":[],"sentCopyState":"$copyState"}""",
                state = state, createdAt = 1, updatedAt = 1,
            )
            dao.saveOutbox(row("older-failed", DurableOutbox.State.FAILED, "WAITING"))
            repeat(50) { index ->
                dao.saveOutbox(row("confirmed-$index", DurableOutbox.State.SENT, "CONFIRMED"))
            }
        }
        compose.waitUntil(10000) {
            vm.outboxCounts.value.actionable == 1L && vm.outboxPage.value.items.size == 50
        }
        compose.onNodeWithContentDescription("Inbox options").performClick()
        compose.onNodeWithText("Outbox (1)").performClick()
        compose.onNodeWithContentDescription("Next Outbox page").performScrollTo()
            .performClick()
        compose.waitUntil(10000) {
            vm.outboxPage.value.pageNumber == 2 &&
                vm.outboxPage.value.items.singleOrNull()?.id == "older-failed"
        }
        compose.waitForIdle()
        compose.onNodeWithText("older-failed@example.test").assertExists()
        compose.onNodeWithText("Failed").assertExists()
        compose.onNodeWithContentDescription("Previous Outbox page").performClick()
        compose.waitUntil(10000) {
            vm.outboxPage.value.pageNumber == 1 && vm.outboxPage.value.items.size == 50
        }
        compose.waitForIdle()
        compose.onNodeWithText("older-failed@example.test").assertDoesNotExist()
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
        compose.onNodeWithText("Searches mail saved on this device", substring = true).assertExists()
        compose.onNode(hasText("This account") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("Search mail").performTextInput("Coffee this weekend?")
        compose.onNodeWithText("All accounts").performClick()
        compose.waitUntil(10000) { vm.pageReady.value && vm.messages.value.size == 3 }
        compose.onNodeWithText("3 results on page 1").assertIsDisplayed()
        compose.onNodeWithText("rhea@example.com · Inbox").assertIsDisplayed()
        compose.onNodeWithText("rhea@example.org · Inbox").assertIsDisplayed()
        compose.onNodeWithText("rhea@community.example.net · Inbox").assertIsDisplayed()
    }

    @Test
    fun selectedFileAndDraftFieldsSurviveSavedStateRestoration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val source =
            File(directory, "picked-note.txt").apply { writeText("A real selected attachment.") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", source)
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            compose.onNodeWithText("Explore the demo inbox").performClick()
            compose.onNodeWithContentDescription("Compose a message").performClick()
            compose.onNodeWithText("To").performTextInput("friend@example.net")
            compose.onNodeWithText("Subject").performTextInput("Selected file draft")
            compose.onNodeWithText("Attach files").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithText("picked-note.txt", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("picked-note.txt", substring = true).assertExists()
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(10000) {
                vm.mailbox.value.messages.any { it.subject == "Selected file draft" }
            }
            val saved = vm.mailbox.value.messages.first { it.subject == "Selected file draft" }
            Assert.assertEquals("friend@example.net", saved.to)
            Assert.assertEquals("picked-note.txt", saved.attachments.single().filename)
            Assert.assertEquals(
                "A real selected attachment.",
                File(directory, saved.attachments.single().localFile!!).readText(),
            )
        } finally {
            Intents.release()
            source.delete()
        }
    }

    @Test
    fun composeCanCorrectAnInvalidRecipientAfterSendFails() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        compose.onNodeWithContentDescription("Compose a message", useUnmergedTree = true).performClick()
        compose.onNodeWithText("To").performTextInput("bad@@example.test")
        compose.onNodeWithText("Subject").performTextInput("Corrected recipient")
        onView(withContentDescription("Message")).perform(replaceText("Body"))
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithText("Enter valid recipient email addresses.").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("To")
            .performTextReplacement("Friend <friend@example.test>")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.messages.any {
                it.subject == "Corrected recipient" && !it.draft &&
                    it.to == "Friend <friend@example.test>"
            }
        }
    }

    @Test
    fun savedDraftRecipientEditCanBeReopenedAndDiscarded() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        compose.onNodeWithContentDescription("Open account drawer").performClick()
        compose.onNodeWithText("Drafts").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Compose a message").performClick()
        compose.onNodeWithText("To").performTextInput("first@example.test")
        compose.onNodeWithText("Subject").performTextInput("Editable draft journey")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.messages.any { it.subject == "Editable draft journey" && it.draft }
        }
        compose.onNodeWithText("Editable draft journey").performClick()
        compose.onNodeWithText("To").performTextReplacement("second@example.test")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.messages.any {
                it.subject == "Editable draft journey" && it.to == "second@example.test"
            }
        }
        compose.onNodeWithText("Editable draft journey").performClick()
        compose.onNodeWithText("second@example.test").assertExists()
        compose.onNodeWithContentDescription("Close compose").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil(10000) {
            vm.mailbox.value.messages.none { it.subject == "Editable draft journey" }
        }
    }

    @Test
    fun readerNavigatesMatchesAndCanMarkMessageUnread() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        compose.onNodeWithText("One email workflow across mobile and desktop").performClick()
        compose.onNodeWithContentDescription("Find in message").performClick()
        compose.onNodeWithText("Find in this message").performTextInput("desktop")
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous match").performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.onNodeWithContentDescription("Message options").performClick()
        compose.onNodeWithText("Mark unread").performClick()
        compose.waitUntil(10000) { vm.mailbox.value.messages.first { it.id == "m01" }.isRead.not() }
    }

    @Test
    fun relatedMessagesExpandAndCollapse() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        vm.preferences { it.copy(threads = true) }
        vm.selectFolder("work-inbox")
        compose.waitUntil(10000) {
            vm.mailbox.value.preferences.selectedFolder == "work-inbox" &&
                vm.mailbox.value.preferences.threads
        }
        compose
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Launch checklist", substring = false))
        compose.onNodeWithText("Launch checklist", substring = false).performClick()
        compose
            .onNodeWithContentDescription("Expand related message")
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithContentDescription("Collapse related message")
            .assertExists()
            .performClick()
        compose.onNodeWithContentDescription("Expand related message").assertExists()
    }

    @Test
    fun unifiedInboxSearchUsesAllAccounts() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        vm.preferences { it.copy(unified = true) }
        compose.waitUntil(10000) { vm.mailbox.value.preferences.unified }
        compose.onNodeWithContentDescription("Open account drawer").performClick()
        compose.onNodeWithText("All inboxes").performClick()
        compose.onNodeWithContentDescription("Search messages").performClick()
        compose.onNodeWithText("This account").assertIsNotEnabled()
        compose.onNode(hasText("All accounts") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("Search mail").performTextInput("Coffee this weekend?")
        compose.waitUntil(10000) { vm.pageReady.value && vm.messages.value.size == 3 }
        compose.onNodeWithText("3 results on page 1").assertIsDisplayed()
    }

    @Test
    fun unifiedPagingRestartsWhenAnInboxIsAdded() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        vm.preferences { it.copy(selectedFolder = "unified", unified = true, started = true) }
        compose.waitUntil(10000) { vm.pageReady.value &&
            vm.mailbox.value.preferences.selectedFolder == "unified" }
        kotlinx.coroutines.runBlocking {
            val base = checkNotNull(db.mailDao().message("m04"))
            db.mailDao().saveMessages((0 until 55).map { index ->
                base.copy(id = "page-membership-$index", accountId = "personal",
                    folderId = "personal-inbox", receivedAt = "2100-01-01T00:00:00Z")
            })
        }
        compose.waitUntil(10000) { vm.pageReady.value && vm.messagePage.value.page.next != null }
        vm.nextMessagePage()
        compose.waitUntil(10000) { vm.pageReady.value && vm.messagePage.value.pageNumber == 2 }

        kotlinx.coroutines.runBlocking {
            val account = db.mailDao().accounts().first().first()
            val inbox = db.mailDao().folders().first().first { it.role == "inbox" }
            db.mailDao().insertAccounts(listOf(account.copy(
                id = "page-added", address = "page-added@example.test")))
            db.mailDao().insertFolders(listOf(inbox.copy(
                id = "page-added-inbox", accountId = "page-added")))
        }
        compose.waitUntil(10000) {
            vm.pageReady.value && vm.messagePage.value.pageNumber == 1 &&
                "page-added-inbox" in vm.messagePage.value.folderIds
        }
    }

    @Test
    fun readerAlignsArabicAndHebrewParagraphsByContent() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        for (id in listOf("personal-design-4", "personal-design-5")) {
            val message = vm.mailbox.value.messages.first { it.id == id }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(message.subject))
            compose.onNodeWithText(message.subject).performClick()
            fun layout(text: String): androidx.compose.ui.text.TextLayoutResult {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithText(text).performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
                ) {
                    it(results)
                }
                return results.single()
            }
            for (text in listOf(message.subject, message.sender, message.body)) {
                val result = layout(text)
                Assert.assertEquals(
                    androidx.compose.ui.text.style.ResolvedTextDirection.Rtl,
                    result.getParagraphDirection(0),
                )
                Assert.assertEquals(result.size.width.toFloat(), result.getLineRight(0), 1f)
            }
            if (id.endsWith("5")) {
                val result = layout(message.body)
                val offset = message.body.indexOf("The next review")
                Assert.assertEquals(
                    androidx.compose.ui.text.style.ResolvedTextDirection.Ltr,
                    result.getParagraphDirection(offset),
                )
                Assert.assertEquals(0f, result.getLineLeft(result.getLineForOffset(offset)), 1f)
            }
            compose.onNodeWithContentDescription("Find in message").performClick()
            compose.onNodeWithText("Find in this message").performTextInput(message.body.take(4))
            Assert.assertEquals(
                androidx.compose.ui.text.style.ResolvedTextDirection.Rtl,
                layout(message.body).getParagraphDirection(0),
            )
            compose.onNodeWithContentDescription("Back").performClick()
        }
    }

    @Test
    fun backFromReaderRetainsListPosition() {
        compose.onNodeWithText("Explore the demo inbox").performClick()
        val subject = "One_email_workflow_across_iOS_and_desktop"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(subject))
        compose.onNodeWithText(subject).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText(subject).assertIsDisplayed()
    }
}
