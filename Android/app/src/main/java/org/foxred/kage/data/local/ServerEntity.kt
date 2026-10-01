// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

/** Server configuration contains no password or OAuth tokens; secrets live outside Room. */
@Entity(
    tableName = "servers",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["accountId", "protocol"], unique = true)],
)
data class ServerEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val protocol: String,
    val hostname: String,
    val port: Int,
    val security: String,
    val username: String,
    val authenticationType: String,
)
