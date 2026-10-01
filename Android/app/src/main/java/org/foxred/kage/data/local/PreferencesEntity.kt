// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

@Entity(tableName = "preferences")
data class PreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val selectedFolder: String = "personal-inbox",
    val unified: Boolean = false,
    val threads: Boolean = false,
    val automaticAttachments: Boolean = false,
    val offline: Boolean = false,
    val started: Boolean = false,
)
