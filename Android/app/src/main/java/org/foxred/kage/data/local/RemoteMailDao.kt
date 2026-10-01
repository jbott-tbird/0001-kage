// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Small projection used when walking a large cached archive by UID. */
data class RemoteMessageRef(val id: String, val uid: Long?, val receivedAt: String)

/** Only these fields are needed while matching a server page to local placeholders. */
data class PlaceholderHeaderRef(val id: String, val envelopeJson: String)

/** Draft scans need identity and upload metadata, never the body or attachments. */
data class DraftUploadRef(val id: String, val uid: Long?, val envelopeJson: String)

/** Account removal stages local filenames without holding the entire archive in memory. */
data class AttachmentFileRef(val id: String, val localFile: String)

/** Automatic caching needs the owning account without loading full MIME metadata. */
data class AutomaticRemoteAttachmentRef(val id: String, val accountId: String)

/** The SQLite rowid is a stable keyset cursor for this Outbox snapshot. */
data class OutboxPageRow(val rowId: Long, @Embedded val entry: OutboxEntity)

data class OutboxCountRow(
    val queued: Long,
    val sending: Long,
    val failed: Long,
    val uncertain: Long,
    val sent: Long,
    val sentUnconfirmed: Long,
    val sentCopyNeedsReview: Long,
)

@Dao
interface RemoteMailDao {
    @Query("DELETE FROM messages WHERE id = :id") suspend fun removeCachedMessage(id: String)

    @Upsert suspend fun saveServers(servers: List<ServerEntity>)

    @Update suspend fun updateServers(servers: List<ServerEntity>): Int

    @Query("SELECT * FROM servers WHERE accountId = :accountId ORDER BY protocol")
    suspend fun servers(accountId: String): List<ServerEntity>

    @Upsert suspend fun saveCursor(cursor: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursors WHERE folderId = :folderId")
    suspend fun cursor(folderId: String): SyncCursorEntity?

    @Upsert suspend fun saveHistoryCursor(cursor: HistoryCursorEntity)

    @Query("SELECT * FROM history_cursors WHERE folderId = :folderId")
    suspend fun historyCursor(folderId: String): HistoryCursorEntity?

    @Query("DELETE FROM history_cursors WHERE folderId = :folderId")
    suspend fun removeHistoryCursor(folderId: String)

    @Query("SELECT id, uid, receivedAt FROM messages WHERE folderId = :folderId AND uidValidity = :uidValidity " +
        "AND uid >= :atOrAbove AND uid < :beforeUid AND bodyDownloaded = 0 " +
        "ORDER BY uid DESC LIMIT :limit")
    suspend fun unhydratedHistoryBodies(
        folderId: String, uidValidity: Long, atOrAbove: Long, beforeUid: Long, limit: Int,
    ): List<RemoteMessageRef>

    @Query("SELECT COUNT(*) FROM messages WHERE folderId = :folderId " +
        "AND uidValidity = :uidValidity AND uid >= :atOrAbove AND bodyDownloaded = 0")
    suspend fun remainingHistoryBodies(folderId: String, uidValidity: Long, atOrAbove: Long): Int

    @Upsert suspend fun saveOperation(operation: PendingOperationEntity)

    @Query("SELECT * FROM pending_operations WHERE accountId = :accountId ORDER BY createdAt, id")
    suspend fun operations(accountId: String): List<PendingOperationEntity>

    @Query("UPDATE pending_operations SET messageId = NULL WHERE messageId = :messageId")
    suspend fun detachOperations(messageId: String)

    @Query("DELETE FROM pending_operations WHERE messageId = :messageId " +
        "AND state IN ('PENDING', 'IN_FLIGHT', 'FAILED')")
    suspend fun removeUnfinishedOperations(messageId: String)

    /** The message/account index avoids loading an account's entire operation history. */
    @Query("SELECT EXISTS(SELECT 1 FROM pending_operations WHERE messageId = :messageId " +
        "AND accountId = :accountId AND kind = 'MOVE' AND state != 'FAILED')")
    suspend fun hasBlockingDraftMove(accountId: String, messageId: String): Boolean

    @Upsert suspend fun saveOutbox(outbox: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE accountId = :accountId ORDER BY createdAt, id")
    suspend fun outbox(accountId: String): List<OutboxEntity>

    // The existing (accountId, state) index stores rowid as its final key.
    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND state = :state " +
        "AND rowid > :afterRowId ORDER BY rowid LIMIT :limit")
    suspend fun outboxStatePage(accountId: String, state: String, afterRowId: Long,
        limit: Int): List<OutboxEntity>

    @Query("SELECT rowid FROM outbox WHERE id = :id")
    suspend fun outboxRowId(id: String): Long?

    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND state = 'PENDING' " +
        "ORDER BY createdAt, id LIMIT 1")
    suspend fun nextPendingOutbox(accountId: String): OutboxEntity?

