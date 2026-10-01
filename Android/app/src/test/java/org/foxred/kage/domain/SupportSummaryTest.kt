// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain

import org.foxred.kage.domain.model.Account
import org.foxred.kage.domain.model.Attachment
import org.foxred.kage.domain.model.Folder
import org.foxred.kage.domain.model.Mailbox
import org.foxred.kage.domain.model.MailCacheCounts
import org.foxred.kage.domain.model.Message
import org.foxred.kage.domain.model.OutboxCounts
import org.foxred.kage.domain.usecase.supportSummary
import org.junit.Assert.*
import org.junit.Test

class SupportSummaryTest {
    @Test fun includesUsefulCountsWithoutAccountOrMessageData() {
        val canary = "private-canary-7391"
        val mailbox = Mailbox(
            accounts = listOf(Account(canary, canary, "$canary@example.test", mode = "REAL")),
            folders = listOf(Folder(canary, canary, canary, "inbox")),
            messages = listOf(Message(
                id = canary, accountId = canary, folderId = canary,
                sender = canary, senderAddress = "$canary@example.test", to = canary,
                subject = canary, body = canary, receivedAt = "2026-09-26",
                draft = true, attachments = listOf(Attachment(canary, canary, canary,
                    "text/plain", 8, cached = true, localFile = canary)),
            )),
        )
        val outbox = OutboxCounts(uncertain = 1, sent = 1, sentCopyNeedsReview = 1)

        val summary = supportSummary(mailbox, MailCacheCounts(1, 1, 1, 1), outbox,
            appVersionCode = 7, androidApi = 35)

        assertTrue(summary.contains("App version code: 7"))
        assertTrue(summary.contains("Real accounts: 1"))
        assertTrue(summary.contains("Cached attachments: 1"))
        assertTrue(summary.contains("Outbox uncertain: 1"))
        assertTrue(summary.contains("Sent copies needing review: 1"))
        assertFalse(summary.contains(canary))
        assertFalse(summary.contains("@"))
    }
}
