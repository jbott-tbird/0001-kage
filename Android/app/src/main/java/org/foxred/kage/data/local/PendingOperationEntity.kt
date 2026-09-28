package org.foxred.kage.data.local

import androidx.room.*

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
            Index(value = ["accountId", "state", "createdAt", "id"]),
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
