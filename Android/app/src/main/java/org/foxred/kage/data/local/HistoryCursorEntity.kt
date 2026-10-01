// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

/** Independent full-history checkpoint; the rolling recent-mail cursor remains untouched. */
@Entity(
    tableName = "history_cursors",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class HistoryCursorEntity(
    @PrimaryKey val folderId: String,
    val uidValidity: Long,
    val beforeUid: Long?,
    val scannedMessages: Int,
    val estimatedTotal: Int,
    val updatedAt: Long,
)
