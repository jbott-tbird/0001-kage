// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

data class MailboxFlagChange(val uid: Long, val read: Boolean, val flagged: Boolean)

/** A CONDSTORE/QRESYNC checkpoint; null from [MailStore.changes] means scan the mailbox. */
data class MailboxChanges(
    val uidValidity: Long,
    val highestModSeq: Long,
    val flags: List<MailboxFlagChange>,
    val vanishedUids: Set<Long>,
)
