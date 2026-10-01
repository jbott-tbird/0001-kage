// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain

import org.foxred.kage.domain.usecase.gmailAutomaticallyFilesSent
import org.junit.Assert.*
import org.junit.Test

class SentCopyPolicyTest {
    @Test fun gmailAliasesAndAbsoluteDnsNamesDoNotOfferAnotherSentCopy() {
        assertTrue(gmailAutomaticallyFilesSent("smtp.gmail.com"))
        assertTrue(gmailAutomaticallyFilesSent(" SMTP.GMAIL.COM. "))
        assertTrue(gmailAutomaticallyFilesSent("smtp.googlemail.com."))
        assertFalse(gmailAutomaticallyFilesSent("smtp.example.com"))
        assertFalse(gmailAutomaticallyFilesSent("not-smtp.gmail.com"))
    }
}
