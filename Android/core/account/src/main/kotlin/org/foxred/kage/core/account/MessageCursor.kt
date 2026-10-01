// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

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
