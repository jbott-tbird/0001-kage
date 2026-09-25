package org.foxred.kage.data.local

import androidx.room.*

@Dao
interface RemoteMailDao {
    @Query("DELETE FROM messages WHERE id = :id") suspend fun removeCachedMessage(id: String)

    @Upsert suspend fun saveServers(servers: List<ServerEntity>)

    @Query("SELECT * FROM servers WHERE accountId = :accountId ORDER BY protocol")
    suspend fun servers(accountId: String): List<ServerEntity>

    @Upsert suspend fun saveCursor(cursor: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursors WHERE folderId = :folderId")
    suspend fun cursor(folderId: String): SyncCursorEntity?

    @Upsert suspend fun saveOperation(operation: PendingOperationEntity)

    @Query("SELECT * FROM pending_operations WHERE accountId = :accountId ORDER BY createdAt, id")
    suspend fun operations(accountId: String): List<PendingOperationEntity>

    @Query("UPDATE pending_operations SET messageId = NULL WHERE messageId = :messageId")
    suspend fun detachOperations(messageId: String)

    @Upsert suspend fun saveOutbox(outbox: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE accountId = :accountId ORDER BY createdAt, id")
    suspend fun outbox(accountId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun outboxEntry(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND messageId = :messageId LIMIT 1")
    suspend fun outboxByMessageId(accountId: String, messageId: String): OutboxEntity?

    @Query("UPDATE outbox SET draftId = NULL WHERE draftId = :messageId")
    suspend fun detachOutbox(messageId: String)

    @Query(
        "SELECT * FROM messages WHERE folderId = :folderId AND uidValidity = :uidValidity AND uid = :uid LIMIT 1"
    )
    suspend fun messageByUid(folderId: String, uidValidity: Long, uid: Long): MessageEntity?

    @Insert suspend fun insertAccount(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): AccountEntity?

    @Query("DELETE FROM accounts WHERE id = :id") suspend fun removeAccount(id: String)

    @Upsert suspend fun saveFolders(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY rowid")
    suspend fun folders(accountId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND role = :role LIMIT 1")
    suspend fun folderByRole(accountId: String, role: String): FolderEntity?

    @Query("DELETE FROM folders WHERE id = :id") suspend fun removeFolder(id: String)

    @Query(
        "UPDATE folders SET uidValidity = :uidValidity, uidNext = :uidNext, " +
            "serverUnreadCount = :unread, serverTotalCount = :total WHERE id = :id"
    )
    suspend fun updateFolderState(id: String, uidValidity: Long, uidNext: Long, unread: Int, total: Int)

    @Query("DELETE FROM sync_cursors WHERE folderId = :folderId")
    suspend fun removeCursor(folderId: String)

    @Query("SELECT id FROM messages WHERE folderId = :folderId AND uid IS NOT NULL")
    suspend fun remoteMessageIds(folderId: String): List<String>

    @Query("SELECT id FROM messages WHERE folderId = :folderId")
    suspend fun messageIds(folderId: String): List<String>

    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?

    @Upsert suspend fun saveMessage(message: MessageEntity)

    @Query("UPDATE messages SET isRead = :read WHERE id = :id")
    suspend fun setRead(id: String, read: Boolean)

    @Query("UPDATE messages SET flagged = :flagged WHERE id = :id")
    suspend fun setFlagged(id: String, flagged: Boolean)

    @Query(
        "UPDATE messages SET folderId = :folderId, uidValidity = :uidValidity, uid = :uid WHERE id = :id"
    )
    suspend fun relocate(id: String, folderId: String, uidValidity: Long?, uid: Long?)

    /**
     * Local stand-ins for moved messages whose queued moves were all confirmed before
     * [appliedBefore], with no outbox row still depending on them.
     */
    @Query(
        "SELECT * FROM messages m WHERE m.folderId = :folderId AND m.uid IS NULL AND m.draft = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM pending_operations p WHERE p.messageId = m.id " +
            "AND NOT (p.state = 'APPLIED' AND p.updatedAt < :appliedBefore)) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.draftId = m.id)"
    )
    suspend fun settledPlaceholders(folderId: String, appliedBefore: Long): List<MessageEntity>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY rowid")
    suspend fun attachments(messageId: String): List<AttachmentEntity>

    @Upsert suspend fun saveAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE messageId = :messageId AND id NOT IN (:keep)")
    suspend fun removeAttachmentsExcept(messageId: String, keep: List<String>)

    @Query(
        "SELECT * FROM pending_operations WHERE accountId = :accountId AND mailbox = :mailbox " +
            "AND uidValidity = :uidValidity AND uid = :uid AND (state IN ('PENDING', 'IN_FLIGHT') " +
            "OR (state = 'APPLIED' AND updatedAt >= :appliedSince)) ORDER BY createdAt, id"
    )
    suspend fun activeOperations(
        accountId: String,
        mailbox: String,
        uidValidity: Long,
        uid: Long,
        appliedSince: Long,
    ): List<PendingOperationEntity>

    @Query(
        "SELECT * FROM pending_operations WHERE accountId = :accountId AND state IN ('PENDING', 'IN_FLIGHT') " +
            "ORDER BY createdAt, id"
    )
    suspend fun activeOperations(accountId: String): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun operation(id: String): PendingOperationEntity?

    @Query("DELETE FROM pending_operations WHERE id = :id") suspend fun removeOperation(id: String)

    @Query(
        "DELETE FROM pending_operations WHERE accountId = :accountId AND mailbox = :mailbox " +
            "AND state = 'APPLIED' AND updatedAt < :before"
    )
    suspend fun purgeApplied(accountId: String, mailbox: String, before: Long)
}
