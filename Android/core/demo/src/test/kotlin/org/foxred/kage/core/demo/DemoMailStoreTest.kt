// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.demo

import java.io.ByteArrayOutputStream
import java.time.Instant
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec
import org.junit.Assert.*
import org.junit.Test

class DemoMailStoreTest {
    private val incoming = Server("demo.invalid", 993, ServerProtocol.IMAP, username = "demo")
    private val outgoing = Server("demo.invalid", 465, ServerProtocol.SMTP, username = "demo")
    private val auth = Authorization("fixture-only")
    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val codec = AngusMimeCodec()

    private fun email(number: Int) =
        OutgoingEmail(
            "<$number@demo.invalid>",
            EmailAddress("from@demo.invalid"),
            listOf(EmailAddress("to@demo.invalid")),
            bcc = listOf(EmailAddress("hidden@demo.invalid")),
            subject = "Message $number",
            body = EmailBody("שלום $number", null),
            attachments =
                listOf(OutgoingAttachment("test.txt", "text/plain", "bytes".toByteArray())),
        )

    private fun store() = DemoMailStore(now = { now }).also { it.connect(incoming, auth) }

    @Test
    fun headersPagingBodyRawAndAttachmentFollowCoreContract() {
        store().use { store ->
            val raw = codec.encode(email(1))
            val first = store.append("INBOX", raw)
            store.append("INBOX", codec.encode(email(2)))
            val page = store.messagePage("INBOX", now.minusSeconds(1), limit = 1)
            assertEquals("Message 2", page.messages.single().subject)
            assertFalse(page.messages.single().bodyDownloaded)
            store.append("INBOX", codec.encode(email(3)))
            val older = store.messagePage("INBOX", now.minusSeconds(1), page.next, 1)
            assertEquals(first, older.messages.single().identity)
            assertNull(older.next)
            assertTrue(store.message(first).bodyDownloaded)
            val attachment = store.message(first).attachments.single()
            assertArrayEquals("bytes".toByteArray(), store.attachment(first, attachment.partId))
            val output = ByteArrayOutputStream()
            store.downloadRawMessage(first, output)
            assertArrayEquals(raw, output.toByteArray())
            assertFalse(store.message(first).read)
            assertTrue(store.messages("INBOX", now.plusSeconds(1)).isEmpty())
        }
    }

    @Test
    fun recentPagingTraversesEmptyOlderWindowsAfterLastMatch() {
        val boundary = Instant.parse("2026-08-26T00:00:00Z")
        var clock = boundary.minusMillis(1)
        DemoMailStore(now = { clock }).use { store ->
            store.connect(incoming, auth)
            repeat(12) { store.append("INBOX", codec.encode(email(it + 1))) }
            clock = boundary
            val atBoundary = store.append("INBOX", codec.encode(email(13)))
            clock = boundary.plusSeconds(1)
            val afterBoundary = store.append("INBOX", codec.encode(email(14)))

            val first = store.messagePage("INBOX", boundary, limit = 1)
            assertEquals(afterBoundary, first.messages.single().identity)
            val second = store.messagePage("INBOX", boundary, first.next, limit = 1)
            assertEquals(atBoundary, second.messages.single().identity)
            var next = second.next
            var emptyWindows = 0
            while (next != null) {
                val page = store.messagePage("INBOX", boundary, next, limit = 1)
                assertTrue(page.messages.isEmpty())
                assertTrue(page.next == null || page.next!!.beforeUid < next.beforeUid)
                emptyWindows++
                next = page.next
            }
            assertEquals(12, emptyWindows)
        }
    }

    @Test
    fun sparseFullHistoryPagesByExistingMessages() {
        store().use { store ->
            val first = store.append("INBOX", codec.encode(email(1)))
            repeat(20) { store.delete(store.append("INBOX", codec.encode(email(it + 2)))) }
            val newest = store.append("INBOX", codec.encode(email(30)))

            val page = store.messagePage("INBOX", Instant.EPOCH, limit = 1)
            assertEquals(listOf(newest), page.messages.map { it.identity })
            val older = store.messagePage("INBOX", Instant.EPOCH, page.next, 1)
            assertEquals(listOf(first), older.messages.map { it.identity })
            assertNull(older.next)
        }
    }

