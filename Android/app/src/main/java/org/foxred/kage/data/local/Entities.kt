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
)

@Entity(
    tableName = "folders",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("accountId")],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val name: String,
    val role: String,
    val parentId: String?,
)

@Entity(
    tableName = "messages",
    foreignKeys =
        [
            ForeignKey(
                entity = FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["folderId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("accountId"), Index("folderId")],
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
)

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
    indices = [Index("messageId")],
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
)

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
