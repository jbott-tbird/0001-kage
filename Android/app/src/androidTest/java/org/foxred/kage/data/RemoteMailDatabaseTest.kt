// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.foxred.kage.data.local.*
import org.junit.Assert.*
import org.junit.Test

class RemoteMailDatabaseTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private suspend fun seed(db: MailDatabase) {
        db.mailDao()
            .insertAccounts(
                listOf("a", "b").map {
                    AccountEntity(
                        it,
                        it,
                        "$it@fixture.invalid",
                        "imap.invalid",
                        "smtp.invalid",
                        993,
                        465,
                        "SSL/TLS",
                        "SSL/TLS",
                    )
                }
            )
        db.mailDao()
            .insertFolders(
                listOf("a", "b").map {
                    FolderEntity(
                        "$it-inbox",
                        it,
                        "Inbox",
                        "inbox",
                        null,
                        remotePath = "INBOX",
                        uidValidity = 7,
                    )
                }
            )
    }

    private fun message(
        id: String = "m",
        account: String = "a",
        generation: Long = 7,
        uid: Long = 1,
    ) =
        MessageEntity(
            id = id,
            accountId = account,
            folderId = "$account-inbox",
            sender = "Sender",
            senderAddress = "sender@fixture.invalid",
            to = "$account@fixture.invalid",
            cc = "",
            bcc = "",
            subject = "Remote",
            body = "",
            html = null,
            receivedAt = "2026-09-25T00:00:00Z",
            isRead = false,
            isNew = false,
            flagged = false,
            pinned = false,
            draft = false,
            relatedGroup = null,
            uidValidity = generation,
            uid = uid,
            bodyDownloaded = false,
        )

    private fun operation() =
        PendingOperationEntity(
            "op",
            "a",
            "m",
            "INBOX",
            7,
            1,
            "READ",
            true,
            null,
            createdAt = 1,
            updatedAt = 1,
        )

    private fun outbox() =
        OutboxEntity(
            "send",
            "a",
            "m",
            "<outbox@fixture.invalid>",
            "/private/payload.eml",
            "{\"bcc\":[\"hidden@fixture.invalid\"]}",
            createdAt = 1,
            updatedAt = 1,
        )

    @Test
    fun serverIdentityIsUniqueAndCrossAccountReferencesAreRejected() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        try {
            seed(db)
            db.mailDao()
                .saveMessages(
                    listOf(
                        message(),
                        message("other", "b"),
                        message("new-generation", generation = 8),
                    )
                )
            // Room Upsert can ignore a uniqueness conflict when the alternate local PK is absent.
            // The invariant is that the duplicate never replaces or creates another remote row.
            db.mailDao().saveMessages(listOf(message("duplicate")))
            assertTrue(
                runCatching {
                        db.mailDao()
                            .saveMessages(
                                listOf(message("cross", "b", uid = 2).copy(folderId = "a-inbox"))
                            )
                    }
                    .isFailure
            )
            assertTrue(
                runCatching { db.remoteMailDao().saveOperation(operation().copy(accountId = "b")) }
                    .isFailure
            )
            assertTrue(
                runCatching { db.remoteMailDao().saveOutbox(outbox().copy(accountId = "b")) }
                    .isFailure
            )
            assertEquals("m", db.remoteMailDao().messageByUid("a-inbox", 7, 1)!!.id)
            assertEquals("other", db.remoteMailDao().messageByUid("b-inbox", 7, 1)!!.id)
            assertEquals("new-generation", db.remoteMailDao().messageByUid("a-inbox", 8, 1)!!.id)
            assertNull(db.mailDao().message("duplicate"))
        } finally {
            db.close()
        }
    }

    @Test
    fun cursorAndIntentSurviveRestartRollbackAndCacheCleanup() = runBlocking {
        val name = "remote-state-test"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, MailDatabase::class.java, name).build()
        try {
            seed(db)
            db.mailDao().saveMessages(listOf(message()))
            db.remoteMailDao()
                .saveServers(
                    listOf(
                        ServerEntity(
                            "imap-a",
                            "a",
                            "IMAP",
                            "imap.invalid",
                            993,
                            "TLS",
                            "incoming-user",
                            "PASSWORD",
                        )
                    )
                )
            val cursor = SyncCursorEntity("a-inbox", 7, 99, 1234, null)
            db.remoteMailDao().saveCursor(cursor)
            db.remoteMailDao().saveOperation(operation())
            db.remoteMailDao().saveOutbox(outbox())
            db.close()
            db = Room.databaseBuilder(context, MailDatabase::class.java, name).build()
            assertEquals(cursor, db.remoteMailDao().cursor("a-inbox"))
            assertEquals("incoming-user", db.remoteMailDao().servers("a").single().username)
            assertEquals(operation(), db.remoteMailDao().operations("a").single())
            assertEquals(outbox(), db.remoteMailDao().outbox("a").single())
            assertTrue(
                runCatching {
                        db.withTransaction {
                            db.mailDao().markRead("m", true)
                            db.remoteMailDao().saveCursor(cursor.copy(beforeUid = 40))
                            error("Simulated interruption before commit")
                        }
                    }
                    .isFailure
            )
            assertFalse(db.mailDao().message("m")!!.isRead)
            assertEquals(99L, db.remoteMailDao().cursor("a-inbox")!!.beforeUid)
            assertTrue(runCatching { db.remoteMailDao().removeCachedMessage("m") }.isFailure)
            db.withTransaction {
                db.remoteMailDao().detachOperations("m")
                db.remoteMailDao().detachOutbox("m")
                db.remoteMailDao().removeCachedMessage("m")
            }
            assertNull(db.mailDao().message("m"))
            assertNull(db.remoteMailDao().operations("a").single().messageId)
            assertEquals(1L, db.remoteMailDao().operations("a").single().uid)
            assertNull(db.remoteMailDao().outbox("a").single().draftId)
            assertEquals(
                outbox().envelopeJson,
                db.remoteMailDao().outbox("a").single().envelopeJson,
            )
            db.mailDao().removeAccount("a")
            assertTrue(db.remoteMailDao().servers("a").isEmpty())
            assertTrue(db.remoteMailDao().operations("a").isEmpty())
            assertTrue(db.remoteMailDao().outbox("a").isEmpty())
            assertNull(db.remoteMailDao().cursor("a-inbox"))
            assertEquals("b-inbox", db.mailDao().folder("b", "inbox"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
