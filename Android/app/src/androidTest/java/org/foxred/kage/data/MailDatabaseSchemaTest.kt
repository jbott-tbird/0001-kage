package org.foxred.kage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.foxred.kage.data.local.buildMailDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailDatabaseSchemaTest {
    @Test
    fun exportedBaselineOpensWithRoomAndKeepsMail() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "baseline-schema-test"
        context.deleteDatabase(name)
        val schema = JSONObject(
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("org.foxred.kage.data.local.MailDatabase/1.json")
                .bufferedReader().use { it.readText() }
        ).getJSONObject("database")
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql")
                        .replace("\${TABLE_NAME}", table))
                }
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) db.execSQL(queries.getString(i))
            db.execSQL("INSERT INTO accounts (id, name, address, incoming, outgoing, incomingPort, outgoingPort, security, outgoingSecurity) VALUES ('personal', 'Rhea', 'rhea@example.com', 'imap.example.com', 'smtp.example.com', 993, 465, 'SSL/TLS', 'SSL/TLS')")
            db.execSQL("INSERT INTO folders (id, accountId, name, role, parentId) VALUES ('personal-inbox', 'personal', 'Inbox', 'inbox', NULL)")
            db.execSQL("INSERT INTO messages (id, accountId, folderId, sender, senderAddress, `to`, cc, bcc, subject, body, html, receivedAt, isRead, isNew, flagged, pinned, draft, relatedGroup) VALUES ('kept', 'personal', 'personal-inbox', 'Roc', 'roc@example.net', 'rhea@example.com', '', '', 'Preserve me', 'Stored in baseline', NULL, '2026-09-24T12:00:00.000000000Z', 1, 0, 1, 0, 0, NULL)")
            db.execSQL("INSERT INTO attachments (id, messageId, filename, mimeType, sizeBytes, cached, asset) VALUES ('kept-pdf', 'kept', 'ticket.pdf', 'application/pdf', 2400, 0, 'sample-ticket.pdf')")
            db.version = 1
        }

        val reopened = buildMailDatabase(context, name)
        try {
            assertEquals("rhea@example.com", reopened.mailDao().accounts().first().single().address)
            assertEquals("Preserve me", reopened.mailDao().message("kept")?.subject)
            assertNotNull(reopened.mailDao().attachment("kept-pdf"))
            reopened.openHelper.writableDatabase.execSQL(
                "INSERT INTO history_cursors (folderId, uidValidity, beforeUid, scannedMessages, estimatedTotal, updatedAt) VALUES ('personal-inbox', 77, 42, 5, 12, 1234)"
            )
            reopened.openHelper.writableDatabase.query(
                "SELECT beforeUid FROM history_cursors WHERE folderId = 'personal-inbox'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(42L, it.getLong(0))
            }
            reopened.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun freshDatabaseStartsAtVersionOne() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fresh-baseline-schema-test"
        context.deleteDatabase(name)
        val reopened = buildMailDatabase(context, name)
        try {
            assertEquals(1, reopened.openHelper.writableDatabase.version)
            assertTrue(reopened.mailDao().accounts().first().isEmpty())
            reopened.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
