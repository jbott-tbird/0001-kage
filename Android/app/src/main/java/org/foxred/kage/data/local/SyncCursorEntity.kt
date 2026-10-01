// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

@Entity(
    tableName = "sync_cursors",
    foreignKeys =
        [
            ForeignKey(
                entity = FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["folderId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class SyncCursorEntity(
    @PrimaryKey val folderId: String,
    val uidValidity: Long,
    val beforeUid: Long?,
    val sinceEpochMillis: Long,
    val lastCompletedAt: Long?,
    val fullPassId: String? = null,
    val highestModSeq: Long? = null,
)
