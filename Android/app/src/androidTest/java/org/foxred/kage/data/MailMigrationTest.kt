package org.foxred.kage.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.foxred.kage.data.local.MIGRATION_1_2
import org.foxred.kage.data.local.MailDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MailMigrationTest {
    @Test
    fun migrationPreservesAccountsAndAddsSafeDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test"
        context.deleteDatabase(name)
        val schema =
            JSONObject(
                    InstrumentationRegistry.getInstrumentation()
                        .context
                        .assets
                        .open("org.foxred.kage.data.local.MailDatabase/1.json")
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
                "INSERT INTO accounts VALUES ('personal', 'Rhea', 'rhea@example.com', 'imap.example.com', 'smtp.example.com', 993, 465, 'SSL/TLS', 'SSL/TLS')"
            )
            db.execSQL(
                "INSERT INTO folders VALUES ('personal-inbox', 'personal', 'Inbox', 'inbox', NULL)"
            )
            db.execSQL(
                "INSERT INTO messages VALUES ('kept', 'personal', 'personal-inbox', 'Roc', 'roc@example.net', 'rhea@example.com', '', '', 'Preserve me', 'Stored before update', NULL, '2026-09-24', 1, 0, 1, 0, 0, NULL)"
            )
            db.execSQL(
                "INSERT INTO attachments VALUES ('kept-pdf', 'kept', 'ticket.pdf', 'application/pdf', 2400, 0, 'sample-ticket.pdf')"
            )
            db.version = 1
        }
        val upgraded =
            Room.databaseBuilder(context, MailDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
        try {
            val account = upgraded.mailDao().accounts().first().single()
            assertEquals("rhea@example.com", account.address)
            assertTrue(account.requireAuth)
            val message = upgraded.mailDao().message("kept")!!
            assertEquals("Preserve me", message.subject)
            assertTrue(message.isRead)
            assertTrue(message.flagged)
            assertNull(upgraded.mailDao().attachment("kept-pdf")!!.localFile)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
