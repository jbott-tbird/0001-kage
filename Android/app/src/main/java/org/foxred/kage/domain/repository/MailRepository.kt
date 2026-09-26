package org.foxred.kage.domain.repository

import kotlinx.coroutines.flow.Flow
import org.foxred.kage.domain.model.*

interface MailRepository {
    val mailbox: Flow<Mailbox>

    suspend fun initialize()

    suspend fun addAccount(account: Account)

    suspend fun removeAccount(id: String)

    suspend fun updatePreferences(preferences: Preferences)

    suspend fun markRead(id: String, read: Boolean)

    suspend fun flag(id: String, value: Boolean)

    suspend fun pin(id: String, value: Boolean)

    suspend fun move(id: String, role: String)

    suspend fun saveDraft(message: Message)

    suspend fun sendDemo(message: Message)

    suspend fun deleteDraft(id: String)

    suspend fun importAttachment(messageId: String, sourceUri: String): Attachment

    suspend fun cacheAttachment(id: String, progress: (Long) -> Unit = {}): String

    suspend fun resetDemo()
}
