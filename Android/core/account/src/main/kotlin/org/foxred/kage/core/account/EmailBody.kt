// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

/** Attachments live on Email so metadata remains available before fetching body content. */
data class EmailBody(val text: String?, val html: String?) {
    val preview: String?
        get() = text?.replace(Regex("\\s+"), " ")?.trim()?.take(200)
}
