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
    private var refreshJob: Job? = null
    val query = MutableStateFlow(MailQuery())
    val messages =
        combine(mailbox, query) { mail, query -> FilterMessages()(mail, query) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
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

    fun preferences(change: (Preferences) -> Preferences) = action {
        preferencesMutex.withLock {
            repository.updatePreferences(change(repository.mailbox.first().preferences))
        }
    }

    fun read(id: String) = action { repository.markRead(id, true) }

    fun flag(message: Message) = action { repository.flag(message.id, !message.flagged) }

    fun pin(message: Message) = action { repository.pin(message.id, !message.pinned) }
}
