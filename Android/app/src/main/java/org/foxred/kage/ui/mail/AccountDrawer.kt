package org.foxred.kage.ui.mail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import org.foxred.kage.domain.model.*
import org.foxred.kage.ui.components.*
import org.foxred.kage.ui.theme.DesignTokens as T

@Composable
fun AccountDrawer(
    mail: Mailbox,
    select: (String) -> Unit,
    addAccount: () -> Unit,
    settings: () -> Unit,
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
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null,
                        )
                    },
                )
                if (expanded) {
                    val folders = mail.folders.filter { it.accountId == account.id }
                    folders
                        .filter { it.parentId == null }
                        .forEach { folder ->
                            DrawerFolder(folder, mail, select)
                            folders
                                .filter { it.parentId == folder.id }
                                .forEach { DrawerFolder(it, mail, select, nested = true) }
                        }
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
private fun DrawerFolder(
    folder: Folder,
    mail: Mailbox,
    select: (String) -> Unit,
    nested: Boolean = false,
) {
    val unread = mail.messages.count { it.folderId == folder.id && !it.isRead }
    NavigationDrawerItem(
        modifier = Modifier.padding(start = if (nested) T.xl else T.sm, end = T.sm),
        label = { Text(folder.name) },
        selected = mail.preferences.selectedFolder == folder.id,
        onClick = { select(folder.id) },
        icon = { Icon(folderIcon(folder.role), null) },
        badge = { if (unread > 0) Text(unread.toString()) },
    )
}
