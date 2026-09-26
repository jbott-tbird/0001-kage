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

/**
 * Source identity survives cache resets: repositories detach the optional message link before
 * deleting cached rows.
 */
@Entity(
    tableName = "pending_operations",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = MessageEntity::class,
                parentColumns = ["id", "accountId"],
                childColumns = ["messageId", "accountId"],
                onDelete = ForeignKey.NO_ACTION,
            ),
        ],
    indices =
        [
            Index("accountId"),
            Index(value = ["messageId", "accountId"]),
            Index(value = ["accountId", "state"]),
        ],
)
data class PendingOperationEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val messageId: String?,
    val mailbox: String,
    val uidValidity: Long,
    val uid: Long,
    val kind: String,
    val desiredValue: Boolean?,
    val targetMailbox: String?,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val moveTargetUidValidity: Long? = null,
    val moveTargetUidNext: Long? = null,
    val moveSourceMessageId: String? = null,
    val moveMode: String? = null,
)

/** MIME source is held in a private file; envelope metadata preserves Bcc independently. */
@Entity(
    tableName = "outbox",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = MessageEntity::class,
                parentColumns = ["id", "accountId"],
                childColumns = ["draftId", "accountId"],
                onDelete = ForeignKey.NO_ACTION,
            ),
        ],
    indices =
        [
            Index(value = ["accountId", "messageId"], unique = true),
            Index(value = ["draftId", "accountId"]),
            Index(value = ["accountId", "state"]),
        ],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val draftId: String?,
    val messageId: String,
    val rawMessagePath: String,
    val envelopeJson: String,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
