// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.local.OutboxEntity
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.core.account.EmailAddress as WireAddress
import org.foxred.kage.core.account.EmailBody as WireBody
import org.foxred.kage.core.account.OutgoingEmail
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMailRepositoryTest {
    private lateinit var db: MailDatabase
    private lateinit var repo: RoomMailRepository

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).build()
        repo =
            RoomMailRepository(
                db,
                context,
                DemoMail(context),
                org.foxred.kage.data.security.AndroidCredentialStore(context, "repository-test"),
            )
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun outboxPagesReachOldActionableRowsAndCountsCoverWholeArchive() = runBlocking {
        repo.initialize()
        val dao = db.remoteMailDao()
        fun row(id: String, state: String, copyState: String,
            reviewPhase: String? = null): OutboxEntity {
            val envelope = JSONObject()
                .put("to", JSONArray().put(JSONObject().put("address", "to@example.test")))
                .put("cc", JSONArray()).put("bcc", JSONArray())
                .put("sentCopyState", copyState)
            if (reviewPhase != null) envelope.put("sentCopyUploadPhase", reviewPhase)
            return OutboxEntity(
                id = id, accountId = "personal", draftId = null,
                messageId = "<$id@example.test>", rawMessagePath = "/unused/$id.eml",
                envelopeJson = envelope.toString(), state = state,
                createdAt = 1L, updatedAt = 1L,
            )
        }
        dao.saveOutbox(row("old-failed", DurableOutbox.State.FAILED, "WAITING"))
        dao.saveOutbox(row("old-pending-copy", DurableOutbox.State.SENT, "PENDING",
            reviewPhase = "UNCERTAIN"))
        repeat(80) { dao.saveOutbox(row("confirmed-$it", DurableOutbox.State.SENT, "CONFIRMED")) }

        val first = repo.observeOutboxPage().first()
        assertEquals(50, first.items.size)
        assertNotNull(first.nextCursor)
        assertFalse(first.items.any { it.id == "old-failed" })
        val second = repo.observeOutboxPage(checkNotNull(first.nextCursor)).first()
        assertEquals(32, second.items.size)
        assertNull(second.nextCursor)
        assertTrue(second.items.any { it.id == "old-failed" && it.status == OutboxStatus.FAILED })
        assertTrue(second.items.any { it.id == "old-pending-copy" &&
            it.sentCopyStatus == SentCopyStatus.PENDING })
        assertEquals(82, (first.items + second.items).map { it.id }.toSet().size)
        assertEquals(first.items, repo.outbox.first())
        val counts = repo.outboxCounts.first()
        assertEquals(81L, counts.sent)
        assertEquals(1L, counts.failed)
        assertEquals(1L, counts.sentUnconfirmed)
        assertEquals(1L, counts.sentCopyNeedsReview)
        assertEquals(2L, counts.actionable)
    }

    @Test
    fun initializationDoesNotOverwriteChanges() = runBlocking {
        repo.initialize()
        val original = repo.mailbox.first()
        assertEquals(3, original.accounts.size)
        original.accounts.forEach { a ->
            assertTrue(
                original.folders
                    .filter { it.accountId == a.id }
                    .map { it.role }
                    .containsAll(
                        listOf("inbox", "drafts", "sent", "archive", "spam", "trash", "custom")
                    )
            )
        }
        val message = original.messages.first { !it.isRead }
        repo.markRead(message.id, true)
        repo.pin(message.id, true)
        repo.initialize()
        val updated = repo.mailbox.first().messages.first { it.id == message.id }
        assertTrue(updated.isRead)
        assertTrue(updated.pinned)
        assertEquals(message.isNew, updated.isNew)
    }

    @Test
    fun archiveAndRemoveStayWithinAccount() = runBlocking {
        repo.initialize()
        val message = repo.mailbox.first().messages.first { it.accountId == "work" }
        repo.move(message.id, "archive")
        assertEquals(
            "work-archive",
            repo.mailbox.first().messages.first { it.id == message.id }.folderId,
        )
        repo.removeAccount("work")
        val after = repo.mailbox.first()
        assertTrue(after.accounts.none { it.id == "work" })
        assertTrue(after.messages.none { it.accountId == "work" })
        assertTrue(after.folders.none { it.accountId == "work" })
        assertTrue(after.messages.any { it.accountId == "personal" })
    }

    @Test
    fun removingAccountDeletesOnlyUnreferencedDraftAttachmentFiles() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
        val file = java.io.File(directory, "shared-draft-${System.nanoTime()}")
        file.writeText("keep until both drafts are removed")
        try {
            for (accountId in listOf("personal", "work")) {
                val id = "$accountId-shared-draft"
                repo.saveDraft(Message(id, accountId, "", "Sender", "sender@example.test",
                    "to@example.test", subject = "File", body = "Body",
                    receivedAt = "2026-09-25",
                    attachments = listOf(Attachment("$id-file", id, "file.txt",
                        "text/plain", file.length(), cached = true, localFile = file.name))))
            }
            repo.removeAccount("personal")
            assertTrue(file.isFile)
            repo.removeAccount("work")
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun removingAccountCleansLocalFilesBeyondOneFilenamePage() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
        val messageId = repo.mailbox.first().messages.first { it.accountId == "personal" }.id
        val names = (0 until 65).map { "remove-page-${System.nanoTime()}-$it.bin" }
        try {
            names.forEach { java.io.File(directory, it).writeText("cached") }
            db.mailDao().saveAttachments(names.mapIndexed { index, name ->
                org.foxred.kage.data.local.AttachmentEntity(
                    "remove-page-$index-$name", messageId, name, "application/octet-stream", 6,
                    cached = true, asset = "", localFile = name,
                )
            })
            repo.removeAccount("personal")
            assertTrue(names.none { java.io.File(directory, it).exists() })
        } finally {
            names.forEach { java.io.File(directory, it).delete() }
        }
    }

    @Test
    fun failedAttachmentCleanupStagingLeavesAccountAndMailIntact() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = java.io.File(context.cacheDir, "cleanup-stage-failure-${System.nanoTime()}")
            .apply { mkdirs() }
        val collision = java.io.File(root, "attachment-cleanup")
        collision.writeText("blocks the cleanup directory")
        val privateContext = object : ContextWrapper(context) {
            override fun getFilesDir(): java.io.File = root
        }
        val isolated = RoomMailRepository(db, privateContext, DemoMail(context),
            org.foxred.kage.data.security.AndroidCredentialStore(context, "repository-test"))
        val before = repo.mailbox.first()
        try {
            assertNotNull(runCatching { isolated.removeAccount("personal") }.exceptionOrNull())
            val after = repo.mailbox.first()
            assertTrue(after.accounts.any { it.id == "personal" })
            assertEquals(before.messages.filter { it.accountId == "personal" },
                after.messages.filter { it.accountId == "personal" })
            assertEquals("blocks the cleanup directory", collision.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun initializationResumesInterruptedAttachmentFileCleanup() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
        val orphan = java.io.File(directory, "orphan-${System.nanoTime()}.bin")
        val staged = java.io.File(context.filesDir, "attachment-cleanup").apply { mkdirs() }
        val manifest = java.io.File(staged, "recovery-${System.nanoTime()}.ready")
        try {
            orphan.writeText("orphaned")
            java.io.DataOutputStream(manifest.outputStream()).use { output ->
                output.writeUTF("personal")
                output.writeBoolean(true)
                output.writeUTF(orphan.name)
                output.writeBoolean(false)
            }
            repo.initialize()
            assertTrue(orphan.exists())
            assertTrue(manifest.exists())
            repo.removeAccount("personal")
            repo.initialize()
            assertFalse(orphan.exists())
            assertFalse(manifest.exists())
        } finally {
            orphan.delete()
            manifest.delete()
        }
    }

    @Test
    fun draftAttachmentsCanBeRemovedAndSentLocally() = runBlocking {
        repo.initialize()
        val draft =
            Message(
                "test-draft",
                "personal",
                "",
                "Rhea",
                "rhea@example.com",
                "friend@example.net",
                subject = "Draft",
                body = "Keep this text",
                receivedAt = "2026-09-24",
                attachments =
                    listOf(
                        Attachment("test-pdf", "test-draft", "ticket.pdf", "application/pdf", 100)
                    ),
            )
        repo.saveDraft(draft)
        assertEquals(1, repo.mailbox.first().messages.first { it.id == draft.id }.attachments.size)
        repo.saveDraft(draft.copy(attachments = emptyList()))
        assertTrue(repo.mailbox.first().messages.first { it.id == draft.id }.attachments.isEmpty())
        repo.send(draft.copy(attachments = emptyList()))
        val sent = repo.mailbox.first().messages.first { it.id == draft.id }
        assertEquals("personal-sent", sent.folderId)
        assertFalse(sent.draft)
    }

    @Test
    fun demoSendAcceptsFormattedRecipientsAndRejectsMalformedOnes() = runBlocking {
        repo.initialize()
        val draft = Message("demo-address-group", "personal", "", "Sender",
            "sender@example.test", "Team: one@example.test, two@example.test;",
            cc = "Named Recipient <three@example.test>",
            subject = "Group reply", body = "Reply body", receivedAt = "2026-09-26")
        assertEquals(org.foxred.kage.domain.repository.SendDisposition.DEMO_SAVED,
            repo.send(draft))
        assertFalse(repo.mailbox.first().messages.single { it.id == draft.id }.draft)
        val failure = runCatching {
            repo.send(draft.copy(id = "bad-demo-recipient", to = "bad@@example.test"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun draftRecipientsAndAttachmentSurviveRestartAndRemovedFileIsCleaned() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "draft-reopen-test"
        context.deleteDatabase(name)
        val file = java.io.File(context.filesDir, "attachments/draft-${System.nanoTime()}")
        file.parentFile!!.mkdirs()
        file.writeText("durable attachment")
        var disk = Room.databaseBuilder(context, MailDatabase::class.java, name).build()
        try {
            var stored = RoomMailRepository(disk, context, DemoMail(context),
                org.foxred.kage.data.security.AndroidCredentialStore(context, "draft-reopen-test"))
            stored.initialize()
            val draft = Message("persistent-draft", "personal", "", "Rhea", "rhea@example.com",
                "to@example.test", cc = "cc@example.test", bcc = "hidden@example.test",
                subject = "Re: Durable", body = "Edited after reply",
                receivedAt = "2026-09-25", inReplyTo = "<source@example.test>",
                references = listOf("<source@example.test>"),
                attachments = listOf(Attachment("persistent-part", "persistent-draft",
                    "file.txt", "text/plain", file.length(), cached = true,
                    localFile = file.name)))
            stored.saveDraft(draft)
            disk.close()
            disk = Room.databaseBuilder(context, MailDatabase::class.java, name).build()
            stored = RoomMailRepository(disk, context, DemoMail(context),
                org.foxred.kage.data.security.AndroidCredentialStore(context, "draft-reopen-test"))
            stored.initialize()
            val reopened = stored.mailbox.first().messages.single { it.id == draft.id }
            assertEquals(draft.to, reopened.to)
            assertEquals(draft.cc, reopened.cc)
            assertEquals(draft.bcc, reopened.bcc)
            assertEquals(draft.inReplyTo, reopened.inReplyTo)
            assertEquals(draft.references, reopened.references)
            assertEquals(file.name, reopened.attachments.single().localFile)
            assertTrue(file.isFile)
            stored.saveDraft(reopened.copy(attachments = emptyList()))
            assertFalse(file.exists())
        } finally {
            disk.close()
            context.deleteDatabase(name)
            file.delete()
        }
    }

    @Test
    fun unfinishedRecipientCanBeSavedLocallyForLaterEditing() = runBlocking {
        repo.initialize()
        val draft = Message("unfinished-recipient", "personal", "", "Rhea",
            "rhea@example.com", "friend@", subject = "Continue later",
            body = "Work in progress", receivedAt = "2026-09-25")
        repo.saveDraft(draft)
        val saved = repo.mailbox.first().messages.single { it.id == draft.id }
        assertEquals("friend@", saved.to)
        assertEquals("Work in progress", saved.body)
    }

    @Test
    fun discardingDraftDetachesQueuedMimeAndRemovesItsPrivateFile() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.filesDir, "attachments/queued-draft-${System.nanoTime()}")
        file.parentFile!!.mkdirs()
        file.writeText("attached bytes")
        try {
            val id = "queued-draft"
            repo.saveDraft(Message(id, "personal", "", "Rhea", "rhea@example.com",
                "to@example.test", subject = "Queued", body = "Keep submission",
                receivedAt = "2026-09-25", attachments = listOf(Attachment(
                    "queued-draft-part", id, "file.txt", "text/plain", file.length(),
                    cached = true, localFile = file.name))))
            val durable = DurableOutbox(db, context, AngusMimeCodec())
            val queued = durable.enqueue("personal", OutgoingEmail("<queued-draft@example.test>",
                WireAddress("rhea@example.com"), listOf(WireAddress("to@example.test")),
                subject = "Queued", body = WireBody("Keep submission", null)), id)
            repo.deleteDraft(id)
            assertNull(db.mailDao().message(id))
            assertNull(db.remoteMailDao().outboxEntry(queued.id)?.draftId)
            assertEquals("Keep submission", AngusMimeCodec().decode(durable.raw(queued)).body.text)
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun discardingUnsavedComposeRemovesImportedFile() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.filesDir, "attachments/unsaved-${System.nanoTime()}")
        file.parentFile!!.mkdirs()
        file.writeText("temporary import")
        try {
            repo.deleteDraft("never-saved", listOf(Attachment(
                "unsaved-file", "never-saved", "file.txt", "text/plain", file.length(),
                cached = true, localFile = file.name)))
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun importedAttachmentPublishesCompleteFileBeforeDraftUsesIt() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = java.io.File(context.filesDir, "attachments/source-${System.nanoTime()}")
        source.parentFile!!.mkdirs()
        source.writeText("complete imported bytes")
        var imported: Attachment? = null
        try {
            val uri = FileProvider.getUriForFile(context,
                "${context.packageName}.attachments", source)
            val attachment = repo.importAttachment("new-draft", uri.toString())
            imported = attachment
            val target = java.io.File(source.parentFile, attachment.localFile!!)
            assertEquals("complete imported bytes", target.readText())
            assertFalse(java.io.File(source.parentFile, "${attachment.id}.tmp").exists())
            repo.deleteDraft("new-draft", listOf(attachment))
            assertFalse(target.exists())
        } finally {
            imported?.localFile?.let { java.io.File(source.parentFile, it).delete() }
            source.delete()
        }
    }

    @Test
    fun oversizedImportRemovesItsTemporaryPrivateFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
        val source = java.io.File(directory, "oversized-source-${System.nanoTime()}")
        source.writeText("larger than eight bytes")
        try {
            val before = directory.listFiles().orEmpty().map { it.name }.toSet()
            val uri = FileProvider.getUriForFile(context,
                "${context.packageName}.attachments", source)
            val bounded = RoomMailRepository(db, context, DemoMail(context),
                org.foxred.kage.data.security.AndroidCredentialStore(context, "repository-test"),
                maxImportedAttachmentBytes = 8)

            val failure = runCatching {
                bounded.importAttachment("new-draft", uri.toString())
            }.exceptionOrNull() as MailFailure
            assertEquals(FailureKind.LIMIT_EXCEEDED, failure.kind)
            assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally {
            source.delete()
        }
    }

    @Test
    fun looseImportCleanupKeepsFilesReferencedBySavedDrafts() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
        val kept = java.io.File(directory, "kept-import-${System.nanoTime()}")
        val removed = java.io.File(directory, "removed-import-${System.nanoTime()}")
        kept.writeText("selected")
        removed.writeText("removed from compose")
        val selected = Attachment("kept-import", "saved-draft", "keep.txt", "text/plain",
            kept.length(), cached = true, localFile = kept.name)
        val discarded = Attachment("removed-import", "saved-draft", "remove.txt", "text/plain",
            removed.length(), cached = true, localFile = removed.name)
        try {
            repo.saveDraft(Message("saved-draft", "personal", "", "Rhea", "rhea@example.com",
                "friend@example.net", subject = "Selected import", body = "Body",
                receivedAt = "2026-09-25", attachments = listOf(selected)))

            repo.cleanupLooseAttachments(listOf(selected, discarded))

            assertTrue(kept.isFile)
            assertFalse(removed.exists())
            assertEquals(listOf(selected.id), repo.mailbox.first().messages
                .single { it.id == "saved-draft" }.attachments.map { it.id })
        } finally {
            kept.delete()
            removed.delete()
        }
    }

    @Test
    fun preferencesAndAddedMailboxesPersist() = runBlocking {
        repo.initialize()
        repo.addAccount(Account("skye", "Skye", "skye@example.net"))
        assertTrue(repo.mailbox.first().messages.any { it.folderId == "skye-inbox" })
        repo.updatePreferences(
            Preferences(selectedFolder = "skye-inbox", offline = true, started = true)
        )
        repo.initialize()
        assertTrue(repo.mailbox.first().preferences.offline)
        assertEquals("skye-inbox", repo.mailbox.first().preferences.selectedFolder)
    }

    @Test
    fun automaticAttachmentPolicyAppliesToNewMailboxesAndWaitsWhileOffline() = runBlocking {
        repo.initialize()
        repo.updatePreferences(Preferences(automaticAttachments = true))
        repo.addAccount(Account("automatic", "Automatic", "automatic@example.net"))
        val downloaded =
            repo.mailbox
                .first()
                .messages
                .filter { it.accountId == "automatic" }
                .flatMap { it.attachments }
        assertTrue(downloaded.isNotEmpty())
        assertTrue(downloaded.all { it.cached })
        repo.updatePreferences(Preferences(automaticAttachments = true, offline = true))
        repo.addAccount(Account("offline", "Offline", "offline@example.net"))
        assertTrue(
            repo.mailbox
                .first()
                .messages
                .filter { it.accountId == "offline" }
                .flatMap { it.attachments }
                .all { !it.cached }
        )
        repo.updatePreferences(Preferences(automaticAttachments = true, offline = false))
        assertTrue(repo.mailbox.first().messages.flatMap { it.attachments }.all { it.cached })
    }

    @Test
    fun automaticAttachmentCachingContinuesPastOneIdPage() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = (0 until 65).map { "automatic-page-${System.nanoTime()}-$it" }
        try {
            db.mailDao().saveAttachments(ids.map { id ->
                org.foxred.kage.data.local.AttachmentEntity(
                    id, "m04", "fixture.pdf", "application/pdf", 0,
                    cached = false, asset = "sample-ticket.pdf",
                )
            })
            repo.updatePreferences(repo.mailbox.first().preferences.copy(
                automaticAttachments = true))
            assertTrue(ids.all { db.mailDao().attachment(it)?.cached == true })
        } finally {
            ids.forEach { id ->
                java.io.File(context.filesDir, "attachments/$id.pdf").delete()
            }
        }
    }

    @Test
    fun failedAutomaticAttachmentDoesNotBlockLaterPartsOrOfflineReopen() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val token = System.nanoTime()
        val badId = "automatic-a-bad-$token"
        val goodId = "automatic-z-good-$token"
        val file = java.io.File(context.filesDir, "attachments/$goodId.pdf")
        try {
            db.mailDao().saveAttachments(listOf(
                org.foxred.kage.data.local.AttachmentEntity(badId, "m04", "missing.pdf",
                    "application/pdf", 0, cached = false, asset = "missing-$token.pdf"),
                org.foxred.kage.data.local.AttachmentEntity(goodId, "m04", "ticket.pdf",
                    "application/pdf", 0, cached = false, asset = "sample-ticket.pdf"),
            ))
            repo.updatePreferences(repo.mailbox.first().preferences.copy(
                automaticAttachments = true))
            assertFalse(db.mailDao().attachment(badId)!!.cached)
            assertTrue(db.mailDao().attachment(goodId)!!.cached)
            assertTrue(file.isFile)

            repo.initialize()
            assertTrue(repo.mailbox.first().messages.any { it.id == "m04" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun incompleteDemoCacheFileIsReplacedBeforeMarkingPartCached() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "partial-demo-${System.nanoTime()}"
        val file = java.io.File(context.filesDir, "attachments/$id.pdf")
        try {
            file.parentFile!!.mkdirs()
            file.writeText("partial")
            db.mailDao().saveAttachments(listOf(
                org.foxred.kage.data.local.AttachmentEntity(id, "m04", "ticket.pdf",
                    "application/pdf", 0, cached = false, asset = "sample-ticket.pdf")))

            repo.cacheAttachment(id)

            val expected = context.assets.open("sample-ticket.pdf").use { it.readBytes() }
            assertArrayEquals(expected, file.readBytes())
            assertTrue(db.mailDao().attachment(id)!!.cached)
            assertTrue(file.parentFile!!.listFiles().orEmpty().none {
                it.name.startsWith("$id.pdf-") && it.name.endsWith(".tmp")
            })
        } finally {
            file.delete()
        }
    }

    @Test
    fun partialPreviouslyCachedDemoAssetIsRepairedWhileOffline() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "cached-partial-${System.nanoTime()}"
        val file = java.io.File(context.filesDir, "attachments/$id.pdf")
        try {
            file.parentFile!!.mkdirs()
            file.writeText("partial")
            db.mailDao().saveAttachments(listOf(
                org.foxred.kage.data.local.AttachmentEntity(id, "m04", "ticket.pdf",
                    "application/pdf", 0, cached = true, asset = "sample-ticket.pdf")))
            repo.updatePreferences(repo.mailbox.first().preferences.copy(offline = true))

            assertEquals(file.canonicalFile, java.io.File(repo.cacheAttachment(id)).canonicalFile)

            val expected = context.assets.open("sample-ticket.pdf").use { it.readBytes() }
            assertArrayEquals(expected, file.readBytes())
            assertTrue(db.mailDao().attachment(id)!!.cached)
        } finally {
            file.delete()
        }
    }

    @Test
    fun storedLocalAttachmentCannotEscapePrivateAttachmentDirectory() = runBlocking {
        repo.initialize()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val token = System.nanoTime()
        val sentinel = java.io.File(context.filesDir, "attachment-sentinel-$token")
        val id = "traversal-part-$token"
        sentinel.writeText("private bytes")
        try {
            db.mailDao().saveAttachments(listOf(
                org.foxred.kage.data.local.AttachmentEntity(id, "m04", "sentinel.txt",
                    "text/plain", sentinel.length(), cached = true, asset = "",
                    localFile = "../${sentinel.name}")))

            val failure = runCatching { repo.cacheAttachment(id) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("private bytes", sentinel.readText())
        } finally {
            sentinel.delete()
        }
    }

    @Test
    fun seedMatchesWebAccountFolderAndMessageAssignments() = runBlocking {
        repo.initialize()
        val mail = repo.mailbox.first()
        assertEquals(66, mail.messages.size)
        assertEquals(
            "One email workflow across mobile and desktop",
            mail.messages.first { it.id == "m01" }.subject,
        )
        assertEquals("work-inbox", mail.messages.first { it.id == "m04" }.folderId)
        assertEquals("work-design", mail.messages.first { it.id == "m07" }.folderId)
        assertEquals("personal-drafts", mail.messages.first { it.id == "m10" }.folderId)
        assertEquals("work-projects", mail.folders.first { it.id == "work-design" }.parentId)
        assertEquals("personal-travel", mail.folders.first { it.id == "personal-trips" }.parentId)
        val offlineAttachments = mail.messages.flatMap { it.attachments }.filter { it.cached }
        repo.updatePreferences(Preferences(offline = true))
        offlineAttachments.forEach {
            assertTrue(java.io.File(repo.cacheAttachment(it.id)).length() > 0)
        }
    }

    @Test
    fun messageListPagesEqualTimestampsWithoutLoadingBodies() = runBlocking {
        repo.initialize()
        val base = requireNotNull(db.mailDao().message("m04"))
        val time = "2100-01-01T00:00:00Z"
        db.mailDao().saveMessages((1..4).map { number ->
            base.copy(id = "page-$number", receivedAt = time,
                body = "body should stay off the list page", html = "<p>HTML body</p>")
        })
        db.mailDao().saveAttachments(listOf(org.foxred.kage.data.local.AttachmentEntity(
            "page-attachment", "page-3", "page.txt", "text/plain", 4,
            cached = false, asset = "",
        )))

        val first = db.mailDao().messageListPage(listOf(base.folderId), null, null, 2)
        val second = db.mailDao().messageListPage(listOf(base.folderId),
            first.last().receivedAt, first.last().id, 2)

        assertEquals(listOf("page-4", "page-3", "page-2", "page-1"),
            (first + second).map { it.id })
        assertEquals(1, first.last().attachmentCount)
        assertTrue(first.size <= 2 && second.size <= 2)

        val oldest = db.mailDao().oldestMessageListPage(listOf(base.folderId),
            "2099-12-31T00:00:00Z", "", 2)
        val nextOldest = db.mailDao().oldestMessageListPage(listOf(base.folderId),
            oldest.last().receivedAt, oldest.last().id, 2)
        assertEquals(listOf("page-1", "page-2", "page-3", "page-4"),
            (oldest + nextOldest).map { it.id })

        val page = repo.messagePage(listOf(base.folderId), null, oldestFirst = false, limit = 2)
        assertEquals(listOf("page-4", "page-3"), page.items.map { it.id })
        assertEquals("", page.items.first().body)
        assertTrue(page.items.last().hasAttachments)
        val continued = repo.messagePage(listOf(base.folderId), page.next,
            oldestFirst = false, limit = 2)
        assertEquals(listOf("page-2", "page-1"), continued.items.map { it.id })
        val ascending = repo.messagePage(listOf(base.folderId),
            MessagePageCursor("2099-12-31T00:00:00Z", ""), oldestFirst = true, limit = 2)
        val nextAscending = repo.messagePage(listOf(base.folderId), ascending.next,
            oldestFirst = true, limit = 2)
        assertEquals(listOf("page-1", "page-2", "page-3", "page-4"),
            (ascending.items + nextAscending.items).map { it.id })
    }

    @Test
    fun messageDetailLoadsItsBodyAndAttachmentsSeparately() = runBlocking {
        repo.initialize()
        val reference = repo.mailbox.first().messages.first { it.attachments.isNotEmpty() }

        val detail = requireNotNull(repo.observeMessage(reference.id).first())
        assertEquals(reference.body, detail.body)
        assertEquals(reference.attachments.map { it.id }, detail.attachments.map { it.id })

        repo.markRead(reference.id, true)
        assertTrue(requireNotNull(repo.observeMessage(reference.id).first()).isRead)
    }

    @Test
    fun databaseCountsStayAccurateWithoutMaterializingMailInTheUi() = runBlocking {
        repo.initialize()
        val mail = repo.mailbox.first()
        val counts = repo.cacheCounts()
        assertEquals(mail.messages.size.toLong(), counts.cachedMessages)
        assertEquals(mail.messages.count { it.bodyDownloaded }.toLong(), counts.downloadedBodies)
        assertEquals(mail.messages.sumOf { m -> m.attachments.count { it.cached } }.toLong(),
            counts.cachedAttachments)
        assertEquals(mail.messages.count { it.draft }.toLong(), counts.drafts)
        val unread = repo.unreadCounts.first()
        val folder = mail.folders.first { it.role == "inbox" }
        assertEquals(mail.messages.count { it.folderId == folder.id && !it.isRead }.toLong(),
            unread[folder.id] ?: 0L)
    }

    @Test
    fun searchPagesScanPastNonmatchesAndKeepAttachmentMatches() = runBlocking {
        repo.initialize()
        val base = requireNotNull(db.mailDao().message("m04"))
        val time = "2100-01-01T00:00:00Z"
        db.mailDao().saveMessages(listOf(
            base.copy(id = "search-nohit", receivedAt = time, body = "ordinary", html = null),
            base.copy(id = "search-body", receivedAt = time, body = "rare body", html = null),
            base.copy(id = "search-attachment", receivedAt = time, body = "ordinary", html = null),
        ))
        db.mailDao().saveAttachments(listOf(org.foxred.kage.data.local.AttachmentEntity(
            "search-file", "search-attachment", "rare-filename.txt", "text/plain", 4,
            cached = false, asset = "",
        )))
        val query = MailQuery(text = "rare", scope = SearchScope.Account)

        val first = repo.filteredPage(listOf(base.folderId), base.accountId, query,
            null, oldestFirst = false, limit = 1)
        val second = repo.filteredPage(listOf(base.folderId), base.accountId, query,
            first.next, oldestFirst = false, limit = 1)

        assertEquals(listOf("search-body"), first.items.map { it.id })
        assertEquals(listOf("search-attachment"), second.items.map { it.id })
        assertEquals("", first.items.single().body)
        assertTrue(second.items.single().hasAttachments)
    }

    @Test
    fun searchScopeSeparatesSelectedAccountFromAllAccounts() = runBlocking {
        repo.initialize()
        val base = requireNotNull(db.mailDao().message("m04"))
        db.mailDao().saveMessages(listOf(
            base.copy(id = "scope-personal-a", accountId = "personal",
                folderId = "personal-inbox", subject = "scope-unique",
                receivedAt = "2100-01-01T00:00:00Z"),
            base.copy(id = "scope-personal-b", accountId = "personal",
                folderId = "personal-inbox", subject = "scope-unique",
                receivedAt = "2100-01-01T00:00:00Z"),
            base.copy(id = "scope-work", accountId = "work",
                folderId = "work-inbox", subject = "scope-unique",
                receivedAt = "2100-01-01T00:00:00Z"),
        ))
        val account = repo.filteredPage(listOf("personal-inbox"), "personal",
            MailQuery(text = "scope-unique", scope = SearchScope.Account), null,
            oldestFirst = false)
        val all = repo.filteredPage(listOf("personal-inbox"), "personal",
            MailQuery(text = "scope-unique", scope = SearchScope.AllAccounts), null,
            oldestFirst = false)

        assertEquals(setOf("scope-personal-a", "scope-personal-b"),
            account.items.map { it.id }.toSet())
        assertEquals(setOf("scope-personal-a", "scope-personal-b", "scope-work"),
            all.items.map { it.id }.toSet())

        val globalQuery = MailQuery(text = "scope-unique", scope = SearchScope.AllAccounts)
        val firstGlobal = repo.filteredPage(listOf("personal-inbox"), "personal", globalQuery,
            null, oldestFirst = false, limit = 1)
        val nextGlobal = repo.filteredPage(listOf("personal-inbox"), "personal", globalQuery,
            firstGlobal.next, oldestFirst = false, limit = 1)
        assertEquals(listOf("scope-work"), firstGlobal.items.map { it.id })
        assertEquals(listOf("scope-personal-b"), nextGlobal.items.map { it.id })

        val scopedQuery = MailQuery(text = "scope-unique", scope = SearchScope.Account)
        val firstOldest = repo.filteredPage(listOf("personal-inbox"), "personal", scopedQuery,
            null, oldestFirst = true, limit = 1)
        val nextOldest = repo.filteredPage(listOf("personal-inbox"), "personal", scopedQuery,
            firstOldest.next, oldestFirst = true, limit = 1)
        assertEquals(listOf("scope-personal-a"), firstOldest.items.map { it.id })
        assertEquals(listOf("scope-personal-b"), nextOldest.items.map { it.id })
    }

    @Test
    fun reopeningDatabaseKeepsLastFolderReadStateAndPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-test"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, MailDatabase::class.java, name).build()
        try {
            var stored =
                RoomMailRepository(
                    disk,
                    context,
                    DemoMail(context),
                    org.foxred.kage.data.security.AndroidCredentialStore(context, "repository-test"),
                )
            stored.initialize()
            stored.markRead("m01", true)
            stored.flag("m01", true)
            stored.updatePreferences(
                Preferences(
                    selectedFolder = "work-design",
                    threads = true,
                    offline = true,
                    started = true,
                )
            )
            disk.close()
            disk = Room.databaseBuilder(context, MailDatabase::class.java, name).build()
            stored =
                RoomMailRepository(
                    disk,
                    context,
                    DemoMail(context),
                    org.foxred.kage.data.security.AndroidCredentialStore(context, "repository-test"),
                )
            stored.initialize()
            val reopened = stored.mailbox.first()
            assertEquals("work-design", reopened.preferences.selectedFolder)
            assertTrue(reopened.preferences.started)
            assertTrue(reopened.preferences.threads)
            assertTrue(reopened.preferences.offline)
            assertTrue(reopened.messages.first { it.id == "m01" }.isRead)
            assertTrue(reopened.messages.first { it.id == "m01" }.flagged)
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }
}
