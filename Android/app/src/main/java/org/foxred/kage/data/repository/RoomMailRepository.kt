package org.foxred.kage.data.repository

import android.content.Context
import androidx.room.withTransaction
import java.io.File
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
        withContext(Dispatchers.IO) { db.withTransaction { if (dao.initialized() == 0) seed() } }

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
        }

    override suspend fun removeAccount(id: String) =
        db.withTransaction {
            dao.removeAccount(id)
            val prefs = dao.getPreferences() ?: Preferences().entity()
            val inbox = dao.firstInbox()
            dao.savePreferences(
                prefs.copy(selectedFolder = inbox.orEmpty(), started = inbox != null)
            )
        }

    override suspend fun updatePreferences(preferences: Preferences) {
        dao.savePreferences(preferences.entity())
        if (preferences.automaticAttachments && !preferences.offline)
            dao.uncached().forEach { cacheAttachment(it.id) }
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

    override suspend fun cacheAttachment(id: String): String =
        withContext(Dispatchers.IO) {
            val item = requireNotNull(dao.attachment(id))
            val directory = File(context.filesDir, "attachments").apply { mkdirs() }
            val file = File(directory, "${item.id.replace(Regex("[^a-zA-Z0-9-]"), "_")}.pdf")
            if (!file.exists()) {
                check(dao.getPreferences()?.offline != true) {
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
            db.withTransaction {
                dao.clearAccounts()
                dao.clearPreferences()
                seed()
            }
        }
}
