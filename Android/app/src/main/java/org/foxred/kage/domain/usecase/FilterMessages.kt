package org.foxred.kage.domain.usecase

import org.foxred.kage.domain.model.*

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
                location &&
                    (!query.filter.unread || !m.isRead) &&
                    (!query.filter.flagged || m.flagged) &&
                    (!query.filter.pinned || m.pinned) &&
                    (!query.filter.attachments || m.attachments.isNotEmpty()) &&
                    (needle.isEmpty() ||
                        listOf(
                                m.sender,
                                m.senderAddress,
                                m.to,
                                m.subject,
                                m.body,
                                m.attachments.joinToString { it.filename },
                            )
                            .any { it.contains(needle, ignoreCase = true) })
            }
            .sortedByDescending { it.receivedAt }
    }
}
