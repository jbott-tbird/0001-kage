// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foxred.kage.core.account.EmailAddress
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.MimeCodec
import org.foxred.kage.core.account.MessageIdentity
import org.foxred.kage.core.account.OutgoingEmail
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.local.OutboxEntity
import org.foxred.kage.domain.model.SentCopyStatus
import org.json.JSONArray
import org.json.JSONObject

/** Durable submission intent. Transport and Sent-folder reconciliation are separate steps. */
class DurableOutbox(
    private val db: MailDatabase,
    context: Context,
    private val codec: MimeCodec,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.remoteMailDao()
    internal val privateFilesDir: File = context.applicationContext.filesDir
    private val directory = File(context.applicationContext.filesDir, "outbox")
    private val lock = Mutex()

    object State {
        const val PENDING = "PENDING"
        const val SENDING = "SENDING"
        const val FAILED = "FAILED"
        const val UNCERTAIN = "UNCERTAIN"
        const val SENT = "SENT"
    }

    /** Encode before changing Room, then atomically publish the file before its row. */
    suspend fun enqueue(accountId: String, email: OutgoingEmail, draftId: String? = null): OutboxEntity =
        lock.withLock {
            withContext(Dispatchers.IO) {
                require(email.messageId.isNotBlank()) { "Outgoing Message-ID is required" }
                dao.outboxByMessageId(accountId, email.messageId)?.let { return@withContext it }
                val raw = codec.encode(email)
                val id = newId()
                require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid outbox ID" }
                val target = File(directory, "$id.eml")
                val temporary = File(directory, "$id.tmp")
                try {
                    ensurePrivateDirectory(directory)
                    FileOutputStream(temporary).use { output ->
                        output.write(raw)
                        output.fd.sync()
                    }
                    check(temporary.renameTo(target)) { "Cannot publish outbox message" }
                    val timestamp = now().toEpochMilli()
                    val row = OutboxEntity(
                        id, accountId, draftId, email.messageId, target.absolutePath,
                        envelope(email).toString(), createdAt = timestamp, updatedAt = timestamp,
                    )
                    db.withTransaction {
                        checkNotNull(dao.account(accountId)) { "Account was removed" }
                        val saved = if (draftId != null) {
                            val draft = checkNotNull(dao.message(draftId)) { "Draft was removed" }
                            require(draft.accountId == accountId && draft.draft) { "Draft belongs to another account" }
                            val revision = JSONObject(draft.envelopeJson).optString("draftEditRevision")
                            row.copy(envelopeJson = JSONObject(row.envelopeJson)
                                .put("draftRevision", revision).toString())
                        } else row
                        // A second caller may have inserted this Message-ID while MIME was encoded.
                        dao.outboxByMessageId(accountId, email.messageId)?.let { return@withTransaction it }
                            ?: saved.also { dao.saveOutbox(it) }
                    }.also { if (it.id != id) target.delete() }
                } catch (error: Throwable) {
                    temporary.delete()
                    // A cancellation may arrive after Room commits but before its result returns.
                    // Preserve the MIME file whenever the durable row may already reference it.
                    val safeToDelete = withContext(NonCancellable) {
                        try { dao.outboxEntry(id)?.rawMessagePath != target.absolutePath }
                        catch (_: Throwable) { false }
                    }
                    if (safeToDelete) target.delete()
                    if (storageExhausted(error)) throw MailFailure(FailureKind.LIMIT_EXCEEDED,
                        "Device storage is full; free space, then check Outbox before sending this draft again",
                        error)
                    throw error
                }
            }
        }

    suspend fun entries(accountId: String): List<OutboxEntity> = dao.outbox(accountId)

    data class StatePage(val entries: List<OutboxEntity>, val afterRowId: Long?)

    private suspend fun readStatePage(accountId: String, state: String,
        afterRowId: Long, limit: Int): StatePage {
        val entries = dao.outboxStatePage(accountId, state, afterRowId, limit)
        val next = entries.lastOrNull()?.let { checkNotNull(dao.outboxRowId(it.id)) }
        return StatePage(entries, next)
    }

    suspend fun statePage(accountId: String, state: String, afterRowId: Long,
        limit: Int = 64): StatePage {
        require(limit in 1..256)
        return db.withTransaction { readStatePage(accountId, state, afterRowId, limit) }
    }

    /** An unsettled submission may have been accepted. Never auto retry it. */
    suspend fun recoverInterrupted(accountId: String, includeSentCopyUploads: Boolean = true) {
        for (state in if (includeSentCopyUploads) listOf(State.SENDING, State.SENT)
            else listOf(State.SENDING)) {
            var afterRowId = 0L
            while (true) {
                val next = db.withTransaction {
                    val page = readStatePage(accountId, state, afterRowId, 64)
                    page.entries.forEach { entry ->
                        if (state == State.SENDING)
                            dao.saveOutbox(entry.copy(state = State.UNCERTAIN,
                                lastError = "Submission interrupted; reconcile Sent before retrying",
                                updatedAt = now().toEpochMilli()))
                        else if (sentCopyUploadPhase(entry) == SentCopyUploadPhase.IN_FLIGHT)
                            markSentCopyUploadUncertainLocked(entry,
                                "Sent-copy upload was interrupted; check Sent before trying again")
                    }
                    page.afterRowId
                }
                afterRowId = next ?: break
            }
        }
    }

    /** Claims a single pending entry. A caller must finish or reconcile the claim. */
    suspend fun claimNext(accountId: String): OutboxEntity? = db.withTransaction {
        val next = dao.nextPendingOutbox(accountId) ?: return@withTransaction null
        next.copy(state = State.SENDING, attempts = next.attempts + 1, updatedAt = now().toEpochMilli())
            .also { dao.saveOutbox(it) }
    }

    /** Cancellation before SMTP starts cannot have delivered mail; make that claim retryable. */
    suspend fun releaseUnstartedClaim(id: String) = db.withTransaction {
        val current = checkNotNull(dao.outboxEntry(id)) { "Outbox entry was removed" }
        check(current.state == State.SENDING) { "Outbox entry is not being prepared" }
        dao.saveOutbox(current.copy(state = State.PENDING,
            attempts = (current.attempts - 1).coerceAtLeast(0),
            lastError = null, updatedAt = now().toEpochMilli()))
    }

    suspend fun finish(id: String, outcome: String, error: String? = null) = db.withTransaction {
        require(outcome in setOf(State.SENT, State.FAILED, State.UNCERTAIN))
        val current = checkNotNull(dao.outboxEntry(id)) { "Outbox entry was removed" }
        check(current.state == State.SENDING) { "Outbox entry is not being sent" }
        val metadata = JSONObject(current.envelopeJson).put("sentCopyState",
            if (outcome == State.SENT) SentCopyStatus.PENDING.name else SentCopyStatus.WAITING.name)
        dao.saveOutbox(current.copy(state = outcome, lastError = error,
            envelopeJson = metadata.toString(), updatedAt = now().toEpochMilli()))
    }

    /** Only a definite rejection is retryable without first checking the provider. */
    suspend fun retryFailed(id: String) = db.withTransaction {
        val current = checkNotNull(dao.outboxEntry(id)) { "Outbox entry was removed" }
        check(current.state == State.FAILED) { "Only a confirmed failure can be retried" }
        dao.saveOutbox(current.copy(state = State.PENDING, lastError = null, updatedAt = now().toEpochMilli()))
    }

    /** A matching server copy confirms both filing and an otherwise uncertain submission. */
    suspend fun confirmSentCopy(id: String, identity: MessageIdentity): Boolean = db.withTransaction {
        val current = dao.outboxEntry(id) ?: return@withTransaction false
        if (sentCopyStatus(current) == SentCopyStatus.CONFIRMED) return@withTransaction false
        check(current.state in setOf(State.SENT, State.UNCERTAIN)) {
            "Outbox entry has no submitted message to reconcile"
        }
        val metadata = JSONObject(current.envelopeJson)
            .put("sentCopyState", SentCopyStatus.CONFIRMED.name)
            .put("sentCopyMailbox", identity.mailbox)
            .put("sentCopyUidValidity", identity.uidValidity)
            .put("sentCopyUid", identity.uid)
        metadata.remove("sentCopyUploadPhase")
        dao.saveOutbox(current.copy(state = State.SENT, lastError = null,
            envelopeJson = metadata.toString(), updatedAt = now().toEpochMilli()))
        true
    }

    /** Claim a single user-requested APPEND; process death leaves a reviewable marker. */
    suspend fun claimSentCopyUpload(id: String): Boolean = db.withTransaction {
        val current = dao.outboxEntry(id) ?: return@withTransaction false
        if (current.state != State.SENT || sentCopyStatus(current) == SentCopyStatus.CONFIRMED ||
            sentCopyUploadPhase(current) != null) return@withTransaction false
        val metadata = JSONObject(current.envelopeJson)
            .put("sentCopyUploadPhase", SentCopyUploadPhase.IN_FLIGHT)
        dao.saveOutbox(current.copy(envelopeJson = metadata.toString(), lastError = null,
            updatedAt = now().toEpochMilli()))
        true
    }

    /** One transaction moves a reviewed uncertain copy directly into its new APPEND attempt. */
    suspend fun claimReviewedSentCopyUpload(id: String): Boolean = db.withTransaction {
        val current = dao.outboxEntry(id) ?: return@withTransaction false
        if (current.state != State.SENT || sentCopyStatus(current) != SentCopyStatus.PENDING ||
            sentCopyUploadPhase(current) != SentCopyUploadPhase.UNCERTAIN)
            return@withTransaction false
        val metadata = JSONObject(current.envelopeJson)
            .put("sentCopyUploadPhase", SentCopyUploadPhase.IN_FLIGHT)
        dao.saveOutbox(current.copy(envelopeJson = metadata.toString(), lastError = null,
            updatedAt = now().toEpochMilli()))
        true
    }

    /** A cancelled pre-APPEND lookup has not uploaded a copy; retain any earlier uncertainty. */
    suspend fun releaseUnstartedSentCopyUpload(id: String, retryAfterReview: Boolean) = db.withTransaction {
        val current = dao.outboxEntry(id) ?: return@withTransaction
        if (current.state != State.SENT || sentCopyUploadPhase(current) != SentCopyUploadPhase.IN_FLIGHT)
            return@withTransaction
        val metadata = JSONObject(current.envelopeJson)
        if (retryAfterReview) metadata.put("sentCopyUploadPhase", SentCopyUploadPhase.UNCERTAIN)
        else metadata.remove("sentCopyUploadPhase")
        dao.saveOutbox(current.copy(envelopeJson = metadata.toString(),
            lastError = if (retryAfterReview) "Earlier Sent-copy upload still needs review" else null,
            updatedAt = now().toEpochMilli()))
    }

    /** An absent server match cannot prove that an interrupted APPEND did not succeed. */
    suspend fun markSentCopyUploadUncertain(id: String, detail: String) = db.withTransaction {
        dao.outboxEntry(id)?.let { markSentCopyUploadUncertainLocked(it, detail) }
    }

    private suspend fun markSentCopyUploadUncertainLocked(entry: OutboxEntity, detail: String) {
        if (entry.state != State.SENT || sentCopyStatus(entry) == SentCopyStatus.CONFIRMED ||
            sentCopyUploadPhase(entry) !in setOf(SentCopyUploadPhase.IN_FLIGHT,
                SentCopyUploadPhase.UNCERTAIN)) return
        val metadata = JSONObject(entry.envelopeJson)
            .put("sentCopyUploadPhase", SentCopyUploadPhase.UNCERTAIN)
        dao.saveOutbox(entry.copy(envelopeJson = metadata.toString(), lastError = detail,
            updatedAt = now().toEpochMilli()))
    }

    suspend fun raw(entry: OutboxEntity): ByteArray = withContext(Dispatchers.IO) {
        val file = File(entry.rawMessagePath)
        require(file.parentFile?.canonicalFile == directory.canonicalFile) { "Outbox path is outside private storage" }
        file.readBytes()
    }

    /** Bcc is intentionally absent from MIME; the durable envelope drives SMTP delivery. */
    fun recipients(entry: OutboxEntity): List<EmailAddress> {
        val saved = JSONObject(entry.envelopeJson)
        val recipients = listOf("to", "cc", "bcc").flatMap { kind ->
            val group = saved.getJSONArray(kind)
            (0 until group.length()).map { index ->
                val address = group.getJSONObject(index)
                EmailAddress(address.getString("address"), address.optString("name"))
            }
        }
        require(recipients.isNotEmpty()) { "Outbox entry has no recipients" }
        return recipients
    }

    /** Recover files orphaned by a crash after Room removed their outbox rows. */
    suspend fun cleanupOrphanFiles() = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!directory.isDirectory) return@withContext
            try {
                val root = directory.canonicalFile
                val stream = Files.newDirectoryStream(directory.toPath())
                try {
                    for (path in stream) {
                        currentCoroutineContext().ensureActive()
                        val file = path.toFile()
                        if (!file.isFile || file.canonicalFile.parentFile != root) continue
                        val name = file.name
                        val orphan = when {
                            name.endsWith(".tmp") -> true
                            name.endsWith(".eml") -> {
                                val id = name.removeSuffix(".eml")
                                val row = dao.outboxEntry(id)
                                row == null || File(row.rawMessagePath).canonicalFile != file.canonicalFile
                            }
                            else -> false
                        }
                        if (orphan) file.delete()
                    }
                } finally {
                    stream.close()
                }
            } catch (_: IOException) {
                // A later app start can retry cleanup without losing the queued MIME rows.
            } catch (_: DirectoryIteratorException) {
                // A partially scanned directory is safe to revisit on the next app start.
            }
        }
    }

    private fun envelope(email: OutgoingEmail): JSONObject {
        fun addresses(values: List<EmailAddress>) = JSONArray().apply {
            values.forEach { put(JSONObject().put("address", it.address).put("name", it.name)) }
        }
        return JSONObject()
            .put("from", addresses(listOf(email.from)))
            .put("to", addresses(email.to))
            .put("cc", addresses(email.cc))
            .put("bcc", addresses(email.bcc))
            .put("sentCopyState", SentCopyStatus.WAITING.name)
    }
}