    @Test
    fun incrementalPageStopsAtItsUidFloorAcrossDeletedUidGaps() {
        store().use { store ->
            val old = store.append("INBOX", codec.encode(email(1)))
            repeat(20) { store.delete(store.append("INBOX", codec.encode(email(it + 2)))) }
            val floor = store.status("INBOX").uidNext
            val fresh = store.append("INBOX", codec.encode(email(30)))
            val before = store.status("INBOX").uidNext
            val cursor = MessageCursor("INBOX", old.uidValidity, before,
                now.minusSeconds(1), floor)

            val page = store.messagePage("INBOX", cursor.since, cursor, limit = 100)
            assertEquals(listOf(fresh), page.messages.map { it.identity })
            assertNull(page.next)
        }
    }

    @Test
    fun mutationsPreserveFlagsAndIsolationAcrossFoldersAndAccounts() {
        store().use { store ->
            val id = store.append("INBOX", codec.encode(email(1)))
            store.markRead(id, true)
            store.flag(id, true)
            store.move(id, "Archive")
            assertEquals(0, store.status("INBOX").messageCount)
            val moved = store.messages("Archive", Instant.EPOCH).single()
            assertTrue(moved.read)
            assertTrue(moved.flagged)
            assertEquals("Archive", moved.identity!!.mailbox)
            assertThrows(MailFailure::class.java) { store.message(id) }
            store().use { other -> assertEquals(0, other.status("Archive").messageCount) }
        }
    }

    @Test
    fun folderGenerationsInvalidateStaleIdentitiesAndCursors() {
        store().use { store ->
            store.createMailbox("Projects")
            store.createMailbox("Projects/Child")
            store.append("Projects/Child", codec.encode(email(1)))
            store.append("Projects/Child", codec.encode(email(2)))
            val page = store.messagePage("Projects/Child", Instant.EPOCH, limit = 1)
            store.renameMailbox("Projects", "Work")
            assertTrue(store.mailboxes().any { it.name == "Work/Child" })
            store.subscribe("Work/Child", false)
            assertFalse(store.mailboxes().first { it.name == "Work/Child" }.isSubscribed)
            assertThrows(MailFailure::class.java) { store.deleteMailbox("Work") }
            store.deleteMailbox("Work/Child")
            store.createMailbox("Projects/Child")
            assertThrows(MailFailure::class.java) {
                store.message(page.messages.single().identity!!)
            }
            assertThrows(MailFailure::class.java) {
                store.messagePage("Projects/Child", Instant.EPOCH, page.next, 1)
            }
        }
    }

    @Test
    fun reconnectPreservesMessagesAndUnavailableIdleUsesPolling() {
        store().use { store ->
            store.append("INBOX", codec.encode(email(1)))
            store.cancel()
            assertThrows(MailFailure::class.java) { store.mailboxes() }
            store.connect(incoming, auth)
            assertFalse(store.supports("IDLE"))
            assertThrows(MailFailure::class.java) { store.awaitChange("INBOX") }
            assertEquals(1, store.poll("INBOX").messageCount)
        }
    }

    @Test
    fun submissionUsesMimeContractAndAnExplicitLocalSink() {
        store().use { store ->
            val submission: MailSubmission = DemoMailSubmission({ store.append("Sent", it, true) })
            submission.send(outgoing, auth, email(1))
            assertEquals(0, store.status("INBOX").messageCount)
            val sent = store.messages("Sent", Instant.EPOCH).single()
            assertEquals("Message 1", sent.subject)
            assertTrue(sent.read)
            assertTrue(sent.bcc.isEmpty())
        }
    }

    @Test
    fun sentLookupRequiresExactMessageIdAndStaysWithinMailbox() {
        store().use { store ->
            val raw = codec.encode(email(1))
            val sent = store.append("Sent", raw, true)
            store.append("INBOX", codec.encode(email(2)))
            assertEquals(sent, store.findByMessageId("Sent", "<1@demo.invalid>"))
            assertNull(store.findByMessageId("Sent", "1@demo.invalid"))
            assertNull(store.findByMessageId("INBOX", "<1@demo.invalid>"))
            assertNull(store.findByMessageId("Sent", "<2@demo.invalid>"))
        }
    }

    @Test
    fun draftAppendRetainsPrivateBccAndCanBeReconciledByMessageId() {
        store().use { store ->
            val draft = email(41).copy(to = emptyList())
            val raw = codec.encodeDraft(draft)
            val identity = store.append("Drafts", raw, read = true, draft = true)
            assertEquals(identity, store.findByMessageId("Drafts", draft.messageId))
            val fetched = store.message(identity)
            assertTrue(fetched.to.isEmpty())
            assertEquals(draft.bcc, fetched.bcc)
            assertEquals(draft.body.text, fetched.body.text)
            assertEquals(draft.attachments.single().filename,
                fetched.attachments.single().filename)
        }
    }
}
