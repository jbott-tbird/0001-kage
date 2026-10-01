// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import java.time.Instant
import org.foxred.kage.core.account.Email
import org.foxred.kage.core.account.EmailBody
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.MessageCursor
import org.foxred.kage.core.account.MessageIdentity
import org.foxred.kage.core.account.MessagePage
import org.foxred.kage.data.repository.validateServerPage
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerPageValidationTest {
    private val request = MessageCursor("INBOX", 77, 11, Instant.EPOCH, 5)

    private fun email(uid: Long, mailbox: String = "INBOX", generation: Long = 77) = Email(
        MessageIdentity(mailbox, generation, uid), null, "subject", emptyList(), emptyList(),
        emptyList(), Instant.EPOCH, EmailBody(null, null), emptyList(),
    )

    private fun rejects(page: MessagePage, limit: Int = 3) {
        val failure = runCatching { validateServerPage(page, request, limit) }
            .exceptionOrNull() as MailFailure
        assertEquals(FailureKind.PROTOCOL, failure.kind)
    }

    @Test fun acceptsBoundedAndDateFilteredPages() {
        validateServerPage(MessagePage(listOf(email(10), email(8)),
            request.copy(beforeUid = 8)), request, 3)
        validateServerPage(MessagePage(emptyList(), request.copy(beforeUid = 7)), request, 3)
        validateServerPage(MessagePage(listOf(email(10)), null), request, 3)
    }

    @Test fun rejectsWrongIdentityOrRowsOutsideTheRequestedWindow() {
        rejects(MessagePage(listOf(email(10, mailbox = "Archive")), null))
        rejects(MessagePage(listOf(email(10, generation = 78)), null))
        rejects(MessagePage(listOf(email(11)), null))
        rejects(MessagePage(listOf(email(4)), null))
        rejects(MessagePage(listOf(email(10), email(10)), null))
        rejects(MessagePage(listOf(email(8), email(10)), null))
    }

    @Test fun rejectsCursorDriftAndOversizedResults() {
        rejects(MessagePage(listOf(email(8)), request.copy(beforeUid = 9)))
        rejects(MessagePage(emptyList(), request.copy(since = Instant.parse("2026-01-01T00:00:00Z"),
            beforeUid = 7)))
        rejects(MessagePage(listOf(email(10), email(9)), null), limit = 1)
    }
}
