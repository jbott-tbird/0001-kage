// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import jakarta.mail.internet.AddressException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.foxred.kage.core.account.EmailAddress as WireAddress
import org.foxred.kage.core.account.EmailBody as WireBody
import org.foxred.kage.core.account.OutgoingAttachment
import org.foxred.kage.core.account.OutgoingEmail
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.mime.BoundedOutputStream
import org.foxred.kage.data.local.*
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.domain.model.*
import org.foxred.kage.domain.repository.MailRepository
import org.foxred.kage.domain.repository.SendDisposition
import org.foxred.kage.domain.usecase.SearchText
import org.foxred.kage.domain.usecase.matches
import org.json.JSONObject

class RoomMailRepository(
    private val db: MailDatabase,
    private val context: Context,
    private val demo: DemoMail,
    private val credentials: org.foxred.kage.core.account.CredentialStore,
    private val remote: RemoteMailRepository? = null,
    private val maxImportedAttachmentBytes: Long = 25L * 1024 * 1024,
) : MailRepository {
    init { require(maxImportedAttachmentBytes > 0) }
    private val dao = db.mailDao()
    private val bundledAssetSizes = ConcurrentHashMap<String, Long>()

    /** Fixture part sizes can differ from bundled sample files; measure the asset itself. */
    private fun bundledAssetSize(asset: String): Long = bundledAssetSizes.computeIfAbsent(asset) {
        context.assets.open(it).use { input ->
            val buffer = ByteArray(8192)
            var bytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
            }
            bytes
        }
    }
    override val messageListRevision = dao.messageListRevision()
    override val unreadCounts = dao.unreadCounts().map { rows ->
        rows.associate { it.folderId to it.unread }
    }
    private fun outboxItem(entry: OutboxEntity): OutboxItem {
        val recipient = JSONObject(entry.envelopeJson).let { envelope ->
            sequenceOf("to", "cc", "bcc")
                .mapNotNull { envelope.optJSONArray(it)?.optJSONObject(0)?.optString("address") }
                .firstOrNull().orEmpty()
        }
        return OutboxItem(
            entry.id, entry.accountId, recipient,
            when (entry.state) {
                DurableOutbox.State.PENDING -> OutboxStatus.QUEUED
                DurableOutbox.State.SENDING -> OutboxStatus.SENDING
                DurableOutbox.State.FAILED -> OutboxStatus.FAILED
                DurableOutbox.State.SENT -> OutboxStatus.SENT
                else -> OutboxStatus.UNCERTAIN
            },
            entry.lastError, entry.createdAt, sentCopyStatus(entry),
            sentCopyUploadNeedsReview(entry), sentCopyUploadCanRetry(entry),
        )
    }

    override val outbox: kotlinx.coroutines.flow.Flow<List<OutboxItem>> =
        observeOutboxPage(Long.MAX_VALUE, 50).map { it.items }

    override val outboxCounts = db.remoteMailDao().observeOutboxCounts().map { row ->
        OutboxCounts(row.queued, row.sending, row.failed, row.uncertain,
            row.sent, row.sentUnconfirmed, row.sentCopyNeedsReview)
    }

    override fun observeOutboxPage(beforeRowId: Long, limit: Int): kotlinx.coroutines.flow.Flow<OutboxPage> {
        require(limit in 1..100)
        require(beforeRowId > 0)
        return db.remoteMailDao().observeOutboxPage(beforeRowId, limit + 1).map { rows ->
            val visible = rows.take(limit)
            OutboxPage(
                visible.map { outboxItem(it.entry) }, beforeRowId,
                if (rows.size > limit) visible.last().rowId else null,
            )
        }
    }
    override val mailbox =
        combine(
            dao.accounts(),
            dao.folders(),
            dao.demoMessages(),
            dao.demoAttachments(),
            dao.preferences(),
        ) { accounts, folders, messages, attachments, preferences ->
            val attachmentsByMessage = attachments.groupBy { it.messageId }
            Mailbox(
                accounts.map { it.domain() },
                folders.map { it.domain() },
                messages.map { m ->
                    m.domain(attachmentsByMessage[m.id].orEmpty().map { it.domain() })
                },
                preferences?.domain() ?: Preferences(),
            )
        }

    override fun observeMessage(id: String) =
        combine(dao.observeMessage(id), dao.observeMessageAttachments(id)) { row, attachments ->
            row?.domain(attachments.map(AttachmentEntity::domain))
        }

    private suspend fun listPage(folderIds: List<String>, cursor: MessagePageCursor?,
        oldestFirst: Boolean, limit: Int): List<MessageListRow> = when {
        oldestFirst && cursor != null ->
            dao.oldestMessageListSeekPage(folderIds, cursor.receivedAt, cursor.id, limit)
        oldestFirst -> dao.oldestMessageListPage(folderIds, null, null, limit)
        cursor == null -> dao.messageListPage(folderIds, null, null, limit)
        else -> dao.messageListSeekPage(folderIds, cursor.receivedAt, cursor.id, limit)
    }

    private suspend fun searchScanPage(accountId: String?, cursor: MessagePageCursor?,
        oldestFirst: Boolean, limit: Int): List<MessageEntity> = when {
        oldestFirst && accountId == null && cursor != null ->
            dao.oldestSearchSeekPage(cursor.receivedAt, cursor.id, limit)
        oldestFirst && accountId == null -> dao.oldestSearchScanPage(null, null, limit)
        oldestFirst && cursor != null ->
            dao.oldestScopedSearchSeekPage(accountId!!, cursor.receivedAt, cursor.id, limit)
        oldestFirst -> dao.oldestScopedSearchScanPage(accountId!!, null, null, limit)
        accountId == null && cursor != null ->
            dao.searchSeekPage(cursor.receivedAt, cursor.id, limit)
        accountId == null -> dao.searchScanPage(null, null, limit)
        cursor != null -> dao.scopedSearchSeekPage(requireNotNull(accountId),
            cursor.receivedAt, cursor.id, limit)
        else -> dao.scopedSearchScanPage(requireNotNull(accountId), null, null, limit)
    }

    override suspend fun messagePage(
        folderIds: List<String>, cursor: MessagePageCursor?, oldestFirst: Boolean, limit: Int,
    ): MessagePage {
        require(limit in 1..100)
        if (folderIds.isEmpty()) return MessagePage()
        val rows = listPage(folderIds, cursor, oldestFirst, limit + 1)
        val visible = rows.take(limit)
        val next = if (rows.size > limit) visible.last().let {
            MessagePageCursor(it.receivedAt, it.id)
        } else null
        return MessagePage(visible.map(MessageListRow::summary), next)
    }

    /** Scan in fixed chunks so searches keep their full-text behavior without retaining mail. */
    override suspend fun filteredPage(
        folderIds: List<String>, accountId: String?, query: MailQuery,
        cursor: MessagePageCursor?, oldestFirst: Boolean, limit: Int,
    ): MessagePage = withContext(Dispatchers.IO) {
        require(limit in 1..100)
        val needle = query.text.trim()
        if (needle.isEmpty() && folderIds.isEmpty()) return@withContext MessagePage()
        if (needle.isNotEmpty() && query.scope == SearchScope.Account && accountId == null)
            return@withContext MessagePage()
        val matches = ArrayList<Message>(limit + 1)
        var scanned = cursor
        if (needle.isEmpty()) {
            val chunkSize = 64
            while (matches.size <= limit) {
                currentCoroutineContext().ensureActive()
                val rows = listPage(folderIds, scanned, oldestFirst, chunkSize)
                if (rows.isEmpty()) break
                for (row in rows) {
                    val summary = row.summary()
                    if (query.filter.matches(summary)) matches += summary
                    scanned = MessagePageCursor(row.receivedAt, row.id)
                    if (matches.size > limit) break
                }
                if (rows.size < chunkSize) break
            }
        } else {
            val scopedAccount = if (query.scope == SearchScope.AllAccounts) null else accountId
            val chunkSize = 4
            while (matches.size <= limit) {
                currentCoroutineContext().ensureActive()
                val rows = searchScanPage(scopedAccount, scanned, oldestFirst, chunkSize)
                if (rows.isEmpty()) break
                for (row in rows) {
                    currentCoroutineContext().ensureActive()
                    val attachments = db.remoteMailDao().attachments(row.id)
                        .map(AttachmentEntity::domain)
                    val full = row.domain(attachments)
                    if (query.filter.matches(full) && SearchText.matches(full, needle))
                        matches += full.copy(body = "", html = null, attachments = emptyList(),
                            attachmentCount = attachments.size)
                    scanned = MessagePageCursor(row.receivedAt, row.id)
                    if (matches.size > limit) break
                }
                if (rows.size < chunkSize) break
            }
        }
        val visible = matches.take(limit)
        val next = if (matches.size > limit) visible.last().let {
            MessagePageCursor(it.receivedAt, it.id)
        } else null
        MessagePage(visible, next)
    }

    override suspend fun cacheCounts(): MailCacheCounts = dao.storedMailCounts().let {
        MailCacheCounts(it.cachedMessages, it.downloadedBodies, it.cachedAttachments, it.drafts)
    }

    override suspend fun initialize() =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                if (dao.initialized() == 0) seed()
                // Demo accounts never complete real-account onboarding, including on older installs.
                val started = dao.realAccountIds().isNotEmpty()
                val prefs = dao.getPreferences() ?: Preferences().entity()
                if (prefs.started != started) dao.savePreferences(prefs.copy(started = started))
            }
            cleanupPendingAttachmentFiles()
            try {
                remote?.cleanupOrphanRemoteAttachments()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Cached mail can open; a later launch retries orphan file cleanup.
            }
            remote?.cleanupOutboxFiles()
            // Network attachment downloads run after a successful foreground refresh.
            cacheAutomaticLocalAttachments()
            if (remote != null)
                dao.realAccountIds().forEach { remote.recoverOutgoing(it) }
        }

    private suspend fun seed() {
        demo.accounts.forEach { insertAccount(it) }
        dao.savePreferences(Preferences().entity())
    }

    private suspend fun insertAccount(account: Account) {
        dao.insertAccounts(listOf(account.entity()))
        dao.insertFolders(demo.folders(account).map { it.entity() })
        val messages = demo.messages(account)
        dao.saveMessages(messages.map { it.entity() })
        // Bundled parts are copied on demand, so file I/O cannot roll back this seed transaction.
        dao.saveAttachments(messages.flatMap { it.attachments }.map { it.entity() })
    }

    override suspend fun addAccount(account: Account) =
        withContext(Dispatchers.IO) {
            require(account.address.contains('@')) { "Enter a valid email address." }
            require(account.incoming.isNotBlank() && account.outgoing.isNotBlank()) {
                "Enter both server addresses."
            }
            require(account.incomingPort in 1..65535 && account.outgoingPort in 1..65535) {
                "Ports must be between 1 and 65535."
            }
            db.withTransaction {
                insertAccount(account.copy(address = account.address.trim().lowercase()))
                dao.savePreferences(
                    (dao.getPreferences() ?: Preferences().entity()).copy(
                        selectedFolder = "${account.id}-inbox",
                        started = true,
                    )
                )
            }
            cacheAutomaticLocalAttachments()
        }

    private suspend fun cacheAutomaticLocalAttachments() = withContext(Dispatchers.IO) {
        val preferences = dao.getPreferences() ?: return@withContext
        if (!preferences.automaticAttachments || preferences.offline) return@withContext
        var afterId: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val ids = if (afterId == null) dao.firstUncachedLocalIdsPage(64)
                else dao.nextUncachedLocalIdsPage(afterId, 64)
            for (id in ids) {
                try {
                    cacheAttachment(id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Automatic caching is optional; a bad part or unavailable server must not
                    // prevent local mail from opening or later attachments from being tried.
                    currentCoroutineContext().ensureActive()
                }
                afterId = id
            }
        } while (ids.size == 64)
    }

    override suspend fun removeAccount(id: String) = removeAccountInternal(id, revokeGoogle = false)

    override suspend fun revokeAndRemoveGoogleAccount(id: String) =
        removeAccountInternal(id, revokeGoogle = true)

    /** A durable filename stream survives a crash between row deletion and file cleanup. */
    private suspend fun stageAccountAttachmentCleanup(accountId: String): File {
        val directory = File(context.filesDir, "attachment-cleanup")
        val token = UUID.randomUUID().toString()
        val temporary = File(directory, "$token.tmp")
        val ready = File(directory, "$token.ready")
        try {
            ensurePrivateDirectory(directory)
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(BufferedOutputStream(file))
                output.writeUTF(accountId)
                var afterId: String? = null
                do {
                    currentCoroutineContext().ensureActive()
                    val rows = if (afterId == null)
                        db.remoteMailDao().firstAccountAttachmentFilePage(accountId, 64)
                    else db.remoteMailDao().nextAccountAttachmentFilePage(accountId, afterId, 64)
                    rows.forEach { row ->
                        output.writeBoolean(true)
                        output.writeUTF(row.localFile)
                        afterId = row.id
                    }
                } while (rows.size == 64)
                output.writeBoolean(false)
                output.flush()
                file.fd.sync()
            }
            check(temporary.renameTo(ready)) { "Cannot stage attachment cleanup" }
            return ready
        } catch (error: Exception) {
            if (storageExhausted(error))
                throw MailFailure(FailureKind.LIMIT_EXCEEDED,
                    "Device storage is full; free space before removing this account", error)
            throw error
        } finally {
            temporary.delete()
        }
    }

    private suspend fun cleanupAttachmentManifest(manifest: File) {
        val directory = File(context.filesDir, "attachments").canonicalFile
        DataInputStream(BufferedInputStream(manifest.inputStream())).use { input ->
            if (db.remoteMailDao().account(input.readUTF()) != null) return
            while (input.readBoolean()) {
                currentCoroutineContext().ensureActive()
                val name = input.readUTF()
                val file = File(directory, name).canonicalFile
                if (file.parentFile == directory &&
                    db.remoteMailDao().attachmentFileReferences(name) == 0 &&
                    file.exists() && !file.delete())
                    throw IOException("Cannot remove cached attachment file")
            }
        }
        manifest.delete()
    }

    private suspend fun cleanupPendingAttachmentFiles() {
        val directory = File(context.filesDir, "attachment-cleanup")
        directory.listFiles()?.filter { it.name.endsWith(".ready") }?.forEach { manifest ->
            try {
                cleanupAttachmentManifest(manifest)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the manifest; the next app launch can retry file cleanup.
            }
        }
    }

    private suspend fun removeAccountInternal(id: String, revokeGoogle: Boolean) =
        withContext(Dispatchers.IO) {
            val cleanupManifest = stageAccountAttachmentCleanup(id)
            try {
                val mode = db.remoteMailDao().account(id)?.mode
                if (revokeGoogle) {
                    check(mode == "REAL") { "Account was removed" }
                    checkNotNull(remote) { "Google account removal is unavailable" }
                        .revokeAndRemoveGoogleAccount(id)
                } else if (mode == "REAL" && remote != null) {
                    remote.removeAccount(id)
                } else credentials.removeAccount(id)
                db.withTransaction {
                    dao.removeAccount(id)
                    val prefs = dao.getPreferences() ?: Preferences().entity()
                    val inbox = dao.firstInbox()
                    val selected = prefs.selectedFolder
                        .takeIf { (it == "unified" && inbox != null) || dao.folderAccountId(it) != null }
                        ?: inbox.orEmpty()
                    dao.savePreferences(
                        prefs.copy(selectedFolder = selected, started = dao.realAccountIds().isNotEmpty())
                    )
                }
            } catch (failure: Throwable) {
                // A surviving account still owns its attachment rows. A later removal restages
                // them; discard this attempt's manifest instead of accumulating retry files.
                withContext(NonCancellable) {
                    val accountRemains = try { db.remoteMailDao().account(id) != null }
                        catch (_: Throwable) { false }
                    if (accountRemains) cleanupManifest.delete()
                }
                throw failure
            }
            try {
                cleanupAttachmentManifest(cleanupManifest)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The durable manifest is retried during the next initialization.
            }
        }

    override suspend fun updatePreferences(preferences: Preferences) {
        dao.savePreferences(preferences.entity())
        cacheAutomaticLocalAttachments()
    }

    override suspend fun markRead(id: String, read: Boolean) {
        if (remote?.isRealMessage(id) == true) remote.markRead(id, read)
        else dao.markRead(id, read)
    }

    override suspend fun flag(id: String, value: Boolean) {
        if (remote?.isRealMessage(id) == true) remote.flag(id, value)
        else dao.flag(id, value)
    }

    override suspend fun pin(id: String, value: Boolean) = dao.pin(id, value)

    override suspend fun move(id: String, role: String) =
        if (remote?.isRealMessage(id) == true) remote.moveToRole(id, role)
        else db.withTransaction {
            val message = requireNotNull(dao.message(id))
            val folder = requireNotNull(dao.folder(message.accountId, role))
            dao.move(id, folder)
        }

    override suspend fun saveDraft(message: Message) {
        val real = db.remoteMailDao().account(message.accountId)?.mode == "REAL"
        if (real) checkNotNull(remote) { "Real draft storage is unavailable" }
            .withDraftLock(message.accountId) { saveDraftLocally(message, true) }
        else saveDraftLocally(message, false)
    }

    private suspend fun saveDraftLocally(message: Message, real: Boolean) {
        require(message.bodyDownloaded) { "Download this draft before editing it" }
        if (real)
            withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, "attachments").canonicalFile
                message.attachments.forEach { attachment ->
                    val name = checkNotNull(attachment.localFile) {
                        "Download ${attachment.filename} before saving this draft"
                    }
                    val file = File(directory, name).canonicalFile
                    require(file.parentFile == directory && file.isFile) {
                        "Attach ${attachment.filename} again before saving this draft"
                    }
                    require(file.length() == attachment.sizeBytes) {
                        "Attachment ${attachment.filename} is incomplete; attach it again"
                    }
                }
            }
        val previousFiles = db.withTransaction {
            val previousAttachments = db.remoteMailDao().attachments(message.id)
            val folder = requireNotNull(dao.folder(message.accountId, "drafts"))
            val previous = dao.message(message.id)
            require(previous == null || (previous.accountId == message.accountId && previous.draft)) {
                "This draft belongs to another account or is no longer a draft"
            }
            val local = message.copy(folderId = folder, draft = true,
                isRead = true, isNew = false).entity()
            val metadata = JSONObject(previous?.envelopeJson ?: "{}")
            val edited = JSONObject(local.envelopeJson)
            listOf("replyToAddress", "inReplyTo", "references").forEach { key ->
                metadata.put(key, edited.opt(key))
            }
            if (message.rfcMessageId != null) metadata.put("messageId", message.rfcMessageId)
            metadata.put("localDraftDirty", true)
            if (real) {
                metadata.put("draftEditRevision", UUID.randomUUID().toString())
                if (metadata.optString("draftUploadPhase") == "PENDING")
                    listOf("draftUploadRevision", "draftUploadMessageId", "draftUploadMailbox",
                        "draftUploadPhase",
                        "draftUploadError").forEach(metadata::remove)
            }
            val sameFolder = previous?.folderId == folder
            dao.saveMessages(
                listOf(
                    local.copy(
                        uidValidity = previous?.uidValidity?.takeIf { sameFolder },
                        uid = previous?.uid?.takeIf { sameFolder },
                        remoteEmailId = previous?.remoteEmailId,
                        rawMessagePath = previous?.rawMessagePath,
                        lastSeenPassId = previous?.lastSeenPassId,
                        envelopeJson = metadata.toString(),
                    )
                )
            )
            dao.clearAttachments(message.id)
            val oldById = previousAttachments.associateBy { it.id }
            dao.saveAttachments(message.attachments.map { selected ->
                val fresh = selected.entity()
                oldById[selected.id]?.let { old ->
                    fresh.copy(partId = old.partId, downloadState = old.downloadState,
                        downloadedBytes = old.downloadedBytes)
                } ?: fresh
            })
            previousAttachments.mapNotNull { it.localFile }
        }
        removeUnusedAttachmentFiles(previousFiles - message.attachments.mapNotNull { it.localFile }.toSet())
    }

    override suspend fun send(message: Message): SendDisposition {
        val account = checkNotNull(db.remoteMailDao().account(message.accountId)) {
            "Account was removed"
        }
        if (account.mode == "REAL") {
            val server = checkNotNull(remote) { "Real account sending is unavailable" }
            require(message.senderAddress.equals(account.address, ignoreCase = true)) {
                "Choose this account's saved address as the sender"
            }
            val outgoing = withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, "attachments").canonicalFile
                var attachmentBytes = 0L
                val parts = message.attachments.map { attachment ->
                    val name = checkNotNull(attachment.localFile) {
                        "Download ${attachment.filename} before sending"
                    }
                    val file = File(directory, name).canonicalFile
                    require(file.parentFile == directory && file.isFile) {
                        "Attach ${attachment.filename} again before sending"
                    }
                    require(file.length() == attachment.sizeBytes) {
                        "Attachment ${attachment.filename} is incomplete; attach it again"
                    }
                    attachmentBytes += file.length()
                    require(attachmentBytes <= 25L * 1024 * 1024) {
                        "Attachments exceed the 25 MB message limit"
                    }
                    val bytes = file.readBytes()
                    require(bytes.size.toLong() == attachment.sizeBytes) {
                        "Attachment ${attachment.filename} changed while reading; attach it again"
                    }
                    OutgoingAttachment(attachment.filename, attachment.mimeType, bytes)
                }
                val domain = account.address.substringAfter('@', "invalid")
                val localId = message.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                OutgoingEmail(
                    "<$localId@$domain>", WireAddress(account.address, account.name),
                    parseRecipientAddresses(message.to), parseRecipientAddresses(message.cc),
                    parseRecipientAddresses(message.bcc),
                    message.subject, WireBody(message.body, message.html), parts,
                    inReplyTo = message.inReplyTo,
                    references = message.references,
                )
            }
            require(outgoing.to.isNotEmpty() || outgoing.cc.isNotEmpty() || outgoing.bcc.isNotEmpty()) {
                "Enter a recipient."
            }
            val existingOutbox = db.remoteMailDao().outboxByMessageId(account.id, outgoing.messageId)
            val storedDraft = db.remoteMailDao().message(message.id)
            val storedParts = storedDraft?.let { db.remoteMailDao().attachments(it.id) }.orEmpty()
            val storedMessage = storedDraft?.domain(storedParts.map { it.domain() })
            val changed = storedMessage == null || !storedMessage.draft ||
                listOf(storedMessage.to, storedMessage.cc, storedMessage.bcc,
                    storedMessage.subject, storedMessage.body, storedMessage.html.orEmpty()) !=
                listOf(message.to, message.cc, message.bcc,
                    message.subject, message.body, message.html.orEmpty()) ||
                storedMessage.inReplyTo != message.inReplyTo ||
                storedMessage.references != message.references ||
                storedMessage.attachments.map { it.id to it.localFile } !=
                    message.attachments.map { it.id to it.localFile }
            if (existingOutbox != null && changed) {
                saveDraft(message)
                error("This draft already has a message in Outbox. Your new edits were saved to Drafts; the queued message still has its earlier content.")
            }
            if (existingOutbox == null || changed ||
                JSONObject(storedDraft?.envelopeJson ?: "{}").optString("draftEditRevision").isBlank())
                saveDraft(message)
            val draftId = db.remoteMailDao().message(message.id)
                ?.takeIf { it.accountId == account.id && it.draft }?.id
            val queued = server.queueOutgoing(account.id, outgoing, draftId)
            return when (queued.state) {
                DurableOutbox.State.PENDING -> SendDisposition.QUEUED
                DurableOutbox.State.SENDING -> SendDisposition.SENDING
                DurableOutbox.State.FAILED -> SendDisposition.FAILED
                DurableOutbox.State.UNCERTAIN -> SendDisposition.UNCERTAIN
                DurableOutbox.State.SENT -> SendDisposition.SENT
                else -> error("Unsupported outbox state")
            }
        }
        val recipients = try {
            listOf(message.to, message.cc, message.bcc).flatMap(::parseRecipientAddresses)
        } catch (failure: AddressException) {
            throw IllegalArgumentException("Enter valid recipient email addresses.", failure)
        }
        require(recipients.isNotEmpty()) { "Enter a recipient." }
        db.withTransaction {
            val folder = requireNotNull(dao.folder(message.accountId, "sent"))
            dao.saveMessages(
                listOf(
                    message
                        .copy(folderId = folder, draft = false, isRead = true, isNew = false)
                        .entity()
                )
            )
            dao.clearAttachments(message.id)
            dao.saveAttachments(message.attachments.map { it.entity() })
        }
        return SendDisposition.DEMO_SAVED
    }

    override suspend fun retryOutbox(id: String): String {
        val server = checkNotNull(remote) { "Real account sending is unavailable" }
        val entry = checkNotNull(db.remoteMailDao().outboxEntry(id)) { "Outbox entry was removed" }
        server.retryOutgoing(id)
        return entry.accountId
    }

    override suspend fun deleteDraft(id: String, looseAttachments: List<Attachment>) {
        val files = db.remoteMailDao().attachments(id).mapNotNull { it.localFile } +
            looseAttachments.mapNotNull { it.localFile }
        val draft = dao.message(id)
        if (draft?.draft == true &&
            db.remoteMailDao().account(draft.accountId)?.mode == "REAL") {
            checkNotNull(remote) { "Real draft deletion is unavailable" }.discardDraft(id)
        } else db.withTransaction {
            val current = dao.message(id) ?: return@withTransaction
            if (!current.draft) return@withTransaction
            db.remoteMailDao().detachOperations(id)
            db.remoteMailDao().detachOutbox(id)
            dao.deleteDraft(id)
        }
        removeUnusedAttachmentFiles(files)
    }

    private suspend fun removeUnusedAttachmentFiles(names: List<String>) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "attachments").canonicalFile
        names.distinct().forEach { name ->
            val file = File(directory, name).canonicalFile
            if (file.parentFile == directory &&
                db.remoteMailDao().attachmentFileReferences(name) == 0) file.delete()
        }
    }

    override suspend fun cleanupLooseAttachments(attachments: List<Attachment>) =
        removeUnusedAttachmentFiles(attachments.mapNotNull { it.localFile })

    override suspend fun importAttachment(messageId: String, sourceUri: String): Attachment {
        var published: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val uri = Uri.parse(sourceUri)
                val resolver = context.contentResolver
                val id = UUID.randomUUID().toString()
                val name =
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Attachment"
                val directory = File(context.filesDir, "attachments")
                val file = File(directory, id)
                val temporary = File(directory, "$id.tmp")
                try {
                    ensurePrivateDirectory(directory)
                    requireNotNull(resolver.openInputStream(uri)) {
                            "Unable to open the selected file."
                        }
                        .use { input ->
                            FileOutputStream(temporary).use { output ->
                                try {
                                    val bounded = BoundedOutputStream(output, maxImportedAttachmentBytes)
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        bounded.write(buffer, 0, count)
                                    }
                                } catch (failure: MailFailure) {
                                    if (failure.kind == FailureKind.LIMIT_EXCEEDED)
                                        throw MailFailure(FailureKind.LIMIT_EXCEEDED,
                                            "Attachment exceeds the size limit", failure)
                                    throw failure
                                }
                                output.fd.sync()
                            }
                        }
                    check(temporary.renameTo(file)) { "Unable to save the selected file." }
                    published = file
                    Attachment(
                        id,
                        messageId,
                        name,
                        resolver.getType(uri) ?: "application/octet-stream",
                        file.length(),
                        cached = true,
                        asset = "",
                        localFile = id,
                    )
                } catch (error: Exception) {
                    temporary.delete()
                    file.delete()
                    if (storageExhausted(error)) throw MailFailure(FailureKind.LIMIT_EXCEEDED,
                        "Device storage is full; free space and attach the file again", error)
                    throw error
                }
            }
        } catch (error: Exception) {
            // Cancellation can arrive while returning from the IO dispatcher, after rename.
            published?.let { file ->
                if (file.exists() && !file.delete())
                    error.addSuppressed(IOException("Could not remove untracked imported attachment"))
            }
            throw error
        }
    }

    override suspend fun cacheAttachment(id: String, progress: (Long) -> Unit): String =
        try {
            withContext(Dispatchers.IO) {
            val item = requireNotNull(dao.attachment(id))
            val privateDirectory = File(context.filesDir, "attachments")
            ensurePrivateDirectory(privateDirectory)
            val directory = privateDirectory.canonicalFile
            if (item.partId != null) {
                val server = checkNotNull(remote) { "Real attachment downloads are unavailable" }
                val target = File(directory, item.id).canonicalFile
                require(target.parentFile == directory) { "Attachment path is outside private storage" }
                if (item.cached && item.downloadState == "DOWNLOADED" && target.isFile &&
                    target.length() == item.downloadedBytes) return@withContext target.absolutePath
                check(dao.getPreferences()?.offline != true) {
                    "This attachment is not available offline. Turn off offline preview to download it."
                }
                return@withContext server.downloadAttachment(id, directory, progress)
            }
            val localName = item.localFile
            val file =
                if (localName != null) File(directory, localName).canonicalFile
                else
                    File(
                        directory,
                        "${item.id.replace(Regex("[^a-zA-Z0-9-]"), "_")}.${item.asset.substringAfterLast('.', "bin")}",
                    ).canonicalFile
            require(file.parentFile == directory) { "Attachment path is outside private storage" }
            if (localName != null) {
                check(file.isFile && file.length() == item.sizeBytes) {
                    "The local attachment is no longer available. Attach it again from Files."
                }
                return@withContext file.absolutePath
            }
            if (!file.isFile || !item.cached || file.length() != bundledAssetSize(item.asset)) {
                check(item.cached || dao.getPreferences()?.offline != true) {
                    "This attachment is not available offline. Turn off offline preview to download it."
                }
                val temporary = File.createTempFile("${file.name}-", ".tmp", directory)
                try {
                    context.assets.open(item.asset).use { input ->
                        FileOutputStream(temporary).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    temporary.delete()
                }
            }
            dao.cached(id)
            progress(file.length())
            file.absolutePath
            }
        } catch (error: Exception) {
            if (error !is MailFailure && storageExhausted(error))
                throw MailFailure(FailureKind.LIMIT_EXCEEDED,
                    "Device storage is full; free space and download the attachment again", error)
            throw error
        }

    override suspend fun resetDemo() =
        withContext(Dispatchers.IO) {
            val demoIds = dao.demoAccountIds()
            db.withTransaction {
                val prefs = dao.getPreferences()
                val selectedAccount = prefs?.selectedFolder?.let { dao.folderAccountId(it) }
                val preserveSelection = selectedAccount != null && selectedAccount in dao.realAccountIds()
                demoIds.forEach { dao.removeAccount(it) }
                demo.accounts.forEach { insertAccount(it) }
                dao.savePreferences(
                    if (preserveSelection) prefs!!.copy(started = true)
                    else Preferences().entity().copy(
                        started = prefs?.started == true || dao.realAccountIds().isNotEmpty(),
                    )
                )
            }
            demoIds.forEach { credentials.removeAccount(it) }
        }
}
