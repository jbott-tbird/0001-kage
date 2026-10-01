// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain

import org.foxred.kage.domain.model.Attachment
import org.foxred.kage.domain.model.Message
import org.foxred.kage.domain.usecase.ComposePreparation
import org.junit.Assert.*
import org.junit.Test

class ComposePreparationTest {
    private fun source(body: String = "Original text", html: String? = null) = Message(
        "source", "account", "inbox", "Sender", "sender@example.test",
        "\"Me, Jr.\" <me@example.test>, \"Friend, A\" <FRIEND@example.test>",
        cc = "friend@example.test, other@example.test",
        subject = "Original", body = body, html = html,
        receivedAt = "2026-09-25T20:00:00Z", rfcMessageId = "<original@example.test>",
        replyToAddress = "alternate@example.test",
        references = listOf("<earlier@example.test>"),
        attachments = listOf(Attachment("part", "source", "note.txt", "text/plain", 4)),
    )

    @Test fun replyAllUsesReplyToAndDeduplicatesParsedAddresses() {
        val prepared = ComposePreparation.prepare("replyAll", source(), "me@example.test", "draft")
        assertEquals("alternate@example.test", prepared.to)
        assertEquals("FRIEND@example.test, other@example.test", prepared.cc)
        assertEquals("Re: Original", prepared.subject)
        assertEquals("<original@example.test>", prepared.inReplyTo)
        assertEquals(listOf("<earlier@example.test>", "<original@example.test>"), prepared.references)
        assertTrue(prepared.attachments.isEmpty())
        assertTrue(prepared.bcc.isEmpty())
    }

    @Test fun replyAllExcludesEveryFormattedReplyToAddressFromCc() {
        val message = source().copy(
            replyToAddress = "\"Support Team\" <alternate@example.test>, backup@example.test",
            to = "backup@example.test, me@example.test",
            cc = "alternate@example.test, third@example.test",
        )
        val prepared = ComposePreparation.prepare("replyAll", message,
            "me@example.test", "draft")
        assertEquals(message.replyToAddress, prepared.to)
        assertEquals("third@example.test", prepared.cc)
    }

    @Test fun replyAllExcludesMembersOfReplyToGroups() {
        val message = source().copy(
            replyToAddress = "Support: one@example.test, two@example.test;",
            to = "one@example.test, me@example.test",
            cc = "two@example.test, third@example.test",
        )
        val prepared = ComposePreparation.prepare("replyAll", message,
            "me@example.test", "draft")
        assertEquals("third@example.test", prepared.cc)
    }

    @Test fun replyToSelfSentMailUsesFirstVisibleRecipient() {
        val sent = source().copy(senderAddress = "me@example.test", replyToAddress = "",
            to = "me@example.test, first@example.test, second@example.test",
            cc = "copy@example.test")
        val prepared = ComposePreparation.prepare("reply", sent, "me@example.test", "draft")
        assertEquals("first@example.test", prepared.to)
        assertEquals("", prepared.cc)
    }

    @Test fun replyAllToSelfSentMailKeepsVisibleToAndCcWithoutSelf() {
        val sent = source().copy(senderAddress = "me@example.test", replyToAddress = "",
            to = "first@example.test, me@example.test, second@example.test",
            cc = "second@example.test, copy@example.test")
        val prepared = ComposePreparation.prepare("replyAll", sent, "me@example.test", "draft")
        assertEquals("first@example.test, second@example.test", prepared.to)
        assertEquals("copy@example.test", prepared.cc)
    }

    @Test fun selfSentMailHonorsExplicitReplyToAnotherMailbox() {
        val sent = source().copy(senderAddress = "me@example.test",
            replyToAddress = "delegate@example.test", to = "first@example.test")
        val prepared = ComposePreparation.prepare("reply", sent, "me@example.test", "draft")
        assertEquals("delegate@example.test", prepared.to)
    }

    @Test fun replyAllToSelfSentCcOnlyMailUsesCcWhenToIsEmpty() {
        val sent = source().copy(senderAddress = "me@example.test", replyToAddress = "",
            to = "", cc = "me@example.test, first@example.test, second@example.test")
        val prepared = ComposePreparation.prepare("replyAll", sent, "me@example.test", "draft")
        assertEquals("first@example.test", prepared.to)
        assertEquals("second@example.test", prepared.cc)
    }

    @Test fun htmlOnlyReplyQuotesReadablePlainText() {
        val message = source(body = "", html = "<p>Hello <b>world</b></p><script>ignored()</script>")
        val prepared = ComposePreparation.prepare("reply", message, "me@example.test", "draft")
        assertEquals("alternate@example.test", prepared.to)
        assertEquals("", prepared.cc)
        assertTrue(prepared.body.contains("Hello world"))
        assertFalse(prepared.body.contains("ignored()"))
    }

    @Test fun forwardSelectsSourceAttachmentsWithoutReplyHeaders() {
        val prepared = ComposePreparation.prepare("forward", source(), "me@example.test", "draft")
        assertEquals("", prepared.to)
        assertEquals("Fwd: Original", prepared.subject)
        assertNull(prepared.inReplyTo)
        assertTrue(prepared.references.isEmpty())
        assertEquals("draft-part", prepared.attachments.single().id)
        assertEquals("draft", prepared.attachments.single().messageId)
        assertTrue(prepared.body.contains("Original text"))
    }

    @Test fun savedDraftKeepsEditedFieldsAndThreadContext() {
        val draft = source().copy(to = "new@example.test", cc = "", bcc = "hidden@example.test",
            subject = "Re: Original", body = "Edited", draft = true,
            inReplyTo = "<original@example.test>")
        val prepared = ComposePreparation.prepare("draft", draft, "me@example.test", draft.id)
        assertEquals("new@example.test", prepared.to)
        assertEquals("hidden@example.test", prepared.bcc)
        assertEquals("Edited", prepared.body)
        assertEquals(draft.inReplyTo, prepared.inReplyTo)
        assertEquals(draft.attachments, prepared.attachments)
    }

    @Test fun htmlOnlyDraftShowsReadableTextAndRetainsHtmlUntilEdited() {
        val html = "<p>Hello <strong>world</strong></p>"
        val prepared = ComposePreparation.prepare("draft", source(body = "", html = html),
            "me@example.test", "source")
        assertEquals("Hello world", prepared.body)
        assertEquals(html, ComposePreparation.htmlForBody(prepared.body, prepared.html,
            prepared.body))
        assertNull(ComposePreparation.htmlForBody(prepared.body, prepared.html,
            "Hello again"))
    }
}
