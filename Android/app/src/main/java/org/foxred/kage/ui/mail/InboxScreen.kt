package org.foxred.kage.ui.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.foxred.kage.domain.model.*
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.components.*
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
    val messages by vm.messages.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    val folder = mail.folders.find { it.id == mail.preferences.selectedFolder }
    val account = mail.accounts.find { it.id == folder?.accountId }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            AccountDrawer(
                mail,
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
                                    text = { Text("Mark all read") },
                                    onClick = {
                                        vm.action {
                                            messages.forEach { vm.repository.markRead(it.id, true) }
                                        }
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
                        )
                        FilterChip(
                            query.scope == SearchScope.AllAccounts,
                            { vm.query.value = query.copy(scope = SearchScope.AllAccounts) },
                            label = { Text("All accounts") },
                        )
                    }
                }
                if (query.filter.active || query.text.isNotEmpty())
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = T.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${messages.size} results",
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
                if (messages.isEmpty())
                    Column(
                        Modifier.fillMaxSize().padding(T.xl),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Inbox, null)
                        Text(
                            if (query.filter.active || query.text.isNotBlank())
                                "No matching messages"
                            else "You’re all caught up",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                else
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = T.bodyMinHeight / 3),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageRow(message) {
                                vm.read(message.id)
                                openMessage(message)
                            }
                        }
                        item {
                            Text(
                                "${messages.size} messages · ● new since last visit",
                                Modifier.padding(T.xl),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun MessageRow(message: Message, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
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
                if (message.attachments.isNotEmpty())
                    Icon(Icons.Outlined.AttachFile, "Has attachments", Modifier.size(T.lg))
            }
            if (message.body.isNotBlank())
                Text(
                    message.body.replace('\n', ' '),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
