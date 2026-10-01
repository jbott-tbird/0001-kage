// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain.usecase

import org.foxred.kage.domain.model.*
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object SearchText {
    fun fromHtml(html: String): String =
        Jsoup.parseBodyFragment(Jsoup.clean(html, Safelist.none())).text()

    fun matches(message: Message, needle: String): Boolean =
        sequenceOf(message.sender, message.senderAddress, message.to, message.subject,
            message.preview, message.body,
            message.attachments.joinToString { it.filename })
            .any { it.contains(needle, ignoreCase = true) } ||
            (message.bodyDownloaded && message.html?.let(::fromHtml)
                ?.contains(needle, ignoreCase = true) == true)
}

fun MailFilter.matches(message: Message): Boolean =
    (!unread || !message.isRead) && (!flagged || message.flagged) &&
        (!pinned || message.pinned) && (!attachments || message.hasAttachments)

/** Search spans the selected account or all accounts; ordinary browsing stays in its folder. */
class FilterMessages {
    operator fun invoke(mailbox: Mailbox, query: MailQuery): List<Message> {
        val folder = mailbox.preferences.selectedFolder
        val account = mailbox.folders.find { it.id == folder }?.accountId
        val needle = query.text.trim()
        return mailbox.messages
            .filter { m ->
                val location =
                    if (needle.isNotEmpty())
                        query.scope == SearchScope.AllAccounts || m.accountId == account
                    else if (folder == "unified")
                        mailbox.folders.any { it.id == m.folderId && it.role == "inbox" }
                    else m.folderId == folder
                location && query.filter.matches(m) &&
                    (needle.isEmpty() || SearchText.matches(m, needle))
            }
            .sortedByDescending { it.receivedAt }
    }
}
