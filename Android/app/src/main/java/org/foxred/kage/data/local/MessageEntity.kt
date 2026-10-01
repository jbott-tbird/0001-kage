// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

@Entity(
    tableName = "messages",
    foreignKeys =
        [
            ForeignKey(
                entity = FolderEntity::class,
                parentColumns = ["id", "accountId"],
                childColumns = ["folderId", "accountId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index("accountId"),
            Index(value = ["accountId", "draft", "id"]),
            Index(value = ["accountId", "receivedAt", "id"]),
            Index("folderId"),
            Index(value = ["folderId", "receivedAt", "id"]),
            Index(value = ["receivedAt", "id"]),
            Index(value = ["isRead", "folderId"]),
            Index(value = ["folderId", "accountId"]),
            Index(value = ["folderId", "uidValidity", "uid"], unique = true),
            Index(value = ["folderId", "uidValidity", "bodyDownloaded", "uid"]),
            Index(value = ["id", "accountId"], unique = true),
        ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val folderId: String,
    val sender: String,
    val senderAddress: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    val html: String?,
    val receivedAt: String,
    val isRead: Boolean,
    val isNew: Boolean,
    val flagged: Boolean,
    val pinned: Boolean,
    val draft: Boolean,
    val relatedGroup: String?,
    @ColumnInfo(defaultValue = "''") val preview: String = "",
    val uidValidity: Long? = null,
    val uid: Long? = null,
    val remoteEmailId: String? = null,
    @ColumnInfo(defaultValue = "'{}'") val envelopeJson: String = "{}",
    @ColumnInfo(defaultValue = "1") val bodyDownloaded: Boolean = true,
    val rawMessagePath: String? = null,
    val lastSeenPassId: String? = null,
)
