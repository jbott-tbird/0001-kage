// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foxred.kage.domain.model.*
import org.foxred.kage.domain.repository.MailRepository
import org.foxred.kage.data.setup.RealAccountSetup
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.HistoryProgress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

data class MessageLookup(val loaded: Boolean = false, val message: Message? = null)

data class MessagePageState(
    val folderId: String? = null,
    val folderIds: List<String> = emptyList(),
    val accountId: String? = null,
    val cursor: MessagePageCursor? = null,
    val page: MessagePage = MessagePage(),
    val pageNumber: Int = 1,
    val oldestFirst: Boolean = false,
    val query: MailQuery = MailQuery(),
)

private data class PageRequest(
    val folderId: String,
    val folderIds: List<String>,
    val cursor: MessagePageCursor?,
    val pageNumber: Int,
    val oldestFirst: Boolean,
    val accountId: String?,
    val query: MailQuery,
)

private fun pageRequest(
    mail: Mailbox, query: MailQuery, cursors: List<MessagePageCursor?>, oldestFirst: Boolean,
): PageRequest {
    val selected = mail.preferences.selectedFolder
    val folderIds = if (selected == "unified" && mail.preferences.unified)
        mail.folders.filter { it.role == "inbox" }.map { it.id }
    else listOf(selected)
    val accountId = mail.folders.find { it.id == selected }?.accountId
    return PageRequest(selected, folderIds, cursors.last(), cursors.size, oldestFirst,
        accountId, query)
}

private fun MessagePageState.matches(request: PageRequest): Boolean =
    folderId == request.folderId && folderIds == request.folderIds &&
        accountId == request.accountId && cursor == request.cursor &&
        pageNumber == request.pageNumber && oldestFirst == request.oldestFirst &&
        query == request.query

data class AttachmentTransferState(
    val loading: Boolean = false,
    val bytes: Long = 0,
    val error: String? = null,
)

data class HistoryDownloadState(
    val folderId: String? = null,
    val running: Boolean = false,
    val progress: HistoryProgress? = null,
    val error: String? = null,
)

