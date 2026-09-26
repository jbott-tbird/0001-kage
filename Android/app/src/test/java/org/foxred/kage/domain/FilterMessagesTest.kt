package org.foxred.kage.domain

import org.foxred.kage.domain.model.*
import org.foxred.kage.domain.usecase.FilterMessages
import org.junit.Assert.*
import org.junit.Test

class FilterMessagesTest {
    private val filter = FilterMessages()
    private val first =
        Message(
            "1",
            "a",
            "a-inbox",
            "Roc",
            "roc@example.net",
            "a@example.net",
            subject = "Review",
            body = "A searchable Japanese 日本語 message",
            receivedAt = "2026-09-24",
            isRead = false,
            flagged = true,
            pinned = true,
            attachments = listOf(Attachment("pdf", "1", "ticket.pdf", "application/pdf", 100)),
        )
    private val mail =
        Mailbox(
            folders =
                listOf(
                    Folder("a-inbox", "a", "Inbox", "inbox"),
                    Folder("a-archive", "a", "Archive", "archive"),
                    Folder("b-inbox", "b", "Inbox", "inbox"),
                ),
            messages =
                listOf(
                    first,
                    first.copy(
                        id = "2",
                        folderId = "a-archive",
                        isRead = true,
                        pinned = false,
                        attachments = emptyList(),
                    ),
                    first.copy(id = "3", accountId = "b", folderId = "b-inbox"),
                ),
            preferences = Preferences(selectedFolder = "a-inbox"),
        )

    @Test
    fun browsingStaysInFolder() {
        assertEquals(listOf("1"), filter(mail, MailQuery()).map { it.id })
    }

    @Test
    fun searchScopeIncludesAccountFoldersOrAllAccounts() {
        assertEquals(setOf("1", "2"), filter(mail, MailQuery("日本語")).map { it.id }.toSet())
        assertEquals(3, filter(mail, MailQuery("review", SearchScope.AllAccounts)).size)
    }

    @Test
    fun allFourFiltersAreConjunctive() {
        assertEquals(
            listOf("1"),
            filter(mail, MailQuery(filter = MailFilter(true, true, true, true))).map { it.id },
        )
        assertTrue(
            filter(
                    mail.copy(messages = listOf(first.copy(pinned = false))),
                    MailQuery(filter = MailFilter(pinned = true)),
                )
                .isEmpty()
        )
        assertTrue(
            filter(
                    mail.copy(messages = listOf(first.copy(flagged = false))),
                    MailQuery(filter = MailFilter(flagged = true)),
                )
                .isEmpty()
        )
        assertTrue(
            filter(
                    mail.copy(messages = listOf(first.copy(attachments = emptyList()))),
                    MailQuery(filter = MailFilter(attachments = true)),
                )
                .isEmpty()
        )
        assertTrue(
            filter(
                    mail.copy(messages = listOf(first.copy(isRead = true))),
                    MailQuery(filter = MailFilter(unread = true)),
                )
                .isEmpty()
        )
    }

    @Test
    fun unifiedIncludesOnlyInboxes() {
        assertEquals(
            setOf("1", "3"),
            filter(mail.copy(preferences = Preferences(selectedFolder = "unified")), MailQuery())
                .map { it.id }
                .toSet(),
        )
    }

    @Test
    fun searchFindsAttachmentsAndIgnoresWhitespace() {
        assertEquals(listOf("1"), filter(mail, MailQuery("  TICKET.PDF  ")).map { it.id })
    }

    @Test
    fun searchIncludesPreviewAndDownloadedHtmlOnlyWithinChosenScope() {
        val html = first.copy(id = "html", body = "", preview = "Server teaser",
            html = "<p>Only in formatted content</p><script>secret marker</script>",
            bodyDownloaded = true)
        val headers = html.copy(id = "headers", accountId = "b", folderId = "b-inbox",
            bodyDownloaded = false)
        val mailbox = mail.copy(messages = listOf(html, headers))
        assertEquals(listOf("html"), filter(mailbox, MailQuery("formatted")).map { it.id })
        assertEquals(listOf("html"), filter(mailbox, MailQuery("teaser")).map { it.id })
        assertEquals(setOf("html", "headers"),
            filter(mailbox, MailQuery("teaser", SearchScope.AllAccounts)).map { it.id }.toSet())
        assertEquals(listOf("html"),
            filter(mailbox, MailQuery("formatted", SearchScope.AllAccounts)).map { it.id })
        assertTrue(filter(mailbox, MailQuery("secret", SearchScope.AllAccounts)).isEmpty())
    }
}
