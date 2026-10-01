// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

@Entity(tableName = "accounts", indices = [Index(value = ["address"], unique = true)])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val incoming: String,
    val outgoing: String,
    val incomingPort: Int,
    val outgoingPort: Int,
    val security: String,
    val outgoingSecurity: String,
    @ColumnInfo(defaultValue = "1") val requireAuth: Boolean = true,
    @ColumnInfo(defaultValue = "'DEMO'") val mode: String = "DEMO",
    @ColumnInfo(defaultValue = "'[]'") val identitiesJson: String = "[]",
    @ColumnInfo(defaultValue = "'never'") val deletePolicy: String = "never",
    @ColumnInfo(defaultValue = "'user-blue'") val avatarColor: String = "user-blue",
    val oauthConfigurationJson: String? = null,
)
