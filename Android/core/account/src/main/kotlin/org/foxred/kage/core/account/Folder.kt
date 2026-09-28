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
