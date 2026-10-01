// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Inbox fields only; bodies and attachment rows stay on disk until opened. */
data class MessageListRow(
    val id: String,
    val accountId: String,
    val folderId: String,
    val sender: String,
    val subject: String,
    val preview: String,
    val receivedAt: String,
    val isRead: Boolean,
    val isNew: Boolean,
    val flagged: Boolean,
    val pinned: Boolean,
    val draft: Boolean,
    val relatedGroup: String?,
    val bodyDownloaded: Boolean,
    val attachmentCount: Int,
)

data class FolderUnreadCount(val folderId: String, val unread: Long)

data class StoredMailCounts(
    val cachedMessages: Long,
    val downloadedBodies: Long,
    val cachedAttachments: Long,
    val drafts: Long,
)

@Dao
interface MailDao {
    @Query("SELECT * FROM accounts ORDER BY rowid") fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM folders ORDER BY rowid") fun folders(): Flow<List<FolderEntity>>

    /** The small sample mailbox stays in memory; real mail is read through list pages. */
    @Query("SELECT m.* FROM messages m JOIN accounts a ON a.id = m.accountId " +
        "WHERE a.mode = 'DEMO' ORDER BY m.receivedAt DESC")
    fun demoMessages(): Flow<List<MessageEntity>>

    /** Stable keyset paging includes equal timestamps without rereading prior rows. */
    @Query("SELECT m.id, m.accountId, m.folderId, m.sender, m.subject, m.preview, " +
        "m.receivedAt, m.isRead, m.isNew, m.flagged, m.pinned, m.draft, " +
        "m.relatedGroup, m.bodyDownloaded, " +
        "(SELECT COUNT(*) FROM attachments a WHERE a.messageId = m.id) AS attachmentCount " +
        "FROM messages m WHERE m.folderId IN (:folderIds) " +
        "AND (:beforeAt IS NULL OR m.receivedAt < :beforeAt OR " +
        "(m.receivedAt = :beforeAt AND m.id < :beforeId)) " +
        "ORDER BY m.receivedAt DESC, m.id DESC LIMIT :limit")
    suspend fun messageListPage(
        folderIds: List<String>, beforeAt: String?, beforeId: String?, limit: Int,
    ): List<MessageListRow>

    /** The composite folder/date index can seek directly to later pages. */
    @Query("SELECT m.id, m.accountId, m.folderId, m.sender, m.subject, m.preview, " +
        "m.receivedAt, m.isRead, m.isNew, m.flagged, m.pinned, m.draft, " +
        "m.relatedGroup, m.bodyDownloaded, " +
        "(SELECT COUNT(*) FROM attachments a WHERE a.messageId = m.id) AS attachmentCount " +
        "FROM messages m WHERE m.folderId IN (:folderIds) " +
        "AND (m.receivedAt, m.id) < (:beforeAt, :beforeId) " +
        "ORDER BY m.receivedAt DESC, m.id DESC LIMIT :limit")
    suspend fun messageListSeekPage(
        folderIds: List<String>, beforeAt: String, beforeId: String, limit: Int,
    ): List<MessageListRow>

    @Query("SELECT m.id, m.accountId, m.folderId, m.sender, m.subject, m.preview, " +
        "m.receivedAt, m.isRead, m.isNew, m.flagged, m.pinned, m.draft, " +
        "m.relatedGroup, m.bodyDownloaded, " +
        "(SELECT COUNT(*) FROM attachments a WHERE a.messageId = m.id) AS attachmentCount " +
        "FROM messages m WHERE m.folderId IN (:folderIds) " +
        "AND (:afterAt IS NULL OR m.receivedAt > :afterAt OR " +
        "(m.receivedAt = :afterAt AND m.id > :afterId)) " +
        "ORDER BY m.receivedAt ASC, m.id ASC LIMIT :limit")
    suspend fun oldestMessageListPage(
        folderIds: List<String>, afterAt: String?, afterId: String?, limit: Int,
    ): List<MessageListRow>

    @Query("SELECT m.id, m.accountId, m.folderId, m.sender, m.subject, m.preview, " +
        "m.receivedAt, m.isRead, m.isNew, m.flagged, m.pinned, m.draft, " +
        "m.relatedGroup, m.bodyDownloaded, " +
        "(SELECT COUNT(*) FROM attachments a WHERE a.messageId = m.id) AS attachmentCount " +
        "FROM messages m WHERE m.folderId IN (:folderIds) " +
        "AND (m.receivedAt, m.id) > (:afterAt, :afterId) " +
        "ORDER BY m.receivedAt ASC, m.id ASC LIMIT :limit")
    suspend fun oldestMessageListSeekPage(
        folderIds: List<String>, afterAt: String, afterId: String, limit: Int,
    ): List<MessageListRow>

