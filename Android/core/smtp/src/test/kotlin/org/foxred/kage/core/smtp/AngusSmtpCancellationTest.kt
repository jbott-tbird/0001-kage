// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.smtp

import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AngusSmtpCancellationTest {
    @Test
    fun cancellationBeforeSubmissionPreventsAnyNetworkAttempt() {
        val client = AngusSmtpClient()
        client.cancel()
        val failure = assertThrows(MailFailure::class.java) {
            client.sendRaw(
                Server("127.0.0.1", 1, ServerProtocol.SMTP, username = "user"),
                Authorization("secret"), byteArrayOf(),
                listOf(EmailAddress("recipient@example.test")),
            )
        }
        assertEquals(FailureKind.CANCELLED, failure.kind)
    }

    @Test
    fun invalidRecipientFailsBeforeConnectingAndHasADefiniteOutcome() {
        val raw = AngusMimeCodec().encode(OutgoingEmail(
            "<preflight@example.test>", EmailAddress("sender@example.test"),
            listOf(EmailAddress("valid@example.test")),
            subject = "Preflight",
            body = EmailBody("Saved message", null),
        ))
        var submissionStarted = false
        val failure = assertThrows(MailFailure::class.java) {
            AngusSmtpClient().sendRaw(
                Server("127.0.0.1", 1, ServerProtocol.SMTP, username = "user"),
                Authorization("secret"), raw, listOf(EmailAddress("not-an-address")),
            ) { submissionStarted = true }
        }
        assertEquals(FailureKind.INVALID_MESSAGE, failure.kind)
        assertFalse(submissionStarted)
    }
}
