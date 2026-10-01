// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import org.foxred.kage.data.security.googleAccessLifetimeSeconds
import org.foxred.kage.data.security.googleReportedExpirySeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleAccessLifetimeTest {
    @Test fun shortReportedGrantCannotBeCachedForLongerThanItsLifetime() {
        assertEquals(1L, googleAccessLifetimeSeconds(0L))
        assertEquals(1L, googleAccessLifetimeSeconds(30L))
        assertEquals(1L, googleAccessLifetimeSeconds(60L))
        assertEquals(60L, googleAccessLifetimeSeconds(120L))
        assertEquals(3_000L, googleAccessLifetimeSeconds(3_600L))
        assertEquals(3_000L, googleAccessLifetimeSeconds(null))
    }

    @Test fun playServicesExpiryAcceptsIntegerLongAndStringBundleValues() {
        assertEquals(30L, googleReportedExpirySeconds(30))
        assertEquals(3_600L, googleReportedExpirySeconds(3_600L))
        assertEquals(90L, googleReportedExpirySeconds("90"))
        assertNull(googleReportedExpirySeconds("unknown"))
        assertNull(googleReportedExpirySeconds(null))
    }
}