    /** Search reads full text in small chunks, then returns only matching list summaries. */
    @Query("SELECT * FROM messages WHERE (:beforeAt IS NULL OR receivedAt < :beforeAt OR " +
        "(receivedAt = :beforeAt AND id < :beforeId)) " +
        "ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun searchScanPage(
        beforeAt: String?, beforeId: String?, limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE (receivedAt, id) < (:beforeAt, :beforeId) " +
        "ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun searchSeekPage(beforeAt: String, beforeId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE accountId = :accountId " +
        "AND (:beforeAt IS NULL OR receivedAt < :beforeAt OR " +
        "(receivedAt = :beforeAt AND id < :beforeId)) " +
        "ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun scopedSearchScanPage(
        accountId: String, beforeAt: String?, beforeId: String?, limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE accountId = :accountId " +
        "AND (receivedAt, id) < (:beforeAt, :beforeId) " +
        "ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun scopedSearchSeekPage(
        accountId: String, beforeAt: String, beforeId: String, limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE (:afterAt IS NULL OR receivedAt > :afterAt OR " +
        "(receivedAt = :afterAt AND id > :afterId)) " +
        "ORDER BY receivedAt ASC, id ASC LIMIT :limit")
    suspend fun oldestSearchScanPage(
        afterAt: String?, afterId: String?, limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE (receivedAt, id) > (:afterAt, :afterId) " +
        "ORDER BY receivedAt ASC, id ASC LIMIT :limit")
    suspend fun oldestSearchSeekPage(afterAt: String, afterId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE accountId = :accountId " +
        "AND (:afterAt IS NULL OR receivedAt > :afterAt OR " +
        "(receivedAt = :afterAt AND id > :afterId)) " +
        "ORDER BY receivedAt ASC, id ASC LIMIT :limit")
    suspend fun oldestScopedSearchScanPage(
        accountId: String, afterAt: String?, afterId: String?, limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE accountId = :accountId " +
        "AND (receivedAt, id) > (:afterAt, :afterId) " +
        "ORDER BY receivedAt ASC, id ASC LIMIT :limit")
    suspend fun oldestScopedSearchSeekPage(
        accountId: String, afterAt: String, afterId: String, limit: Int,
    ): List<MessageEntity>

    /** Room invalidates this on any list or attachment change; the value is only a trigger. */
    @Query("SELECT (SELECT COUNT(*) FROM messages) + (SELECT COUNT(*) FROM attachments)")
    fun messageListRevision(): Flow<Long>

    @Query("SELECT t.* FROM attachments t JOIN messages m ON m.id = t.messageId " +
        "JOIN accounts a ON a.id = m.accountId WHERE a.mode = 'DEMO'")
    fun demoAttachments(): Flow<List<AttachmentEntity>>

    @Query("SELECT folderId, COUNT(*) AS unread FROM messages WHERE isRead = 0 GROUP BY folderId")
    fun unreadCounts(): Flow<List<FolderUnreadCount>>

    @Query("SELECT (SELECT COUNT(*) FROM messages) AS cachedMessages, " +
        "(SELECT COUNT(*) FROM messages WHERE bodyDownloaded = 1) AS downloadedBodies, " +
        "(SELECT COUNT(*) FROM attachments WHERE cached = 1) AS cachedAttachments, " +
        "(SELECT COUNT(*) FROM messages WHERE draft = 1) AS drafts")
    suspend fun storedMailCounts(): StoredMailCounts

    @Query("SELECT * FROM preferences WHERE id = 1") fun preferences(): Flow<PreferencesEntity?>

    @Query("SELECT COUNT(*) FROM preferences") suspend fun initialized(): Int

    @Query("SELECT * FROM preferences WHERE id = 1")
    suspend fun getPreferences(): PreferencesEntity?

    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeMessage(id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY rowid")
    fun observeMessageAttachments(messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun attachment(id: String): AttachmentEntity?

    @Query("SELECT id FROM attachments WHERE cached = 0 AND partId IS NULL " +
        "ORDER BY id LIMIT :limit")
    suspend fun firstUncachedLocalIdsPage(limit: Int): List<String>

    @Query("SELECT id FROM attachments WHERE cached = 0 AND partId IS NULL " +
        "AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun nextUncachedLocalIdsPage(afterId: String, limit: Int): List<String>

    @Query("SELECT id FROM folders WHERE accountId = :accountId AND role = :role LIMIT 1")
    suspend fun folder(accountId: String, role: String): String?

    @Query("SELECT id FROM folders WHERE role = 'inbox' LIMIT 1") suspend fun firstInbox(): String?

    @Query("SELECT accountId FROM folders WHERE id = :folderId LIMIT 1")
    suspend fun folderAccountId(folderId: String): String?

    @Query("SELECT id FROM accounts WHERE mode = 'DEMO'")
    suspend fun demoAccountIds(): List<String>

    @Query("SELECT id FROM accounts WHERE mode = 'REAL'")
    suspend fun realAccountIds(): List<String>

    @Insert suspend fun insertAccounts(accounts: List<AccountEntity>)

    @Insert suspend fun insertFolders(folders: List<FolderEntity>)

    @Upsert suspend fun saveMessages(messages: List<MessageEntity>)

    @Upsert suspend fun saveAttachments(attachments: List<AttachmentEntity>)

    @Upsert suspend fun savePreferences(preferences: PreferencesEntity)

    @Query("UPDATE messages SET isRead = :read WHERE id = :id")
    suspend fun markRead(id: String, read: Boolean)

    @Query("UPDATE messages SET flagged = :value WHERE id = :id")
    suspend fun flag(id: String, value: Boolean)

    @Query("UPDATE messages SET pinned = :value WHERE id = :id")
    suspend fun pin(id: String, value: Boolean)

    @Query("UPDATE messages SET folderId = :folderId WHERE id = :id")
    suspend fun move(id: String, folderId: String)

    @Query("UPDATE attachments SET cached = 1 WHERE id = :id") suspend fun cached(id: String)

    @Query("DELETE FROM messages WHERE id = :id AND draft = 1") suspend fun deleteDraft(id: String)

    @Query("DELETE FROM attachments WHERE messageId = :id") suspend fun clearAttachments(id: String)

    @Query("DELETE FROM accounts WHERE id = :id") suspend fun removeAccount(id: String)

    @Query("DELETE FROM accounts") suspend fun clearAccounts()

    @Query("DELETE FROM preferences") suspend fun clearPreferences()
}
