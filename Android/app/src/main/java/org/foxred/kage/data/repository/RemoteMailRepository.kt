// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import org.foxred.kage.core.account.*
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.foxred.kage.data.local.*
import org.foxred.kage.data.sync.AccountSessions
import org.foxred.kage.data.security.validateGoogleMailAuthorization
import org.foxred.kage.data.security.GoogleOAuthGrantRevoker
import org.foxred.kage.data.security.OAuthGrantRevoker
import org.json.JSONObject

data class HistoryProgress(
    val folderId: String,
    val scannedMessages: Int,
    val estimatedTotal: Int,
    val scanComplete: Boolean,
    val remainingBodies: Int,
)

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
    private val refreshTimeoutMillis: Long = 300_000,
    transportFactory: () -> RawMailSubmission = { AngusSmtpClient() },
    private val authorization: suspend (String, ServerProtocol) -> Authorization? =
        { accountId, protocol -> credentials.authorization(accountId, protocol) },
    private val grantRevoker: OAuthGrantRevoker = GoogleOAuthGrantRevoker(),
    private val clearCachedAccessToken: suspend (String) -> Unit = {},
    private val onRejectedAuthorization: suspend (String, Authorization) -> Unit = { _, _ -> },
) {
    init { require(refreshTimeoutMillis > 0) }
    private val dao = db.remoteMailDao()
    private val folderLocks = ConcurrentHashMap<String, Mutex>()
    private val historyLocks = ConcurrentHashMap<String, Mutex>()
    private val attachmentLocks = Array(64) { Mutex() }
    private fun attachmentLock(id: String): Mutex =
        attachmentLocks[(id.hashCode() and Int.MAX_VALUE) % attachmentLocks.size]
    private val sentCopyLocks = ConcurrentHashMap<String, Mutex>()
    private val queueLocks = ConcurrentHashMap<String, Mutex>()
    private val draftLocks = ConcurrentHashMap<String, Mutex>()
    private val accountMutationLocks = ConcurrentHashMap<String, Mutex>()
    private val outboxSender = OutboxSender(db, outbox, credentials, transportFactory,
        authorization, sentCopyLocks, onRejectedAuthorization)
    private val draftCodec = AngusMimeCodec()

    private fun storageFailure(error: Exception, recovery: String): Exception =
        if (storageExhausted(error))
            MailFailure(FailureKind.LIMIT_EXCEEDED,
                "Device storage is full; free space and $recovery", error)
        else error

    suspend fun <T> withDraftLock(accountId: String, block: suspend () -> T): T =
        draftLocks.computeIfAbsent(accountId) { Mutex() }.withLock { block() }

    suspend fun queueOutgoing(accountId: String, email: OutgoingEmail, draftId: String? = null): OutboxEntity =
        outbox.enqueue(accountId, email, draftId)

    suspend fun flushOutgoing(accountId: String) {
        outboxSender.flush(accountId)
        reconcileSentBestEffort(accountId)
    }

    suspend fun cleanupOutboxFiles() = outbox.cleanupOrphanFiles()

    /** Recover private part files orphaned by expunges, UID resets or interrupted cleanup. */
    suspend fun cleanupOrphanRemoteAttachments() = withContext(Dispatchers.IO) {
        val directory = File(outbox.privateFilesDir, "attachments")
        if (!directory.isDirectory) return@withContext
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                currentCoroutineContext().ensureActive()
                val name = path.fileName.toString()
                if (!name.startsWith("remote-part-")) continue
                try {
                    attachmentLock(name).withLock {
                        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                            dao.attachmentFileReferences(name) == 0)
                            Files.deleteIfExists(path)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed query or delete leaves the file for the next launch.
                }
            }
        }
    }

    private suspend fun reconcileSentBestEffort(accountId: String) {
        try {
            reconcileSent(accountId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Delivery state is durable; an unavailable IMAP server leaves uncertainty visible.
        }
    }

    /** A server copy proves filing and delivery; absence never triggers an automatic resend. */
    suspend fun reconcileSent(accountId: String): Int =
        sentCopyLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            val path = dao.folderByRole(accountId, "sent")?.remotePath
            var confirmed = 0
            if (path != null) for (state in listOf(DurableOutbox.State.SENT,
                    DurableOutbox.State.UNCERTAIN)) {
                var afterRowId = 0L
                while (true) {
                    val page = outbox.statePage(accountId, state, afterRowId)
                    if (page.entries.isEmpty()) break
                    for (entry in page.entries) {
                        if (sentCopyStatus(entry) ==
                            org.foxred.kage.domain.model.SentCopyStatus.CONFIRMED) continue
                        val found = sessions.withStore(accountId) {
                            it.findByMessageId(path, entry.messageId)
                        }
                        if (found != null && found.mailbox == path && found.uidValidity > 0 &&
                            found.uid > 0 && outbox.confirmSentCopy(entry.id, found)) confirmed++
                    }
                    afterRowId = checkNotNull(page.afterRowId)
                }
            }
            cleanupConfirmedSentDrafts(accountId)
            confirmed
        }

    /** Explicit fallback for providers without an automatic Sent copy. Never resend SMTP. */
    suspend fun saveMissingSentCopy(
        outboxId: String, retryAfterReview: Boolean = false,
    ): Boolean {
        val accountId = checkNotNull(dao.outboxEntry(outboxId)) { "Outbox entry was removed" }.accountId
        return sentCopyLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            val entry = checkNotNull(dao.outboxEntry(outboxId)) { "Outbox entry was removed" }
            check(entry.state == DurableOutbox.State.SENT) { "SMTP delivery is not confirmed" }
            if (sentCopyStatus(entry) == org.foxred.kage.domain.model.SentCopyStatus.CONFIRMED)
                return@withLock true
            val account = checkNotNull(dao.account(accountId)) { "Account was removed" }
            check(!org.foxred.kage.domain.usecase.gmailAutomaticallyFilesSent(account.outgoing)) {
                "Gmail files Sent automatically; use Check Sent instead"
            }
            val path = requireNotNull(dao.folderByRole(accountId, "sent")?.remotePath) {
                "This account has no Sent folder"
            }
            suspend fun confirm(identity: MessageIdentity): Boolean {
                require(identity.mailbox == path && identity.uidValidity > 0 && identity.uid > 0) {
                    "Server returned an invalid Sent identity"
                }
                val confirmed = outbox.confirmSentCopy(outboxId, identity)
                if (confirmed) cleanupConfirmedSentDrafts(accountId)
                return confirmed
            }
            val existing = sessions.withStore(accountId) {
                it.findByMessageId(path, entry.messageId)
            }
            if (existing != null) return@withLock confirm(existing)
            if (!retryAfterReview && sentCopyUploadNeedsReview(entry)) return@withLock false
            val raw = outbox.raw(entry)
            val claimed = if (retryAfterReview) outbox.claimReviewedSentCopyUpload(outboxId)
                else outbox.claimSentCopyUpload(outboxId)
            check(claimed) {
                "Sent-copy upload is already awaiting review"
            }
            val appendStarted = AtomicBoolean(false)
            suspend fun settleInterruptedUpload(detail: String) = withContext(NonCancellable) {
                if (appendStarted.get()) outbox.markSentCopyUploadUncertain(outboxId, detail)
                else outbox.releaseUnstartedSentCopyUpload(outboxId, retryAfterReview)
            }
            val found = try {
                sessions.withStore(accountId) { store ->
                    store.findByMessageId(path, entry.messageId)
                        ?: run {
                            appendStarted.set(true)
                            store.append(path, raw, read = true)
                                ?: store.findByMessageId(path, entry.messageId)
                        }
                }
            } catch (cancelled: CancellationException) {
                settleInterruptedUpload(
                    "Sent-copy upload was interrupted; check Sent before trying again")
                throw cancelled
            } catch (failure: Exception) {
                settleInterruptedUpload(
                    "Sent-copy upload outcome is unknown; check Sent before trying again")
                throw failure
            }
            if (found == null) {
                outbox.markSentCopyUploadUncertain(outboxId,
                    "Sent-copy upload outcome is unknown; check Sent before trying again")
                false
            } else if (found.mailbox != path || found.uidValidity < 1 || found.uid < 1) {
                outbox.markSentCopyUploadUncertain(outboxId,
                    "Sent server returned an invalid copy identity; check Sent before trying again")
                throw MailFailure(FailureKind.PROTOCOL, "Server returned an invalid Sent identity")
            } else confirm(found)
        }
    }

    private suspend fun cleanupConfirmedSentDrafts(accountId: String) {
        var afterRowId = 0L
        while (true) {
            val page = outbox.statePage(accountId, DurableOutbox.State.SENT, afterRowId)
            if (page.entries.isEmpty()) break
            page.entries.filter {
                sentCopyStatus(it) == org.foxred.kage.domain.model.SentCopyStatus.CONFIRMED &&
                    it.draftId != null
            }.forEach { entry ->
                val draftId = checkNotNull(entry.draftId)
                val draft = dao.message(draftId)
                if (draft == null) {
                    dao.detachOutbox(draftId)
                    return@forEach
                }
                val submittedRevision = JSONObject(entry.envelopeJson).optString("draftRevision")
                val currentRevision = JSONObject(draft.envelopeJson).optString("draftEditRevision")
                if (submittedRevision.isBlank() || submittedRevision != currentRevision) {
                    dao.detachOutbox(draftId)
                    return@forEach
                }
                try {
                    discardDraft(draftId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the draft and its link for the next foreground reconciliation.
                }
            }
            afterRowId = checkNotNull(page.afterRowId)
        }
    }

    /** Uploads locally edited drafts. An interrupted APPEND is searched, never blindly repeated. */
    suspend fun flushDrafts(accountId: String): Int = withDraftLock(accountId) {
        val path = dao.folderByRole(accountId, "drafts")?.remotePath ?: return@withDraftLock 0
        var uploaded = 0
        var afterId: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val drafts = dao.draftUploadPage(accountId, afterId, 64)
            for (candidate in drafts) {
                afterId = candidate.id
                if (candidate.uid != null &&
                    !JSONObject(candidate.envelopeJson).optBoolean("localDraftDirty")) continue
                if (dao.hasOutboxForDraft(candidate.id)) continue
                var passes = 0
                while (passes++ < 4) {
                    val current = dao.message(candidate.id)?.takeIf(::locallyEditedDraft) ?: break
                    try {
                        if (!uploadDraftOnce(current, path)) break
                        uploaded++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        updateDraftMetadata(candidate.id) {
                            if (it.optString("draftUploadPhase") == "IN_FLIGHT")
                                it.put("draftUploadPhase", "UNCERTAIN")
                            it.put("draftUploadError", failure.message ?: "Draft sync failed")
                        }
                        break
                    }
                }
            }
        } while (drafts.size == 64)
        uploaded
    }

    /** User-approved retry after the earlier APPEND result could not be established. */
    suspend fun retryUncertainDraft(messageId: String): Int {
        val accountId = checkNotNull(dao.message(messageId)) { "Draft was removed" }.accountId
        withDraftLock(accountId) {
            val row = checkNotNull(dao.message(messageId)) { "Draft was removed" }
            val metadata = JSONObject(row.envelopeJson)
            check(metadata.optString("draftUploadPhase") == "UNCERTAIN") {
                "This draft has no uncertain upload to retry"
            }
            val path = requireNotNull(dao.folder(row.folderId)?.remotePath) {
                "Draft mailbox is unavailable"
            }
            val uploadId = metadata.getString("draftUploadMessageId")
            val found = sessions.withStore(accountId) { it.findByMessageId(path, uploadId) }
            if (found == null) updateDraftMetadata(messageId) {
                it.put("draftUploadPhase", "PENDING")
                    .put("draftUploadRevision", it.optString("draftEditRevision"))
                    .remove("draftUploadError")
            }
        }
        return flushDrafts(accountId)
    }

    /** True after one saved revision is confirmed on the server and its old UID is removed. */
    private suspend fun uploadDraftOnce(draft: MessageEntity, path: String): Boolean {
        var row = draft
        var metadata = JSONObject(row.envelopeJson)
        var uploadId = metadata.optString("draftUploadMessageId")
        if (uploadId.isBlank()) {
            val domain = checkNotNull(dao.account(row.accountId)) { "Account was removed" }
                .address.substringAfter('@')
            uploadId = "<draft-${newId().replace(Regex("[^A-Za-z0-9-]"), "_")}@$domain>"
            val version = metadata.optString("draftEditRevision").ifBlank { newId() }
            row = checkNotNull(updateDraftMetadata(row.id) {
                it.put("draftEditRevision", version)
                    .put("draftUploadRevision", version)
                    .put("draftUploadMessageId", uploadId)
                    .put("draftUploadMailbox", path)
                    .put("draftUploadPhase", "PENDING")
                    .remove("draftUploadError")
            })
            metadata = JSONObject(row.envelopeJson)
        }
        val phase = metadata.optString("draftUploadPhase", "PENDING")
        val found = when (phase) {
            "PENDING" -> {
                // Build before claiming the network attempt. Invalid partial addresses stay local.
                val raw = draftMime(row, uploadId)
                val existing = sessions.withStore(row.accountId) {
                    it.findByMessageId(path, uploadId)
                }
                if (existing != null) existing else {
                    updateDraftMetadata(row.id) { it.put("draftUploadPhase", "IN_FLIGHT") }
                    sessions.withStore(row.accountId) { store ->
                        store.append(path, raw, read = true, draft = true)
                            ?: store.findByMessageId(path, uploadId)
                    }
                }
            }
            "IN_FLIGHT", "UNCERTAIN" -> sessions.withStore(row.accountId) {
                it.findByMessageId(path, uploadId)
            }
            "APPENDED" -> sessions.withStore(row.accountId) { store ->
                val saved = MessageIdentity(path,
                    metadata.getLong("draftUploadedUidValidity"), metadata.getLong("draftUploadedUid"))
                if (store.status(path).uidValidity == saved.uidValidity && store.exists(saved)) saved
                else store.findByMessageId(path, uploadId)
            }
            else -> error("Unknown draft upload phase")
        }
        if (found == null) {
            updateDraftMetadata(row.id) { it.put("draftUploadPhase", "UNCERTAIN") }
            return false
        }
        require(found.mailbox == path && found.uidValidity > 0 && found.uid > 0) {
            "Draft APPEND returned an invalid server identity"
        }
        updateDraftMetadata(row.id) {
            it.put("draftUploadPhase", "APPENDED")
                .put("draftUploadedUidValidity", found.uidValidity)
                .put("draftUploadedUid", found.uid)
                .remove("draftUploadError")
        }
        row = checkNotNull(dao.message(row.id))
        val oldValidity = row.uidValidity
        val oldUid = row.uid
        val old = if (oldValidity != null && oldUid != null)
            MessageIdentity(path, oldValidity, oldUid) else null
        if (old != null && old != found) sessions.withStore(row.accountId) { store ->
            if (store.status(path).uidValidity == old.uidValidity && store.exists(old))
                store.delete(old)
        }
        db.withTransaction {
            val current = dao.message(row.id) ?: return@withTransaction
            val saved = JSONObject(current.envelopeJson)
            if (saved.optString("draftUploadMessageId") != uploadId) return@withTransaction
            val uploadedRevision = saved.optString("draftUploadRevision")
            saved.put("messageId", uploadId)
                .put("localDraftDirty", saved.optString("draftEditRevision") != uploadedRevision)
            listOf("draftUploadRevision", "draftUploadMessageId", "draftUploadMailbox",
                "draftUploadPhase",
                "draftUploadedUidValidity", "draftUploadedUid", "draftUploadError")
                .forEach(saved::remove)
            dao.saveMessage(current.copy(uidValidity = found.uidValidity, uid = found.uid,
                envelopeJson = saved.toString(), rawMessagePath = null))
            dao.clearAttachmentPartIds(current.id)
        }
        return true
    }

    private suspend fun updateDraftMetadata(
        id: String, change: (JSONObject) -> Unit,
    ): MessageEntity? = db.withTransaction {
        val row = dao.message(id)?.takeIf { it.draft } ?: return@withTransaction null
        val metadata = JSONObject(row.envelopeJson)
        change(metadata)
        row.copy(envelopeJson = metadata.toString()).also { dao.saveMessage(it) }
    }

    private suspend fun draftMime(row: MessageEntity, messageId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val account = checkNotNull(dao.account(row.accountId)) { "Account was removed" }
            require(row.senderAddress.equals(account.address, ignoreCase = true)) {
                "Use this account's saved sender address before syncing the draft"
            }
            val directory = File(outbox.privateFilesDir, "attachments").canonicalFile
            var bytes = 0L
            val attachments = dao.attachments(row.id).map { part ->
                val name = checkNotNull(part.localFile) {
                    "Download ${part.filename} before syncing the draft"
                }
                val file = File(directory, name).canonicalFile
                require(file.parentFile == directory && file.isFile) {
                    "Attach ${part.filename} again before syncing the draft"
                }
                bytes += file.length()
                require(bytes <= 25L * 1024 * 1024) { "Draft attachments exceed 25 MB" }
                OutgoingAttachment(part.filename, part.mimeType, file.readBytes())
            }
            val source = row.domain(emptyList())
            draftCodec.encodeDraft(OutgoingEmail(
                messageId, EmailAddress(account.address, account.name),
                parseRecipientAddresses(row.to), parseRecipientAddresses(row.cc),
                parseRecipientAddresses(row.bcc),
                row.subject, EmailBody(row.body, row.html), attachments,
                inReplyTo = source.inReplyTo, references = source.references,
            ))
        }

    suspend fun recoverOutgoing(accountId: String) = outboxSender.recover(accountId)

    suspend fun outgoing(accountId: String): List<OutboxEntity> = outbox.entries(accountId)

    suspend fun retryOutgoing(id: String) = outbox.retryFailed(id)

    enum class OperationKind {
        READ,
        FLAG,
        MOVE,
        DELETE_DRAFT,
        DELETE_DRAFT_BY_ID,
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
            if (account.incomingServer.authenticationType == AuthenticationType.OAUTH2 ||
                account.outgoingServer.authenticationType == AuthenticationType.OAUTH2 ||
                incoming.kind == Authorization.Kind.OAUTH2 ||
                outgoing.kind == Authorization.Kind.OAUTH2)
                validateGoogleMailAuthorization(account, incoming, outgoing)
            require(dao.account(account.id) == null) { "Account already exists" }
            var databaseAttempted = false
            try {
                credentials.save(account.id, ServerProtocol.IMAP, incoming)
                credentials.save(account.id, ServerProtocol.SMTP, outgoing)
                databaseAttempted = true
                db.withTransaction {
                    dao.insertAccount(entity)
                    dao.saveServers(account.servers.map { CoreRoomMapper.server(account.id, it) })
                    dao.saveFolders(CoreRoomMapper.folderTree(account.id, initialMailboxes))
                    // Account creation and onboarding completion must survive together.
                    val mailDao = db.mailDao()
                    val preferences = mailDao.getPreferences()
                        ?: org.foxred.kage.data.local.PreferencesEntity()
                    mailDao.savePreferences(preferences.copy(started = true))
                }
            } catch (error: Throwable) {
                // Room may commit before cancellation reaches this continuation. Keep credentials
                // if the account row may now depend on them; otherwise remove partial saves.
                withContext(NonCancellable) {
                    val mayHaveCommitted = databaseAttempted && try {
                        dao.account(account.id) != null
                    } catch (_: Throwable) {
                        true
                    }
                    if (!mayHaveCommitted) try {
                        credentials.removeAccount(account.id)
                    } catch (cleanupFailure: Throwable) {
                        error.addSuppressed(cleanupFailure)
                    }
                }
                throw error
            }
        }

    suspend fun account(accountId: String): Account? =
        dao.account(accountId)?.let { CoreRoomMapper.account(it, dao.servers(accountId)) }

    /** Replace credentials on the same account identity so cached mail and pending work survive. */
    suspend fun replaceAccountAuthorization(
        accountId: String,
        incoming: Authorization,
        outgoing: Authorization,
        oauthConfiguration: OAuthConfiguration? = null,
    ) = withContext(Dispatchers.IO) {
        accountMutationLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            val stored = checkNotNull(dao.account(accountId)) { "Account was removed" }
            check(stored.mode == "REAL") { "Only real accounts have server credentials" }
            val account = CoreRoomMapper.account(stored, dao.servers(accountId))
            require(incoming.kind == outgoing.kind && incoming.kind != Authorization.Kind.NONE) {
                "Use the same sign-in method for IMAP and SMTP"
            }
            val authenticationType = when (incoming.kind) {
                Authorization.Kind.APP_PASSWORD -> {
                    require(oauthConfiguration == null)
                    AuthenticationType.PASSWORD
                }
                Authorization.Kind.OAUTH2 -> {
                    requireNotNull(oauthConfiguration) {
                        "Google authorization settings are required"
                    }
                    AuthenticationType.OAUTH2
                }
                Authorization.Kind.NONE -> error("Unauthenticated accounts cannot be switched")
            }
            val updated = account.copy(
                incomingServer = account.incomingServer.copy(authenticationType = authenticationType),
                outgoingServer = account.outgoingServer.copy(authenticationType = authenticationType),
                authConfig = oauthConfiguration,
            )
            if (authenticationType == AuthenticationType.OAUTH2)
                validateGoogleMailAuthorization(updated, incoming, outgoing)
            outboxSender.withDeliveryPaused(accountId) {
                sessions.withDisconnected(accountId) {
                    val oldIncoming = checkNotNull(credentials.authorization(accountId,
                        ServerProtocol.IMAP)) { "Existing IMAP credential is unavailable" }
                    val oldOutgoing = checkNotNull(credentials.authorization(accountId,
                        ServerProtocol.SMTP)) { "Existing SMTP credential is unavailable" }
                    // The two Keystore files and Room cannot share a transaction. Keep the
                    // existing account/cache intact and restore credentials on ordinary failures.
                    withContext(NonCancellable) {
                        try {
                            credentials.save(accountId, ServerProtocol.IMAP, incoming)
                            credentials.save(accountId, ServerProtocol.SMTP, outgoing)
                            db.withTransaction {
                                checkNotNull(dao.account(accountId)) { "Account was removed" }
                                check(dao.updateAccount(CoreRoomMapper.account(updated)) == 1) {
                                    "Account was removed during sign-in change"
                                }
                                check(dao.updateServers(updated.servers.map {
                                    CoreRoomMapper.server(accountId, it)
                                }) == 2) { "Account servers changed during sign-in change" }
                            }
                        } catch (failure: Throwable) {
                            runCatching { credentials.save(accountId, ServerProtocol.IMAP, oldIncoming) }
                                .exceptionOrNull()?.let(failure::addSuppressed)
                            runCatching { credentials.save(accountId, ServerProtocol.SMTP, oldOutgoing) }
                                .exceptionOrNull()?.let(failure::addSuppressed)
                            throw failure
                        }
                    }
                }
            }
        }
    }

    suspend fun inboxFolder(accountId: String): String? = dao.folderByRole(accountId, "inbox")?.id

    suspend fun isRemoteFolder(folderId: String): Boolean = dao.folder(folderId)?.remotePath != null

    suspend fun isRealMessage(messageId: String): Boolean = dao.message(messageId)?.let {
        dao.account(it.accountId)?.mode == "REAL"
    } ?: false

    suspend fun moveToRole(messageId: String, role: String) {
        val row = checkNotNull(dao.message(messageId)) { "Message was removed" }
        val target = checkNotNull(dao.folderByRole(row.accountId, role)) {
            "This account has no $role folder"
        }
        move(messageId, target.id)
    }

    /** A visible-folder refresh discovers current paths before paging the selected mailbox. */
    suspend fun refreshVisibleFolder(
        folderId: String, since: Instant, full: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): Int =
        try {
            withTimeout(refreshTimeoutMillis) {
                val folder = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
                requireNotNull(folder.remotePath) { "Folder has no server mailbox" }
                onProgress("Checking folders…")
                refreshFolders(folder.accountId)
                checkNotNull(dao.folder(folderId)) { "Folder was removed on the server" }
                // Reconnect and apply durable user intent before merging the server's state.
                onProgress("Uploading pending changes…")
                flushOperations(folder.accountId)
                flushDrafts(folder.accountId)
                val synced = syncMessages(folderId, since, full = full,
                    reconcile = true, hydrateBodies = true, onProgress = onProgress)
                onProgress("Checking sent mail…")
                reconcileSentBestEffort(folder.accountId)
                synced
            }
        } catch (timeout: TimeoutCancellationException) {
            throw MailFailure(FailureKind.CONNECTION, "Mailbox refresh timed out")
        } catch (error: Exception) {
            throw storageFailure(error, "retry")
        }

    /** Leaving a folder records the largest UID shown and clears its local new markers. */
    suspend fun finishVisit(folderId: String) = db.withTransaction {
        val folder = dao.folder(folderId) ?: return@withTransaction
        if (folder.remotePath == null) return@withTransaction
        val high = maxOf(folder.lastVisitedUid ?: 0L, dao.highestCachedUid(folderId) ?: 0L)
        dao.saveVisit(folderId, high)
        dao.clearNew(folderId)
    }

    /** Cancels active network work first so no in-flight response can recreate removed rows. */
    suspend fun removeAccount(accountId: String) =
        withContext(Dispatchers.IO) {
            accountMutationLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
                outboxSender.withDeliveryPaused(accountId) {
                    sessions.withDisconnected(accountId) {
                        removeAccountLocked(accountId)
                    }
                }
            }
        }

    /** Keep local mail intact if Google revocation fails, so the user can retry online. */
    suspend fun revokeAndRemoveGoogleAccount(accountId: String) = withContext(Dispatchers.IO) {
        accountMutationLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            val stored = checkNotNull(dao.account(accountId)) { "Account was removed" }
            check(stored.mode == "REAL" && stored.oauthConfigurationJson != null) {
                "This account does not use Google authorization"
            }
            val incoming = checkNotNull(authorization(accountId, ServerProtocol.IMAP)) {
                "Google authorization is unavailable; remove this account from the device instead"
            }
            val outgoing = checkNotNull(authorization(accountId, ServerProtocol.SMTP)) {
                "Google authorization is unavailable; remove this account from the device instead"
            }
            validateGoogleMailAuthorization(
                CoreRoomMapper.account(stored, dao.servers(accountId)), incoming, outgoing)
            outboxSender.withDeliveryPaused(accountId) {
                sessions.withDisconnected(accountId) {
                    currentCoroutineContext().ensureActive()
                    val cutoff = Instant.now().plusSeconds(30)
                    val tokens = listOf(incoming, outgoing).map { grant ->
                        grant.refreshToken?.takeIf(String::isNotBlank) ?: run {
                            check(grant.expiresAt?.isAfter(cutoff) == true) {
                                "Google authorization expired; sign in again before revoking, or remove this account from the device"
                            }
                            grant.secret
                        }
                    }.distinct()
                    // Once revocation begins, finish local cleanup even if the UI leaves.
                    withContext(NonCancellable) {
                        tokens.forEach(grantRevoker::revoke)
                        // Revocation is authoritative even if the SDK cache cannot be cleared.
                        listOf(incoming.secret, outgoing.secret).distinct().forEach { token ->
                            runCatching { clearCachedAccessToken(token) }
                        }
                        removeAccountLocked(accountId)
                    }
                }
            }
        }
    }

    /** The caller has paused delivery and disconnected IMAP before entering this block. */
    private suspend fun removeAccountLocked(accountId: String) = withContext(NonCancellable) {
        val oldIncoming = runCatching { credentials.authorization(accountId, ServerProtocol.IMAP) }.getOrNull()
        val oldOutgoing = runCatching { credentials.authorization(accountId, ServerProtocol.SMTP) }.getOrNull()
        var databaseAttempted = false
        try {
            credentials.removeAccount(accountId)
            databaseAttempted = true
            db.withTransaction { dao.removeAccount(accountId) }
        } catch (failure: Throwable) {
            // Restore readable credentials only when the account row remains. If the row cannot
            // be checked, keep secrets removed rather than leave credentials for a deleted account.
            val rowRemains = !databaseAttempted || try {
                dao.account(accountId) != null
            } catch (_: Throwable) {
                false
            }
            if (rowRemains) {
                oldIncoming?.let { authorization ->
                    runCatching { credentials.save(accountId, ServerProtocol.IMAP, authorization) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
                oldOutgoing?.let { authorization ->
                    runCatching { credentials.save(accountId, ServerProtocol.SMTP, authorization) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
        outbox.cleanupOrphanFiles()
    }

    suspend fun refreshFolders(accountId: String) {
        val mailboxes = sessions.withStore(accountId) { store ->
            store.namespaces()
            store.mailboxes()
        }
        // An incomplete LIST must not be interpreted as deletion of every cached folder.
        if (mailboxes.none { it.role == MailboxRole.INBOX && it.selectable })
            throw MailFailure(FailureKind.PROTOCOL,
                "Server folder list did not include a selectable Inbox")
        db.withTransaction {
            checkNotNull(dao.account(accountId)) { "Account was removed" }
            val existing = dao.folders(accountId).associateBy { it.id }
            val folders =
                CoreRoomMapper.folderTree(accountId, mailboxes).map { fresh ->
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
                .filter { it.id !in kept }
                .forEach { gone ->
                    // A successful LIST can still omit a folder temporarily. Keep downloaded
                    // mail and its generation until a later LIST can confirm it again.
                    if (!dao.hasMessages(gone.id)) dao.removeFolder(gone.id)
                    else dao.saveFolders(listOf(gone.copy(remotePath = null,
                        serverUnreadCount = null, serverTotalCount = null)))
                }
        }
    }

    /**
     * Pages messages received on or after [since] into Room. Each page commits with its cursor;
     * an interrupted pass resumes where it stopped. After a completed pass, the next refresh only
     * pages UIDs above the previous pass's UIDNEXT, then continues any unfinished older range.
     * [full] re-reads the whole window so server flag changes on older messages are merged.
     * A visible refresh uses QRESYNC deltas when available, otherwise a resumable full scan.
     */
    suspend fun syncMessages(
        folderId: String,
        since: Instant,
        pageSize: Int = 100,
        full: Boolean = false,
        reconcile: Boolean = false,
        hydrateBodies: Boolean = false,
        onProgress: (String) -> Unit = {},
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
            // Room stores server timestamps as UTC ISO strings. Use an earlier coarse bound
            // for SQL, then check the exact instant in Kotlin at the rolling-window edge.
            val coarseSince = org.foxred.kage.data.local.mailTimestampKey(
                since.minusSeconds(86_400))
            val previous = dao.cursor(folderId)?.takeIf {
                start.uidValidity == generation && it.uidValidity == generation &&
                    it.sinceEpochMillis <= since.toEpochMilli()
            }
            val changes = if (reconcile && !full && previous?.beforeUid == null &&
                previous?.highestModSeq != null && previous.fullPassId == null)
                readChanges(start.accountId, path, generation, previous.highestModSeq)
            else null
            val scanFully = full || previous?.fullPassId != null || (reconcile && changes == null)
            val scanToken = if (scanFully)
                if (previous?.fullPassId != null && previous.beforeUid != null)
                    previous.highestModSeq
                else readChanges(start.accountId, path, generation, null)?.highestModSeq
            else null
            data class Segment(val from: Long, val floor: Long?)
            var lastCompletedAt: Long? = null
            val settlePassId = "settle:${UUID.randomUUID()}"
            var fullPassId: String? = null
            val segments =
                db.withTransaction {
                    val folder = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
                    if (folder.uidValidity != null && folder.uidValidity != generation)
                        resetGeneration(folder)
                    val cursor =
                        dao.cursor(folderId)?.takeIf {
                            folder.uidValidity == generation &&
                                it.uidValidity == generation &&
                                // A rolling 30-day cutoff only moves forward. Its earlier
                                // checkpoint has already scanned a superset of today's range.
                                it.sinceEpochMillis <= since.toEpochMilli() &&
                            if (scanFully) it.fullPassId != null && it.beforeUid != null
                                else it.fullPassId == null
                        }
                    if (scanFully) fullPassId = cursor?.fullPassId ?: newId()
                    lastCompletedAt = dao.cursor(folderId)?.lastCompletedAt
                    // Every later page for this mailbox is fetched after this point, under the lock.
                    // A QRESYNC delta was read before this transaction; keep confirmed intent
                    // until it has been merged so an older response cannot undo it.
                    if (changes == null) dao.purgeApplied(folder.accountId, path, passStartedAt)
                    // Snapshot eligible placeholders without retaining every ID in memory.
                    dao.markSettledPlaceholders(folderId, passStartedAt, settlePassId)
                    val top = folder.uidNext?.takeIf { cursor != null }?.coerceAtMost(status.uidNext)
                    if (top == null) listOf(Segment(status.uidNext, null))
                    else listOfNotNull(Segment(status.uidNext, top), cursor?.beforeUid?.let { Segment(it, null) })
                }
            var stored = 0
            onProgress("Checking message headers…")
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
                            val cursor = MessageCursor(path, generation, before, since, floor)
                            sessions.withStore(start.accountId) {
                                it.messagePage(path, since, cursor, limit)
                            }.also { validateServerPage(it, cursor, limit) }
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
                        val placeholders = placeholdersForPage(folderId, page.messages)
                        page.messages.forEach {
                            require(it.identity?.uidValidity == generation) {
                                "Page belongs to another mailbox generation"
                            }
                            if (upsert(it, folder, placeholders, fetchedAt, fullPassId)) stored++
                        }
                        dao.updateFolderState(
                            folderId,
                            generation,
                            status.uidNext,
                            status.unreadCount,
                            status.messageCount,
                        )
                        if (resumeAt == null) lastCompletedAt = now().toEpochMilli()
                        if (resumeAt == null) dao.initializeVisit(folderId, status.uidNext - 1)
                        if (resumeAt == null && fullPassId != null) {
                            val completedPass = checkNotNull(fullPassId)
                            var beforeUid = status.uidNext
                            while (true) {
                                val refs = dao.staleRecentRefs(folderId, generation, beforeUid,
                                    coarseSince, completedPass, pageSize)
                                if (refs.isEmpty()) break
                                refs.filter { ref ->
                                    runCatching { !Instant.parse(ref.receivedAt).isBefore(since) }
                                        .getOrDefault(false)
                                }.forEach { detach(it.id) }
                                beforeUid = checkNotNull(refs.last().uid)
                            }
                        }
                        dao.saveCursor(
                            SyncCursorEntity(
                                folderId,
                                generation,
                                resumeAt,
                                since.toEpochMilli(),
                                lastCompletedAt,
                                fullPassId = if (resumeAt == null) null else fullPassId,
                                highestModSeq = if (scanFully) scanToken else previous?.highestModSeq,
                            )
                        )
                        if (resumeAt == null) {
                            var afterId: String? = null
                            while (true) {
                                val ids = dao.markedSettledPlaceholderIds(folderId,
                                    settlePassId, Long.MAX_VALUE, afterId, pageSize)
                                if (ids.isEmpty()) break
                                ids.forEach { detach(it) }
                                afterId = ids.last()
                            }
                        }
                    }
                    onProgress("$stored message headers saved")
                    before = lower
                } while (!done)
            }
            if (changes != null) applyChanges(folderId, generation, path, changes, passStartedAt)
            if (hydrateBodies) {
                // Count the same pending-body window we will fetch, in bounded pages.
                // The server's folder total also includes old and already-cached mail.
                onProgress("Counting pending message bodies…")
                var total = 0
                var countBeforeUid = Long.MAX_VALUE
                while (true) {
                    val refs = dao.recentBodyRefs(folderId, generation, countBeforeUid,
                        coarseSince, pageSize)
                    if (refs.isEmpty()) break
                    total += refs.count {
                        runCatching { !Instant.parse(it.receivedAt).isBefore(since) }
                            .getOrDefault(false)
                    }
                    countBeforeUid = checkNotNull(refs.last().uid)
                }
                var downloaded = 0
                onProgress("Downloading message bodies · 0 of $total saved")
                var beforeUid = Long.MAX_VALUE
                while (true) {
                    val refs = dao.recentBodyRefs(folderId, generation, beforeUid,
                        coarseSince, pageSize)
                    if (refs.isEmpty()) break
                    for (row in refs) {
                        if (!runCatching { !Instant.parse(row.receivedAt).isBefore(since) }
                                .getOrDefault(false)) continue
                        try {
                            downloadBodyLocked(row.id)
                            downloaded++
                            onProgress("Downloading message bodies · $downloaded of $total saved")
                        } catch (failure: MailFailure) {
                            if (failure.kind == FailureKind.PROTOCOL) {
                                val current = dao.message(row.id)
                                val uid = current?.uid
                                if (current == null || current.folderId != folderId ||
                                    current.uidValidity != generation || uid == null) continue
                                val identity = MessageIdentity(path, generation, uid)
                                if (!sessions.withStore(start.accountId) { it.exists(identity) }) {
                                    detachIfStillAtIdentity(current.id, folderId, identity)
                                    continue
                                }
                            }
                            // Keep an oversized or malformed header visible while older mail loads.
                            if (failure.kind !in setOf(FailureKind.LIMIT_EXCEEDED,
                                    FailureKind.INVALID_MESSAGE)) throw failure
                        }
                    }
                    beforeUid = checkNotNull(refs.last().uid)
                }
            }
            stored
        }
    }

    suspend fun historyProgress(folderId: String): HistoryProgress? {
        val cursor = dao.historyCursor(folderId) ?: return null
        return HistoryProgress(folderId, cursor.scannedMessages, cursor.estimatedTotal,
            cursor.beforeUid == null,
            dao.remainingHistoryBodies(folderId, cursor.uidValidity, cursor.beforeUid ?: 1L))
    }

    /** One durable page at a time; the recent-mail cursor and on-demand attachments are separate. */
    suspend fun downloadHistory(
        folderId: String,
        pageSize: Int = 100,
        restartCompleted: Boolean = false,
        onProgress: (HistoryProgress) -> Unit = {},
    ): HistoryProgress {
        require(pageSize in 1..1000)
        try {
            val accountId = checkNotNull(dao.folder(folderId)) { "Folder was removed" }.accountId
            refreshFolders(accountId)
            return historyLocks.computeIfAbsent(folderId) { Mutex() }.withLock {
                val folderLock = folderLocks.computeIfAbsent(folderId) { Mutex() }
                val (path, status, initialCursor) = folderLock.withLock {
                    val start = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
                    val path = requireNotNull(start.remotePath) {
                        "Folder is unavailable on the server"
                    }
                    val status = sessions.withStore(accountId) { it.status(path) }
                    if (status.uidValidity < 1 || status.uidNext < 1)
                        throw MailFailure(FailureKind.PROTOCOL,
                            "Server did not report mailbox identity")
                    val checkpoint = db.withTransaction {
                        val folder = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
                        if (folder.uidValidity != null && folder.uidValidity != status.uidValidity)
                            resetGeneration(folder)
                        val prior = dao.historyCursor(folderId)
                            ?.takeIf { it.uidValidity == status.uidValidity }
                        val saved = prior?.takeUnless {
                            restartCompleted && it.beforeUid == null
                        } ?: HistoryCursorEntity(folderId, status.uidValidity, status.uidNext,
                            0, status.messageCount, now().toEpochMilli())
                        dao.saveHistoryCursor(saved)
                        dao.updateFolderHistoryStatus(folderId, status.uidValidity,
                            status.unreadCount, status.messageCount)
                        saved
                    }
                    Triple(path, status, checkpoint)
                }
                var cursor = initialCursor
                suspend fun report() {
                    onProgress(checkNotNull(historyProgress(folderId)))
                }
                suspend fun hydrate(messageId: String) {
                    try {
                        folderLock.withLock {
                            if (dao.message(messageId)?.bodyDownloaded == false)
                                downloadBodyLocked(messageId)
                        }
                    } catch (failure: MailFailure) {
                        if (failure.kind == FailureKind.PROTOCOL) {
                            val vanished = folderLock.withLock {
                                val current = dao.message(messageId)
                                val uid = current?.uid
                                if (current == null || current.folderId != folderId ||
                                    current.uidValidity != status.uidValidity || uid == null)
                                    true
                                else {
                                    val identity = MessageIdentity(path, status.uidValidity, uid)
                                    if (sessions.withStore(accountId) { it.exists(identity) }) false
                                    else {
                                        detachIfStillAtIdentity(current.id, folderId, identity)
                                        true
                                    }
                                }
                            }
                            if (vanished) return
                        }
                        // Keep a damaged or oversized header visible without stalling older mail.
                        if (failure.kind !in setOf(FailureKind.LIMIT_EXCEEDED,
                                FailureKind.INVALID_MESSAGE)) throw failure
                    }
                }
                // A prior run may have committed a page before its body download was interrupted.
                var hydrationBefore = Long.MAX_VALUE
                while (true) {
                    val rows = dao.unhydratedHistoryBodies(folderId, status.uidValidity,
                        cursor.beforeUid ?: 1L, hydrationBefore, pageSize)
                    if (rows.isEmpty()) break
                    rows.forEach { hydrate(it.id) }
                    hydrationBefore = checkNotNull(rows.last().uid)
                }
                report()
                while (cursor.beforeUid != null) {
                    val before = checkNotNull(cursor.beforeUid)
                    val (page, nextCursor) = folderLock.withLock {
                        check(dao.historyCursor(folderId)?.beforeUid == before) {
                            "History checkpoint changed; resume the download"
                        }
                        val request = MessageCursor(path, status.uidValidity, before, Instant.EPOCH)
                        val page = sessions.withStore(accountId) {
                            it.messagePage(path, Instant.EPOCH, request, pageSize)
                        }.also { validateServerPage(it, request, pageSize) }
                        val next = page.next?.beforeUid
                        check(next == null || next < before) {
                            "Server history paging made no progress"
                        }
                        val fetchedAt = now().toEpochMilli()
                        val saved = db.withTransaction {
                            val folder = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
                            check(folder.uidValidity == status.uidValidity) {
                                "Mailbox identity changed during history download"
                            }
                            val placeholders = placeholdersForPage(folderId, page.messages)
                            page.messages.forEach { email ->
                                require(email.identity?.uidValidity == status.uidValidity) {
                                    "History page belongs to another mailbox generation"
                                }
                                upsert(email, folder, placeholders, fetchedAt)
                            }
                            cursor.copy(beforeUid = next,
                                scannedMessages = cursor.scannedMessages + page.messages.size,
                                updatedAt = now().toEpochMilli())
                                .also { dao.saveHistoryCursor(it) }
                        }
                        page to saved
                    }
                    cursor = nextCursor
                    report()
                    page.messages.forEach { email ->
                        val identity = checkNotNull(email.identity)
                        dao.messageByUid(folderId, identity.uidValidity, identity.uid)?.let {
                            hydrate(it.id)
                        }
                    }
                    report()
                }
                checkNotNull(historyProgress(folderId))
            }
        } catch (error: Exception) {
            throw storageFailure(error, "resume history download")
        }
    }

    /** The server check happens outside Room; a move may change this row before deletion. */
    private suspend fun detachIfStillAtIdentity(
        messageId: String, folderId: String, identity: MessageIdentity,
    ) = db.withTransaction {
        val current = dao.message(messageId) ?: return@withTransaction
        if (current.folderId == folderId && current.uidValidity == identity.uidValidity &&
            current.uid == identity.uid && dao.folder(folderId)?.remotePath == identity.mailbox)
            detach(messageId)
    }

    private suspend fun readChanges(
        accountId: String, path: String, uidValidity: Long, sinceModSeq: Long?,
    ): MailboxChanges? = try {
        sessions.withStore(accountId) { it.changes(path, uidValidity, sinceModSeq) }
            ?.takeIf { it.uidValidity == uidValidity &&
                it.highestModSeq >= (sinceModSeq ?: 0L) }
    } catch (failure: MailFailure) {
        if (failure.kind == FailureKind.PROTOCOL) null else throw failure
    }

    private suspend fun applyChanges(
        folderId: String, generation: Long, path: String,
        changes: MailboxChanges, passStartedAt: Long,
    ) = db.withTransaction {
        val folder = checkNotNull(dao.folder(folderId)) { "Folder was removed" }
        check(folder.uidValidity == generation) { "Mailbox identity changed during sync" }
        changes.vanishedUids.forEach { uid ->
            dao.messageByUid(folderId, generation, uid)?.let { detach(it.id) }
        }
        changes.flags.forEach { change ->
            val row = dao.messageByUid(folderId, generation, change.uid) ?: return@forEach
            val intent = dao.activeOperations(folder.accountId, path, generation,
                change.uid, Long.MIN_VALUE)
            dao.saveMessage(row.copy(
                isRead = if (intent.any { it.kind == OperationKind.READ.name }) row.isRead else change.read,
                flagged = if (intent.any { it.kind == OperationKind.FLAG.name }) row.flagged else change.flagged,
            ))
        }
        dao.purgeApplied(folder.accountId, path, passStartedAt)
        dao.cursor(folderId)?.takeIf { it.uidValidity == generation && it.beforeUid == null }
            ?.let { dao.saveCursor(it.copy(highestModSeq = changes.highestModSeq)) }
    }

    /** Fetches text/HTML and attachment metadata for one cached message. */
    suspend fun downloadBody(messageId: String) {
        try {
            val folderId = checkNotNull(dao.message(messageId)) { "Message was removed" }.folderId
            folderLocks.computeIfAbsent(folderId) { Mutex() }.withLock {
                downloadBodyLocked(messageId)
            }
        } catch (error: Exception) {
            throw storageFailure(error, "retry")
        }
    }

    /** Caller owns the folder lock; each body commits independently so a failed pass can resume. */
    private suspend fun downloadBodyLocked(messageId: String) {
        val row = checkNotNull(dao.message(messageId)) { "Message was removed" }
        val folder = checkNotNull(dao.folder(row.folderId)) { "Folder was removed" }
        val identity = remoteIdentity(row, folder)
        val fetchedAt = now().toEpochMilli()
        val email = sessions.withStore(folder.accountId) { it.message(identity) }
        if (email.identity != identity)
            throw MailFailure(FailureKind.PROTOCOL,
                "Server returned a different message; retry this download")
        db.withTransaction {
            val current = dao.message(messageId) ?: return@withTransaction
            val currentFolder = dao.folder(current.folderId) ?: return@withTransaction
            if (current.folderId != row.folderId ||
                currentFolder.remotePath != identity.mailbox ||
                current.uid != identity.uid || current.uidValidity != identity.uidValidity)
                return@withTransaction
            upsert(email, currentFolder, mutableListOf(), fetchedAt)
        }
    }

    suspend fun attachmentIds(messageId: String): List<String> =
        dao.attachments(messageId).map { it.id }

    suspend fun inlineImageAttachmentIds(messageId: String): List<String> =
        withContext(Dispatchers.IO) {
            val directory = File(outbox.privateFilesDir, "attachments")
            dao.attachments(messageId).filter { part ->
                val target = File(directory, part.id)
                part.inline && part.contentId != null && part.partId != null &&
                    part.mimeType.lowercase(Locale.ROOT) in
                        setOf("image/png", "image/jpeg", "image/gif", "image/webp") &&
                    part.sizeBytes <= 2L * 1024 * 1024 &&
                    !(part.cached && part.downloadState == "DOWNLOADED" && target.isFile &&
                        target.length() == part.downloadedBytes)
            }.map { it.id }
        }

    /** Optional remote caching runs after mailbox refresh, outside local startup. */
    suspend fun cacheAutomaticAttachments() = withContext(Dispatchers.IO) {
        val directory = File(outbox.privateFilesDir, "attachments")
        val unavailableAccounts = HashSet<String>()
        var afterId: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val preferences = db.mailDao().getPreferences() ?: return@withContext
            if (!preferences.automaticAttachments || preferences.offline) return@withContext
            val parts = if (afterId == null) dao.firstUncachedRemoteAttachmentPage(64)
                else dao.nextUncachedRemoteAttachmentPage(afterId, 64)
            for (part in parts) {
                currentCoroutineContext().ensureActive()
                val current = db.mailDao().getPreferences() ?: return@withContext
                if (!current.automaticAttachments || current.offline) return@withContext
                afterId = part.id
                if (part.accountId in unavailableAccounts) continue
                try {
                    downloadAttachment(part.id, directory)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: MailFailure) {
                    if (failure.kind in setOf(FailureKind.CONNECTION, FailureKind.AUTHENTICATION,
                            FailureKind.CANCELLED)) unavailableAccounts += part.accountId
                    currentCoroutineContext().ensureActive()
                } catch (failure: Exception) {
                    // A full Room database cannot record any later cache result in this pass.
                    if (generateSequence<Throwable>(failure) { it.cause }
                            .any { it is SQLiteFullException }) return@withContext
                    currentCoroutineContext().ensureActive()
                }
            }
        } while (parts.size == 64)
    }

    /** Streams one MIME part to a private file and publishes it only after a complete download. */
    suspend fun downloadAttachment(
        attachmentId: String,
        directory: File,
        progress: (Long) -> Unit = {},
    ): String = try {
        withContext(Dispatchers.IO) {
        attachmentLock(attachmentId).withLock {
            val part = checkNotNull(db.mailDao().attachment(attachmentId)) { "Attachment was removed" }
            val partId = requireNotNull(part.partId) { "Attachment has no server part" }
            val row = checkNotNull(dao.message(part.messageId)) { "Message was removed" }
            val folder = checkNotNull(dao.folder(row.folderId)) { "Folder was removed" }
            val identity = remoteIdentity(row, folder)
            ensurePrivateDirectory(directory)
            val root = directory.canonicalFile
            val target = File(root, attachmentId).canonicalFile
            require(target.parentFile == root) { "Attachment path is outside private storage" }
            if (part.cached && part.downloadState == "DOWNLOADED" && target.isFile &&
                target.length() == part.downloadedBytes) return@withLock target.absolutePath
            val temporary = File.createTempFile("part-", ".tmp", root)
            var published = false
            try {
                // A short existing file must stop advertising itself as an offline copy
                // while its replacement is being streamed.
                dao.saveAttachments(listOf(part.copy(cached = false, localFile = null,
                    downloadState = "DOWNLOADING", downloadedBytes = 0)))
                val count = FileOutputStream(temporary).use { fileOutput ->
                    val output = fileOutput.buffered()
                    var written = 0L
                    val counted = object : FilterOutputStream(output) {
                        override fun write(value: Int) {
                            out.write(value)
                            written++
                            progress(written)
                        }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            out.write(bytes, offset, length)
                            written += length
                            progress(written)
                        }
                    }
                    sessions.withStore(row.accountId) {
                        it.downloadAttachment(identity, partId, counted)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                    written
                }
                db.withTransaction {
                    val current = checkNotNull(db.mailDao().attachment(attachmentId)) {
                        "Attachment was removed"
                    }
                    val message = dao.message(current.messageId)
                    val currentFolder = message?.let { dao.folder(it.folderId) }
                    if (current.messageId != row.id || current.partId != partId ||
                        message?.folderId != row.folderId ||
                        message.uidValidity != identity.uidValidity || message.uid != identity.uid ||
                        currentFolder?.remotePath != identity.mailbox)
                        throw MailFailure(FailureKind.PROTOCOL,
                            "Attachment changed while downloading; reopen the message")
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    published = true
                    dao.saveAttachments(listOf(current.copy(cached = true, localFile = target.name,
                        downloadState = "DOWNLOADED", downloadedBytes = count)))
                }
                target.absolutePath
            } catch (failure: Throwable) {
                fun removePartial(file: File) {
                    try {
                        if (file.exists() && !file.delete())
                            failure.addSuppressed(IOException("Could not remove incomplete attachment cache"))
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                removePartial(temporary)
                try {
                    withContext(NonCancellable) {
                        val current = db.mailDao().attachment(attachmentId)
                        val committed = published && current?.cached == true &&
                            current.localFile == target.name &&
                            current.downloadState == "DOWNLOADED"
                        if (published && !committed) removePartial(target)
                        if (current?.messageId == row.id && current.partId == partId) {
                            when {
                                current.downloadState == "DOWNLOADING" ->
                                    dao.saveAttachments(listOf(current.copy(
                                        cached = false, localFile = null,
                                        downloadState = "NOT_DOWNLOADED", downloadedBytes = 0)))
                                committed && !target.isFile ->
                                    dao.saveAttachments(listOf(current.copy(cached = false,
                                        localFile = null, downloadState = "NOT_DOWNLOADED",
                                        downloadedBytes = 0)))
                            }
                        }
                    }
                } catch (cleanup: Throwable) {
                    // If the commit status is unknown, keep the published file: Room may reference it.
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
        }
    } catch (failure: Exception) {
        if (failure !is MailFailure && storageExhausted(failure))
            throw MailFailure(FailureKind.LIMIT_EXCEEDED,
                "Device storage is full; free space and download the attachment again", failure)
        throw failure
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

    /** Hide a discarded server draft and queue its UID for idempotent deletion. */
    suspend fun discardDraft(messageId: String) {
        val accountId = checkNotNull(dao.message(messageId)) { "Draft was removed" }.accountId
        withDraftLock(accountId) {
            val snapshot = checkNotNull(dao.message(messageId)) { "Draft was removed" }
            val attachedFiles = dao.attachments(messageId).mapNotNull { it.localFile }
            val folder = checkNotNull(dao.folder(snapshot.folderId)) { "Draft folder was removed" }
            val upload = JSONObject(snapshot.envelopeJson)
            val uploadId = upload.optString("draftUploadMessageId")
            val mayHaveUploaded = upload.optString("draftUploadPhase") in
                setOf("IN_FLIGHT", "UNCERTAIN", "APPENDED")
            val uploadMailbox = upload.optString("draftUploadMailbox")
                .ifBlank { folder.remotePath.orEmpty() }
            if (mayHaveUploaded) require(uploadId.isNotBlank() && uploadMailbox.isNotBlank()) {
                "Draft upload identity is unavailable"
            }
            queueLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
                db.withTransaction {
                    val row = checkNotNull(dao.message(messageId)) { "Draft was removed" }
                    require(row.draft) { "Message is no longer a draft" }
                    check(JSONObject(row.envelopeJson).optString("draftUploadMessageId") == uploadId) {
                        "Draft changed while discarding; try again"
                    }
                    require(!dao.hasBlockingDraftMove(row.accountId, messageId)) {
                        "Wait for this draft's queued move to finish before discarding it"
                    }
                    dao.removeUnfinishedOperations(messageId)
                    val old = if (row.uid != null) remoteIdentity(row, folder) else null
                    listOfNotNull(old).forEach { identity ->
                        val timestamp = now().toEpochMilli()
                        dao.saveOperation(PendingOperationEntity(
                            newId(), row.accountId, null, identity.mailbox, identity.uidValidity,
                            identity.uid, OperationKind.DELETE_DRAFT.name, null, null,
                            createdAt = timestamp, updatedAt = timestamp,
                        ))
                    }
                    if (mayHaveUploaded) {
                        val timestamp = now().toEpochMilli()
                        dao.saveOperation(PendingOperationEntity(
                            newId(), row.accountId, null, uploadMailbox, 1, 1,
                            OperationKind.DELETE_DRAFT_BY_ID.name, null, null,
                            createdAt = timestamp, updatedAt = timestamp,
                            moveSourceMessageId = uploadId,
                        ))
                    }
                    dao.detachOperations(messageId)
                    dao.detachOutbox(messageId)
                    dao.removeCachedMessage(messageId)
                }
            }
            withContext(Dispatchers.IO) {
                val directory = File(outbox.privateFilesDir, "attachments").canonicalFile
                attachedFiles.distinct().forEach { name ->
                    val file = File(directory, name).canonicalFile
                    if (file.parentFile == directory &&
                        dao.attachmentFileReferences(name) == 0) file.delete()
                }
            }
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
     * moved placeholder. An operation interrupted mid-flight is retried; a later server rejection
     * stays failed rather than assuming the move succeeded.
     */
    suspend fun flushOperations(accountId: String): FlushResult =
        queueLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
            var applied = 0
            var failed = 0
            var afterCreatedAt: Long? = null
            var afterId: String? = null
            while (true) {
                // Each state has its own index range. Merge their next pages so replay still
                // follows creation order even when PENDING and IN_FLIGHT are interleaved.
                val queued = listOf(OperationState.PENDING, OperationState.IN_FLIGHT)
                    .flatMap { state ->
                        if (afterCreatedAt == null)
                            dao.firstActiveOperationsPage(accountId, state, 64)
                        else dao.nextActiveOperationsPage(accountId, state,
                            checkNotNull(afterCreatedAt), checkNotNull(afterId), 64)
                    }
                    .sortedWith(compareBy<PendingOperationEntity> { it.createdAt }.thenBy { it.id })
                    .take(64)
                if (queued.isEmpty()) break
                for (op in queued) {
                    val started =
                        db.withTransaction {
                            dao.operation(op.id)
                                ?.takeIf { it.state == OperationState.PENDING || it.state == OperationState.IN_FLIGHT }
                                ?.let {
                                    it.copy(
                                        state = OperationState.IN_FLIGHT,
                                        attempts = it.attempts + 1,
                                        updatedAt = now().toEpochMilli(),
                                    ).also { next -> dao.saveOperation(next) }
                                }
                        } ?: continue
                    val kind = OperationKind.valueOf(started.kind)
                    try {
                        if (kind == OperationKind.DELETE_DRAFT_BY_ID) {
                            if (!performDraftDeleteByMessageId(started)) {
                                // An interrupted APPEND can reach the server after this lookup. Keep
                                // the tombstone until the copy is found and removed.
                                finish(started.id, OperationState.PENDING, null)
                                continue
                            }
                        } else if (kind == OperationKind.MOVE) {
                            performMove(started, MessageIdentity(started.mailbox, started.uidValidity, started.uid))
                        }
                        else sessions.withStore(accountId) { store ->
                            val identity = MessageIdentity(started.mailbox, started.uidValidity, started.uid)
                            when (kind) {
                                OperationKind.READ -> store.markRead(identity, requireNotNull(started.desiredValue))
                                OperationKind.FLAG -> store.flag(identity, requireNotNull(started.desiredValue))
                                OperationKind.MOVE -> error("MOVE is handled with a durable checkpoint")
                                OperationKind.DELETE_DRAFT -> {
                                    if (store.exists(identity)) store.delete(identity)
                                }
                                OperationKind.DELETE_DRAFT_BY_ID -> error("Draft deletion is handled by Message-ID")
                            }
                        }
                        finish(started.id, OperationState.APPLIED, null)
                        applied++
                    } catch (failure: MailFailure) {
                        if (failure.kind in RETRYABLE) {
                            finish(started.id, OperationState.PENDING, failure.message)
                            throw failure
                        }
                        if (kind == OperationKind.DELETE_DRAFT_BY_ID)
                            finish(started.id, OperationState.PENDING, failure.message)
                        else reject(started, failure)
                        failed++
                    }
                }
                afterCreatedAt = queued.last().createdAt
                afterId = queued.last().id
            }
            FlushResult(applied, failed)
        }

    /** A discarded uncertain APPEND stays hidden until its exact server copy is gone. */
    private suspend fun performDraftDeleteByMessageId(op: PendingOperationEntity): Boolean {
        val headerId = requireNotNull(op.moveSourceMessageId)
        val copy = sessions.withStore(op.accountId) { it.findByMessageId(op.mailbox, headerId) }
            ?: return op.moveTargetUidNext != null
        db.withTransaction {
            dao.operation(op.id)?.let {
                // These checkpoint fields hold the copy's identity. If DELETE succeeds but
                // its reply is lost, a later absent lookup confirms completion.
                dao.saveOperation(it.copy(moveTargetUidValidity = copy.uidValidity,
                    moveTargetUidNext = copy.uid))
            }
        }
        sessions.withStore(op.accountId) { store ->
            if (store.exists(copy)) store.delete(copy)
        }
        return true
    }

    /** Replays a MOVE only after checking for a copy created since its durable target boundary. */
    private suspend fun performMove(op: PendingOperationEntity, source: MessageIdentity) {
        val target = requireNotNull(op.targetMailbox)
        if (op.moveMode == "LEGACY_UNCERTAIN")
            throw MailFailure(FailureKind.PROTOCOL, "Older move needs manual reconciliation")
        val checkpoint = if (op.moveTargetUidNext != null) op else {
            val boundary = sessions.withStore(op.accountId) { store ->
                val status = store.status(target)
                if (status.uidValidity < 1 || status.uidNext < 1)
                    throw MailFailure(FailureKind.PROTOCOL, "Target mailbox has no stable UID boundary")
                val mode = when {
                    store.supports("MOVE") -> "MOVE"
                    store.supports("UIDPLUS") -> "COPY_UIDPLUS"
                    else -> throw MailFailure(FailureKind.PROTOCOL, "Server does not support safe MOVE")
                }
                status to mode
            }
            val headerId = op.messageId?.let { dao.message(it) }?.let(::headerMessageId)
            db.withTransaction {
                val current = checkNotNull(dao.operation(op.id)) { "Queued move was removed" }
                current.copy(
                    moveTargetUidValidity = boundary.first.uidValidity,
                    moveTargetUidNext = boundary.first.uidNext,
                    moveSourceMessageId = headerId,
                    moveMode = boundary.second,
                ).also { next -> dao.saveOperation(next) }
            }
        }
        sessions.withStore(op.accountId) { store ->
            if (checkpoint.attempts > 1 && reconcileMove(store, checkpoint, source)) return@withStore
            store.move(source, target)
        }
    }

    /** Returns true when the earlier attempt was completed without issuing another COPY. */
    private fun reconcileMove(
        store: MailStore, op: PendingOperationEntity, source: MessageIdentity,
    ): Boolean {
        val target = requireNotNull(op.targetMailbox)
        val headerId = op.moveSourceMessageId
            ?: throw MailFailure(FailureKind.PROTOCOL, "Move outcome needs manual reconciliation")
        val targetStatus = store.status(target)
        if (targetStatus.uidValidity != op.moveTargetUidValidity)
            throw MailFailure(FailureKind.PROTOCOL, "Target mailbox identity changed during move")
        val copies = targetCopiesSince(store, target, targetStatus,
            requireNotNull(op.moveTargetUidNext), headerId)
        if (copies.size > 1)
            throw MailFailure(FailureKind.PROTOCOL, "Multiple target copies need reconciliation")
        val sourceExists = store.exists(source)
        if (copies.isEmpty()) {
            if (!sourceExists)
                throw MailFailure(FailureKind.PROTOCOL, "Source disappeared without a target copy")
            return false
        }
        if (sourceExists) {
            if (op.moveMode != "COPY_UIDPLUS")
                throw MailFailure(FailureKind.PROTOCOL, "Move outcome needs manual reconciliation")
            store.delete(source)
        }
        return true
    }

    private fun targetCopiesSince(
        store: MailStore, path: String, status: MailboxStatus,
        firstUid: Long, headerId: String,
    ): List<Email> {
        val matches = mutableListOf<Email>()
        var before = status.uidNext
        while (before > firstUid && matches.size < 2) {
            val page = store.messagePage(path, Instant.EPOCH,
                MessageCursor(path, status.uidValidity, before, Instant.EPOCH), 100)
            matches += page.messages.filter {
                (it.identity?.uid ?: 0L) >= firstUid && it.messageId == headerId
            }
            val next = page.next?.beforeUid ?: break
            if (next >= before)
                throw MailFailure(FailureKind.PROTOCOL, "Target paging made no progress")
            before = next
        }
        return matches
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

    private suspend fun hasLocalDraftUploadId(accountId: String, messageId: String): Boolean {
        var afterId: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val drafts = dao.draftUploadPage(accountId, afterId, 64)
            if (drafts.any {
                    JSONObject(it.envelopeJson).optString("draftUploadMessageId") == messageId
                }) return true
            afterId = drafts.lastOrNull()?.id
        } while (drafts.size == 64)
        return false
    }

    /** Returns whether the server copy was written; queued moves keep their local placeholder. */
    private suspend fun upsert(
        email: Email,
        folder: FolderEntity,
        placeholders: MutableList<MessageEntity>,
        fetchedAt: Long,
        seenPassId: String? = null,
    ): Boolean {
        val identity = requireNotNull(email.identity) { "Server message has no identity" }
        val intent =
            dao.activeOperations(folder.accountId, identity.mailbox, identity.uidValidity, identity.uid, fetchedAt)
        if (intent.any { it.kind == OperationKind.MOVE.name ||
                it.kind == OperationKind.DELETE_DRAFT.name }) return false
        val headerId = email.messageId
        if (folder.role == "drafts" && headerId != null) {
            if (dao.activeDraftDeleteByMessageId(folder.accountId, identity.mailbox,
                    headerId, fetchedAt) > 0) return false
            if (hasLocalDraftUploadId(folder.accountId, headerId)) return false
        }
        val remoteId = dao.messageByUid(folder.id, identity.uidValidity, identity.uid)?.id
            ?: CoreRoomMapper.messageId(folder.id, identity)
        val existing = dao.message(remoteId)
        if (existing != null && locallyEditedDraft(existing)) {
            dao.saveMessage(existing.copy(lastSeenPassId = seenPassId ?: existing.lastSeenPassId))
            return true
        }
        var merged = email
        intent.lastOrNull { it.kind == OperationKind.READ.name }?.desiredValue?.let {
            merged = merged.copy(read = it)
        }
        intent.lastOrNull { it.kind == OperationKind.FLAG.name }?.desiredValue?.let {
            merged = merged.copy(flagged = it)
        }
        val replaced =
            if (existing != null || email.messageId == null) null
            else placeholders.firstOrNull { headerMessageId(it) == email.messageId }
        // An IMAP MOVE changes the server UID, not the message's local identity or MIME parts.
        // Reuse its placeholder so downloaded bodies, attachment files and reader links survive.
        val reusable = replaced?.takeUnless(::locallyEditedDraft)
        val id = reusable?.id ?: remoteId
        val cached = existing ?: reusable
        if (replaced != null) {
            placeholders.remove(replaced)
            if (reusable == null) detach(replaced.id)
        }
        val fresh = CoreRoomMapper.email(merged, folder)
        dao.saveMessage(
            fresh.copy(
                    id = id,
                    pinned = cached?.pinned ?: replaced?.pinned ?: false,
                    isNew = cached?.isNew ?: (folder.lastVisitedUid?.let { identity.uid > it } ?: false),
                    remoteEmailId = cached?.remoteEmailId,
                    rawMessagePath = cached?.rawMessagePath,
                    lastSeenPassId = seenPassId ?: cached?.lastSeenPassId,
                    body = if (!email.bodyDownloaded && cached?.bodyDownloaded == true)
                        cached.body else fresh.body,
                    html = if (!email.bodyDownloaded && cached?.bodyDownloaded == true)
                        cached.html else fresh.html,
                    preview = if (!email.bodyDownloaded && cached?.bodyDownloaded == true)
                        cached.preview else fresh.preview,
                    bodyDownloaded = email.bodyDownloaded || cached?.bodyDownloaded == true,
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
        detachFolderMessages(folder.id, remoteOnly = true)
        dao.removeCursor(folder.id)
        dao.removeHistoryCursor(folder.id)
        dao.saveFolders(listOf(folder.copy(uidValidity = null, uidNext = null, lastVisitedUid = null)))
    }

    /** Delete in keyset pages so a large downloaded history never becomes one ID list. */
    private suspend fun detachFolderMessages(folderId: String, remoteOnly: Boolean) {
        var afterId: String? = null
        while (true) {
            val ids = if (remoteOnly) dao.remoteMessageIdsPage(folderId, afterId, 100)
                else dao.messageIdsPage(folderId, afterId, 100)
            if (ids.isEmpty()) break
            ids.forEach { detach(it) }
            afterId = ids.last()
        }
    }

    private suspend fun detach(messageId: String) {
        val row = dao.message(messageId)
        if (row != null && locallyEditedDraft(row)) {
            dao.detachOperations(messageId)
            dao.saveMessage(row.copy(uidValidity = null, uid = null, lastSeenPassId = null))
            return
        }
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

    private fun headerMessageId(row: MessageEntity): String? = headerMessageId(row.envelopeJson)

    private fun headerMessageId(envelopeJson: String): String? =
        JSONObject(envelopeJson).let { if (it.isNull("messageId")) null else it.getString("messageId") }

    /** Match only headers in this page while keeping the placeholder scan in fixed chunks. */
    private suspend fun placeholdersForPage(
        folderId: String, emails: List<Email>,
    ): MutableList<MessageEntity> {
        val wanted = emails.mapNotNull(Email::messageId).toHashSet()
        if (wanted.isEmpty()) return mutableListOf()
        val matches = LinkedHashMap<String, MessageEntity>()
        var afterId: String? = null
        do {
            val chunk = dao.settledPlaceholderPage(folderId, Long.MAX_VALUE, afterId, 128)
            for (row in chunk) {
                val messageId = headerMessageId(row.envelopeJson) ?: continue
                if (messageId in wanted && messageId !in matches)
                    dao.message(row.id)?.let { matches[messageId] = it }
            }
            if (matches.size == wanted.size || chunk.size < 128) break
            afterId = chunk.last().id
        } while (true)
        return matches.values.toMutableList()
    }

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
