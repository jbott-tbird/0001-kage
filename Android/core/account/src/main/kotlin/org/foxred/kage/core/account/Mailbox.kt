// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

enum class MailboxRole {
    INBOX,
    SENT,
    DRAFTS,
    ARCHIVE,
    TRASH,
    JUNK,
    ALL,
    IMPORTANT,
}

/** Unknown rights remain null until the server supplies them, rather than inventing permissions. */
data class MailboxRights(
    val mayReadItems: Boolean? = null,
    val mayAddItems: Boolean? = null,
    val mayRemoveItems: Boolean? = null,
    val maySetSeen: Boolean? = null,
    val maySetKeywords: Boolean? = null,
    val mayCreateChild: Boolean? = null,
    val mayRename: Boolean? = null,
    val mayDelete: Boolean? = null,
)

data class Mailbox(
    val name: String,
    val delimiter: Char,
    val selectable: Boolean,
    val attributes: Set<String> = emptySet(),
    val isSubscribed: Boolean = false,
    val unreadEmails: Int? = null,
    val totalEmails: Int? = null,
    val rights: MailboxRights = MailboxRights(),
    val id: String? = null,
) {
    val role: MailboxRole?
        get() =
            if (name.equals("INBOX", true)) MailboxRole.INBOX
            else
                MailboxRole.entries.firstOrNull { candidate ->
                    attributes.any { it.equals("\\" + candidate.name, true) }
                }
}