    @Query("SELECT rowid AS rowId, outbox.* FROM outbox WHERE rowid < :beforeRowId " +
        "ORDER BY rowid DESC LIMIT :limit")
    fun observeOutboxPage(beforeRowId: Long, limit: Int): Flow<List<OutboxPageRow>>

    /** Count each state without materializing MIME metadata or historical rows. */
    @Query("SELECT " +
        "COUNT(CASE WHEN state = 'PENDING' THEN 1 END) AS queued, " +
        "COUNT(CASE WHEN state = 'SENDING' THEN 1 END) AS sending, " +
        "COUNT(CASE WHEN state = 'FAILED' THEN 1 END) AS failed, " +
        "COUNT(CASE WHEN state = 'UNCERTAIN' THEN 1 END) AS uncertain, " +
        "COUNT(CASE WHEN state = 'SENT' THEN 1 END) AS sent, " +
        "COUNT(CASE WHEN state = 'SENT' AND envelopeJson NOT LIKE " +
            "'%\"sentCopyState\":\"CONFIRMED\"%' THEN 1 END) AS sentUnconfirmed, " +
        "COUNT(CASE WHEN envelopeJson LIKE '%\"sentCopyUploadPhase\":\"IN_FLIGHT\"%' " +
            "OR envelopeJson LIKE '%\"sentCopyUploadPhase\":\"UNCERTAIN\"%' " +
            "THEN 1 END) AS sentCopyNeedsReview FROM outbox")
    fun observeOutboxCounts(): Flow<OutboxCountRow>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun outboxEntry(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND messageId = :messageId LIMIT 1")
    suspend fun outboxByMessageId(accountId: String, messageId: String): OutboxEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE draftId = :messageId)")
    suspend fun hasOutboxForDraft(messageId: String): Boolean

    @Query("UPDATE outbox SET draftId = NULL WHERE draftId = :messageId")
    suspend fun detachOutbox(messageId: String)

    @Query(
        "SELECT * FROM messages WHERE folderId = :folderId AND uidValidity = :uidValidity AND uid = :uid LIMIT 1"
    )
    suspend fun messageByUid(folderId: String, uidValidity: Long, uid: Long): MessageEntity?

    @Insert suspend fun insertAccount(account: AccountEntity)

