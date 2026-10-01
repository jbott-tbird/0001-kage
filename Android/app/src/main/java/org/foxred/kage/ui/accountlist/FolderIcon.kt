// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.accountlist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

fun folderIcon(role: String): ImageVector =
    when (role) {
        "inbox" -> Icons.Outlined.Inbox
        "drafts" -> Icons.Outlined.Drafts
        "sent" -> Icons.AutoMirrored.Outlined.Send
        "archive" -> Icons.Outlined.Archive
        "spam" -> Icons.Outlined.Report
        "trash" -> Icons.Outlined.Delete
        else -> Icons.Outlined.Folder
    }
