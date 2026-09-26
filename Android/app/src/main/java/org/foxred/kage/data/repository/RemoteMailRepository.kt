// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foxred.kage.core.account.*
import org.foxred.kage.data.local.*
import org.foxred.kage.data.sync.AccountSessions
import org.json.JSONObject

/**
 * Real-account mail state backed by the tested core protocol APIs.
 *
 * Room rows are the observable source of truth. Network calls run through [AccountSessions] and
 * never inside a database transaction; each committed step leaves a state that restart can resume.
 * Queued user intent (read, flag, move) wins over server state until the server confirms it.
 */
class RemoteMailRepository(
    private val db: MailDatabase,
    private val sessions: AccountSessions,
    private val credentials: CredentialStore,
    private val outbox: DurableOutbox,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.remoteMailDao()
    private val folderLocks = ConcurrentHashMap<String, Mutex>()
    private val queueLocks = ConcurrentHashMap<String, Mutex>()

    enum class OperationKind {
        READ,
        FLAG,
        MOVE,
    }

    /** Stored operation states. APPLIED rows shield later-committing stale server pages. */
    object OperationState {
        const val PENDING = "PENDING"
        const val IN_FLIGHT = "IN_FLIGHT"
        const val APPLIED = "APPLIED"
        const val FAILED = "FAILED"
    }

    data class FlushResult(val applied: Int, val failed: Int)

    suspend fun addAccount(
        account: Account,
        incoming: Authorization,
        outgoing: Authorization,
        initialMailboxes: List<org.foxred.kage.core.account.Mailbox> = emptyList(),
    ) =
        withContext(Dispatchers.IO) {
            val entity = CoreRoomMapper.account(account)
            require(dao.account(account.id) == null) { "Account already exists" }
            credentials.save(account.id, ServerProtocol.IMAP, incoming)
            credentials.save(account.id, ServerProtocol.SMTP, outgoing)
            try {
                db.withTransaction {
                    dao.insertAccount(entity)
                    dao.saveServers(account.servers.map { CoreRoomMapper.server(account.id, it) })
                    dao.saveFolders(initialMailboxes.map { CoreRoomMapper.folder(account.id, it) })
                }
            } catch (error: Throwable) {
                credentials.removeAccount(account.id)
                throw error
            }
        }

    suspend fun account(accountId: String): Account? =
        dao.account(accountId)?.let { CoreRoomMapper.account(it, dao.servers(accountId)) }

    suspend fun inboxFolder(accountId: String): String? = dao.folderByRole(accountId, "inbox")?.id

    /** Cancels active network work first so no in-flight response can recreate removed rows. */
    suspend fun removeAccount(accountId: String) =
        withContext(Dispatchers.IO) {
            sessions.close(accountId)
            credentials.removeAccount(accountId)
            val outgoing = outbox.entries(accountId)
            db.withTransaction { dao.removeAccount(accountId) }
            outbox.removeFiles(outgoing)
        }

    suspend fun refreshFolders(accountId: String) {
        val mailboxes = sessions.withStore(accountId) { it.mailboxes() }
        db.withTransaction {
            checkNotNull(dao.account(accountId)) { "Account was removed" }
            val existing = dao.folders(accountId).associateBy { it.id }
            val folders =
                mailboxes.map { mailbox ->
                    val fresh = CoreRoomMapper.folder(accountId, mailbox)
                    existing[fresh.id]?.let { old ->
                        fresh.copy(
                            uidValidity = old.uidValidity,
                            uidNext = old.uidNext,
                            lastVisitedUid = old.lastVisitedUid,
                            serverUnreadCount = fresh.serverUnreadCount ?: old.serverUnreadCount,
                            serverTotalCount = fresh.serverTotalCount ?: old.serverTotalCount,
                        )
                    } ?: fresh
                }
            dao.saveFolders(folders)
            val kept = folders.mapTo(HashSet()) { it.id }
            existing.values
                .filter { it.remotePath != null && it.id !in kept }
                .forEach { gone ->
                    dao.messageIds(gone.id).forEach { detach(it) }
                    dao.removeFolder(gone.id)
                }
        }
    }

    /**
     * Pages messages received on or after [since] into Room. Each page commits with its cursor;
     * an interrupted pass resumes where it stopped. After a completed pass, the next refresh only
     * pages UIDs above the previous pass's UIDNEXT, then continues any unfinished older range.
     * [full] re-reads the whole window so server flag changes on older messages are merged.
     * Server-side expunges and flag deltas are reconciled by incremental sync (T13).
     */
    suspend fun syncMessages(
        folderId: String,
        since: Instant,
        pageSize: Int = 100,
        full: Boolean = false,
    ): Int {
        require(pageSize in 1..1000)
        return folderLocks.computeIfAbsent(folderId) { Mutex() }.withLock {
            val start = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
            val path = requireNotNull(start.remotePath) { "Folder has no server mailbox" }
            val passStartedAt = now().toEpochMilli()
            val status = sessions.withStore(start.accountId) { it.status(path) }
            if (status.uidValidity < 1 || status.uidNext < 1)
                throw MailFailure(FailureKind.PROTOCOL, "Server did not report mailbox identity")
            val generation = status.uidValidity
            data class Segment(val from: Long, val floor: Long?)
            var lastCompletedAt: Long? = null
            var settle: Set<String> = emptySet()
            val segments =
                db.withTransaction {
                    val folder = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
                    if (folder.uidValidity != null && folder.uidValidity != generation)
                        resetGeneration(folder)
                    val cursor =
                        dao.cursor(folderId)?.takeIf {
                            !full &&
                                folder.uidValidity == generation &&
                                it.uidValidity == generation &&
                                it.sinceEpochMillis == since.toEpochMilli()
                        }
                    lastCompletedAt = dao.cursor(folderId)?.lastCompletedAt
                    // Every later page for this mailbox is fetched after this point, under the lock.
                    dao.purgeApplied(folder.accountId, path, passStartedAt)
                    // Moves confirmed before this pass began have server copies within its range.
                    settle = dao.settledPlaceholders(folderId, passStartedAt).mapTo(HashSet()) { it.id }
                    val top = folder.uidNext?.takeIf { cursor != null }?.coerceAtMost(status.uidNext)
                    if (top == null) listOf(Segment(status.uidNext, null))
                    else listOfNotNull(Segment(status.uidNext, top), cursor?.beforeUid?.let { Segment(it, null) })
                }
            var stored = 0
            for ((index, segment) in segments.withIndex()) {
                val floor = segment.floor ?: 1L
                var before = segment.from
                do {
                    val fetchedAt = now().toEpochMilli()
                    val page =
                        if (before <= floor) MessagePage(emptyList(), null)
                        else {
                            val limit =
                                if (segment.floor == null) pageSize
                                else minOf(pageSize.toLong(), before - floor).toInt()
                            val cursor = MessageCursor(path, generation, before, since)
                            sessions.withStore(start.accountId) {
                                it.messagePage(path, since, cursor, limit)
                            }
                        }
                    val lower = if (before <= floor) before else page.next?.beforeUid ?: 1L
                    check(lower < before || before <= floor) { "Server paging made no progress" }
                    val done = lower <= floor
                    val resumeAt = if (!done) lower else segments.getOrNull(index + 1)?.from
                    db.withTransaction {
                        val folder = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
                        check(folder.uidValidity == null || folder.uidValidity == generation) {
                            "Mailbox identity changed during sync"
                        }
                        val placeholders =
                            dao.settledPlaceholders(folderId, Long.MAX_VALUE).toMutableList()
                        page.messages.forEach {
                            require(it.identity?.uidValidity == generation) {
                                "Page belongs to another mailbox generation"
                            }
                            if (upsert(it, folder, placeholders, fetchedAt)) stored++
                        }
                        dao.updateFolderState(
                            folderId,
                            generation,
                            status.uidNext,
                            status.unreadCount,
                            status.messageCount,
                        )
                        if (resumeAt == null) lastCompletedAt = now().toEpochMilli()
                        dao.saveCursor(
                            SyncCursorEntity(
                                folderId,
                                generation,
                                resumeAt,
                                since.toEpochMilli(),
                                lastCompletedAt,
                            )
                        )
                        if (resumeAt == null)
                            dao.settledPlaceholders(folderId, Long.MAX_VALUE)
                                .filter { it.id in settle }
                                .forEach { detach(it.id) }
                    }
                    before = lower
                } while (!done)
            }
            stored
        }
    }

    /** Fetches text/HTML and attachment metadata for one cached message. */
    suspend fun downloadBody(messageId: String) {
        val folderId = checkNotNull(dao.message(messageId)) { "Message was removed" }.folderId
        folderLocks.computeIfAbsent(folderId) { Mutex() }.withLock {
            val row = checkNotNull(dao.message(messageId)) { "Message was removed" }
            val folder = checkNotNull(dao.folder(row.folderId)) { "Folder was removed" }
            val identity = remoteIdentity(row, folder)
            val fetchedAt = now().toEpochMilli()
            val email = sessions.withStore(folder.accountId) { it.message(identity) }
            db.withTransaction {
                val current = dao.message(messageId) ?: return@withTransaction
                val currentFolder = dao.folder(current.folderId) ?: return@withTransaction
                if (current.uid != identity.uid || current.uidValidity != identity.uidValidity)
                    return@withTransaction
                upsert(email, currentFolder, mutableListOf(), fetchedAt)
            }
        }
    }

    suspend fun markRead(messageId: String, read: Boolean) =
        enqueue(messageId, OperationKind.READ, read, null) { dao.setRead(messageId, read) }

    suspend fun flag(messageId: String, flagged: Boolean) =
        enqueue(messageId, OperationKind.FLAG, flagged, null) { dao.setFlagged(messageId, flagged) }

    /**
     * The local row moves immediately as a placeholder without a UID. The source folder will not
     * re-add it while the move is queued, and the target's server copy replaces it after sync.
     */
    suspend fun move(messageId: String, targetFolderId: String) {
        val target = checkNotNull(dao.folder(targetFolderId)) { "Folder was removed" }
        val targetPath = requireNotNull(target.remotePath) { "Folder has no server mailbox" }
        enqueue(messageId, OperationKind.MOVE, null, targetPath) { row ->
            require(target.accountId == row.accountId) { "Messages cannot move between accounts" }
            require(target.id != row.folderId) { "Message is already in this folder" }
            dao.relocate(messageId, target.id, null, null)
        }
    }

    private suspend fun enqueue(
        messageId: String,
        kind: OperationKind,
        desired: Boolean?,
        targetMailbox: String?,
        applyLocally: suspend (MessageEntity) -> Unit,
    ) =
        db.withTransaction {
            val row = checkNotNull(dao.message(messageId)) { "Message was removed" }
            val folder = checkNotNull(dao.folder(row.folderId)) { "Folder was removed" }
            val identity = remoteIdentity(row, folder)
            val timestamp = now().toEpochMilli()
            val queued =
                dao.activeOperations(row.accountId, identity.mailbox, identity.uidValidity, identity.uid, Long.MAX_VALUE)
                    .lastOrNull { it.kind == kind.name && it.state == OperationState.PENDING }
            applyLocally(row)
            dao.saveOperation(
                queued?.copy(desiredValue = desired, targetMailbox = targetMailbox, updatedAt = timestamp)
                    ?: PendingOperationEntity(
                        newId(),
                        row.accountId,
                        messageId,
                        identity.mailbox,
                        identity.uidValidity,
                        identity.uid,
                        kind.name,
                        desired,
                        targetMailbox,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    )
            )
        }

    /**
     * Sends queued intent in creation order. Connection-level failures stop the flush and keep the
     * remaining intent queued; server rejections mark only that operation failed and restore any
     * moved placeholder. An operation interrupted mid-flight is retried; for MOVE a rejection
     * after interruption is treated as possibly applied and left for target-folder sync.
     */
    suspend fun flushOperations(accountId: String): FlushResult =
        queueLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            val queued = dao.activeOperations(accountId)
            val interrupted =
                queued.filter { it.state == OperationState.IN_FLIGHT }.mapTo(HashSet()) { it.id }
            var applied = 0
            var failed = 0
            for (op in queued) {
                val started =
                    db.withTransaction {
                        dao.operation(op.id)
                            ?.takeIf { it.state == OperationState.PENDING || it.state == OperationState.IN_FLIGHT }
                            ?.also {
                                dao.saveOperation(
                                    it.copy(
                                        state = OperationState.IN_FLIGHT,
                                        attempts = it.attempts + 1,
                                        updatedAt = now().toEpochMilli(),
                                    )
                                )
                            }
                    } ?: continue
                val identity = MessageIdentity(started.mailbox, started.uidValidity, started.uid)
                try {
                    sessions.withStore(accountId) { store ->
                        when (OperationKind.valueOf(started.kind)) {
                            OperationKind.READ -> store.markRead(identity, requireNotNull(started.desiredValue))
                            OperationKind.FLAG -> store.flag(identity, requireNotNull(started.desiredValue))
                            OperationKind.MOVE -> store.move(identity, requireNotNull(started.targetMailbox))
                        }
                    }
                    finish(started.id, OperationState.APPLIED, null)
                    applied++
                } catch (failure: MailFailure) {
                    if (failure.kind in RETRYABLE) {
                        finish(started.id, OperationState.PENDING, failure.message)
                        throw failure
                    }
                    if (started.kind == OperationKind.MOVE.name && started.id in interrupted) {
                        finish(started.id, OperationState.APPLIED, failure.message)
                        applied++
                    } else {
                        reject(started, failure)
                        failed++
                    }
                }
            }
            FlushResult(applied, failed)
        }

    private suspend fun finish(id: String, state: String, error: String?) =
        db.withTransaction {
            dao.operation(id)?.let {
                dao.saveOperation(it.copy(state = state, lastError = error, updatedAt = now().toEpochMilli()))
            }
        }

    private suspend fun reject(op: PendingOperationEntity, failure: MailFailure) =
        db.withTransaction {
            val current = dao.operation(op.id) ?: return@withTransaction
            dao.saveOperation(
                current.copy(
                    state = OperationState.FAILED,
                    lastError = failure.message,
                    updatedAt = now().toEpochMilli(),
                )
            )
            val row = current.messageId?.let { dao.message(it) } ?: return@withTransaction
            if (current.kind != OperationKind.MOVE.name || row.uid != null) return@withTransaction
            val source = dao.folder(CoreRoomMapper.folderId(current.accountId, current.mailbox))
            if (
                source?.uidValidity == current.uidValidity &&
                    dao.messageByUid(source.id, current.uidValidity, current.uid) == null
            )
                dao.relocate(row.id, source.id, current.uidValidity, current.uid)
            else detach(row.id)
        }

    /** Returns whether the server copy was written; queued moves keep their local placeholder. */
    private suspend fun upsert(
        email: Email,
        folder: FolderEntity,
        placeholders: MutableList<MessageEntity>,
        fetchedAt: Long,
    ): Boolean {
        val identity = requireNotNull(email.identity) { "Server message has no identity" }
        val intent =
            dao.activeOperations(folder.accountId, identity.mailbox, identity.uidValidity, identity.uid, fetchedAt)
        if (intent.any { it.kind == OperationKind.MOVE.name }) return false
        val id = CoreRoomMapper.messageId(folder.id, identity)
        val existing = dao.message(id)
        var merged = email
        if (!email.bodyDownloaded && existing?.bodyDownloaded == true) {
            val cached = CoreRoomMapper.email(existing, folder, dao.attachments(id))
            merged = merged.copy(body = cached.body, attachments = cached.attachments, bodyDownloaded = true)
        }
        intent.lastOrNull { it.kind == OperationKind.READ.name }?.desiredValue?.let {
            merged = merged.copy(read = it)
        }
        intent.lastOrNull { it.kind == OperationKind.FLAG.name }?.desiredValue?.let {
            merged = merged.copy(flagged = it)
        }
        val replaced =
            if (existing != null || email.messageId == null) null
            else placeholders.firstOrNull { headerMessageId(it) == email.messageId }
        if (replaced != null) {
            placeholders.remove(replaced)
            detach(replaced.id)
        }
        dao.saveMessage(
            CoreRoomMapper.email(merged, folder)
                .copy(
                    pinned = existing?.pinned ?: replaced?.pinned ?: false,
                    isNew = existing?.isNew ?: false,
                    remoteEmailId = existing?.remoteEmailId,
                    rawMessagePath = existing?.rawMessagePath,
                )
        )
        if (email.bodyDownloaded) saveAttachments(id, email.attachments)
        return true
    }

    /** Server part metadata replaces stale parts; local download state for a kept part survives. */
    private suspend fun saveAttachments(messageId: String, attachments: List<EmailAttachment>) {
        val existing = dao.attachments(messageId).associateBy { it.id }
        val rows =
            attachments.map { attachment ->
                val fresh = CoreRoomMapper.attachment(messageId, attachment)
                existing[fresh.id]?.let {
                    fresh.copy(
                        cached = it.cached,
                        asset = it.asset,
                        localFile = it.localFile,
                        downloadState = it.downloadState,
                        downloadedBytes = it.downloadedBytes,
                    )
                } ?: fresh
            }
        dao.removeAttachmentsExcept(messageId, rows.map { it.id })
        dao.saveAttachments(rows)
    }

    /** A new UIDVALIDITY invalidates every cached UID; queued intent keeps its old identity. */
    private suspend fun resetGeneration(folder: FolderEntity) {
        dao.remoteMessageIds(folder.id).forEach { detach(it) }
        dao.removeCursor(folder.id)
        dao.saveFolders(listOf(folder.copy(uidValidity = null, uidNext = null)))
    }

    private suspend fun detach(messageId: String) {
        dao.detachOperations(messageId)
        dao.detachOutbox(messageId)
        dao.removeCachedMessage(messageId)
    }

    private fun remoteIdentity(row: MessageEntity, folder: FolderEntity): MessageIdentity {
        val path = folder.remotePath
        val uidValidity = row.uidValidity
        val uid = row.uid
        if (path == null || uidValidity == null || uid == null)
            throw IllegalStateException("Wait until this message is confirmed by the server")
        return MessageIdentity(path, uidValidity, uid)
    }

    private fun headerMessageId(row: MessageEntity): String? =
        JSONObject(row.envelopeJson).let { if (it.isNull("messageId")) null else it.getString("messageId") }

    companion object {
        private val RETRYABLE =
            setOf(FailureKind.CANCELLED, FailureKind.CONNECTION, FailureKind.AUTHENTICATION)

        /** Incoming server lookup for [AccountSessions]; configuration lives in Room, not secrets. */
        fun incomingServer(db: MailDatabase): suspend (String) -> Server = { accountId ->
            val row =
                db.remoteMailDao().servers(accountId).firstOrNull { it.protocol == ServerProtocol.IMAP.name }
                    ?: throw MailFailure(FailureKind.AUTHENTICATION, "Account has no incoming server")
            CoreRoomMapper.server(row)
        }
    }
}
