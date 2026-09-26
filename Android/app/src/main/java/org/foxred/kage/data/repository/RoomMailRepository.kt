package org.foxred.kage.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.foxred.kage.data.local.*
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.domain.model.*
import org.foxred.kage.domain.repository.MailRepository

class RoomMailRepository(
    private val db: MailDatabase,
    private val context: Context,
    private val demo: DemoMail,
    private val credentials: org.foxred.kage.core.account.CredentialStore,
    private val remote: RemoteMailRepository? = null,
) : MailRepository {
    private val dao = db.mailDao()
    override val mailbox =
        combine(
            dao.accounts(),
            dao.folders(),
            dao.messages(),
            dao.attachments(),
            dao.preferences(),
        ) { accounts, folders, messages, attachments, preferences ->
            Mailbox(
                accounts.map { it.domain() },
                folders.map { it.domain() },
                messages.map { m ->
                    m.domain(attachments.filter { it.messageId == m.id }.map { it.domain() })
                },
                preferences?.domain() ?: Preferences(),
            )
        }

    override suspend fun initialize() =
        withContext(Dispatchers.IO) {
            db.withTransaction { if (dao.initialized() == 0) seed() }
            cacheAutomaticAttachments()
        }

    private suspend fun seed() {
        demo.accounts.forEach { insertAccount(it) }
        dao.savePreferences(Preferences().entity())
    }

    private suspend fun insertAccount(account: Account) {
        dao.insertAccounts(listOf(account.entity()))
        dao.insertFolders(demo.folders(account).map { it.entity() })
        val messages = demo.messages(account)
        dao.saveMessages(messages.map { it.entity() })
        dao.saveAttachments(messages.flatMap { it.attachments }.map { it.entity() })
        messages.flatMap { it.attachments }.filter { it.cached }.forEach { cacheAttachment(it.id) }
    }

    override suspend fun addAccount(account: Account) =
        withContext(Dispatchers.IO) {
            require(account.address.contains('@')) { "Enter a valid email address." }
            require(account.incoming.isNotBlank() && account.outgoing.isNotBlank()) {
                "Enter both server addresses."
            }
            require(account.incomingPort in 1..65535 && account.outgoingPort in 1..65535) {
                "Ports must be between 1 and 65535."
            }
            db.withTransaction {
                insertAccount(account.copy(address = account.address.trim().lowercase()))
                dao.savePreferences(
                    (dao.getPreferences() ?: Preferences().entity()).copy(
                        selectedFolder = "${account.id}-inbox",
                        started = true,
                    )
                )
            }
            cacheAutomaticAttachments()
        }

    private suspend fun cacheAutomaticAttachments() {
        val preferences = dao.getPreferences() ?: return
        if (preferences.automaticAttachments && !preferences.offline)
            dao.uncached().forEach { cacheAttachment(it.id) }
    }

    override suspend fun removeAccount(id: String) =
        withContext(Dispatchers.IO) {
            if (db.remoteMailDao().account(id)?.mode == "REAL" && remote != null)
                remote.removeAccount(id)
            else credentials.removeAccount(id)
            db.withTransaction {
                dao.removeAccount(id)
                val prefs = dao.getPreferences() ?: Preferences().entity()
                val inbox = dao.firstInbox()
                dao.savePreferences(
                    prefs.copy(selectedFolder = inbox.orEmpty(), started = inbox != null)
                )
            }
        }

    override suspend fun updatePreferences(preferences: Preferences) {
        dao.savePreferences(preferences.entity())
        cacheAutomaticAttachments()
    }

    override suspend fun markRead(id: String, read: Boolean) = dao.markRead(id, read)

    override suspend fun flag(id: String, value: Boolean) = dao.flag(id, value)

    override suspend fun pin(id: String, value: Boolean) = dao.pin(id, value)

    override suspend fun move(id: String, role: String) =
        db.withTransaction {
            val message = requireNotNull(dao.message(id))
            val folder = requireNotNull(dao.folder(message.accountId, role))
            dao.move(id, folder)
        }

    override suspend fun saveDraft(message: Message) =
        db.withTransaction {
            val folder = requireNotNull(dao.folder(message.accountId, "drafts"))
            dao.saveMessages(
                listOf(
                    message
                        .copy(folderId = folder, draft = true, isRead = true, isNew = false)
                        .entity()
                )
            )
            dao.clearAttachments(message.id)
            dao.saveAttachments(message.attachments.map { it.entity() })
        }

    override suspend fun sendDemo(message: Message) =
        db.withTransaction {
            require(message.to.isNotBlank()) { "Enter a recipient." }
            require(
                listOf(message.to, message.cc, message.bcc)
                    .filter { it.isNotBlank() }
                    .joinToString(",")
                    .split(',', ';')
                    .all { it.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) }
            ) {
                "Enter valid recipient email addresses."
            }
            val folder = requireNotNull(dao.folder(message.accountId, "sent"))
            dao.saveMessages(
                listOf(
                    message
                        .copy(folderId = folder, draft = false, isRead = true, isNew = false)
                        .entity()
                )
            )
            dao.clearAttachments(message.id)
            dao.saveAttachments(message.attachments.map { it.entity() })
        }

    override suspend fun deleteDraft(id: String) = dao.deleteDraft(id)

    override suspend fun importAttachment(messageId: String, sourceUri: String): Attachment =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(sourceUri)
            val resolver = context.contentResolver
            val id = UUID.randomUUID().toString()
            val name =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "Attachment"
            val directory = File(context.filesDir, "attachments").apply { mkdirs() }
            val file = File(directory, id)
            try {
                requireNotNull(resolver.openInputStream(uri)) {
                        "Unable to open the selected file."
                    }
                    .use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                Attachment(
                    id,
                    messageId,
                    name,
                    resolver.getType(uri) ?: "application/octet-stream",
                    file.length(),
                    cached = true,
                    asset = "",
                    localFile = id,
                )
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    override suspend fun cacheAttachment(id: String): String =
        withContext(Dispatchers.IO) {
            val item = requireNotNull(dao.attachment(id))
            val directory = File(context.filesDir, "attachments").apply { mkdirs() }
            val file =
                if (item.localFile != null) File(directory, item.localFile)
                else
                    File(
                        directory,
                        "${item.id.replace(Regex("[^a-zA-Z0-9-]"), "_")}.${item.asset.substringAfterLast('.', "bin")}",
                    )
            if (item.localFile != null) {
                check(file.isFile) {
                    "The local attachment is no longer available. Attach it again from Files."
                }
                return@withContext file.absolutePath
            }
            if (!file.exists()) {
                check(item.cached || dao.getPreferences()?.offline != true) {
                    "This attachment is not available offline. Turn off offline preview to download it."
                }
                context.assets.open(item.asset).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            dao.cached(id)
            file.absolutePath
        }

    override suspend fun resetDemo() =
        withContext(Dispatchers.IO) {
            val demoIds = dao.demoAccountIds()
            db.withTransaction {
                val prefs = dao.getPreferences()
                val selectedAccount = prefs?.selectedFolder?.let { dao.folderAccountId(it) }
                val preserveSelection = selectedAccount != null && selectedAccount in dao.realAccountIds()
                demoIds.forEach { dao.removeAccount(it) }
                demo.accounts.forEach { insertAccount(it) }
                dao.savePreferences(
                    if (preserveSelection) prefs!! else Preferences().entity()
                )
            }
            demoIds.forEach { credentials.removeAccount(it) }
        }
}