private object SentCopyUploadPhase {
    const val IN_FLIGHT = "IN_FLIGHT"
    const val UNCERTAIN = "UNCERTAIN"
}

fun sentCopyUploadNeedsReview(entry: OutboxEntity): Boolean =
    sentCopyUploadPhase(entry) in setOf(SentCopyUploadPhase.IN_FLIGHT,
        SentCopyUploadPhase.UNCERTAIN)

fun sentCopyUploadCanRetry(entry: OutboxEntity): Boolean =
    sentCopyUploadPhase(entry) == SentCopyUploadPhase.UNCERTAIN

private fun sentCopyUploadPhase(entry: OutboxEntity): String? =
    JSONObject(entry.envelopeJson).optString("sentCopyUploadPhase").ifBlank { null }

/** Existing rows predate copy tracking; an SMTP-accepted row still needs a Sent lookup. */
fun sentCopyStatus(entry: OutboxEntity): SentCopyStatus =
    when (JSONObject(entry.envelopeJson).optString("sentCopyState")) {
        SentCopyStatus.WAITING.name -> SentCopyStatus.WAITING
        SentCopyStatus.PENDING.name -> SentCopyStatus.PENDING
        SentCopyStatus.CONFIRMED.name -> SentCopyStatus.CONFIRMED
        else -> if (entry.state == DurableOutbox.State.SENT) SentCopyStatus.PENDING
            else SentCopyStatus.WAITING
    }
