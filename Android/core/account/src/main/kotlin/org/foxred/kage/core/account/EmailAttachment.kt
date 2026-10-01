// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

data class EmailAttachment(
    val partId: String,
    val filename: String,
    val mediaType: String,
    val size: Long,
    val contentId: String? = null,
    val inline: Boolean = false,
)
