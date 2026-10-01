// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.foxred.kage.core.account.*
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.seed.DemoMail
import org.junit.Assert.*
import org.junit.Test

class CredentialStoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun namespace() = "credential-test-${UUID.randomUUID()}"

    @Test
    fun interruptedAndOversizedReplacementsPreservePreviousCredential() {
        val namespace = namespace()
        val store = AndroidCredentialStore(context, namespace)
        try {
            store.save("one", ServerProtocol.IMAP, Authorization("original-marker"))
            val file = File(context.noBackupFilesDir, namespace).listFiles()!!.single()
            File(file.parentFile, file.name + ".new").writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(
                "original-marker",
                AndroidCredentialStore(context, namespace)
                    .authorization("one", ServerProtocol.IMAP)!!
                    .secret,
            )
            assertThrows(CredentialFailure::class.java) {
                store.save("one", ServerProtocol.IMAP, Authorization("x".repeat(100_000)))
            }
            assertEquals(
                "original-marker",
                store.authorization("one", ServerProtocol.IMAP)!!.secret,
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun repositoryRemovalAndResetRevokeStoredCredentials() = runBlocking {
        val namespace = namespace()
        val store = AndroidCredentialStore(context, namespace)
        val db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        try {
            val repository = RoomMailRepository(db, context, DemoMail(context), store)
            repository.initialize()
            val accounts = repository.mailbox.first().accounts
            val first = accounts[0].id
            val second = accounts[1].id
            store.save(first, ServerProtocol.IMAP, Authorization("first-marker"))
            store.save(second, ServerProtocol.SMTP, Authorization("second-marker"))
            repository.removeAccount(first)
            assertNull(store.authorization(first, ServerProtocol.IMAP))
            assertEquals("second-marker", store.authorization(second, ServerProtocol.SMTP)!!.secret)
            assertFalse(db.mailDao().accounts().first().any { it.id == first })
            repository.resetDemo()
            assertNull(store.authorization(second, ServerProtocol.SMTP))
            assertTrue(
                keys().aliases().toList().none { it.startsWith("org.foxred.kage.$namespace.") }
            )
        } finally {
            store.clear()
            db.close()
        }
    }

    @Test
    fun backupRulesExcludeCredentialsAndFileProviderCannotExposeThem() {
        val path = "no_backup/mail-credentials/"
        for ((resource, expected) in
            listOf(
                org.foxred.kage.R.xml.backup_rules to 1,
                org.foxred.kage.R.xml.data_extraction_rules to 2,
            )) {
            context.resources.getXml(resource).use { xml ->
                var count = 0
                while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (
                        xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                            xml.name == "exclude" &&
                            xml.getAttributeValue(null, "path") == path
                    )
                        count++
                    xml.next()
                }
                assertEquals(expected, count)
            }
        }
        val credential = File(context.noBackupFilesDir, "mail-credentials/example")
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".attachments",
                credential,
            )
        }
    }

    @Test
    fun credentialsPersistEncryptedWithIndependentAccountAndProtocolKeys() {
        val namespace = namespace()
        val store: CredentialStore = AndroidCredentialStore(context, namespace)
        try {
            val expiration = Instant.parse("2026-09-25T00:00:00Z")
            store.save("one", ServerProtocol.IMAP, Authorization("incoming-secret-marker"))
            store.save(
                "one",
                ServerProtocol.SMTP,
                Authorization(
                    "access-marker",
                    Authorization.Kind.OAUTH2,
                    expiration,
                    "refresh-marker",
                ),
            )
            store.save("two", ServerProtocol.IMAP, Authorization("other-marker"))
            val reopened: CredentialProvider = AndroidCredentialStore(context, namespace)
            assertEquals(
                "incoming-secret-marker",
                reopened.authorization("one", ServerProtocol.IMAP)!!.secret,
            )
            val outgoing = reopened.authorization("one", ServerProtocol.SMTP)!!
            assertEquals("access-marker", outgoing.secret)
            assertEquals("refresh-marker", outgoing.refreshToken)
            assertEquals(expiration, outgoing.expiresAt)
            assertEquals(Authorization.Kind.OAUTH2, outgoing.kind)
            assertEquals(
                "other-marker",
                reopened.authorization("two", ServerProtocol.IMAP)!!.secret,
            )
            assertNull(reopened.authorization("two", ServerProtocol.SMTP))
            val directory = File(context.noBackupFilesDir, namespace)
            assertEquals(context.noBackupFilesDir.canonicalFile, directory.canonicalFile.parentFile)
            assertEquals(3, directory.listFiles()!!.size)
            directory.listFiles()!!.forEach { file ->
                val stored = file.readBytes().toString(Charsets.ISO_8859_1)
                listOf("incoming-secret-marker", "access-marker", "refresh-marker", "other-marker")
                    .forEach { assertFalse(stored.contains(it)) }
            }
            val aliases =
                keys().aliases().toList().filter { it.startsWith("org.foxred.kage.$namespace.") }
            assertEquals(3, aliases.size)
            aliases.forEach { assertNull(keys().getKey(it, null).encoded) }
            store.removeAccount("one")
            assertNull(reopened.authorization("one", ServerProtocol.IMAP))
            assertNull(reopened.authorization("one", ServerProtocol.SMTP))
            assertEquals(
                "other-marker",
                reopened.authorization("two", ServerProtocol.IMAP)!!.secret,
            )
            assertFalse(outgoing.toString().contains("marker"))
        } finally {
            store.clear()
        }
    }

    @Test
    fun tamperedCiphertextAndMissingKeyRequireSignInAndCanBeReplaced() {
        val namespace = namespace()
        val store = AndroidCredentialStore(context, namespace)
        try {
            store.save("one", ServerProtocol.IMAP, Authorization("old-secret"))
            val file = File(context.noBackupFilesDir, namespace).listFiles()!!.single()
            val original = file.readBytes()
            val tampered = original.copyOf()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            file.writeBytes(tampered)
            val corrupt =
                assertThrows(CredentialFailure::class.java) {
                    store.authorization("one", ServerProtocol.IMAP)
                }
            assertEquals(CredentialFailureReason.UNREADABLE, corrupt.reason)
            assertFalse(corrupt.toString().contains("old-secret"))
            assertNull(corrupt.cause)
            store.save("one", ServerProtocol.IMAP, Authorization("new-secret"))
            assertEquals("new-secret", store.authorization("one", ServerProtocol.IMAP)!!.secret)
            keys()
                .aliases()
                .toList()
                .filter { it.startsWith("org.foxred.kage.$namespace.") }
                .forEach { keys().deleteEntry(it) }
            val missing =
                assertThrows(CredentialFailure::class.java) {
                    store.authorization("one", ServerProtocol.IMAP)
                }
            assertEquals(CredentialFailureReason.UNREADABLE, missing.reason)
            store.save("one", ServerProtocol.IMAP, Authorization("replacement-secret"))
            assertEquals(
                "replacement-secret",
                store.authorization("one", ServerProtocol.IMAP)!!.secret,
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun rewritesUseFreshNoncesAndRejectSwappedAccountFiles() {
        val namespace = namespace()
        val store = AndroidCredentialStore(context, namespace)
        try {
            store.save("one", ServerProtocol.IMAP, Authorization("same-secret"))
            val one = File(context.noBackupFilesDir, namespace).listFiles()!!.single()
            val before = one.readBytes()
            store.save("one", ServerProtocol.IMAP, Authorization("same-secret"))
            assertFalse(before.contentEquals(one.readBytes()))
            store.save("two", ServerProtocol.IMAP, Authorization("same-secret"))
            val two = File(context.noBackupFilesDir, namespace).listFiles()!!.first { it != one }
            two.writeBytes(one.readBytes())
            assertThrows(CredentialFailure::class.java) {
                store.authorization("two", ServerProtocol.IMAP)
            }
            assertEquals("same-secret", store.authorization("one", ServerProtocol.IMAP)!!.secret)
        } finally {
            store.clear()
        }
    }
}
