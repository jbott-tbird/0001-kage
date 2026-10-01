// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MailDao {
    @Query("SELECT * FROM accounts ORDER BY rowid") fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM folders ORDER BY rowid") fun folders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM messages ORDER BY receivedAt DESC")
    fun messages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM attachments") fun attachments(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM preferences WHERE id = 1") fun preferences(): Flow<PreferencesEntity?>

    @Query("SELECT COUNT(*) FROM preferences") suspend fun initialized(): Int

    @Query("SELECT * FROM preferences WHERE id = 1")
    suspend fun getPreferences(): PreferencesEntity?

    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: String): MessageEntity?

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun attachment(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE cached = 0")
    suspend fun uncached(): List<AttachmentEntity>

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
