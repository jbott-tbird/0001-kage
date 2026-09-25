package org.foxred.kage.ui.compose

import androidx.activity.compose.BackHandler
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
import java.time.Instant
import java.util.UUID
import org.foxred.kage.domain.model.*
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.components.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(vm: MailViewModel, sourceId: String?, mode: String, back: () -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val source = mail.messages.find { it.id == sourceId }
    val initialAccount =
        source?.accountId
            ?: mail.folders.find { it.id == mail.preferences.selectedFolder }?.accountId
            ?: mail.accounts.firstOrNull()?.id
    var accountId by rememberSaveable { mutableStateOf(initialAccount.orEmpty()) }
    val account = mail.accounts.find { it.id == accountId } ?: mail.accounts.firstOrNull()
    if (account == null) {
        Text("Add an account before composing.")
        return
    }
    val id = rememberSaveable {
        if (mode == "draft" && source != null) source.id else UUID.randomUUID().toString()
    }
    var to by rememberSaveable {
        mutableStateOf(
            when (mode) {
                "draft" -> source?.to.orEmpty()
                "reply",
                "replyAll" -> source?.senderAddress.orEmpty()
                else -> ""
            }
        )
    }
    var cc by rememberSaveable {
        mutableStateOf(
            when (mode) {
                "draft" -> source?.cc.orEmpty()
                "replyAll" ->
                    listOf(source?.to.orEmpty(), source?.cc.orEmpty())
                        .flatMap { it.split(',', ';') }
                        .map { it.trim() }
                        .filter {
                            it.isNotEmpty() &&
                                !it.equals(account.address, true) &&
                                !it.equals(source?.senderAddress, true)
                        }
                        .distinct()
                        .joinToString(", ")
                else -> ""
            }
        )
    }
    var bcc by rememberSaveable {
        mutableStateOf(if (mode == "draft") source?.bcc.orEmpty() else "")
    }
    var subject by rememberSaveable {
        mutableStateOf(
            when (mode) {
                "draft" -> source?.subject.orEmpty()
                "reply",
                "replyAll" -> "Re: ${source?.subject.orEmpty().removePrefix("Re: ")}"
                "forward" -> "Fwd: ${source?.subject.orEmpty()}"
                else -> ""
            }
        )
    }
    var body by rememberSaveable {
        mutableStateOf(
            when (mode) {
                "draft" -> source?.body.orEmpty()
                "reply",
                "replyAll",
                "forward" ->
                    "\n\nOn ${source?.receivedAt}, ${source?.sender} wrote:\n${source?.body.orEmpty()}"
                else -> ""
            }
        )
    }
    var attachments: List<Attachment> by remember {
        mutableStateOf<List<Attachment>>(
            if (mode in listOf("draft", "forward"))
                source?.attachments.orEmpty().map {
                    it.copy(id = if (mode == "draft") it.id else "$id-${it.id}", messageId = id)
                }
            else emptyList()
        )
    }
    var accountMenu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val close = {
        if (to.isNotBlank() || body.isNotBlank() || subject.isNotBlank()) discard = true else back()
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
            receivedAt = Instant.now().toString(),
            draft = true,
            attachments = attachments,
        )
    fun save(send: Boolean) {
        busy = true
        vm.action {
            try {
                if (send) vm.repository.sendDemo(message()) else vm.repository.saveDraft(message())
                vm.notice.value = if (send) "Saved to Sent. No email was sent." else "Draft saved"
                back()
            } finally {
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
                    TextButton(onClick = { save(false) }, enabled = !busy) { Text("Save") }
                    IconButton(onClick = { save(true) }, enabled = !busy && to.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Send demo message")
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
            Box {
                TextButton(onClick = { accountMenu = true }) {
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
            OutlinedTextField(
                subject,
                { subject = it },
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                body,
                { body = it },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth().heightIn(min = T.bodyMinHeight),
            )
            attachments.forEach { attachment ->
                InputChip(
                    selected = false,
                    onClick = { attachments = attachments.filterNot { it.id == attachment.id } },
                    label = { Text(attachment.filename) },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remove attachment") },
                )
            }
            TextButton(
                onClick = {
                    attachments =
                        attachments +
                            Attachment(
                                UUID.randomUUID().toString(),
                                id,
                                "ticket.pdf",
                                "application/pdf",
                                2400,
                            )
                }
            ) {
                Icon(Icons.Outlined.AttachFile, null)
                Text("Attach sample PDF")
            }
            Text(
                "Demo only. Messages stay on this device.",
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
                        vm.action(success = back) { vm.repository.deleteDraft(id) }
                        discard = false
                    }
                ) {
                    Text("Discard")
                }
            },
        )
}
