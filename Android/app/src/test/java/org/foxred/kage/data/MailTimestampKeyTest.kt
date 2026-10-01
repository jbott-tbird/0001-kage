// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import java.time.Instant
import org.foxred.kage.data.local.mailTimestampKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailTimestampKeyTest {
    @Test fun fixedWidthKeysKeepSameSecondInChronologicalOrder() {
        val instants = listOf(
            Instant.parse("2026-09-24T12:00:00Z"),
            Instant.parse("2026-09-24T12:00:00.001Z"),
            Instant.parse("2026-09-24T12:00:00.001000001Z"),
            Instant.parse("2026-09-24T12:00:00.1Z"),
        )
        val keys = instants.map(::mailTimestampKey)
        assertEquals(keys.sorted(), keys)
        assertEquals("2026-09-24T12:00:00.000000000Z", keys.first())
        assertEquals(keys, keys.map(::mailTimestampKey))
    }

    @Test fun oldInstantStringsNormalizeAndDateOnlySamplesRemainReadable() {
        assertTrue(mailTimestampKey("2026-09-24T12:00:00.001Z") >
            mailTimestampKey("2026-09-24T12:00:00Z"))
        assertEquals("2026-09-24", mailTimestampKey("2026-09-24"))
    }
}
