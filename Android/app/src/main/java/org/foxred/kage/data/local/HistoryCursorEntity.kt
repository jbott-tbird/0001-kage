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
