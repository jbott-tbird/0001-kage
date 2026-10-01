// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.MessageCursor
import org.foxred.kage.core.account.MessagePage

/** Reject a malformed adapter page before any mail row or durable cursor is changed. */
internal fun validateServerPage(page: MessagePage, request: MessageCursor, limit: Int) {
    fun reject(): Nothing = throw MailFailure(FailureKind.PROTOCOL,
        "Server returned an invalid message page; retry sync")
    if (page.messages.size > limit) reject()
    var previousUid = request.beforeUid
    for (email in page.messages) {
        val identity = email.identity ?: reject()
        if (identity.mailbox != request.mailbox ||
            identity.uidValidity != request.uidValidity ||
            identity.uid < request.atOrAboveUid || identity.uid >= previousUid) reject()
        previousUid = identity.uid
    }
    page.next?.let { next ->
        if (next.mailbox != request.mailbox || next.uidValidity != request.uidValidity ||
            next.since != request.since || next.atOrAboveUid != request.atOrAboveUid ||
            next.beforeUid < request.atOrAboveUid || next.beforeUid >= request.beforeUid ||
            (page.messages.isNotEmpty() && next.beforeUid > previousUid)) reject()
    }
}
