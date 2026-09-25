// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foxred.kage.core.account.EmailAddress
import org.foxred.kage.core.account.MimeCodec
import org.foxred.kage.core.account.OutgoingEmail
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.local.OutboxEntity
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
                directory.mkdirs()
                val target = File(directory, "$id.eml")
                val temporary = File(directory, "$id.tmp")
                try {
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
                        if (draftId != null) {
                            val draft = checkNotNull(dao.message(draftId)) { "Draft was removed" }
                            require(draft.accountId == accountId && draft.draft) { "Draft belongs to another account" }
                        }
                        // A second caller may have inserted this Message-ID while MIME was encoded.
                        dao.outboxByMessageId(accountId, email.messageId)?.let { return@withTransaction it }
                            ?: row.also { dao.saveOutbox(it) }
                    }.also { if (it.id != id) target.delete() }
                } catch (error: Throwable) {
                    temporary.delete()
                    target.delete()
                    throw error
                }
            }
        }

    suspend fun entries(accountId: String): List<OutboxEntity> = dao.outbox(accountId)

    /** A process death during submission has an unknown delivery result. Never auto retry it. */
    suspend fun recoverInterrupted(accountId: String) = db.withTransaction {
        dao.outbox(accountId).filter { it.state == State.SENDING }.forEach {
            dao.saveOutbox(it.copy(state = State.UNCERTAIN, lastError = "Submission interrupted; reconcile Sent before retrying", updatedAt = now().toEpochMilli()))
        }
    }

    /** Claims a single pending entry. A caller must finish or reconcile the claim. */
    suspend fun claimNext(accountId: String): OutboxEntity? = db.withTransaction {
        val next = dao.outbox(accountId).firstOrNull { it.state == State.PENDING } ?: return@withTransaction null
        next.copy(state = State.SENDING, attempts = next.attempts + 1, updatedAt = now().toEpochMilli())
            .also { dao.saveOutbox(it) }
    }

    suspend fun finish(id: String, outcome: String, error: String? = null) = db.withTransaction {
        require(outcome in setOf(State.SENT, State.FAILED, State.UNCERTAIN))
        val current = checkNotNull(dao.outboxEntry(id)) { "Outbox entry was removed" }
        check(current.state == State.SENDING) { "Outbox entry is not being sent" }
        dao.saveOutbox(current.copy(state = outcome, lastError = error, updatedAt = now().toEpochMilli()))
    }

    /** Only a definite rejection is retryable without first checking the provider. */
    suspend fun retryFailed(id: String) = db.withTransaction {
        val current = checkNotNull(dao.outboxEntry(id)) { "Outbox entry was removed" }
        check(current.state == State.FAILED) { "Only a confirmed failure can be retried" }
        dao.saveOutbox(current.copy(state = State.PENDING, lastError = null, updatedAt = now().toEpochMilli()))
    }

    /** Called after Sent reconciliation establishes that an uncertain message was delivered. */
    suspend fun confirmDelivered(id: String) = db.withTransaction {
        val current = checkNotNull(dao.outboxEntry(id)) { "Outbox entry was removed" }
        check(current.state == State.UNCERTAIN) { "Outbox entry is not awaiting reconciliation" }
        dao.saveOutbox(current.copy(state = State.SENT, lastError = null, updatedAt = now().toEpochMilli()))
    }

    suspend fun raw(entry: OutboxEntity): ByteArray = withContext(Dispatchers.IO) {
        val file = File(entry.rawMessagePath)
        require(file.parentFile?.canonicalFile == directory.canonicalFile) { "Outbox path is outside private storage" }
        file.readBytes()
    }

    /** Account rows have already been removed; delete only files owned by this private store. */
    suspend fun removeFiles(entries: List<OutboxEntity>) = withContext(Dispatchers.IO) {
        entries.forEach { entry ->
            val file = File(entry.rawMessagePath)
            if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
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
    }
}
