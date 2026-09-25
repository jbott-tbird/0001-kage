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

    @Query("UPDATE outbox SET draftId = NULL WHERE draftId = :messageId")
    suspend fun detachOutbox(messageId: String)

    @Query(
        "SELECT * FROM messages WHERE folderId = :folderId AND uidValidity = :uidValidity AND uid = :uid LIMIT 1"
    )
    suspend fun messageByUid(folderId: String, uidValidity: Long, uid: Long): MessageEntity?
}
