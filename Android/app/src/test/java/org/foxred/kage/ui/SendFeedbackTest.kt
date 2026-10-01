// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui

import org.foxred.kage.domain.model.OutboxCounts
import org.junit.Assert.*
import org.junit.Test

class SendFeedbackTest {
    @Test fun existingHistoryAndSentCopyChangesAreSilent() {
        val feedback = SendFeedback()
        assertNull(feedback.update(OutboxCounts(sent = 8, failed = 2, sentUnconfirmed = 1)))
        assertNull(feedback.update(OutboxCounts(sent = 8, failed = 2)))
        assertNull(feedback.update(OutboxCounts(sent = 7, failed = 1)))
    }

    @Test fun fastAndBatchedSendsAreAnnouncedOnce() {
        val feedback = SendFeedback()
        assertNull(feedback.update(OutboxCounts(queued = 1)))
        assertEquals("Email sent.", feedback.update(OutboxCounts(sent = 1)))
        assertNull(feedback.update(OutboxCounts(sent = 1)))
        assertEquals("2 emails sent.", feedback.update(OutboxCounts(sent = 3)))
    }

    @Test fun sendingIsNotSuccessAndUnknownDeliveryIsNotFailure() {
        val feedback = SendFeedback()
        feedback.update(OutboxCounts())
        assertNull(feedback.update(OutboxCounts(sending = 1)))
        assertEquals("Delivery couldn’t be confirmed. Check Outbox before retrying.",
            feedback.update(OutboxCounts(uncertain = 1)))
        assertEquals("Email couldn’t be sent. Check Outbox for details.",
            feedback.update(OutboxCounts(uncertain = 1, failed = 1)))
    }

    @Test fun mixedOutcomesKeepBothMessages() {
        val feedback = SendFeedback()
        feedback.update(OutboxCounts(queued = 2))
        assertEquals("Email sent. Email couldn’t be sent. Check Outbox for details.",
            feedback.update(OutboxCounts(sent = 1, failed = 1)))
    }
}
