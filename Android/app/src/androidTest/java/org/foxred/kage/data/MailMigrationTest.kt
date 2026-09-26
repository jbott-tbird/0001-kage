package org.foxred.kage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.foxred.kage.data.local.MIGRATION_1_2
import org.foxred.kage.data.local.MIGRATION_2_3
import org.foxred.kage.data.local.MIGRATION_3_4
import org.foxred.kage.data.local.MIGRATION_4_5
import org.foxred.kage.data.local.MailDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MailMigrationTest {
    @Test
    fun migrationFromV1PreservesAccountsAndAddsSafeDefaults() = runBlocking { verifyMigration(1) }

    @Test
    fun migrationFromV2PreservesCachedAttachmentsAndSmtpSettings() = runBlocking {
        verifyMigration(2)
    }

    @Test
    fun migrationFromV3PreservesRemoteCursorAndMessages() = runBlocking {
        verifyMigration(3)
    }

    @Test
    fun migrationFromV4PreservesPendingActions() = runBlocking { verifyMigration(4) }

    private suspend fun verifyMigration(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test-$version"
        context.deleteDatabase(name)
        val schema =
            JSONObject(
                    InstrumentationRegistry.getInstrumentation()
                        .context
                        .assets
                        .open("org.foxred.kage.data.local.MailDatabase/$version.json")
                        .bufferedReader()
                        .use { it.readText() }
                )
                .getJSONObject("database")
        // Create the exact exported v1 schema, then let the real Room database migrate and validate
        // it.
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(
                    indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                )
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) db.execSQL(queries.getString(i))
            db.execSQL(
                "INSERT INTO accounts (id, name, address, incoming, outgoing, incomingPort, outgoingPort, security, outgoingSecurity) VALUES ('personal', 'Rhea', 'rhea@example.com', 'imap.example.com', 'smtp.example.com', 993, 465, 'SSL/TLS', 'SSL/TLS')"
            )
            db.execSQL(
                "INSERT INTO folders (id, accountId, name, role, parentId) VALUES ('personal-inbox', 'personal', 'Inbox', 'inbox', NULL)"
            )
            db.execSQL(
                "INSERT INTO messages (id, accountId, folderId, sender, senderAddress, `to`, cc, bcc, subject, body, html, receivedAt, isRead, isNew, flagged, pinned, draft, relatedGroup) VALUES ('kept', 'personal', 'personal-inbox', 'Roc', 'roc@example.net', 'rhea@example.com', '', '', 'Preserve me', 'Stored before update', NULL, '2026-09-24', 1, 0, 1, 0, 0, NULL)"
            )
            db.execSQL(
                "INSERT INTO attachments (id, messageId, filename, mimeType, sizeBytes, cached, asset) VALUES ('kept-pdf', 'kept', 'ticket.pdf', 'application/pdf', 2400, 0, 'sample-ticket.pdf')"
            )
            if (version >= 2) {
                db.execSQL("UPDATE accounts SET requireAuth = 0")
                db.execSQL("UPDATE attachments SET cached = 1, localFile = '/private/kept.pdf'")
            }
            if (version >= 3) {
                db.execSQL("UPDATE attachments SET downloadState = 'DOWNLOADED', downloadedBytes = 2400")
                db.execSQL("INSERT INTO servers VALUES ('imap-v3', 'personal', 'IMAP', 'imap.example.com', 993, 'TLS', 'rhea@example.com', 'PASSWORD')")
                db.execSQL("INSERT INTO servers VALUES ('smtp-v3', 'personal', 'SMTP', 'smtp.example.com', 465, 'TLS', 'rhea@example.com', 'NONE')")
                if (version == 3)
                    db.execSQL("INSERT INTO sync_cursors VALUES ('personal-inbox', 77, 42, 1234, NULL)")
                else {
                    db.execSQL("INSERT INTO sync_cursors (folderId, uidValidity, beforeUid, sinceEpochMillis) VALUES ('personal-inbox', 77, 42, 1234)")
                    db.execSQL("INSERT INTO pending_operations (id, accountId, messageId, mailbox, uidValidity, uid, kind, targetMailbox, state, attempts, createdAt, updatedAt) VALUES ('move-v4', 'personal', 'kept', 'INBOX', 77, 1, 'MOVE', 'Archive', 'PENDING', 2, 1, 1)")
                }
            }
            db.version = version
        }
        val upgraded =
            Room.databaseBuilder(context, MailDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        try {
            val account = upgraded.mailDao().accounts().first().single()
            assertEquals("rhea@example.com", account.address)
            assertEquals(version == 1, account.requireAuth)
            assertEquals("DEMO", account.mode)
            val servers = upgraded.remoteMailDao().servers("personal")
            assertEquals(2, servers.size)
            assertEquals("rhea@example.com", servers.first().username)
            assertEquals("TLS", servers.first().security)
            assertEquals(
                if (version == 1) "PASSWORD" else "NONE",
                servers.first { it.protocol == "SMTP" }.authenticationType,
            )
            val message = upgraded.mailDao().message("kept")!!
            assertEquals("Preserve me", message.subject)
            assertTrue(message.isRead)
            assertTrue(message.flagged)
            assertTrue(message.bodyDownloaded)
            assertNull(message.lastSeenPassId)
            if (version >= 3) {
                val cursor = upgraded.remoteMailDao().cursor("personal-inbox")!!
                assertEquals(42L, cursor.beforeUid)
                assertNull(cursor.fullPassId)
                assertNull(cursor.highestModSeq)
            }
            if (version == 4) {
                val action = upgraded.remoteMailDao().operations("personal").single()
                assertEquals("MOVE", action.kind)
                assertNull(action.moveTargetUidNext)
                assertNull(action.moveSourceMessageId)
                assertEquals("LEGACY_UNCERTAIN", action.moveMode)
            }
            assertNull(message.uid)
            val attachment = upgraded.mailDao().attachment("kept-pdf")!!
            assertEquals(if (version >= 2) "/private/kept.pdf" else null, attachment.localFile)
            assertEquals(
                if (version >= 2) "DOWNLOADED" else "NOT_DOWNLOADED",
                attachment.downloadState,
            )
            assertEquals(if (version >= 2) 2400L else 0L, attachment.downloadedBytes)
            upgraded.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
