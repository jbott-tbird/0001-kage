// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.repository

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.foxred.kage.core.account.*
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.foxred.kage.data.local.CoreRoomMapper
import org.foxred.kage.data.local.MailDatabase

/** Sends durable MIME without re-encoding it. The repository reconciles Sent afterward. */
class OutboxSender(
    private val db: MailDatabase,
    private val outbox: DurableOutbox,
    private val credentials: CredentialProvider,
    private val transportFactory: () -> RawMailSubmission = { AngusSmtpClient() },
    private val authorization: suspend (String, ServerProtocol) -> Authorization? =
        { accountId, protocol -> credentials.authorization(accountId, protocol) },
    private val sentCopyLocks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap(),
    private val onRejectedAuthorization: suspend (String, Authorization) -> Unit = { _, _ -> },
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val recoveredSentCopies = ConcurrentHashMap.newKeySet<String>()
    private val stopped = ConcurrentHashMap.newKeySet<String>()
    private val pauseCounts = ConcurrentHashMap<String, Int>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeTransports = ConcurrentHashMap<String, RawMailSubmission>()

    private fun paused(accountId: String): Boolean =
        accountId in stopped || (pauseCounts[accountId] ?: 0) > 0

    /** Stop delivery and wait for its durable outcome before deleting an account. */
    suspend fun stop(accountId: String) {
        stopped.add(accountId)
        activeTransports[accountId]?.cancel()
        activeJobs[accountId]?.cancel()
        locks.computeIfAbsent(accountId) { Mutex() }.withLock {
            // Wait for the active flush to record its last durable state.
            recoveredSentCopies.remove(accountId)
        }
    }

    /** Wait for an interrupted send to settle before credentials change, then allow later sends. */
    suspend fun <T> withDeliveryPaused(accountId: String, block: suspend () -> T): T {
        pauseCounts.compute(accountId) { _, count -> (count ?: 0) + 1 }
        return try {
            activeTransports[accountId]?.cancel()
            activeJobs[accountId]?.cancel()
            locks.computeIfAbsent(accountId) { Mutex() }.withLock { block() }
        } finally {
            pauseCounts.computeIfPresent(accountId) { _, count ->
                if (count == 1) null else count - 1
            }
        }
    }

    /** A previous process may have died after SMTP accepted DATA; hold those rows for review. */
    suspend fun recover(accountId: String) = locks.computeIfAbsent(accountId) { Mutex() }.withLock {
        recoverLocked(accountId)
    }

    /** Process pending rows in order. A failed or uncertain row remains visible and stops this pass. */
    suspend fun flush(accountId: String) =
        locks.computeIfAbsent(accountId) { Mutex() }.withLock {
            if (paused(accountId)) return@withLock
            val job = currentCoroutineContext()[Job]
            if (job != null) activeJobs[accountId] = job
            try {
                recoverLocked(accountId)
                while (!paused(accountId)) {
                    currentCoroutineContext().ensureActive()
                    val entry = outbox.claimNext(accountId) ?: break
                    val submissionStarted = AtomicBoolean(false)
                    var transport: RawMailSubmission? = null
                    var usedAuthorization: Authorization? = null
                    try {
                        val server = db.remoteMailDao().servers(accountId)
                            .firstOrNull { it.protocol == ServerProtocol.SMTP.name }
                            ?.let(CoreRoomMapper::server)
                            ?: throw MailFailure(FailureKind.AUTHENTICATION,
                                "Account has no outgoing server")
                        val authorization = withContext(Dispatchers.IO) {
                            authorization(accountId, ServerProtocol.SMTP)
                        } ?: throw MailFailure(FailureKind.AUTHENTICATION,
                            "Sign in again to send from this account")
                        usedAuthorization = authorization
                        val raw = outbox.raw(entry)
                        val recipients = outbox.recipients(entry)
                        transport = transportFactory()
                        activeTransports[accountId] = transport
                        if (paused(accountId)) transport.cancel()
                        currentCoroutineContext().ensureActive()
                        sendInterruptibly(transport, server, authorization, raw, recipients) {
                            submissionStarted.set(true)
                        }
                        withContext(NonCancellable) { outbox.finish(entry.id, DurableOutbox.State.SENT) }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            if (submissionStarted.get())
                                outbox.finish(entry.id, DurableOutbox.State.UNCERTAIN,
                                    "Submission interrupted; check Sent before retrying")
                            else outbox.releaseUnstartedClaim(entry.id)
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        val rejected = usedAuthorization
                        if (rejected?.kind == Authorization.Kind.OAUTH2 &&
                            failure is MailFailure && failure.kind == FailureKind.AUTHENTICATION)
                            withContext(NonCancellable) {
                                runCatching { withTimeout(5_000) {
                                    onRejectedAuthorization(accountId, rejected)
                                } }.exceptionOrNull()?.let(failure::addSuppressed)
                            }
                        if (!submissionStarted.get() && (paused(accountId) ||
                                (failure is MailFailure && failure.kind == FailureKind.CANCELLED))) {
                            // A stopped or canceled connection never began SMTP submission.
                            // Keep the original durable intent for the next foreground pass.
                            withContext(NonCancellable) { outbox.releaseUnstartedClaim(entry.id) }
                            break
                        }
                        // Once the envelope may have started, even a protocol rejection cannot
                        // prove that no recipient was accepted by an opaque transport.
                        val uncertain = submissionStarted.get()
                        val outcome = if (uncertain) DurableOutbox.State.UNCERTAIN
                            else DurableOutbox.State.FAILED
                        val detail = if (uncertain) "Delivery outcome is unknown; check Sent before retrying"
                            else if (failure is MailFailure) failure.message
                            else "Queued message could not be prepared; save it again"
                        withContext(NonCancellable) { outbox.finish(entry.id, outcome, detail) }
                        break
                    } finally {
                        transport?.let { activeTransports.remove(accountId, it) }
                    }
                }
            } finally {
                if (job != null) activeJobs.remove(accountId, job)
            }
        }

    private suspend fun recoverLocked(accountId: String) {
        // A failed Room write can leave SENDING behind in this process. The account lock
        // excludes active sends, so every later pass can safely recover those rows. Sent-copy
        // APPEND has its own lock and may be active; its recovery must wait for that upload.
        val includeSentCopyUploads = recoveredSentCopies.add(accountId)
        try {
            if (includeSentCopyUploads)
                sentCopyLocks.computeIfAbsent(accountId) { Mutex() }.withLock {
                    outbox.recoverInterrupted(accountId)
                }
            else outbox.recoverInterrupted(accountId, includeSentCopyUploads = false)
        } catch (failure: Throwable) {
            if (includeSentCopyUploads) recoveredSentCopies.remove(accountId)
            throw failure
        }
    }

    /** Coroutine cancellation closes the active SMTP socket, then the caller records uncertainty. */
    private suspend fun sendInterruptibly(
        transport: RawMailSubmission,
        server: Server,
        authorization: Authorization,
        raw: ByteArray,
        recipients: List<EmailAddress>,
        onSubmissionStart: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        coroutineScope {
            val finished = AtomicBoolean(false)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) transport.cancel()
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                transport.sendRaw(server, authorization, raw, recipients, onSubmissionStart)
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
    }
}
