// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.accountlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import org.foxred.kage.domain.model.*
import org.foxred.kage.ui.shared.Avatar
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

@Composable
fun AccountDrawer(
    mail: Mailbox,
    select: (String) -> Unit,
    addAccount: () -> Unit,
    settings: () -> Unit,
    outboxCount: Long,
    openOutbox: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.widthIn(max = T.drawerMax)) {
        Text(
            "Mailboxes",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(T.xl),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (mail.preferences.unified)
                NavigationDrawerItem(
                    label = { Text("All inboxes") },
                    selected = mail.preferences.selectedFolder == "unified",
                    onClick = { select("unified") },
                    icon = { Icon(Icons.Outlined.AllInbox, null) },
                    badge = {
                        val unread = mail.folders.filter { it.role == "inbox" }.sumOf { folder ->
                            folder.serverUnreadCount ?: mail.messages.count {
                                it.folderId == folder.id && !it.isRead
                            }
                        }
                        if (unread > 0) Text(unread.toString())
                    },
                )
            // The durable sending queue is shared across accounts, separate from server folders.
            NavigationDrawerItem(
                label = { Text("Outbox") },
                selected = false,
                onClick = openOutbox,
                icon = { Icon(Icons.Outlined.Outbox, null) },
                badge = { if (outboxCount > 0) Text(outboxCount.toString()) },
            )
            mail.accounts.forEach { account ->
                var expanded by
                    rememberSaveable(account.id) {
                        mutableStateOf(
                            mail.folders
                                .find { it.id == mail.preferences.selectedFolder }
                                ?.accountId == account.id
                        )
                    }
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(account.name)
                            Text(account.address, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    selected = false,
                    onClick = { expanded = !expanded },
                    icon = { Avatar(account.name) },
                    badge = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(T.sm),
                        ) {
                            val unread =
                                mail.folders.filter { it.accountId == account.id }.sumOf { folder ->
                                    folder.serverUnreadCount ?: mail.messages.count {
                                        it.folderId == folder.id && !it.isRead
                                    }
                                }
                            if (!expanded && unread > 0) Text(unread.toString())
                            Icon(
                                if (expanded) Icons.Outlined.ExpandLess
                                else Icons.Outlined.ExpandMore,
                                null,
                            )
                        }
                    },
                )
                if (expanded) {
                    val folders = mail.folders.filter { it.accountId == account.id }
                    folders
                        .filter { it.parentId == null }
                        .forEach { folder -> DrawerFolder(folder, mail, select) }
                }
                HorizontalDivider(Modifier.padding(T.md))
            }
            TextButton(onClick = addAccount, modifier = Modifier.padding(T.sm)) {
                Icon(Icons.Outlined.Add, null)
                Text("Add account")
            }
        }
        TextButton(onClick = settings, modifier = Modifier.padding(T.lg)) {
            Icon(Icons.Outlined.Settings, null)
            Spacer(Modifier.width(T.sm))
            Text("Settings")
        }
    }
}

@Composable
private fun DrawerFolder(folder: Folder, mail: Mailbox, select: (String) -> Unit, depth: Int = 0) {
    val children = mail.folders.filter { it.parentId == folder.id }
    var expanded by
        rememberSaveable(folder.id) {
            mutableStateOf(
                folder.name == "Projects" ||
                    children.any { it.id == mail.preferences.selectedFolder }
            )
        }
    fun descendants(id: String): List<Folder> = mail.folders.filter { it.parentId == id }
        .flatMap { listOf(it) + descendants(it.id) }
    val countedFolders = listOf(folder) + descendants(folder.id)
    val unread = countedFolders.sumOf { candidate ->
        candidate.serverUnreadCount ?: mail.messages.count {
            it.folderId == candidate.id && !it.isRead
        }
    }
    Row(
        Modifier.padding(start = T.sm + T.lg * depth, end = T.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavigationDrawerItem(
            modifier = Modifier.weight(1f)
                .then(if (folder.selectable) Modifier else Modifier.alpha(0.65f).semantics { disabled() }),
            label = { Text(folder.name) },
            selected = mail.preferences.selectedFolder == folder.id,
            onClick = { if (folder.selectable) select(folder.id) },
            icon = { Icon(folderIcon(folder.role), null) },
            badge = { if (unread > 0) Text(unread.toString()) },
        )
        if (children.isNotEmpty())
            MailIconButton(
                "${if (expanded) "Collapse" else "Expand"} ${folder.name}",
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            ) {
                expanded = !expanded
            }
    }
    if (expanded) children.forEach { DrawerFolder(it, mail, select, depth + 1) }
}
