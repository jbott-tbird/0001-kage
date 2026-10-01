// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain.repository

import kotlinx.coroutines.flow.Flow
import org.foxred.kage.domain.model.*

interface MailRepository {
    val mailbox: Flow<Mailbox>
    val outbox: Flow<List<OutboxItem>>
    val outboxCounts: Flow<OutboxCounts>
    val messageListRevision: Flow<Long>
    val unreadCounts: Flow<Map<String, Long>>

    /** Load the selected message and its attachments independently of list paging. */
    fun observeMessage(id: String): Flow<Message?>

    suspend fun messagePage(
        folderIds: List<String>, cursor: MessagePageCursor?, oldestFirst: Boolean,
        limit: Int = 50,
    ): MessagePage

    suspend fun filteredPage(
        folderIds: List<String>, accountId: String?, query: MailQuery,
        cursor: MessagePageCursor?, oldestFirst: Boolean, limit: Int = 50,
    ): MessagePage

    suspend fun cacheCounts(): MailCacheCounts

    /** Observe one bounded Outbox page, newest rowids first. */
    fun observeOutboxPage(beforeRowId: Long = Long.MAX_VALUE,
        limit: Int = 50): Flow<OutboxPage>

    suspend fun initialize()

    suspend fun addAccount(account: Account)

    suspend fun removeAccount(id: String)
    suspend fun revokeAndRemoveGoogleAccount(id: String)

    suspend fun updatePreferences(preferences: Preferences)

    suspend fun markRead(id: String, read: Boolean)

    suspend fun flag(id: String, value: Boolean)

    suspend fun pin(id: String, value: Boolean)

    suspend fun move(id: String, role: String)

    suspend fun saveDraft(message: Message)

    suspend fun send(message: Message): SendDisposition

    suspend fun retryOutbox(id: String): String

    suspend fun deleteDraft(id: String, looseAttachments: List<Attachment> = emptyList())

    suspend fun importAttachment(messageId: String, sourceUri: String): Attachment

    /** Delete private imports that no saved message still references. */
    suspend fun cleanupLooseAttachments(attachments: List<Attachment>)

    suspend fun cacheAttachment(id: String, progress: (Long) -> Unit = {}): String

    suspend fun resetDemo()
}

enum class SendDisposition { DEMO_SAVED, QUEUED, SENDING, FAILED, UNCERTAIN, SENT }
