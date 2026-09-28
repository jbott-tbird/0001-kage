package org.foxred.kage.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.foxred.kage.domain.model.Account
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MailViewModel, back: () -> Unit, setup: () -> Unit, welcome: () -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<Account?>(null) }
    var remove by remember { mutableStateOf<Account?>(null) }
    var reset by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, back)
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(T.lg),
            verticalArrangement = Arrangement.spacedBy(T.md),
        ) {
            Text("Accounts", style = MaterialTheme.typography.titleLarge)
            mail.accounts.forEach { account ->
                OutlinedCard(onClick = { details = account }) {
                    ListItem(
                        headlineContent = { Text(account.name) },
                        supportingContent = { Text(account.address) },
                        trailingContent = {
                            MailIconButton("Remove ${account.address}", Icons.Outlined.Delete) {
                                remove = account
                            }
                        },
                    )
                }
            }
            TextButton(onClick = setup) {
                Icon(Icons.Outlined.Add, null)
                Text("Add account")
            }
            HorizontalDivider()
            Text("Storage and offline", style = MaterialTheme.typography.titleLarge)
            SettingSwitch(
                "Download attachments automatically",
                "Off: complete message bodies with attachments on demand",
                mail.preferences.automaticAttachments,
            ) { value ->
                vm.preferences { it.copy(automaticAttachments = value) }
            }
            SettingSwitch(
                "Offline preview",
                "Read cached messages and downloaded attachments",
                mail.preferences.offline,
            ) { value ->
                vm.preferences { it.copy(offline = value) }
            }
            HorizontalDivider()
            Text("Roadmap previews", style = MaterialTheme.typography.titleLarge)
            SettingSwitch(
                "Unified inbox",
                "Show All inboxes in the account drawer",
                mail.preferences.unified,
            ) { value ->
                vm.preferences {
                    it.copy(
                        unified = value,
                        selectedFolder =
                            if (!value && it.selectedFolder == "unified")
                                mail.folders.firstOrNull { f -> f.role == "inbox" }?.id.orEmpty()
                            else it.selectedFolder,
                    )
                }
            }
            SettingSwitch(
                "Related messages",
                "Show sample conversations below a message",
                mail.preferences.threads,
            ) { value ->
                vm.preferences { it.copy(threads = value) }
            }
            Text(
                "IMAP / SMTP: planned transport integration\nJMAP: planned for v2.0",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            TextButton(onClick = welcome) { Text("Getting started") }
            OutlinedButton(onClick = { reset = true }) { Text("Reset sample data") }
            Text(
                "Kage · native mail prototype\nAll actions use local sample data.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    details?.let { account ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(account.address) },
            text = {
                Text(
                    "IMAP: ${account.incoming}:${account.incomingPort}\n${account.security}\n\nSMTP: ${account.outgoing}:${account.outgoingPort}\n${account.outgoingSecurity}\nAuthentication: ${if (account.requireAuth) "Required" else "Not required"}\n\nSample account. No credentials are stored."
                )
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } },
        )
    }
    remove?.let { account ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remove account?") },
            text = { Text("Remove ${account.address} and its local sample mail?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action(success = { if (mail.accounts.size == 1) welcome() }) {
                            vm.repository.removeAccount(account.id)
                        }
                        remove = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } },
        )
    }
    if (reset)
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text("Reset sample data?") },
            text = {
                Text(
                    "Your local drafts and changes will be replaced by the original sample mailboxes."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action(success = welcome) { vm.repository.resetDemo() }
                        reset = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } },
        )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked, onCheckedChange = change) },
    )
}
