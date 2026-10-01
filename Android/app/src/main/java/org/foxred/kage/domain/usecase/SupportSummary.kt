// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain.usecase

import org.foxred.kage.domain.model.Mailbox
import org.foxred.kage.domain.model.MailCacheCounts
import org.foxred.kage.domain.model.OutboxCounts

/** Fixed labels and counts only; no mail or account fields enter the output. */
fun supportSummary(
    mailbox: Mailbox,
    cache: MailCacheCounts,
    outbox: OutboxCounts,
    appVersionCode: Long,
    androidApi: Int,
): String {
    return buildString {
        appendLine("Kage support summary")
        appendLine("App version code: $appVersionCode")
        appendLine("Android API: $androidApi")
        appendLine("Real accounts: ${mailbox.accounts.count { it.mode == "REAL" }}")
        appendLine("Sample accounts: ${mailbox.accounts.count { it.mode != "REAL" }}")
        appendLine("Folders: ${mailbox.folders.size}")
        appendLine("Cached messages: ${cache.cachedMessages}")
        appendLine("Downloaded bodies: ${cache.downloadedBodies}")
        appendLine("Cached attachments: ${cache.cachedAttachments}")
        appendLine("Drafts: ${cache.drafts}")
        appendLine("Outbox queued: ${outbox.queued}")
        appendLine("Outbox sending: ${outbox.sending}")
        appendLine("Outbox failed: ${outbox.failed}")
        appendLine("Outbox uncertain: ${outbox.uncertain}")
        appendLine("Outbox sent: ${outbox.sent}")
        appendLine("Sent copies needing review: ${outbox.sentCopyNeedsReview}")
        appendLine("Offline preview: ${if (mailbox.preferences.offline) "on" else "off"}")
    }
}
