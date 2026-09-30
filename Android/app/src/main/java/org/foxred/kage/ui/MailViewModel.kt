package org.foxred.kage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foxred.kage.domain.model.*
import org.foxred.kage.domain.repository.MailRepository
import org.foxred.kage.domain.usecase.FilterMessages
import org.foxred.kage.data.setup.RealAccountSetup
import org.foxred.kage.data.repository.RemoteMailRepository
import java.time.LocalDate
import java.time.ZoneOffset

data class FolderSyncState(
    val folderId: String? = null,
    val remote: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class MessageLoadState(
    val messageId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class AttachmentTransferState(
    val loading: Boolean = false,
    val bytes: Long = 0,
    val error: String? = null,
)

class MailViewModel(
    val repository: MailRepository,
    val realAccountSetup: RealAccountSetup? = null,
    private val remote: RemoteMailRepository? = null,
) : ViewModel() {
    private val preferencesMutex = Mutex()
    val mailbox = MutableStateFlow(Mailbox())
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)
    val folderSync = MutableStateFlow(FolderSyncState())
    val messageLoad = MutableStateFlow(MessageLoadState())
    val attachmentTransfers = MutableStateFlow<Map<String, AttachmentTransferState>>(emptyMap())
    private var refreshJob: Job? = null
    private val attachmentJobs = mutableMapOf<String, Job>()
    val query = MutableStateFlow(MailQuery())
    val messages =
        combine(mailbox, query) { mail, query -> FilterMessages()(mail, query) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val feedback = SendFeedback()
            repository.outboxCounts.collect { counts ->
                feedback.update(counts)?.let { notice.value = it }
            }
        }
        action {
            repository.initialize()
            repository.mailbox.collect {
                mailbox.value = it
                ready.value = true
            }
        }
    }

    fun action(success: (() -> Unit)? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "Could not save this change. Please try again."
            }
        }
    }

    fun selectFolder(id: String) {
        query.value = MailQuery()
        preferences { it.copy(selectedFolder = id, started = true) }
    }

    /** Refreshes the selected real mailbox on open or when the user requests it. */
    fun refreshFolder(folderId: String, full: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val server = remote
            val isRemote = server?.isRemoteFolder(folderId) == true
            folderSync.value = FolderSyncState(folderId, remote = isRemote)
            if (!isRemote || mailbox.value.preferences.offline) return@launch
            folderSync.value = FolderSyncState(folderId, remote = true, loading = true)
            try {
                val since = LocalDate.now(ZoneOffset.UTC).minusDays(30)
                    .atStartOfDay(ZoneOffset.UTC).toInstant()
                server.refreshVisibleFolder(folderId, since, full)
                if (folderSync.value.folderId == folderId)
                    folderSync.value = FolderSyncState(folderId, remote = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (folderSync.value.folderId == folderId)
                    folderSync.value = FolderSyncState(
                        folderId, remote = true,
                        error = failure.message ?: "Could not refresh this mailbox",
                    )
            }
        }
    }

    fun leaveFolder(folderId: String) {
        viewModelScope.launch { remote?.finishVisit(folderId) }
    }

    /** The reader calls this in a keyed effect, so leaving the screen cancels its network fetch. */
    suspend fun loadBody(messageId: String) {
        if (mailbox.value.preferences.offline) return
        val server = remote ?: return
        messageLoad.value = MessageLoadState(messageId, loading = true)
        try {
            server.downloadBody(messageId)
            messageLoad.value = MessageLoadState(messageId)
            if (mailbox.value.preferences.automaticAttachments) {
                viewModelScope.launch {
                    server.attachmentIds(messageId).forEach { id ->
                        try { repository.cacheAttachment(id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            error.value = failure.message ?: "Could not download an attachment"
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            messageLoad.value = MessageLoadState(messageId,
                error = failure.message ?: "Could not load this message")
        }
    }

    /** CID images are part of the message body and load when the reader is visible. */
    suspend fun prefetchInlineImages(messageId: String) {
        if (mailbox.value.preferences.offline) return
        val server = remote ?: return
        server.inlineImageAttachmentIds(messageId).forEach { id ->
            try { repository.cacheAttachment(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The attachment remains available for a manual retry. */ }
        }
    }

    fun downloadAttachment(id: String, onReady: (String) -> Unit) {
        if (attachmentTransfers.value[id]?.loading == true) return
        attachmentTransfers.value = attachmentTransfers.value +
            (id to AttachmentTransferState(loading = true))
        attachmentJobs[id] = viewModelScope.launch {
            try {
                val path = repository.cacheAttachment(id) { bytes ->
                    attachmentTransfers.value = attachmentTransfers.value +
                        (id to AttachmentTransferState(loading = true, bytes = bytes))
                }
                attachmentTransfers.value = attachmentTransfers.value +
                    (id to AttachmentTransferState())
                onReady(path)
            } catch (cancelled: CancellationException) {
                attachmentTransfers.value = attachmentTransfers.value +
                    (id to AttachmentTransferState())
                throw cancelled
            } catch (failure: Exception) {
                attachmentTransfers.value = attachmentTransfers.value +
                    (id to AttachmentTransferState(error =
                        failure.message ?: "Could not download this attachment"))
            } finally {
                attachmentJobs.remove(id)
            }
        }
    }

    fun cancelAttachment(id: String) {
        attachmentJobs[id]?.cancel()
    }

    fun preferences(change: (Preferences) -> Preferences) = action {
        preferencesMutex.withLock {
            repository.updatePreferences(change(repository.mailbox.first().preferences))
        }
    }

    fun read(id: String) = action { repository.markRead(id, true) }

    fun flag(message: Message) = action { repository.flag(message.id, !message.flagged) }

    fun pin(message: Message) = action { repository.pin(message.id, !message.pinned) }
}
