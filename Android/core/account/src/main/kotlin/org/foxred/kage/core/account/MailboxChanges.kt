package org.foxred.kage.core.account

data class MailboxFlagChange(val uid: Long, val read: Boolean, val flagged: Boolean)

/** A CONDSTORE/QRESYNC checkpoint; null from [MailStore.changes] means scan the mailbox. */
data class MailboxChanges(
    val uidValidity: Long,
    val highestModSeq: Long,
    val flags: List<MailboxFlagChange>,
    val vanishedUids: Set<Long>,
)