    @Update suspend fun updateAccount(account: AccountEntity): Int

    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): AccountEntity?

    @Query("DELETE FROM accounts WHERE id = :id") suspend fun removeAccount(id: String)

    @Upsert suspend fun saveFolders(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY rowid")
    suspend fun folders(accountId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): FolderEntity?

    @Query("UPDATE folders SET lastVisitedUid = :uid WHERE id = :id AND lastVisitedUid IS NULL")
    suspend fun initializeVisit(id: String, uid: Long)

    @Query("UPDATE folders SET lastVisitedUid = :uid WHERE id = :id")
    suspend fun saveVisit(id: String, uid: Long)

    @Query("UPDATE messages SET isNew = 0 WHERE folderId = :folderId")
    suspend fun clearNew(folderId: String)

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND role = :role LIMIT 1")
    suspend fun folderByRole(accountId: String, role: String): FolderEntity?

    @Query("DELETE FROM folders WHERE id = :id") suspend fun removeFolder(id: String)

    @Query(
        "UPDATE folders SET uidValidity = :uidValidity, uidNext = :uidNext, " +
            "serverUnreadCount = :unread, serverTotalCount = :total WHERE id = :id"
    )
    suspend fun updateFolderState(id: String, uidValidity: Long, uidNext: Long, unread: Int, total: Int)

    /** History observes server counts but must not move the recent-sync UIDNEXT boundary. */
    @Query("UPDATE folders SET uidValidity = :uidValidity, " +
        "serverUnreadCount = :unread, serverTotalCount = :total WHERE id = :id")
    suspend fun updateFolderHistoryStatus(id: String, uidValidity: Long, unread: Int, total: Int)

    @Query("DELETE FROM sync_cursors WHERE folderId = :folderId")
    suspend fun removeCursor(folderId: String)

    @Query("SELECT id FROM messages WHERE folderId = :folderId AND uid IS NOT NULL " +
        "AND (:afterId IS NULL OR id > :afterId) ORDER BY id LIMIT :limit")
    suspend fun remoteMessageIdsPage(folderId: String, afterId: String?, limit: Int): List<String>

    @Query("SELECT id, uid, receivedAt FROM messages WHERE folderId = :folderId " +
        "AND uidValidity = :uidValidity AND uid IS NOT NULL AND uid < :beforeUid " +
        "AND receivedAt >= :coarseSince AND bodyDownloaded = 0 " +
        "ORDER BY uid DESC LIMIT :limit")
    suspend fun recentBodyRefs(
        folderId: String, uidValidity: Long, beforeUid: Long, coarseSince: String, limit: Int,
    ): List<RemoteMessageRef>

    @Query("SELECT id, uid, receivedAt FROM messages WHERE folderId = :folderId " +
        "AND uidValidity = :uidValidity AND uid IS NOT NULL AND uid < :beforeUid " +
        "AND receivedAt >= :coarseSince AND " +
        "(lastSeenPassId IS NULL OR lastSeenPassId != :passId) " +
        "ORDER BY uid DESC LIMIT :limit")
    suspend fun staleRecentRefs(
        folderId: String, uidValidity: Long, beforeUid: Long, coarseSince: String,
        passId: String, limit: Int,
    ): List<RemoteMessageRef>

    @Query("SELECT MAX(uid) FROM messages WHERE folderId = :folderId")
    suspend fun highestCachedUid(folderId: String): Long?

    @Query("SELECT id FROM messages WHERE folderId = :folderId " +
        "AND (:afterId IS NULL OR id > :afterId) ORDER BY id LIMIT :limit")
    suspend fun messageIdsPage(folderId: String, afterId: String?, limit: Int): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE folderId = :folderId)")
    suspend fun hasMessages(folderId: String): Boolean

    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?

    @Query("SELECT id, uid, envelopeJson FROM messages WHERE accountId = :accountId AND draft = 1 " +
        "AND (:afterId IS NULL OR id > :afterId) ORDER BY id LIMIT :limit")
    suspend fun draftUploadPage(
        accountId: String, afterId: String?, limit: Int,
    ): List<DraftUploadRef>

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
        "UPDATE messages SET lastSeenPassId = :passId WHERE id IN (" +
            "SELECT m.id FROM messages m WHERE m.folderId = :folderId AND m.uid IS NULL " +
            "AND m.draft = 0 " +
            "AND NOT EXISTS (SELECT 1 FROM pending_operations p WHERE p.messageId = m.id " +
            "AND NOT (p.state = 'APPLIED' AND p.updatedAt < :appliedBefore)) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.draftId = m.id))"
    )
    suspend fun markSettledPlaceholders(folderId: String, appliedBefore: Long, passId: String)

    @Query(
        "SELECT m.id FROM messages m WHERE m.folderId = :folderId AND m.uid IS NULL " +
            "AND m.draft = 0 AND m.lastSeenPassId = :passId " +
            "AND (:afterId IS NULL OR m.id > :afterId) " +
            "AND NOT EXISTS (SELECT 1 FROM pending_operations p WHERE p.messageId = m.id " +
            "AND NOT (p.state = 'APPLIED' AND p.updatedAt < :appliedBefore)) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.draftId = m.id) " +
            "ORDER BY m.id LIMIT :limit"
    )
    suspend fun markedSettledPlaceholderIds(
        folderId: String, passId: String, appliedBefore: Long, afterId: String?, limit: Int,
    ): List<String>

    @Query(
        "SELECT m.id, m.envelopeJson FROM messages m WHERE m.folderId = :folderId " +
            "AND m.uid IS NULL AND m.draft = 0 " +
            "AND (:afterId IS NULL OR m.id > :afterId) " +
            "AND NOT EXISTS (SELECT 1 FROM pending_operations p WHERE p.messageId = m.id " +
            "AND NOT (p.state = 'APPLIED' AND p.updatedAt < :appliedBefore)) " +
            "AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.draftId = m.id) " +
            "ORDER BY m.id LIMIT :limit"
    )
    suspend fun settledPlaceholderPage(
        folderId: String, appliedBefore: Long, afterId: String?, limit: Int,
    ): List<PlaceholderHeaderRef>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY rowid")
    suspend fun attachments(messageId: String): List<AttachmentEntity>

    @Query("SELECT a.id, m.accountId FROM attachments a JOIN messages m ON m.id = a.messageId " +
        "WHERE a.cached = 0 AND a.partId IS NOT NULL " +
        "ORDER BY a.id LIMIT :limit")
    suspend fun firstUncachedRemoteAttachmentPage(limit: Int): List<AutomaticRemoteAttachmentRef>

    @Query("SELECT a.id, m.accountId FROM attachments a JOIN messages m ON m.id = a.messageId " +
        "WHERE a.cached = 0 AND a.partId IS NOT NULL AND a.id > :afterId " +
        "ORDER BY a.id LIMIT :limit")
    suspend fun nextUncachedRemoteAttachmentPage(afterId: String,
        limit: Int): List<AutomaticRemoteAttachmentRef>

    @Query("UPDATE attachments SET partId = NULL WHERE messageId = :messageId")
    suspend fun clearAttachmentPartIds(messageId: String)

    @Query("SELECT a.id, a.localFile FROM attachments a CROSS JOIN messages m " +
        "ON m.id = a.messageId WHERE m.accountId = :accountId " +
        "AND a.localFile IS NOT NULL ORDER BY a.id LIMIT :limit")
    suspend fun firstAccountAttachmentFilePage(accountId: String, limit: Int): List<AttachmentFileRef>

    @Query("SELECT a.id, a.localFile FROM attachments a CROSS JOIN messages m " +
        "ON m.id = a.messageId WHERE m.accountId = :accountId " +
        "AND a.localFile IS NOT NULL AND a.id > :afterId ORDER BY a.id LIMIT :limit")
    suspend fun nextAccountAttachmentFilePage(
        accountId: String, afterId: String, limit: Int,
    ): List<AttachmentFileRef>

    @Query("SELECT COUNT(*) FROM attachments WHERE localFile = :name")
    suspend fun attachmentFileReferences(name: String): Int

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

    @Query("SELECT COUNT(*) FROM pending_operations WHERE accountId = :accountId " +
        "AND mailbox = :mailbox AND kind = 'DELETE_DRAFT_BY_ID' " +
        "AND moveSourceMessageId = :headerId AND (state IN ('PENDING', 'IN_FLIGHT') " +
        "OR (state = 'APPLIED' AND updatedAt >= :appliedSince))")
    suspend fun activeDraftDeleteByMessageId(
        accountId: String, mailbox: String, headerId: String, appliedSince: Long,
    ): Int

    @Query("SELECT * FROM pending_operations WHERE accountId = :accountId AND state = :state " +
        "ORDER BY createdAt, id LIMIT :limit")
    suspend fun firstActiveOperationsPage(
        accountId: String, state: String, limit: Int,
    ): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE accountId = :accountId AND state = :state " +
        "AND (createdAt, id) > (:afterCreatedAt, :afterId) " +
        "ORDER BY createdAt, id LIMIT :limit")
    suspend fun nextActiveOperationsPage(
        accountId: String, state: String, afterCreatedAt: Long, afterId: String, limit: Int,
    ): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_operations WHERE id = :id")
    suspend fun operation(id: String): PendingOperationEntity?

    @Query("DELETE FROM pending_operations WHERE id = :id") suspend fun removeOperation(id: String)

    @Query(
        "DELETE FROM pending_operations WHERE accountId = :accountId AND mailbox = :mailbox " +
            "AND state = 'APPLIED' AND updatedAt < :before"
    )
    suspend fun purgeApplied(accountId: String, mailbox: String, before: Long)
}
