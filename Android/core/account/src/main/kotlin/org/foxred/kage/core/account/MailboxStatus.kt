package org.foxred.kage.core.account

data class MailboxStatus(
    val uidValidity: Long,
    val uidNext: Long,
    val messageCount: Int,
    val unreadCount: Int,
)
