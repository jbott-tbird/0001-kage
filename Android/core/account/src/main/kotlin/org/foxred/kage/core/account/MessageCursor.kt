package org.foxred.kage.core.account

import java.time.Instant

/** Cursor binds a bounded UID window to a mailbox generation and an exact sync cutoff. */
data class MessageCursor(
    val mailbox: String,
    val uidValidity: Long,
    val beforeUid: Long,
    val since: Instant,
) {
    init {
        require(uidValidity > 0 && beforeUid > 0)
    }
}

data class MessagePage(val messages: List<Email>, val next: MessageCursor?)
