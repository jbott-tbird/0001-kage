// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.foxred.kage.domain.model.*
import org.foxred.kage.domain.usecase.ComposePreparation
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.MessageLookup
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T
import org.foxred.kage.domain.repository.SendDisposition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(vm: MailViewModel, sourceId: String?, mode: String, back: () -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val bodyLoad by vm.messageLoad.collectAsStateWithLifecycle()
    val resumeGeneration by vm.resumeGeneration.collectAsStateWithLifecycle()
    val sourceLookup by remember(sourceId) { vm.lookupMessage(sourceId.orEmpty()) }
        .collectAsStateWithLifecycle(initialValue = MessageLookup())
    val source = sourceLookup.message
    var bodyRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(mode, source?.id, source?.bodyDownloaded, mail.preferences.offline, bodyRetry,
        resumeGeneration) {
        if (mode in setOf("draft", "reply", "replyAll", "forward") &&
            source != null && !source.bodyDownloaded && !mail.preferences.offline)
            vm.loadBody(source.id)
    }
    if (mode in setOf("draft", "reply", "replyAll", "forward") && source == null) {
        Text(if (ready && sourceLookup.loaded) "Message is no longer available."
            else "Loading message…")
        return
    }
    if (source != null && !source.bodyDownloaded) {
        Column {
            Text(when {
                mail.preferences.offline -> "Connect to download this message before composing."
                bodyLoad.messageId == source.id && bodyLoad.error != null -> bodyLoad.error!!
                else -> "Loading message…"
            })
            if (!mail.preferences.offline && bodyLoad.messageId == source.id &&
                bodyLoad.error != null)
                TextButton(onClick = { bodyRetry++ }) { Text("Retry download") }
        }
        return
    }
    val initialAccount =
        source?.accountId
            ?: mail.folders.find { it.id == mail.preferences.selectedFolder }?.accountId
            ?: mail.accounts.firstOrNull()?.id
    var accountId by rememberSaveable { mutableStateOf(initialAccount.orEmpty()) }
    val account = mail.accounts.find { it.id == accountId }
        ?: if (accountId.isEmpty()) mail.accounts.firstOrNull() else null
    LaunchedEffect(account?.id) {
        if (accountId.isEmpty() && account != null) accountId = account.id
    }
    if (account == null) {
        Text(if (accountId.isNotEmpty()) "The selected sending account is no longer available."
            else "Add an account before composing.")
        return
    }
    val id = rememberSaveable {
        if (mode == "draft" && source != null) source.id else UUID.randomUUID().toString()
    }
    val initial = remember(mode, source, account.address, id) {
        ComposePreparation.prepare(mode, source, account.address, id)
    }
    var to by rememberSaveable { mutableStateOf(initial.to) }
    var cc by rememberSaveable { mutableStateOf(initial.cc) }
    var bcc by rememberSaveable { mutableStateOf(initial.bcc) }
    var recipientsExpanded by rememberSaveable { mutableStateOf(false) }
    var editedHtml by rememberSaveable { mutableStateOf<String?>(null) }
    var subject by rememberSaveable { mutableStateOf(initial.subject) }
    var body by rememberSaveable { mutableStateOf(initial.body) }
    // The source draft is in Room; keeping its HTML out of saved instance state avoids
    // duplicating a potentially large MIME part in the Activity bundle.
    val initialBody = remember(id) { initial.body }
    val initialHtml = remember(id) { initial.html }
    var attachments: List<Attachment> by
        rememberSaveable(stateSaver = AttachmentListSaver) {
            mutableStateOf(initial.attachments)
        }
    var imported: List<Attachment> by
        rememberSaveable(stateSaver = AttachmentListSaver) {
            mutableStateOf(emptyList())
        }
    var importing by remember { mutableStateOf(false) }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                importing = true
                vm.action {
                    try {
                        for (uri in uris) {
                            val attachment = vm.repository.importAttachment(id, uri.toString())
                            attachments = attachments + attachment
                            imported = imported + attachment
                        }
                    } finally {
                        importing = false
                    }
                }
            }
        }
    var accountMenu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var retryDraftSync by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val close = {
        if (busy || importing) Unit
        else if (
            to.isNotBlank() ||
                cc.isNotBlank() ||
                bcc.isNotBlank() ||
                body.isNotBlank() ||
                subject.isNotBlank() ||
                attachments.isNotEmpty()
        )
            discard = true
        else if (imported.isNotEmpty())
            vm.action(success = back) { vm.repository.cleanupLooseAttachments(imported) }
        else back()
    }
    BackHandler { close() }
    fun message() =
        Message(
            id,
            account.id,
            "${account.id}-drafts",
            account.name,
            account.address,
            to.trim(),
            cc.trim(),
            bcc.trim(),
            subject.trim(),
            body,
            html = editedHtml ?: ComposePreparation.htmlForBody(initialBody, initialHtml, body),
            receivedAt = Instant.now().toString(),
            draft = true,
            attachments = attachments,
            inReplyTo = initial.inReplyTo,
            references = initial.references,
        )
    fun save(send: Boolean) {
        busy = true
        vm.action {
            try {
                if (mode in setOf("forward", "draft") && source != null) {
                    val prepared = mutableListOf<Attachment>()
                    val selectedAttachments = attachments
                    try {
                        for (selected in selectedAttachments) {
                            val original = source.attachments.firstOrNull {
                                selected.id == if (mode == "forward") "$id-${it.id}" else it.id
                            }
                            prepared += if (original == null ||
                                (mode == "forward" && selected.localFile == selected.id) ||
                                (mode == "draft" && selected.localFile != null))
                                selected
                            else {
                                if (mode == "forward") require(selected.sizeBytes <= 25L * 1024 * 1024) {
                                    "Forwarded attachment exceeds 25 MB"
                                }
                                val originalFile = File(vm.repository.cacheAttachment(original.id) { })
                                val copy = if (mode == "draft") originalFile
                                    else copyForwardAttachment(originalFile, selected.id)
                                selected.copy(localFile = copy.name, cached = true,
                                    sizeBytes = copy.length()).also { preparedCopy ->
                                    if (mode == "forward" &&
                                        imported.none { it.id == preparedCopy.id })
                                        imported = imported + preparedCopy
                                }
                            }
                        }
                        attachments = prepared
                    } catch (failure: Exception) {
                        attachments = prepared + selectedAttachments.filterNot { selected ->
                            prepared.any { it.id == selected.id }
                        }
                        throw failure
                    }
                }
                val disposition = if (send) vm.repository.send(message()) else {
                    vm.repository.saveDraft(message())
                    null
                }
                vm.notice.value = when (disposition) {
                    SendDisposition.DEMO_SAVED -> "Saved to Sent. No email was sent."
                    SendDisposition.QUEUED -> "Queued for sending. Check Outbox for status."
                    SendDisposition.SENDING -> "This message is already sending. Check Outbox for status."
                    SendDisposition.FAILED -> "Sending previously failed. Retry it from Outbox."
                    SendDisposition.UNCERTAIN -> "Delivery may have succeeded. Review Outbox before retrying."
                    SendDisposition.SENT -> "This message was already accepted by SMTP."
                    null -> "Draft saved on this device"
                }
                back()
                if (disposition == SendDisposition.QUEUED) vm.flushOutgoing(account.id)
                if (disposition == null) vm.syncDrafts(account.id)
            } finally {
                val selectedIds = attachments.mapTo(mutableSetOf()) { it.id }
                withContext(NonCancellable) {
                    try {
                        vm.repository.cleanupLooseAttachments(
                            imported.filterNot { it.id in selectedIds })
                    } catch (_: Exception) {
                        // A saved draft or queued send remains valid if optional cleanup fails.
                    }
                }
                busy = false
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
                navigationIcon = { MailIconButton("Close compose", Icons.Outlined.Close, close) },
                actions = {
                    TextButton(onClick = { save(false) }, enabled = !busy && !importing) {
                        Text("Save")
                    }
                    IconButton(
                        onClick = { save(true) },
                        enabled = !busy && !importing &&
                            (to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank()),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(T.lg),
            verticalArrangement = Arrangement.spacedBy(T.sm),
        ) {
            if (mode == "draft" && source != null) {
                when (source.draftSyncState) {
                    DraftSyncState.UNCERTAIN -> {
                        Text("Server draft sync needs review. Your edits remain on this device.")
                        source.draftSyncError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val savedFieldsVisible = to == source.to && cc == source.cc &&
                            bcc == source.bcc && subject == source.subject && body == source.body &&
                            editedHtml == null &&
                            attachments.map { it.id } == source.attachments.map { it.id }
                        TextButton(onClick = { retryDraftSync = true },
                            enabled = savedFieldsVisible && !busy && !importing &&
                                !mail.preferences.offline) {
                            Text("Check and retry draft sync")
                        }
                        if (!savedFieldsVisible)
                            Text("Save your current edits before retrying sync.",
                                style = MaterialTheme.typography.bodySmall)
                    }
                    DraftSyncState.SYNCING -> Text("Checking the server draft copy…",
                        style = MaterialTheme.typography.bodySmall)
                    DraftSyncState.SYNCED -> Text("Draft synced to the mail server.",
                        style = MaterialTheme.typography.bodySmall)
                    DraftSyncState.DEVICE_ONLY -> Text("Draft saved on this device.",
                        style = MaterialTheme.typography.bodySmall)
                    null -> Unit
                }
                if (source.draftSyncState != DraftSyncState.UNCERTAIN)
                    source.draftSyncError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
            }
            Box {
                TextButton(onClick = { accountMenu = true },
                    enabled = mode != "draft" || source == null) {
                    Text("From: ${account.address}")
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(accountMenu, { accountMenu = false }) {
                    mail.accounts.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.address) },
                            onClick = {
                                accountId = a.id
                                accountMenu = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                to,
                { to = it },
                label = { Text("To") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { recipientsExpanded = !recipientsExpanded }) {
                Text(if (recipientsExpanded) "Hide Cc / Bcc"
                    else if (cc.isNotBlank() || bcc.isNotBlank()) "Show Cc / Bcc · recipients added"
                    else "Add Cc / Bcc")
                Icon(if (recipientsExpanded) Icons.Outlined.ExpandLess
                    else Icons.Outlined.ExpandMore, null)
            }
            // Collapsing only hides the fields; draft and reply-all recipients stay intact.
            if (recipientsExpanded) {
                OutlinedTextField(
                    cc,
                    { cc = it },
                    label = { Text("Cc") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    bcc,
                    { bcc = it },
                    label = { Text("Bcc") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                subject,
                { subject = it },
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth(),
            )
            RichMessageEditor(
                initialText = body,
                initialHtml = editedHtml ?: source?.html.takeIf { mode == "draft" },
                enabled = !busy,
                onChange = { text, html ->
                    body = text
                    editedHtml = html
                },
            )
            attachments.forEach { attachment ->
                InputChip(
                    selected = false,
                    onClick = { attachments = attachments.filterNot { it.id == attachment.id } },
                    label = { Text("${attachment.filename} · ${attachment.sizeBytes} bytes") },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remove ${attachment.filename}") },
                )
            }
            TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !importing) {
                Icon(Icons.Outlined.AttachFile, null)
                Text(if (importing) "Adding attachments…" else "Attach files")
            }
            Text(
                "Drafts save on this device and sync to a real account when available. Demo messages stay here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Keep this draft?") },
            text = { Text("Save your changes to Drafts or discard this message.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        discard = false
                        save(false)
                    }
                ) {
                    Text("Save draft")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        vm.action(success = back) {
                            vm.repository.deleteDraft(id, attachments + imported)
                        }
                        discard = false
                    }
                ) {
                    Text("Discard")
                }
            },
        )
    if (retryDraftSync && source != null)
        AlertDialog(
            onDismissRequest = { retryDraftSync = false },
            title = { Text("Retry draft sync?") },
            text = { Text("The earlier server save may have succeeded. Check Drafts in another mail app first; retrying could create a second copy.") },
            confirmButton = {
                TextButton(onClick = {
                    retryDraftSync = false
                    vm.retryDraftSync(source.id)
                }, enabled = !mail.preferences.offline) { Text("Check and retry") }
            },
            dismissButton = {
                TextButton(onClick = { retryDraftSync = false }) { Text("Cancel") }
            },
        )
}
