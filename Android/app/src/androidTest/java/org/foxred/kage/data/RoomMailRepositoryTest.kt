package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.domain.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMailRepositoryTest {
    private lateinit var db: MailDatabase
    private lateinit var repo: RoomMailRepository

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        repo = RoomMailRepository(db, context, DemoMail(context))
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun initializationDoesNotOverwriteChanges() = runBlocking {
        repo.initialize()
        val original = repo.mailbox.first()
        assertEquals(3, original.accounts.size)
        original.accounts.forEach { a ->
            assertTrue(
                original.folders
                    .filter { it.accountId == a.id }
                    .map { it.role }
                    .containsAll(
                        listOf("inbox", "drafts", "sent", "archive", "spam", "trash", "custom")
                    )
            )
        }
        val message = original.messages.first { !it.isRead }
        repo.markRead(message.id, true)
        repo.pin(message.id, true)
        repo.initialize()
        val updated = repo.mailbox.first().messages.first { it.id == message.id }
        assertTrue(updated.isRead)
        assertTrue(updated.pinned)
        assertEquals(message.isNew, updated.isNew)
    }

    @Test
    fun archiveAndRemoveStayWithinAccount() = runBlocking {
        repo.initialize()
        val message = repo.mailbox.first().messages.first { it.accountId == "work" }
        repo.move(message.id, "archive")
        assertEquals(
            "work-archive",
            repo.mailbox.first().messages.first { it.id == message.id }.folderId,
        )
        repo.removeAccount("work")
        val after = repo.mailbox.first()
        assertTrue(after.accounts.none { it.id == "work" })
        assertTrue(after.messages.none { it.accountId == "work" })
        assertTrue(after.folders.none { it.accountId == "work" })
        assertTrue(after.messages.any { it.accountId == "personal" })
    }

    @Test
    fun draftAttachmentsCanBeRemovedAndSentLocally() = runBlocking {
        repo.initialize()
        val draft =
            Message(
                "test-draft",
                "personal",
                "",
                "Rhea",
                "rhea@example.com",
                "friend@example.net",
                subject = "Draft",
                body = "Keep this text",
                receivedAt = "2026-09-24",
                attachments =
                    listOf(
                        Attachment("test-pdf", "test-draft", "ticket.pdf", "application/pdf", 100)
                    ),
            )
        repo.saveDraft(draft)
        assertEquals(1, repo.mailbox.first().messages.first { it.id == draft.id }.attachments.size)
        repo.saveDraft(draft.copy(attachments = emptyList()))
        assertTrue(repo.mailbox.first().messages.first { it.id == draft.id }.attachments.isEmpty())
        repo.sendDemo(draft.copy(attachments = emptyList()))
        val sent = repo.mailbox.first().messages.first { it.id == draft.id }
        assertEquals("personal-sent", sent.folderId)
        assertFalse(sent.draft)
    }

    @Test
    fun preferencesAndAddedMailboxesPersist() = runBlocking {
        repo.initialize()
        repo.addAccount(Account("skye", "Skye", "skye@example.net"))
        assertTrue(repo.mailbox.first().messages.any { it.folderId == "skye-inbox" })
        repo.updatePreferences(
            Preferences(selectedFolder = "skye-inbox", offline = true, started = true)
        )
        repo.initialize()
        assertTrue(repo.mailbox.first().preferences.offline)
        assertEquals("skye-inbox", repo.mailbox.first().preferences.selectedFolder)
    }
}
