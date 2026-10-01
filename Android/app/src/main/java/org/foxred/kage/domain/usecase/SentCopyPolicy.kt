// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain.usecase

import java.util.Locale

/** Gmail's SMTP service files Sent mail, so a second IMAP APPEND risks a duplicate. */
fun gmailAutomaticallyFilesSent(outgoingHost: String): Boolean =
    outgoingHost.trim().removeSuffix(".").lowercase(Locale.ROOT) in
        setOf("smtp.gmail.com", "smtp.googlemail.com")