class MailViewModel(
    val repository: MailRepository,
    val realAccountSetup: RealAccountSetup? = null,
    private val remote: RemoteMailRepository? = null,
    val googleAuthorization: org.foxred.kage.data.security.GoogleAuthorizationGateway? = null,
) : ViewModel() {
    private val preferencesMutex = Mutex()
    val mailbox = MutableStateFlow(Mailbox())
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<String?>(null)
    val folderSync = MutableStateFlow(FolderSyncState())
    val refreshProgress = MutableStateFlow<Map<String, String>>(emptyMap())
    val messageLoad = MutableStateFlow(MessageLoadState())
    val resumeGeneration = MutableStateFlow(0L)
    val attachmentTransfers = MutableStateFlow<Map<String, AttachmentTransferState>>(emptyMap())
    val historyDownload = MutableStateFlow(HistoryDownloadState())
    val outbox = repository.outbox.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val outboxCounts = repository.outboxCounts.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), OutboxCounts())
    private val outboxCursors = MutableStateFlow(listOf(Long.MAX_VALUE))
    val outboxPage = outboxCursors.flatMapLatest { cursors ->
        repository.observeOutboxPage(cursors.last()).map { it.copy(pageNumber = cursors.size) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
        OutboxPage(beforeRowId = 0))

    fun firstOutboxPage() { outboxCursors.value = listOf(Long.MAX_VALUE) }

    fun nextOutboxPage() {
        val page = outboxPage.value
        if (page.beforeRowId == outboxCursors.value.last())
            page.nextCursor?.let { outboxCursors.value = outboxCursors.value + it }
    }

    fun previousOutboxPage() {
        if (outboxCursors.value.size > 1)
            outboxCursors.value = outboxCursors.value.dropLast(1)
    }

    fun isCurrentOutboxPage(page: OutboxPage): Boolean =
        page.beforeRowId == outboxCursors.value.last() &&
            page.pageNumber == outboxCursors.value.size
    val unreadCounts = repository.unreadCounts.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    private var refreshJob: Job? = null
    private var recentInboxesJob: Job? = null
    private var automaticAttachmentJob: Job? = null
    private var automaticAttachmentRequested = false
    private var foregroundActive = true
    private var activeReaderTransfers = 0
    private val readerJobs = ConcurrentHashMap.newKeySet<Job>()
    private var historyJob: Job? = null
    private var historyProgressRequest = 0L
    @Volatile
    private var historyRunRequest = 0L
    private val activeRemoteRefreshes = mutableMapOf<String, Deferred<Int>>()
    private var refreshingFolderId: String? = null
    private var refreshingTargetIds: List<String> = emptyList()
    private val attachmentJobs = mutableMapOf<String, Job>()
    val query = MutableStateFlow(MailQuery())
    val oldestFirst = MutableStateFlow(false)
    private val pageCursors = MutableStateFlow(listOf<MessagePageCursor?>(null))

    private val pageRequests = combine(mailbox, query, pageCursors, oldestFirst) {
        mail, currentQuery, cursors, oldest ->
        pageRequest(mail, currentQuery, cursors, oldest)
    }

    fun lookupMessage(id: String): Flow<MessageLookup> =
        repository.observeMessage(id).map { MessageLookup(loaded = true, message = it) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messagePage = pageRequests.combine(repository.messageListRevision) { request, _ -> request }
        .mapLatest { request ->
            val page = if (request.query.text.isNotBlank() || request.query.filter.active)
                repository.filteredPage(request.folderIds, request.accountId, request.query,
                    request.cursor, request.oldestFirst)
            else repository.messagePage(request.folderIds, request.cursor, request.oldestFirst)
            MessagePageState(request.folderId, request.folderIds, request.accountId,
                request.cursor, page, request.pageNumber, request.oldestFirst, request.query)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MessagePageState())

    val pageReady = combine(pageRequests, messagePage) { request, page -> page.matches(request) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val messages = combine(pageRequests, messagePage) { request, page ->
        if (page.matches(request)) page.page.items else emptyList()
    }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Check source values too: the derived readiness flow can lag a folder or query change.
    fun isCurrentPage(page: MessagePageState): Boolean =
        page.matches(pageRequest(mailbox.value, query.value, pageCursors.value,
            oldestFirst.value))

    init {
        viewModelScope.launch {
            val feedback = SendFeedback()
            repository.outboxCounts.collect { counts ->
                feedback.update(counts)?.let { notice.value = it }
            }
        }
        viewModelScope.launch {
            query.drop(1).collect { pageCursors.value = listOf(null) }
        }
        action {
            repository.initialize()
            var lastOffline: Boolean? = null
            var lastAutomaticAttachments: Boolean? = null
            var lastSelectedFolder: String? = null
            var lastAccountIds: List<String>? = null
            var lastUnifiedInboxIds: List<String>? = null
            var lastRealInboxIds: Set<String>? = null
            repository.mailbox.collect {
                val accountIds = it.accounts.map { account -> account.id }
                val realAccountIds = it.accounts.filter { account -> account.mode == "REAL" }
                    .mapTo(HashSet()) { account -> account.id }
                val realInboxIds = it.folders.filter { folder ->
                    folder.role == "inbox" && folder.accountId in realAccountIds
                }.mapTo(HashSet()) { folder -> folder.id }
                val realInboxesChanged = lastRealInboxIds != null &&
                    realInboxIds != lastRealInboxIds
                val unifiedInboxIds = if (it.preferences.selectedFolder == "unified")
                    it.folders.filter { folder -> folder.role == "inbox" }.map { folder -> folder.id }
                else emptyList()
                if (lastSelectedFolder != it.preferences.selectedFolder ||
                    lastAccountIds != accountIds || lastUnifiedInboxIds != unifiedInboxIds) {
                    pageCursors.value = listOf(null)
                }
                lastSelectedFolder = it.preferences.selectedFolder
                lastAccountIds = accountIds
                lastUnifiedInboxIds = unifiedInboxIds
                mailbox.value = it
                ready.value = true
                if (it.preferences.offline && lastOffline != true) {
                    refreshJob?.cancel()
                    recentInboxesJob?.cancel()
                    pauseHistory()
                    activeRemoteRefreshes.values.forEach { task -> task.cancel() }
                    folderSync.value = folderSync.value.copy(loading = false)
                }
                if (it.preferences.offline || !it.preferences.automaticAttachments) {
                    pauseAutomaticAttachments()
                }
                if (foregroundActive && !it.preferences.offline &&
                    (lastOffline == null || lastOffline == true))
                    it.accounts.forEach { account ->
                        flushOutgoing(account.id)
                        syncDrafts(account.id)
                    }
                if (!it.preferences.offline && it.preferences.automaticAttachments &&
                    (lastAutomaticAttachments != true || lastOffline == true))
                    scheduleAutomaticAttachments()
                if (!it.preferences.offline &&
                    (lastOffline == null || lastOffline == true || realInboxesChanged)) {
                    // An account added while online must join the recent pass immediately.
                    if (realInboxesChanged) recentInboxesJob?.cancel()
                    refreshRecentInboxes()
                }
                lastOffline = it.preferences.offline
                lastAutomaticAttachments = it.preferences.automaticAttachments
                lastRealInboxIds = realInboxIds
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
        pageCursors.value = listOf(null)
        preferences { it.copy(selectedFolder = id, started = true) }
    }

    fun setOldestFirst(value: Boolean) {
        if (oldestFirst.value == value) return
        oldestFirst.value = value
        pageCursors.value = listOf(null)
    }

    fun nextMessagePage() {
        val current = messagePage.value
        if (!current.matches(pageRequest(mailbox.value, query.value, pageCursors.value,
                oldestFirst.value))) return
        current.page.next?.let { pageCursors.value = pageCursors.value + it }
    }

    fun previousMessagePage() {
        if (pageCursors.value.size > 1) pageCursors.value = pageCursors.value.dropLast(1)
    }

    fun flushOutgoing(accountId: String) {
        if (mailbox.value.preferences.offline) return
        action { remote?.flushOutgoing(accountId) }
    }

    fun syncDrafts(accountId: String) {
        if (mailbox.value.preferences.offline) return
        action {
            val synced = remote?.flushDrafts(accountId) ?: 0
            if (synced > 0)
                notice.value = "$synced draft${if (synced == 1) "" else "s"} synced to the mail server."
        }
    }

    fun retryDraftSync(messageId: String) {
        if (mailbox.value.preferences.offline) return
        action {
            val synced = remote?.retryUncertainDraft(messageId) ?: return@action
            notice.value = if (synced > 0) "Draft synced to the mail server."
                else "Draft remains on this device. Check its sync status before retrying."
        }
    }

    fun retryOutbox(id: String) {
        if (mailbox.value.preferences.offline) return
        action { flushOutgoing(repository.retryOutbox(id)) }
    }

    fun checkSentCopy(accountId: String) {
        if (mailbox.value.preferences.offline) return
        action {
            val confirmed = remote?.reconcileSent(accountId) ?: return@action
            notice.value = if (confirmed > 0)
                "$confirmed message${if (confirmed == 1) "" else "s"} found in Sent."
            else "No matching Sent copy found yet. Nothing was resent."
        }
    }

    fun saveSentCopy(outboxId: String, retryAfterReview: Boolean = false) {
        if (mailbox.value.preferences.offline) return
        action {
            val saved = remote?.saveMissingSentCopy(outboxId, retryAfterReview) ?: return@action
            notice.value = if (saved) "Copy found in Sent."
                else "Sent copy needs review. Check Sent before trying again."
        }
    }

    /** Keep each account's recent inbox cached when the app opens or returns to foreground. */
    fun refreshRecentInboxes() {
        if (!foregroundActive || mailbox.value.preferences.offline ||
            recentInboxesJob?.isActive == true) return
        pauseAutomaticAttachments()
        recentInboxesJob = viewModelScope.launch {
            val server = remote ?: return@launch
            val since = recentStart()
            val inboxes = mailbox.value.folders.filter { it.role == "inbox" }.map { it.id }
            var firstError: String? = null
            var refreshed = false
            for (folderId in inboxes) {
                if (!server.isRemoteFolder(folderId)) continue
                try {
                    refreshRemoteOnce(server, folderId, since, full = false)
                    refreshed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (firstError == null)
                        firstError = failure.message ?: "Could not refresh an account inbox"
                }
            }
            if (refreshed) scheduleAutomaticAttachments()
            if (firstError != null) error.value = firstError
        }
    }

    private fun recentStart() = Instant.now().minus(Duration.ofDays(30))

    private fun pauseAutomaticAttachments() {
        automaticAttachmentRequested = false
        automaticAttachmentJob?.cancel()
    }

    fun onAppStopped() {
        foregroundActive = false
        refreshJob?.cancel()
        recentInboxesJob?.cancel()
        // Shared passes are launched in viewModelScope, outside either caller's job.
        activeRemoteRefreshes.values.forEach { it.cancel() }
        folderSync.value = folderSync.value.copy(loading = false)
        readerJobs.toList().forEach { it.cancel() }
        attachmentJobs.values.toList().forEach { it.cancel() }
        if (messageLoad.value.loading)
            messageLoad.value = messageLoad.value.copy(loading = false,
                error = "Download paused while the app was away. Retry to continue.")
        pauseHistory(resumeAutomaticAttachments = false)
        pauseAutomaticAttachments()
    }

    fun onAppResumed() {
        val recentBeforeResume = recentInboxesJob
        val wasStopped = !foregroundActive
        foregroundActive = true
        if (wasStopped) {
            resumeGeneration.value++
            if (ready.value && !mailbox.value.preferences.offline)
                mailbox.value.accounts.forEach { account ->
                    flushOutgoing(account.id)
                    syncDrafts(account.id)
                }
        }
        // The lifecycle observer starts the recent pass immediately after this call. Let it
        // take priority; it schedules optional parts only after a successful refresh.
        viewModelScope.launch {
            yield()
            if (recentInboxesJob === recentBeforeResume)
                scheduleAutomaticAttachments()
        }
    }

    /** Run optional server downloads separately from mailbox readiness and visible refresh. */
    private fun scheduleAutomaticAttachments() {
        val server = remote ?: return
        if (!foregroundActive || mailbox.value.preferences.offline ||
            !mailbox.value.preferences.automaticAttachments) return
        automaticAttachmentRequested = true
        if (activeRemoteRefreshes.values.any { it.isActive } ||
            historyDownload.value.running || activeReaderTransfers > 0 ||
            attachmentJobs.isNotEmpty()) return
        if (automaticAttachmentJob?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            do {
                automaticAttachmentRequested = false
                try {
                    server.cacheAutomaticAttachments()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@launch
                }
            } while (automaticAttachmentRequested &&
                !mailbox.value.preferences.offline &&
                mailbox.value.preferences.automaticAttachments)
        }
        automaticAttachmentJob = job
        job.invokeOnCompletion {
            viewModelScope.launch {
                if (automaticAttachmentJob === job) {
                    automaticAttachmentJob = null
                    // A new request may have arrived while this job was still cancelling.
                    if (automaticAttachmentRequested) scheduleAutomaticAttachments()
                }
            }
        }
        job.start()
    }

    /** Share one network pass when startup and a visible inbox request the same folder. */
    private suspend fun refreshRemoteOnce(
        server: RemoteMailRepository, folderId: String, since: Instant, full: Boolean,
    ): Int {
        activeRemoteRefreshes[folderId]?.takeIf { it.isActive }?.let { inFlight ->
            try {
                val count = inFlight.await()
                if (!full) return count
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!full) throw failure
                // A manual full refresh can retry after the shared startup pass fails.
            }
        }
        val task = viewModelScope.async {
            try {
                server.refreshVisibleFolder(folderId, since, full) { progress ->
                    refreshProgress.value = refreshProgress.value + (folderId to progress)
                }
            } finally {
                refreshProgress.value = refreshProgress.value - folderId
            }
        }
        activeRemoteRefreshes[folderId] = task
        task.invokeOnCompletion {
            viewModelScope.launch {
                if (activeRemoteRefreshes[folderId] === task)
                    activeRemoteRefreshes.remove(folderId)
            }
        }
        return task.await()
    }

    /** Refreshes the selected real mailbox on open or when the user requests it. */
    fun refreshFolder(folderId: String, full: Boolean = false) {
        if (!foregroundActive) return
        val targets = if (folderId == "unified" && mailbox.value.preferences.unified)
            mailbox.value.folders.filter { it.role == "inbox" }.map { it.id }
        else listOf(folderId)
        if (!full && refreshingFolderId == folderId && refreshingTargetIds == targets &&
            refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshingFolderId = folderId
        refreshingTargetIds = targets
        refreshJob = viewModelScope.launch {
            val server = remote
            val remoteTargets = targets.filter { server?.isRemoteFolder(it) == true }
            val isRemote = remoteTargets.isNotEmpty()
            folderSync.value = FolderSyncState(folderId, remote = isRemote)
            if (!isRemote || mailbox.value.preferences.offline) return@launch
            pauseAutomaticAttachments()
            folderSync.value = FolderSyncState(folderId, remote = true, loading = true)
            val since = recentStart()
            var firstError: String? = null
            var refreshed = false
            try {
                for (target in remoteTargets) {
                    try {
                        refreshRemoteOnce(server!!, target, since, full)
                        refreshed = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (firstError == null)
                            firstError = failure.message ?: "Could not refresh this mailbox"
                    }
                }
                if (refreshed) scheduleAutomaticAttachments()
                if (folderSync.value.folderId == folderId)
                    folderSync.value = FolderSyncState(folderId, remote = true, error = firstError)
            } finally {
                if (refreshJob == kotlinx.coroutines.currentCoroutineContext()[Job])
                    folderSync.value = folderSync.value.copy(loading = false)
            }
        }
    }

    fun leaveFolder(folderId: String) {
        if (historyDownload.value.folderId == folderId && historyDownload.value.running)
            pauseHistory()
        viewModelScope.launch { remote?.finishVisit(folderId) }
    }

    fun loadHistoryProgress(folderId: String) {
        // Folder navigation can start this effect before the old screen's disposal pauses work.
        if (historyDownload.value.running && historyDownload.value.folderId != folderId)
            pauseHistory()
        val request = ++historyProgressRequest
        viewModelScope.launch {
            try {
                val server = remote ?: return@launch
                if (!server.isRemoteFolder(folderId)) return@launch
                val progress = server.historyProgress(folderId)
                if (request == historyProgressRequest && !historyDownload.value.running)
                    historyDownload.value = HistoryDownloadState(folderId, progress = progress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request == historyProgressRequest && !historyDownload.value.running)
                    historyDownload.value = HistoryDownloadState(folderId,
                        error = failure.message ?: "Could not load mail history progress")
            }
        }
    }

    fun downloadHistory(folderId: String, restartCompleted: Boolean = false) {
        if (!foregroundActive) {
            historyDownload.value = HistoryDownloadState(folderId,
                progress = historyDownload.value.progress?.takeIf { it.folderId == folderId },
                error = "Return to Kage to download mail history")
            return
        }
        if (mailbox.value.preferences.offline) {
            if (historyJob?.isActive == true) pauseHistory()
            historyDownload.value = HistoryDownloadState(folderId,
                progress = historyDownload.value.progress, error = "Go online to download mail history")
            return
        }
        if (historyJob?.isActive == true) {
            if (historyDownload.value.folderId == folderId) return
            historyJob?.cancel()
        }
        val server = remote ?: return
        historyProgressRequest++
        val run = ++historyRunRequest
        pauseAutomaticAttachments()
        historyDownload.value = HistoryDownloadState(folderId, running = true,
            progress = historyDownload.value.progress?.takeIf { it.folderId == folderId })
        historyJob = viewModelScope.launch {
            try {
                val result = server.downloadHistory(folderId,
                    restartCompleted = restartCompleted) { progress ->
                    if (run == historyRunRequest && historyDownload.value.folderId == folderId &&
                        historyDownload.value.running)
                        historyDownload.value = HistoryDownloadState(folderId, running = true,
                            progress = progress)
                }
                if (run == historyRunRequest && historyDownload.value.folderId == folderId &&
                    historyDownload.value.running)
                    historyDownload.value = HistoryDownloadState(folderId, progress = result)
            } catch (cancelled: CancellationException) {
                if (run == historyRunRequest && historyDownload.value.folderId == folderId)
                    historyDownload.value = historyDownload.value.copy(running = false)
                throw cancelled
            } catch (failure: Exception) {
                if (run != historyRunRequest) return@launch
                val checkpoint = try { server.historyProgress(folderId) }
                    catch (_: Exception) { historyDownload.value.progress }
                if (run == historyRunRequest && historyDownload.value.folderId == folderId &&
                    historyDownload.value.running)
                    historyDownload.value = HistoryDownloadState(folderId,
                        progress = checkpoint,
                        error = failure.message ?: "Could not download mail history")
            } finally {
                if (run == historyRunRequest && !historyDownload.value.running)
                    scheduleAutomaticAttachments()
            }
        }
    }

    fun pauseHistory(resumeAutomaticAttachments: Boolean = true) {
        historyRunRequest++
        historyJob?.cancel()
        historyDownload.value = historyDownload.value.copy(running = false)
        if (resumeAutomaticAttachments) scheduleAutomaticAttachments()
    }

    /** The reader calls this in a keyed effect, so leaving the screen cancels its network fetch. */
    suspend fun loadBody(messageId: String) {
        if (!foregroundActive || mailbox.value.preferences.offline) return
        val server = remote ?: return
        val job = currentCoroutineContext()[Job]
        if (job != null) readerJobs.add(job)
        pauseAutomaticAttachments()
        activeReaderTransfers++
        messageLoad.value = MessageLoadState(messageId, loading = true)
        try {
            server.downloadBody(messageId)
            messageLoad.value = MessageLoadState(messageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            messageLoad.value = MessageLoadState(messageId,
                error = failure.message ?: "Could not load this message")
        } finally {
            if (job != null) readerJobs.remove(job)
            activeReaderTransfers--
            if (activeReaderTransfers == 0) scheduleAutomaticAttachments()
        }
    }

    /** CID images are part of the message body and load when the reader is visible. */
    suspend fun prefetchInlineImages(messageId: String) {
        if (!foregroundActive || mailbox.value.preferences.offline) return
        val server = remote ?: return
        val job = currentCoroutineContext()[Job]
        if (job != null) readerJobs.add(job)
        try {
            val ids = server.inlineImageAttachmentIds(messageId)
            if (ids.isEmpty()) return
            pauseAutomaticAttachments()
            activeReaderTransfers++
            try {
                ids.forEach { id ->
                    try { repository.cacheAttachment(id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The attachment remains available for a manual retry. */ }
                }
            } finally {
                activeReaderTransfers--
                if (activeReaderTransfers == 0) scheduleAutomaticAttachments()
            }
        } finally {
            if (job != null) readerJobs.remove(job)
        }
    }

    fun downloadAttachment(id: String, onReady: (String) -> Unit) {
        if (!foregroundActive || attachmentTransfers.value[id]?.loading == true) return
        pauseAutomaticAttachments()
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
                if (attachmentJobs.isEmpty()) scheduleAutomaticAttachments()
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

    fun markRead(ids: List<String>, onSuccess: (() -> Unit)? = null) = action(onSuccess) {
        ids.forEach { repository.markRead(it, true) }
        notice.value = "${ids.size} messages marked read"
    }

    fun flag(message: Message) = action { repository.flag(message.id, !message.flagged) }

    fun pin(message: Message) = action { repository.pin(message.id, !message.pinned) }
}
