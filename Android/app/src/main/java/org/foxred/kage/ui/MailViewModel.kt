package org.foxred.kage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foxred.kage.domain.model.*
import org.foxred.kage.domain.repository.MailRepository
import org.foxred.kage.domain.usecase.FilterMessages

class MailViewModel(val repository: MailRepository) : ViewModel() {
    private val preferencesMutex = Mutex()
    val mailbox = MutableStateFlow(Mailbox())
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)
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

    fun preferences(change: (Preferences) -> Preferences) = action {
        preferencesMutex.withLock {
            repository.updatePreferences(change(repository.mailbox.first().preferences))
        }
    }

    fun read(id: String) = action { repository.markRead(id, true) }

    fun flag(message: Message) = action { repository.flag(message.id, !message.flagged) }

    fun pin(message: Message) = action { repository.pin(message.id, !message.pinned) }
}
