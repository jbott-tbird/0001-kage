// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import java.text.DateFormat
import java.util.Date
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import org.foxred.kage.data.background.BackgroundMailSettings
import org.foxred.kage.data.background.BackgroundCheckResult
import org.foxred.kage.data.background.backgroundMailEligible
import org.foxred.kage.domain.model.Account
import org.foxred.kage.domain.usecase.supportSummary
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MailViewModel,
    back: () -> Unit,
    setup: () -> Unit,
    welcome: () -> Unit,
    updatePassword: (String) -> Unit,
    updateGoogle: (String) -> Unit,
) {
    val context = LocalContext.current
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val hasBackgroundAccount = mail.accounts.any(::backgroundMailEligible)
    var details by remember { mutableStateOf<Account?>(null) }
    var remove by remember { mutableStateOf<Account?>(null) }
    var reset by remember { mutableStateOf(false) }
    var backgroundEnabled by remember { mutableStateOf(BackgroundMailSettings.enabled(context)) }
    var lastBackgroundCheck by remember {
        mutableStateOf(BackgroundMailSettings.lastCheck(context))
    }
    var notificationsEnabled by remember {
        mutableStateOf(BackgroundMailSettings.notificationsEnabled(context) &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundEnabled = BackgroundMailSettings.enabled(context)
                notificationsEnabled = BackgroundMailSettings.notificationsEnabled(context) &&
                    (Build.VERSION.SDK_INT < 33 ||
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED)
                lastBackgroundCheck = BackgroundMailSettings.lastCheck(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { BackgroundMailSettings.setNotificationsEnabled(context, true) }
                .onSuccess { notificationsEnabled = true }
                .onFailure { vm.error.value = it.message ?: "Could not save notification setting" }
        } else vm.notice.value = "Notifications are off. Background refresh stays on."
    }
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
                        supportingContent = {
                            Text("${account.address} · ${if (account.mode == "REAL") "Real mailbox" else "Demo mailbox"}")
                        },
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
                Text("Connect a real account")
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
            Text("Background", style = MaterialTheme.typography.titleLarge)
            SettingSwitch(
                "Refresh in background",
                "Check app-password inboxes roughly hourly when Android allows. Google sign-in stays foreground-only. Paused in Offline preview.",
                backgroundEnabled,
                enabled = hasBackgroundAccount || backgroundEnabled,
            ) { value ->
                runCatching {
                    BackgroundMailSettings.setEnabled(context, value,
                        hasBackgroundAccount, mail.preferences.offline)
                }.onSuccess {
                    backgroundEnabled = value
                    lastBackgroundCheck = BackgroundMailSettings.lastCheck(context)
                    if (!value) notificationsEnabled = false
                }.onFailure {
                    vm.error.value = it.message ?: "Could not change background refresh"
                }
            }
            SettingSwitch(
                "New mail notifications",
                "Generic alert without sender, subject or message text",
                notificationsEnabled,
                enabled = backgroundEnabled,
            ) { value ->
                if (value && Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else runCatching {
                    BackgroundMailSettings.setNotificationsEnabled(context, value)
                }.onSuccess { notificationsEnabled = value }
                    .onFailure { vm.error.value = it.message ?: "Could not change notifications" }
            }
            if (backgroundEnabled) {
                val check = lastBackgroundCheck
                Text(
                    if (!hasBackgroundAccount) "Paused until an app-password account is available."
                    else if (check == null) "No background check recorded yet."
                    else {
                        val outcome = when (check.result) {
                            BackgroundCheckResult.SUCCESS ->
                                "Last background check succeeded"
                            BackgroundCheckResult.RETRYING ->
                                "Last background check lost its connection; retry pending"
                            BackgroundCheckResult.NEEDS_ATTENTION ->
                                "Last background check needs attention; open the inbox to inspect it"
                        }
                        "$outcome · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date.from(check.completedAt))}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
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
                "Real accounts use IMAP and SMTP. JMAP is planned for a later release.",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            Text("Support", style = MaterialTheme.typography.titleLarge)
            Text("Copy a summary of counts and app versions. It contains no addresses, message content, or credentials. Review it before sharing.")
            OutlinedButton(onClick = {
                val versionCode = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                }.getOrDefault(0L)
                vm.action {
                    val summary = supportSummary(mail, vm.repository.cacheCounts(),
                        vm.repository.outboxCounts.first(), versionCode, Build.VERSION.SDK_INT)
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Kage support summary", summary))
                    vm.notice.value = "Support summary copied. Review it before sharing."
                }
            }) { Text("Copy support summary") }
            HorizontalDivider()
            TextButton(onClick = welcome) { Text("Getting started") }
            OutlinedButton(onClick = { reset = true }) { Text("Reset sample data") }
            Text(
                "Kage · Android mail",
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
                    "IMAP: ${account.incoming}:${account.incomingPort}\n${account.security}\n\nSMTP: ${account.outgoing}:${account.outgoingPort}\n${account.outgoingSecurity}\nAuthentication: ${if (account.requireAuth) "Required" else "Not required"}\n\n" +
                        if (account.mode == "REAL")
                            if (account.usesOAuth)
                                "Google authorization is stored encrypted on this device."
                            else "App password is stored encrypted on this device."
                        else "Sample account. No credentials are stored."
                )
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } },
            dismissButton = {
                if (account.mode == "REAL") {
                    Row {
                        if (!account.usesOAuth)
                            TextButton(onClick = {
                                details = null
                                updatePassword(account.id)
                            }) { Text("Update app password") }
                        if (account.incoming == "imap.gmail.com" &&
                            account.outgoing == "smtp.gmail.com")
                            TextButton(onClick = {
                                details = null
                                updateGoogle(account.id)
                            }) { Text(if (account.usesOAuth) "Reauthorize Google" else "Use Google sign-in") }
                    }
                }
            },
        )
    }
    remove?.let { account ->
        var revokeGoogle by remember(account.id) { mutableStateOf(account.usesOAuth) }
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remove account?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(T.sm)) {
                    Text(if (account.mode == "REAL")
                        "Remove ${account.address}, its saved credentials, cached mail, and unsent local drafts? Messages already on the server remain there."
                    else "Remove ${account.address} and its local sample mail?")
                    if (account.usesOAuth) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(revokeGoogle, onCheckedChange = { revokeGoogle = it })
                            Text("Revoke Kage's Google access for this account first (requires internet). If revocation fails, the account stays on this device.")
                        }
                        if (mail.preferences.offline && revokeGoogle)
                            Text("Turn off Offline preview to revoke, or clear this option to remove local data.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action(success = {
                            if (mail.accounts.none { it.mode == "REAL" && it.id != account.id }) welcome()
                        }) {
                            if (account.usesOAuth && revokeGoogle)
                                vm.repository.revokeAndRemoveGoogleAccount(account.id)
                            else vm.repository.removeAccount(account.id)
                        }
                        remove = null
                    },
                    enabled = !account.usesOAuth || !revokeGoogle || !mail.preferences.offline,
                ) {
                    Text(if (account.usesOAuth && revokeGoogle) "Revoke and remove" else "Remove")
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
                    "Sample accounts, their drafts, and local changes will be replaced. Real accounts and their mail remain."
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
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked, onCheckedChange = change, enabled = enabled) },
    )
}
