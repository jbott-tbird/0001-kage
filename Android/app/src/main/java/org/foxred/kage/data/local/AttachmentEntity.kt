// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

@Entity(
    tableName = "attachments",
    foreignKeys =
        [
            ForeignKey(
                entity = MessageEntity::class,
                parentColumns = ["id"],
                childColumns = ["messageId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("messageId"), Index(value = ["cached", "id"])],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val cached: Boolean,
    val asset: String,
    val localFile: String? = null,
    val partId: String? = null,
    val contentId: String? = null,
    @ColumnInfo(defaultValue = "0") val inline: Boolean = false,
    @ColumnInfo(defaultValue = "'NOT_DOWNLOADED'") val downloadState: String = "NOT_DOWNLOADED",
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
)
