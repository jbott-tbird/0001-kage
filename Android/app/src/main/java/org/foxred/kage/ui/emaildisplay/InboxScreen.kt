// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.emaildisplay

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.foxred.kage.domain.model.*
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.accountlist.AccountDrawer
import org.foxred.kage.ui.shared.*
import org.foxred.kage.ui.theme.DesignTokens as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: MailViewModel,
    openMessage: (Message) -> Unit,
    compose: () -> Unit,
    setup: () -> Unit,
    settings: () -> Unit,
) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val page by vm.messagePage.collectAsStateWithLifecycle()
    val observedPageReady by vm.pageReady.collectAsStateWithLifecycle()
    val oldestFirst by vm.oldestFirst.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sync by vm.folderSync.collectAsStateWithLifecycle()
    val refreshProgress by vm.refreshProgress.collectAsStateWithLifecycle()
    val history by vm.historyDownload.collectAsStateWithLifecycle()
    val outboxCounts by vm.outboxCounts.collectAsStateWithLifecycle()
    val outboxPage by vm.outboxPage.collectAsStateWithLifecycle()
    val unreadCounts by vm.unreadCounts.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var showingOutbox by remember { mutableStateOf(false) }
    LaunchedEffect(showingOutbox) {
        if (showingOutbox) vm.firstOutboxPage()
    }
    var saveSentCopyRequest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val listState = rememberLazyListState()
    var resetScroll by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(oldestFirst) {
        if (resetScroll) {
            listState.scrollToItem(0)
            resetScroll = false
        }
    }
    val pageReady = observedPageReady && vm.isCurrentPage(page)
    val displayed = if (pageReady) page.page.items else emptyList()
    LaunchedEffect(page.folderId, page.folderIds, page.pageNumber, page.query,
        page.oldestFirst, pageReady) {
        if (pageReady)
            listState.scrollToItem(0)
    }
    val threadSizes = remember(mail.messages, mail.preferences.threads) {
        if (mail.preferences.threads)
            mail.messages.asSequence().mapNotNull { it.relatedGroup }
                .groupingBy { it }.eachCount()
        else emptyMap()
    }
    val visibleIds = displayed.map { it.id }.toSet()
    val selectedVisible = selectedIds.filter { it in visibleIds }
    fun markRead(ids: List<String>) {
        vm.markRead(ids) {
            selectedIds = emptyList()
            selecting = false
        }
    }
    val unifiedInboxIds = if (mail.preferences.selectedFolder == "unified")
        mail.folders.filter { it.role == "inbox" }.map { it.id }
    else emptyList()
    LaunchedEffect(mail.preferences.selectedFolder, unifiedInboxIds) {
        selectedIds = emptyList()
        selecting = false
        if (mail.preferences.selectedFolder == "unified")
            vm.query.value = vm.query.value.copy(scope = SearchScope.AllAccounts)
        vm.refreshFolder(mail.preferences.selectedFolder)
        vm.loadHistoryProgress(mail.preferences.selectedFolder)
    }
    DisposableEffect(mail.preferences.selectedFolder) {
        val current = mail.preferences.selectedFolder
        onDispose { vm.leaveFolder(current) }
    }
    val folder = mail.folders.find { it.id == mail.preferences.selectedFolder }
    val account = mail.accounts.find { it.id == folder?.accountId }
    val isRefreshing = sync.folderId == mail.preferences.selectedFolder && sync.loading
    // Both manual entry points reconcile server changes through the same refresh.
    val refreshMailbox: () -> Unit = {
        if (!isRefreshing && !mail.preferences.offline)
            vm.refreshFolder(mail.preferences.selectedFolder, full = true)
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AccountDrawer(
                mail,
                unreadCounts,
                {
                    vm.selectFolder(it)
                    scope.launch { drawer.close() }
                },
                {
                    scope.launch {
                        drawer.close()
                        setup()
                    }
                },
                {
                    scope.launch {
                        drawer.close()
                        settings()
                    }
                },
                outboxCount = outboxCounts.actionable,
                openOutbox = {
                    scope.launch {
                        drawer.close()
                        showingOutbox = true
                    }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (query.text.isNotEmpty()) "Search"
                                else if (mail.preferences.selectedFolder == "unified") "All inboxes"
                                else folder?.name ?: "Inbox"
                            )
                            Text(
                                account?.address ?: "All accounts",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        MailIconButton("Open account drawer", Icons.Outlined.Menu) {
                            scope.launch { drawer.open() }
                        }
                    },
                    actions = {
                        if (sync.remote && sync.folderId == mail.preferences.selectedFolder)
                            IconButton(
                                onClick = refreshMailbox,
                                enabled = !isRefreshing && !mail.preferences.offline,
                            ) {
                                Icon(Icons.Outlined.Refresh, "Refresh mailbox")
                            }
                        MailIconButton("Search messages", Icons.Outlined.Search) {
                            searching = !searching
                        }
                        Box {
                            IconButton(onClick = { filters = true }) {
                                Icon(
                                    Icons.Outlined.FilterList,
                                    "Filter messages",
                                    tint =
                                        if (query.filter.active) MaterialTheme.colorScheme.primary
                                        else LocalContentColor.current,
                                )
                            }
                            DropdownMenu(
                                expanded = filters,
                                onDismissRequest = { filters = false },
                            ) {
                                Text(
                                    "Filter",
                                    Modifier.padding(horizontal = T.lg, vertical = T.sm),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                listOf(
                                        "Unread" to query.filter.unread,
                                        "Flagged" to query.filter.flagged,
                                        "Pinned" to query.filter.pinned,
                                        "Has attachments" to query.filter.attachments,
                                    )
                                    .forEachIndexed { i, (label, checked) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            leadingIcon = {
                                                Checkbox(checked, onCheckedChange = null)
                                            },
                                            onClick = {
                                                vm.query.value =
                                                    query.copy(
                                                        filter =
                                                            when (i) {
                                                                0 ->
                                                                    query.filter.copy(
                                                                        unread = !checked
                                                                    )
                                                                1 ->
                                                                    query.filter.copy(
                                                                        flagged = !checked
                                                                    )
                                                                2 ->
                                                                    query.filter.copy(
                                                                        pinned = !checked
                                                                    )
                                                                else ->
                                                                    query.filter.copy(
                                                                        attachments = !checked
                                                                    )
                                                            }
                                                    )
                                            },
                                        )
                                    }
                            }
                        }
                        Box {
                            MailIconButton("Inbox options", Icons.Outlined.MoreHoriz) {
                                options = true
                            }
                            DropdownMenu(options, { options = false }) {
                                DropdownMenuItem(
                                    text = { Text("Outbox (${outboxCounts.actionable})") },
                                    onClick = {
                                        options = false
                                        showingOutbox = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (selecting) "Cancel selection" else "Select messages"
                                        )
                                    },
                                    onClick = {
                                        selecting = !selecting
                                        selectedIds = emptyList()
                                        options = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (oldestFirst) "Newest first" else "Oldest first")
                                    },
                                    onClick = {
                                        resetScroll = true
                                        vm.setOldestFirst(!oldestFirst)
                                        options = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Mark all read") },
                                    enabled = displayed.isNotEmpty(),
                                    onClick = {
                                        markRead(displayed.map { it.id })
                                        options = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        options = false
                                        settings()
                                    },
                                )
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = compose) {
                    Icon(Icons.Outlined.Edit, "Compose a message")
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (sync.remote && sync.folderId == mail.preferences.selectedFolder) {
                    if (sync.loading)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    val progressTargets = if (mail.preferences.selectedFolder == "unified")
                        unifiedInboxIds else listOf(mail.preferences.selectedFolder)
                    val activeProgress = progressTargets.mapNotNull { id ->
                        refreshProgress[id]?.let { progress ->
                            if (progressTargets.size > 1)
                                "${mail.folders.firstOrNull { it.id == id }?.name ?: "Inbox"} · $progress"
                            else progress
                        }
                    }
                    Text(if (sync.loading) activeProgress.joinToString("\n")
                        .ifEmpty { "Waiting to refresh…" }
                        else "Recent mail · cached locally", Modifier.padding(horizontal = T.lg),
                        style = MaterialTheme.typography.bodySmall)
                    sync.error?.let { problem ->
                        Row(Modifier.fillMaxWidth().padding(T.md),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(problem, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { vm.refreshFolder(mail.preferences.selectedFolder) }) {
                                Text("Retry")
                            }
                        }
                    }
                    if (folder != null) {
                        val current = history.takeIf { it.folderId == folder.id }
                        val progress = current?.progress
                        if (current?.running == true)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (progress != null)
                            Text(
                                if (progress.scanComplete && progress.remainingBodies == 0)
                                    "Full history downloaded · ${progress.scannedMessages} messages scanned"
                                else "Full history · ${progress.scannedMessages} scanned" +
                                    " · about ${progress.estimatedTotal} in this folder at start" +
                                    (if (progress.remainingBodies > 0)
                                        " · ${progress.remainingBodies} bodies pending"
                                    else ""),
                                Modifier.padding(horizontal = T.lg),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        current?.error?.let { problem ->
                            Text(problem, Modifier.padding(horizontal = T.lg),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (current?.running == true)
                            TextButton(onClick = vm::pauseHistory) { Text("Pause folder download") }
                        else
                            TextButton(onClick = { vm.downloadHistory(folder.id,
                                restartCompleted = progress?.scanComplete == true &&
                                    (progress?.remainingBodies ?: 0) == 0) },
                                enabled = !mail.preferences.offline) {
                                Text(if (progress == null) "Download full folder history"
                                    else if (progress.scanComplete && progress.remainingBodies == 0)
                                        "Check folder history again"
                                    else if (progress.scanComplete) "Retry body downloads"
                                    else "Resume folder download")
                            }
                    }
                }
                if (mail.preferences.offline)
                    Text(
                        "Offline preview · cached messages",
                        Modifier.padding(T.md),
                        color = MaterialTheme.colorScheme.primary,
                    )
                if (searching) {
                    OutlinedTextField(
                        query.text,
                        { vm.query.value = query.copy(text = it) },
                        Modifier.fillMaxWidth().padding(horizontal = T.lg),
                        label = { Text("Search mail") },
                        singleLine = true,
                        trailingIcon = {
                            MailIconButton("Close search", Icons.Outlined.Close) {
                                searching = false
                                vm.query.value = query.copy(text = "")
                            }
                        },
                    )
                    Row(
                        Modifier.padding(horizontal = T.lg),
                        horizontalArrangement = Arrangement.spacedBy(T.sm),
                    ) {
                        FilterChip(
                            query.scope == SearchScope.Account,
                            { vm.query.value = query.copy(scope = SearchScope.Account) },
                            label = { Text("This account") },
                            enabled = mail.preferences.selectedFolder != "unified",
                        )
                        FilterChip(
                            query.scope == SearchScope.AllAccounts,
                            { vm.query.value = query.copy(scope = SearchScope.AllAccounts) },
                            label = { Text("All accounts") },
                        )
                    }
                    Text(
                        "Searches mail saved on this device. Real accounts cache the last 30 days by default and any full folder history you download. Attachments download when opened.",
                        Modifier.padding(horizontal = T.lg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (query.filter.active || query.text.isNotEmpty())
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = T.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (pageReady) "${displayed.size} results on page ${page.pageNumber}"
                            else "Searching…",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (query.filter.active)
                            TextButton(
                                onClick = { vm.query.value = query.copy(filter = MailFilter()) }
                            ) {
                                Text("Clear filters")
                            }
                    }
                if (selecting)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = T.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked =
                                displayed.isNotEmpty() && selectedVisible.size == displayed.size,
                            onCheckedChange = {
                                selectedIds = if (it) displayed.map { m -> m.id } else emptyList()
                            },
                            modifier =
                                Modifier.semantics { contentDescription = "Select all messages" },
                        )
                        Text(
                            "${selectedVisible.size} selected",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { markRead(selectedVisible) },
                            enabled = selectedVisible.isNotEmpty(),
                        ) {
                            Text("Mark read")
                        }
                        TextButton(
                            onClick = {
                                selecting = false
                                selectedIds = emptyList()
                            }
                        ) {
                            Text("Done")
                        }
                    }
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = refreshMailbox,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (displayed.isEmpty())
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(T.xl),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Outlined.Inbox, null)
                            Text(
                                if (!pageReady) "Loading messages…"
                                else if (query.filter.active || query.text.isNotBlank())
                                    "No matching messages"
                                else if (sync.loading && sync.folderId == mail.preferences.selectedFolder)
                                    "Loading mail…"
                                else if (sync.remote && sync.folderId == mail.preferences.selectedFolder)
                                    "No messages in this folder"
                                else "You’re all caught up",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (pageReady && page.pageNumber > 1)
                                TextButton(onClick = vm::previousMessagePage) {
                                    Text("Previous page")
                                }
                        }
                    else
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = T.bodyMinHeight / 3),
                        ) {
                            items(displayed, key = { it.id }) { message ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selecting)
                                        Checkbox(
                                            checked = message.id in selectedVisible,
                                            onCheckedChange = { checked ->
                                                selectedIds =
                                                    if (checked) (selectedIds + message.id).distinct()
                                                    else selectedIds - message.id
                                            },
                                            modifier =
                                                Modifier.semantics {
                                                    contentDescription =
                                                        "Select ${message.subject.ifBlank { "(No subject)" }}"
                                                },
                                        )
                                    val location =
                                        if (
                                            query.text.isNotBlank() &&
                                                query.scope == SearchScope.AllAccounts
                                        )
                                            "${mail.accounts.find { it.id == message.accountId }?.address.orEmpty()} · ${mail.folders.find { it.id == message.folderId }?.name.orEmpty()}"
                                        else null
                                    MessageRow(
                                        message,
                                        if (mail.preferences.threads && message.relatedGroup != null)
                                            threadSizes[message.relatedGroup] ?: 0
                                        else 0,
                                        location,
                                        if (selecting) message.id in selectedVisible else null,
                                    ) {
                                        if (selecting)
                                            selectedIds =
                                                if (message.id in selectedIds) selectedIds - message.id
                                                else selectedIds + message.id
                                        else {
                                            vm.read(message.id)
                                            openMessage(message)
                                        }
                                    }
                                }
                            }
                            item {
                                if (pageReady) {
                                    Row(Modifier.fillMaxWidth().padding(T.lg),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (page.pageNumber > 1)
                                            TextButton(onClick = vm::previousMessagePage) {
                                                Text("Previous")
                                            }
                                        Text("Page ${page.pageNumber} · ${displayed.size} messages",
                                            style = MaterialTheme.typography.bodySmall)
                                        if (page.page.next != null)
                                            TextButton(onClick = vm::nextMessagePage) {
                                                Text("Next")
                                            }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
    if (showingOutbox)
        AlertDialog(
            onDismissRequest = { showingOutbox = false },
            title = { Text("Outbox") },
            text = {
                if (!vm.isCurrentOutboxPage(outboxPage)) Text("Loading Outbox…")
                else if (outboxPage.items.isEmpty()) Column {
                    Text(if (outboxPage.pageNumber == 1) "No queued messages"
                        else "No messages remain on this page")
                    if (outboxPage.pageNumber > 1)
                        TextButton(onClick = vm::previousOutboxPage,
                            modifier = Modifier.semantics {
                                contentDescription = "Previous Outbox page"
                            }) { Text("Previous") }
                }
                else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(outboxPage.items, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = T.sm)) {
                            Text(item.recipient.ifBlank { "Recipient unavailable" },
                                style = MaterialTheme.typography.titleSmall)
                            Text(when (item.status) {
                                OutboxStatus.QUEUED -> "Queued"
                                OutboxStatus.SENDING -> "Sending"
                                OutboxStatus.FAILED -> "Failed"
                                OutboxStatus.UNCERTAIN -> "Needs review: delivery may have succeeded"
                                OutboxStatus.SENT ->
                                    if (item.sentCopyStatus == SentCopyStatus.CONFIRMED)
                                        "SMTP accepted · copy found in Sent"
                                    else if (item.sentCopyUploadCanRetry)
                                        "SMTP accepted · Sent copy needs review"
                                    else if (item.sentCopyUploadNeedsReview)
                                        "SMTP accepted · saving Sent copy"
                                    else "SMTP accepted · checking Sent copy"
                            })
                            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (item.status in setOf(OutboxStatus.SENT, OutboxStatus.UNCERTAIN) &&
                                item.sentCopyStatus != SentCopyStatus.CONFIRMED)
                                TextButton(onClick = { vm.checkSentCopy(item.accountId) },
                                    enabled = !mail.preferences.offline) {
                                    Text("Check Sent")
                                }
                            if (item.status == OutboxStatus.QUEUED)
                                TextButton(onClick = { vm.flushOutgoing(item.accountId) },
                                    enabled = !mail.preferences.offline) {
                                    Text("Send queued mail")
                                }
                            if (item.status == OutboxStatus.FAILED)
                                TextButton(onClick = { vm.retryOutbox(item.id) },
                                    enabled = !mail.preferences.offline) {
                                    Text("Retry failed send")
                                }
                            if (item.status == OutboxStatus.SENT &&
                                item.sentCopyStatus == SentCopyStatus.PENDING &&
                                (!item.sentCopyUploadNeedsReview || item.sentCopyUploadCanRetry) &&
                                mail.folders.any { it.accountId == item.accountId && it.role == "sent" } &&
                                mail.accounts.any { account ->
                                    account.id == item.accountId &&
                                        !org.foxred.kage.domain.usecase.gmailAutomaticallyFilesSent(
                                            account.outgoing)
                                })
                                TextButton(onClick = {
                                    showingOutbox = false
                                    saveSentCopyRequest = item.id to item.sentCopyUploadCanRetry
                                }, enabled = !mail.preferences.offline) {
                                    Text(if (item.sentCopyUploadCanRetry)
                                        "Review and retry Sent copy" else "Save copy to Sent")
                                }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (outboxPage.pageNumber > 1)
                                TextButton(onClick = vm::previousOutboxPage,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Previous Outbox page"
                                    }) { Text("Previous") }
                            Text("Page ${outboxPage.pageNumber}",
                                style = MaterialTheme.typography.bodySmall)
                            if (outboxPage.nextCursor != null)
                                TextButton(onClick = vm::nextOutboxPage,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Next Outbox page"
                                    }) { Text("Next") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showingOutbox = false }) { Text("Close") }
            },
        )
    saveSentCopyRequest?.let { (id, retryAfterReview) ->
        AlertDialog(
            onDismissRequest = {
                saveSentCopyRequest = null
                showingOutbox = true
            },
            title = { Text(if (retryAfterReview) "Retry saving a Sent copy?"
                else "Save a Sent copy?") },
            text = { Text("Check Sent and your provider first. If it files this message later, saving another copy could create a duplicate. This action does not resend the message.") },
            confirmButton = {
                TextButton(onClick = {
                    saveSentCopyRequest = null
                    showingOutbox = true
                    vm.saveSentCopy(id, retryAfterReview)
                }) { Text(if (retryAfterReview) "Retry copy" else "Save copy") }
            },
            dismissButton = {
                TextButton(onClick = {
                    saveSentCopyRequest = null
                    showingOutbox = true
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MessageRow(
    message: Message,
    threadCount: Int,
    location: String?,
    isSelected: Boolean?,
    open: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .semantics { if (isSelected != null) selected = isSelected }
            .clickable(onClick = open)
            .padding(horizontal = T.lg, vertical = T.md),
        horizontalArrangement = Arrangement.spacedBy(T.sm),
    ) {
        Column(Modifier.width(T.lg), horizontalAlignment = Alignment.CenterHorizontally) {
            if (message.pinned)
                Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(T.marker), tint = T.flagColor)
            if (message.flagged)
                Icon(Icons.Outlined.Flag, "Flagged", Modifier.size(T.marker), tint = T.flagColor)
            if (!message.isRead || message.isNew)
                Text(if (message.isNew) "●" else "○", color = T.newColor)
        }
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    if (message.draft) "Draft" else message.sender,
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.Bold,
                )
                Spacer(Modifier.width(T.sm))
                Text(
                    message.receivedAt.take(10).removePrefix("2026-"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.subject.ifBlank { "(No subject)" },
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (threadCount > 1) Badge { Text(threadCount.toString()) }
                if (message.hasAttachments)
                    Icon(Icons.Outlined.AttachFile, "Has attachments", Modifier.size(T.lg))
            }
            if (message.preview.isNotBlank())
                Text(
                    message.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (location != null)
                Text(
                    location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
        }
    }
}
