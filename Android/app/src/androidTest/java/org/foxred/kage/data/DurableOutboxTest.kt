// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.CredentialProvider
import org.foxred.kage.core.account.EmailAddress
import org.foxred.kage.core.account.EmailBody
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.MessageIdentity
import org.foxred.kage.core.account.OutgoingEmail
import org.foxred.kage.core.account.RawMailSubmission
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.data.local.AccountEntity
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.local.OutboxEntity
import org.foxred.kage.data.local.ServerEntity
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.repository.OutboxSender
import org.foxred.kage.data.repository.sentCopyStatus
import org.foxred.kage.data.repository.sentCopyUploadCanRetry
import org.foxred.kage.data.repository.sentCopyUploadNeedsReview
import org.foxred.kage.domain.model.SentCopyStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class DurableOutboxTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseName = "durable-outbox-test"
    private val databases = mutableListOf<MailDatabase>()

    private fun open() = Room.databaseBuilder(context, MailDatabase::class.java, databaseName)
        .build().also { databases += it }

    private fun outbox(db: MailDatabase) = DurableOutbox(db, context, AngusMimeCodec())

    private fun email(id: String = "<queued@example.test>") = OutgoingEmail(
        id, EmailAddress("from@example.test"), listOf(EmailAddress("to@example.test")),
        bcc = listOf(EmailAddress("hidden@example.test")), subject = "Queued mail",
        body = EmailBody("Durable body", null), sentAt = Instant.EPOCH,
    )

    @Before fun prepare() { context.deleteDatabase(databaseName) }

    @After fun cleanup() {
        databases.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    @Test fun mimeAndEnvelopeSurviveRestartAndDuplicateQueue() = runBlocking {
        var db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val first = outbox(db).enqueue("a", email())
        assertEquals("Durable body", AngusMimeCodec().decode(outbox(db).raw(first)).body.text)
        assertEquals("hidden@example.test", JSONObject(first.envelopeJson)
            .getJSONArray("bcc").getJSONObject(0).getString("address"))
        db.close()
        db = open()
        assertEquals(first.id, outbox(db).enqueue("a", email()).id)
        assertEquals(1, outbox(db).entries("a").size)
        assertArrayEquals(outbox(db).raw(first), outbox(db).raw(outbox(db).entries("a").single()))
        assertEquals(listOf("to@example.test", "hidden@example.test"),
            outbox(db).recipients(outbox(db).entries("a").single()).map { it.address })
    }

    @Test fun interruptedSubmissionIsHeldForReconciliation() = runBlocking {
        var db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().insertAccount(account("b"))
        outbox(db).enqueue("a", email())
        outbox(db).enqueue("b", email())
        val claim = outbox(db).claimNext("a")!!
        assertEquals(DurableOutbox.State.SENDING, claim.state)
        db.close()
        db = open()
        val resumed = outbox(db)
        resumed.recoverInterrupted("a")
        assertNull(resumed.claimNext("a"))
        assertEquals(DurableOutbox.State.UNCERTAIN, resumed.entries("a").single().state)
        assertEquals(DurableOutbox.State.PENDING, resumed.entries("b").single().state)
        assertTrue(runCatching { resumed.retryFailed(claim.id) }.isFailure)
        assertTrue(resumed.confirmSentCopy(claim.id, MessageIdentity("Sent", 77, 42)))
        assertEquals(DurableOutbox.State.SENT, resumed.entries("a").single().state)
        assertEquals(1, resumed.entries("a").single().attempts)
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(resumed.entries("a").single()))
        assertFalse(resumed.confirmSentCopy(claim.id, MessageIdentity("Sent", 77, 42)))
    }

    @Test fun recoveryScansEveryStatePageWithoutTouchingOtherAccounts() = runBlocking {
        val db = open()
        val dao = db.remoteMailDao()
        dao.insertAccount(account("a"))
        dao.insertAccount(account("b"))
        repeat(70) { index ->
            dao.saveOutbox(OutboxEntity("sending-$index", "a", null,
                "<sending-$index@example.test>", "unused", "{}",
                DurableOutbox.State.SENDING, createdAt = index.toLong(), updatedAt = 0))
            dao.saveOutbox(OutboxEntity("sent-$index", "a", null,
                "<sent-$index@example.test>", "unused",
                "{\"sentCopyUploadPhase\":\"IN_FLIGHT\"}",
                DurableOutbox.State.SENT, createdAt = index.toLong(), updatedAt = 0))
        }
        dao.saveOutbox(OutboxEntity("other", "b", null, "<other@example.test>",
            "unused", "{}", DurableOutbox.State.SENDING, createdAt = 0, updatedAt = 0))
        val queue = outbox(db)

        queue.recoverInterrupted("a")

        val recovered = queue.entries("a")
        assertEquals(70, recovered.count { it.state == DurableOutbox.State.UNCERTAIN })
        assertEquals(70, recovered.count { it.state == DurableOutbox.State.SENT &&
            sentCopyUploadCanRetry(it) })
        assertEquals(DurableOutbox.State.SENDING, queue.entries("b").single().state)
    }

    @Test fun interruptedRecoveryResumesAfterItsLastCommittedPage() = runBlocking {
        val db = open()
        val dao = db.remoteMailDao()
        dao.insertAccount(account("a"))
        repeat(70) { index ->
            dao.saveOutbox(OutboxEntity("sending-$index", "a", null,
                "<sending-$index@example.test>", "unused", "{}",
                DurableOutbox.State.SENDING, createdAt = index.toLong(), updatedAt = 0))
        }
        val trigger = "fail_recovery_${System.nanoTime()}"
        try {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE UPDATE ON outbox " +
                "WHEN OLD.id = 'sending-64' AND NEW.state = 'UNCERTAIN' " +
                "BEGIN SELECT RAISE(ABORT, 'recovery interrupted'); END")
            assertNotNull(runCatching { outbox(db).recoverInterrupted("a") }.exceptionOrNull())
            assertEquals(64, outbox(db).entries("a")
                .count { it.state == DurableOutbox.State.UNCERTAIN })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")

            outbox(db).recoverInterrupted("a")
            assertEquals(70, outbox(db).entries("a")
                .count { it.state == DurableOutbox.State.UNCERTAIN })
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
        }
    }

    @Test fun senderRechecksUnsettledClaimsLaterInTheSameProcess() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization.none()
        })
        sender.recover("a")
        queue.claimNext("a")

        sender.recover("a")

        val held = queue.entries("a").single()
        assertEquals(DurableOutbox.State.UNCERTAIN, held.state)
        assertEquals(1, held.attempts)
    }

    @Test fun repeatedSenderRecoveryLeavesActiveSentCopyUploadInFlight() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val queue = outbox(db)
        val queued = queue.enqueue("a", email("<active-copy@example.test>"))
        queue.claimNext("a")
        queue.finish(queued.id, DurableOutbox.State.SENT)
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization.none()
        })
        sender.recover("a")
        assertTrue(queue.claimSentCopyUpload(queued.id))

        sender.recover("a")

        val active = queue.entries("a").single()
        assertEquals(DurableOutbox.State.SENT, active.state)
        assertTrue(sentCopyUploadNeedsReview(active))
        assertFalse(sentCopyUploadCanRetry(active))
    }

    @Test fun initialSenderRecoveryWaitsForActiveSentCopyUpload() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val queue = outbox(db)
        val queued = queue.enqueue("a", email("<copy-race@example.test>"))
        queue.claimNext("a")
        queue.finish(queued.id, DurableOutbox.State.SENT)
        assertTrue(queue.claimSentCopyUpload(queued.id))
        val copyLock = Mutex(locked = true)
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization.none()
        }, sentCopyLocks = ConcurrentHashMap(mapOf("a" to copyLock)))

        val recovering = async(start = CoroutineStart.UNDISPATCHED) { sender.recover("a") }
        try {
            assertFalse(recovering.isCompleted)
            assertTrue(queue.confirmSentCopy(queued.id, MessageIdentity("Sent", 77, 45)))
        } finally {
            copyLock.unlock()
        }
        recovering.await()

        val saved = queue.entries("a").single()
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(saved))
        assertFalse(sentCopyUploadNeedsReview(saved))
    }

    @Test fun acceptedSubmissionKeepsSentCopyPendingAcrossRestart() = runBlocking {
        var db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val queued = outbox(db).enqueue("a", email("<accepted@example.test>"))
        outbox(db).claimNext("a")
        outbox(db).finish(queued.id, DurableOutbox.State.SENT)
        assertEquals(SentCopyStatus.PENDING, sentCopyStatus(outbox(db).entries("a").single()))
        db.close()
        db = open()
        val resumed = outbox(db)
        val persisted = resumed.entries("a").single()
        assertEquals(DurableOutbox.State.SENT, persisted.state)
        assertEquals(SentCopyStatus.PENDING, sentCopyStatus(persisted))
        assertTrue(resumed.confirmSentCopy(persisted.id, MessageIdentity("Sent", 77, 43)))
        val confirmed = resumed.entries("a").single()
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(confirmed))
        val receipt = JSONObject(confirmed.envelopeJson)
        assertEquals("Sent", receipt.getString("sentCopyMailbox"))
        assertEquals(77L, receipt.getLong("sentCopyUidValidity"))
        assertEquals(43L, receipt.getLong("sentCopyUid"))
    }

    @Test fun interruptedSentCopyAppendIsHeldForReviewAfterRestart() = runBlocking {
        var db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val queued = outbox(db).enqueue("a", email("<copy-interrupted@example.test>"))
        outbox(db).claimNext("a")
        outbox(db).finish(queued.id, DurableOutbox.State.SENT)
        assertTrue(outbox(db).claimSentCopyUpload(queued.id))
        db.close()

        db = open()
        val resumed = outbox(db)
        resumed.recoverInterrupted("a")
        val held = resumed.entries("a").single()
        assertEquals(DurableOutbox.State.SENT, held.state)
        assertTrue(sentCopyUploadNeedsReview(held))
        assertFalse(resumed.claimSentCopyUpload(queued.id))
        assertTrue(resumed.claimReviewedSentCopyUpload(queued.id))
        assertFalse(resumed.claimSentCopyUpload(queued.id))
        assertTrue(resumed.confirmSentCopy(queued.id, MessageIdentity("Sent", 77, 44)))
        assertFalse(sentCopyUploadNeedsReview(resumed.entries("a").single()))
    }

    @Test fun preAppendReleasePreservesEarlierCopyUncertainty() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val queue = outbox(db)
        val entry = queue.enqueue("a", email("<pre-append@example.test>"))
        queue.claimNext("a")
        queue.finish(entry.id, DurableOutbox.State.SENT)

        assertTrue(queue.claimSentCopyUpload(entry.id))
        queue.releaseUnstartedSentCopyUpload(entry.id, retryAfterReview = false)
        assertFalse(sentCopyUploadNeedsReview(queue.entries("a").single()))
        assertTrue(queue.claimSentCopyUpload(entry.id))
        queue.markSentCopyUploadUncertain(entry.id, "Earlier APPEND may have succeeded")
        assertTrue(queue.claimReviewedSentCopyUpload(entry.id))
        queue.releaseUnstartedSentCopyUpload(entry.id, retryAfterReview = true)
        assertTrue(sentCopyUploadCanRetry(queue.entries("a").single()))
        assertEquals(DurableOutbox.State.SENT, queue.entries("a").single().state)
    }

    @Test fun olderAcceptedRowsWithoutCopyMetadataStillNeedReconciliation() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        val queued = outbox(db).enqueue("a", email("<legacy@example.test>"))
        val legacy = queued.copy(state = DurableOutbox.State.SENT,
            envelopeJson = JSONObject(queued.envelopeJson).apply {
                remove("sentCopyState")
            }.toString())
        db.remoteMailDao().saveOutbox(legacy)
        assertEquals(SentCopyStatus.PENDING, sentCopyStatus(outbox(db).entries("a").single()))
        assertTrue(outbox(db).confirmSentCopy(legacy.id, MessageIdentity("Sent", 77, 44)))
        assertEquals(SentCopyStatus.CONFIRMED, sentCopyStatus(outbox(db).entries("a").single()))
    }

    @Test fun definiteFailureCanRetryButMissingAccountCannotQueue() = runBlocking {
        val db = open()
        val queue = outbox(db)
        assertTrue(runCatching { queue.enqueue("missing", email()) }.isFailure)
        assertTrue(queue.entries("missing").isEmpty())
        db.remoteMailDao().insertAccount(account("a"))
        val entry = queue.enqueue("a", email())
        queue.claimNext("a")
        queue.finish(entry.id, DurableOutbox.State.FAILED, "Rejected before DATA")
        queue.retryFailed(entry.id)
        assertEquals(DurableOutbox.State.SENDING, queue.claimNext("a")!!.state)
        assertEquals(2, queue.entries("a").single().attempts)
    }

    @Test fun senderKeepsUnknownDeliveryOutOfAutomaticRetry() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        val first = queue.enqueue("a", email("<first@example.test>"))
        var expectedRaw = queue.raw(first)
        var failure: FailureKind? = FailureKind.UNCERTAIN_DELIVERY
        var submissions = 0
        val senderFactory = {
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    submissions++
                    assertArrayEquals(expectedRaw, raw)
                    assertEquals(listOf("to@example.test", "hidden@example.test"),
                        recipients.map { it.address })
                    failure?.let { throw MailFailure(it, "Fixture delivery result") }
                }
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>,
                    onSubmissionStart: () -> Unit) {
                    // This fixture's protocol rejection happens before the SMTP envelope.
                    if (failure != FailureKind.PROTOCOL) onSubmissionStart()
                    sendRaw(server, authorization, raw, recipients)
                }
            }
        }
        val credentials = object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }
        val sender = OutboxSender(db, queue, credentials, senderFactory)
        sender.flush("a")
        assertEquals(DurableOutbox.State.UNCERTAIN,
            db.remoteMailDao().outboxEntry(first.id)?.state)
        assertEquals(1, submissions)
        sender.flush("a")
        assertEquals(DurableOutbox.State.UNCERTAIN,
            db.remoteMailDao().outboxEntry(first.id)?.state)
        assertEquals(1, submissions)

        val second = queue.enqueue("a", email("<second@example.test>"))
        expectedRaw = queue.raw(second)
        failure = FailureKind.PROTOCOL
        sender.flush("a")
        assertEquals(DurableOutbox.State.FAILED,
            db.remoteMailDao().outboxEntry(second.id)?.state)
        queue.retryFailed(second.id)
        failure = null
        sender.flush("a")
        assertEquals(DurableOutbox.State.SENT,
            db.remoteMailDao().outboxEntry(second.id)?.state)
        assertEquals(3, submissions)

        val third = queue.enqueue("a", email("<third@example.test>"))
        expectedRaw = queue.raw(third)
        failure = FailureKind.CONNECTION
        sender.flush("a")
        assertEquals(DurableOutbox.State.UNCERTAIN,
            db.remoteMailDao().outboxEntry(third.id)?.state)
        sender.flush("a")
        assertEquals(DurableOutbox.State.UNCERTAIN,
            db.remoteMailDao().outboxEntry(third.id)?.state)
        assertEquals(4, submissions)
    }

    @Test fun protocolFailureAfterSubmissionStartsRequiresReview() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        val entry = queue.enqueue("a", email())
        var submissions = 0
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    submissions++
                    throw MailFailure(FailureKind.PROTOCOL, "Server disconnected after envelope")
                }
            }
        })

        sender.flush("a")
        assertEquals(DurableOutbox.State.UNCERTAIN, queue.entries("a").single().state)
        assertTrue(runCatching { queue.retryFailed(entry.id) }.isFailure)
        sender.flush("a")
        assertEquals(1, submissions)
    }

    @Test fun cancellingActiveSubmissionLeavesAnUncertainEntry() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() { released.countDown() }
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    entered.countDown()
                    check(released.await(5, TimeUnit.SECONDS)) { "Transport was not cancelled" }
                    throw MailFailure(FailureKind.UNCERTAIN_DELIVERY, "Interrupted after DATA")
                }
            }
        })
        val job = async(Dispatchers.Default) { sender.flush("a") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals(DurableOutbox.State.UNCERTAIN, queue.entries("a").single().state)
    }

    @Test fun cancellationWhileResolvingCredentialsKeepsTheMessageQueued() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val entered = CompletableDeferred<Unit>()
        val credentials = object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }
        val waiting = OutboxSender(db, queue, credentials,
            transportFactory = { error("SMTP must not start during credential lookup") },
            authorization = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            })
        val job = async(Dispatchers.Default) { waiting.flush("a") }
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { job.cancelAndJoin() }
        val queued = queue.entries("a").single()
        assertEquals(DurableOutbox.State.PENDING, queued.state)
        assertEquals(0, queued.attempts)

        val retry = OutboxSender(db, queue, credentials, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) = Unit
            }
        })
        retry.flush("a")
        assertEquals(DurableOutbox.State.SENT, queue.entries("a").single().state)
        assertEquals(1, queue.entries("a").single().attempts)
    }

    @Test fun rejectedGoogleSmtpTokenIsInvalidatedWithoutClaimingDelivery() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "OAUTH2")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val token = Authorization("rejected", Authorization.Kind.OAUTH2,
            Instant.now().plusSeconds(3600))
        val invalidations = mutableListOf<Authorization>()
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) = token
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>,
                    onSubmissionStart: () -> Unit) {
                    throw MailFailure(FailureKind.AUTHENTICATION, "SMTP authentication failed")
                }
            }
        }, onRejectedAuthorization = { _, rejected -> invalidations += rejected })

        sender.flush("a")

        assertEquals(listOf(token), invalidations)
        assertEquals(DurableOutbox.State.FAILED, queue.entries("a").single().state)
    }

    @Test fun cancellationWhilePreparingTransportDoesNotClaimSmtpStarted() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val submitted = AtomicBoolean(false)
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "Transport setup was not released" }
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    submitted.set(true)
                }
            }
        })
        val job = async(Dispatchers.Default) { sender.flush("a") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        released.countDown()
        withTimeout(5_000) { job.cancelAndJoin() }
        assertFalse(submitted.get())
        val queued = queue.entries("a").single()
        assertEquals(DurableOutbox.State.PENDING, queued.state)
        assertEquals(0, queued.attempts)
    }

    @Test fun cancellationDuringSmtpConnectionLeavesTheClaimQueued() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val connecting = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() { disconnected.countDown() }
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    error("The sender must use the submission-start callback")
                }
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>,
                    onSubmissionStart: () -> Unit) {
                    connecting.countDown()
                    check(disconnected.await(5, TimeUnit.SECONDS)) { "Connection was not cancelled" }
                    throw MailFailure(FailureKind.CONNECTION, "Connection closed before MAIL FROM")
                }
            }
        })
        val job = async(Dispatchers.Default) { sender.flush("a") }
        assertTrue(connecting.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }
        val queued = queue.entries("a").single()
        assertEquals(DurableOutbox.State.PENDING, queued.state)
        assertEquals(0, queued.attempts)
    }

    @Test fun transportCancellationBeforeSubmissionCanBeFlushedAgain() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        var attempts = 0
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) =
                    error("The sender must use the submission-start callback")
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>,
                    onSubmissionStart: () -> Unit) {
                    attempts++
                    if (attempts == 1)
                        throw MailFailure(FailureKind.CANCELLED, "Closed before SMTP submission")
                    onSubmissionStart()
                }
            }
        })

        sender.flush("a")
        assertEquals(DurableOutbox.State.PENDING, queue.entries("a").single().state)
        assertEquals(0, queue.entries("a").single().attempts)
        sender.flush("a")
        assertEquals(2, attempts)
        assertEquals(DurableOutbox.State.SENT, queue.entries("a").single().state)
    }

    @Test fun credentialTypeMismatchFailsBeforeSmtpSubmission() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "OAUTH2")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("old-app-password")
        })
        sender.flush("a")
        val entry = queue.entries("a").single()
        assertEquals(DurableOutbox.State.FAILED, entry.state)
        assertTrue(entry.lastError.orEmpty().contains("Sign-in method"))
    }

    @Test fun stoppingAccountWaitsForActiveSubmissionBeforeDeletion() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() { released.countDown() }
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    entered.countDown()
                    check(released.await(5, TimeUnit.SECONDS)) { "Transport was not cancelled" }
                    throw MailFailure(FailureKind.UNCERTAIN_DELIVERY, "Interrupted after DATA")
                }
            }
        })
        val job = async(Dispatchers.Default) { sender.flush("a") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { sender.stop("a") }
        assertTrue(job.isCancelled)
        assertEquals(DurableOutbox.State.UNCERTAIN, queue.entries("a").single().state)
        db.remoteMailDao().removeAccount("a")
        assertTrue(queue.entries("a").isEmpty())
    }

    @Test fun temporaryPauseCannotResumeAPermanentlyStoppedSender() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        var submissions = 0
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    submissions++
                }
            }
        })

        sender.stop("a")
        sender.withDeliveryPaused("a") { Unit }
        sender.flush("a")

        assertEquals(0, submissions)
        assertEquals(DurableOutbox.State.PENDING, queue.entries("a").single().state)
    }

    @Test fun onePauseEndingCannotSendWhileAnotherPauseIsWaiting() = runBlocking {
        val db = open()
        db.remoteMailDao().insertAccount(account("a"))
        db.remoteMailDao().saveServers(listOf(ServerEntity(
            "smtp-a", "a", "SMTP", "fixture.invalid", 465, "TLS", "a", "PASSWORD")))
        val queue = outbox(db)
        queue.enqueue("a", email())
        var submissions = 0
        val sender = OutboxSender(db, queue, object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }, transportFactory = {
            object : RawMailSubmission {
                override fun cancel() = Unit
                override fun sendRaw(server: Server, authorization: Authorization,
                    raw: ByteArray, recipients: List<EmailAddress>) {
                    submissions++
                }
            }
        })
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            sender.withDeliveryPaused("a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val flush = async(start = CoroutineStart.UNDISPATCHED) { sender.flush("a") }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            sender.withDeliveryPaused("a") {
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
        }
        releaseFirst.complete(Unit)
        withTimeout(5_000) { flush.await() }
        assertEquals(0, submissions)
        assertEquals(DurableOutbox.State.PENDING, queue.entries("a").single().state)
        withTimeout(5_000) { secondEntered.await() }
        releaseSecond.complete(Unit)
        withTimeout(5_000) { first.await(); second.await() }
    }

    private fun account(id: String) = AccountEntity(
        id, id, "$id@example.test", "imap.example.test", "smtp.example.test",
        993, 465, "SSL/TLS", "SSL/TLS",
    )
}
