// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

data class Folder(
    val accountId: String,
    val mailbox: Mailbox,
    val parentPath: String?,
    val subfolders: List<Folder> = emptyList(),
    val id: String = "$accountId:${mailbox.name}",
) {
    val path: String
        get() = mailbox.name

    val name: String
        get() = mailbox.name.substringAfterLast(mailbox.delimiter)

    val unreadEmails: Int?
        get() = mailbox.unreadEmails

    val totalEmails: Int?
        get() = mailbox.totalEmails

    fun aggregatedUnreadCount(): Int =
        (unreadEmails ?: 0) + subfolders.sumOf { it.aggregatedUnreadCount() }
}
