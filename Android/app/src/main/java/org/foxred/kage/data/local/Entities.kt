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
    indices =
        [
            Index("accountId"),
            Index(value = ["id", "accountId"], unique = true),
            Index(value = ["accountId", "remotePath"], unique = true),
        ],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val name: String,
    val role: String,
    val parentId: String?,
    val remotePath: String? = null,
    @ColumnInfo(defaultValue = "'/'") val delimiter: String = "/",
    @ColumnInfo(defaultValue = "1") val subscribed: Boolean = true,
    @ColumnInfo(defaultValue = "'[]'") val attributesJson: String = "[]",
    val rightsJson: String? = null,
    val uidValidity: Long? = null,
    val uidNext: Long? = null,
    val serverUnreadCount: Int? = null,
    val serverTotalCount: Int? = null,
    val lastVisitedUid: Long? = null,
)

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
            Index("folderId"),
            Index(value = ["folderId", "accountId"]),
            Index(value = ["folderId", "uidValidity", "uid"], unique = true),
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
    val partId: String? = null,
    val contentId: String? = null,
    @ColumnInfo(defaultValue = "0") val inline: Boolean = false,
    @ColumnInfo(defaultValue = "'NOT_DOWNLOADED'") val downloadState: String = "NOT_DOWNLOADED",
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
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
