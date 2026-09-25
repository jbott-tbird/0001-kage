package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.foxred.kage.core.account.EmailAddress
import org.foxred.kage.core.account.EmailBody
import org.foxred.kage.core.account.OutgoingEmail
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.data.local.AccountEntity
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.DurableOutbox
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
        resumed.confirmDelivered(claim.id)
        assertEquals(DurableOutbox.State.SENT, resumed.entries("a").single().state)
        assertEquals(1, resumed.entries("a").single().attempts)
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

    private fun account(id: String) = AccountEntity(
        id, id, "$id@example.test", "imap.example.test", "smtp.example.test",
        993, 465, "SSL/TLS", "SSL/TLS",
    )
}
