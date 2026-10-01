// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import jakarta.mail.internet.AddressException
import org.foxred.kage.data.repository.parseRecipientAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecipientAddressesTest {
    @Test fun expandsReplyToGroupIntoMailboxRecipients() {
        assertEquals(listOf("one@example.test", "two@example.test"),
            parseRecipientAddresses("Support: one@example.test, two@example.test;")
                .map { it.address })
    }

    @Test fun acceptsLegacySemicolonSeparatedMailboxes() {
        assertEquals(listOf("one@example.test", "two@example.test"),
            parseRecipientAddresses("one@example.test; two@example.test")
                .map { it.address })
    }

    @Test fun rejectsMalformedGroupInsteadOfTreatingItAsAList() {
        assertThrows(AddressException::class.java) {
            parseRecipientAddresses("Support: one@example.test, two@example.test")
        }
    }
}
