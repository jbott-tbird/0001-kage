package org.foxred.kage.ui.message

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.components.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(vm: MailViewModel, id: String, back: () -> Unit, compose: (String) -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val message = mail.messages.find { it.id == id }
    var details by remember { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var html by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    if (message == null) {
        Column(Modifier.safeDrawingPadding().padding(T.xl)) {
            Text("Message not found")
            TextButton(onClick = back) { Text("Back to inbox") }
        }
        return
    }
    val highlighted = remember(message.body, query) { highlight(message.body, query) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message") },
                navigationIcon = {
                    MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, back)
                },
                actions = {
                    MailIconButton("Find in message", Icons.Outlined.Search) { finding = !finding }
                    MailIconButton("Archive message", Icons.Outlined.Archive) {
                        vm.action(success = back) { vm.repository.move(id, "archive") }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                MailIconButton("Reply", Icons.AutoMirrored.Outlined.Reply) { compose("reply") }
                MailIconButton("Reply all", Icons.AutoMirrored.Outlined.ReplyAll) {
                    compose("replyAll")
                }
                Spacer(Modifier.weight(1f))
                MailIconButton("Delete message", Icons.Outlined.Delete) {
                    vm.action(success = back) { vm.repository.move(id, "trash") }
                }
                Spacer(Modifier.weight(1f))
                MailIconButton("Forward", Icons.AutoMirrored.Outlined.Forward) {
                    compose("forward")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(T.lg),
            verticalArrangement = Arrangement.spacedBy(T.lg),
        ) {
            Text(
                message.subject.ifBlank { "(No subject)" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(message.sender, style = MaterialTheme.typography.titleLarge)
            Text(
                message.receivedAt.replace('T', ' ').removeSuffix("Z"),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { details = true }) { Text("To: ${message.to}", maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(T.sm)) {
                FilterChip(
                    message.flagged,
                    { vm.flag(message) },
                    label = { Text("Flagged") },
                    leadingIcon = { Icon(Icons.Outlined.Flag, null) },
                )
                FilterChip(
                    message.pinned,
                    { vm.pin(message) },
                    label = { Text("Pinned") },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                )
            }
            if (finding) {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Find in this message") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        MailIconButton("Close find", Icons.Outlined.Close) {
                            finding = false
                            query = ""
                        }
                    },
                )
                if (query.isNotBlank())
                    Text(
                        "${Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(message.body).count()} matches",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
            if (message.html != null && query.isBlank()) {
                TextButton(onClick = { html = !html }) {
                    Text(if (html) "Show plain text" else "Show formatted message")
                }
                if (html) SafeHtml(message.html) else Text(message.body)
            } else if (message.body.isBlank())
                Text(
                    "This message has no text body.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else Text(highlighted, style = MaterialTheme.typography.bodyLarge)
            if (message.attachments.isNotEmpty())
                Text(
                    "${message.attachments.size} attachments",
                    style = MaterialTheme.typography.titleMedium,
                )
            message.attachments.forEach { attachment ->
                OutlinedCard(
                    onClick = {
                        vm.action {
                            val path = vm.repository.cacheAttachment(attachment.id)
                            val uri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.attachments",
                                    File(path),
                                )
                            val intent =
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, attachment.mimeType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                vm.notice.value =
                                    "Sample attachment downloaded. No PDF viewer is installed."
                            }
                        }
                    }
                ) {
                    ListItem(
                        headlineContent = { Text(attachment.filename) },
                        supportingContent = {
                            Text(
                                "${attachment.sizeBytes / 1000} KB · ${if (attachment.cached) "Available offline" else "Download on demand"}"
                            )
                        },
                        leadingContent = { Icon(Icons.Outlined.AttachFile, null) },
                    )
                }
            }
            if (mail.preferences.threads && message.relatedGroup != null)
                mail.messages
                    .filter { it.relatedGroup == message.relatedGroup && it.id != id }
                    .forEach { related ->
                        ElevatedCard {
                            Column(Modifier.padding(T.lg)) {
                                Text(related.sender, style = MaterialTheme.typography.titleMedium)
                                Text(related.body)
                            }
                        }
                    }
        }
    }
    if (details)
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("Message details") },
            text = {
                Text(
                    "From: ${message.sender} <${message.senderAddress}>\n\nTo: ${message.to}\n\nCc: ${message.cc.ifBlank { "None" }}\n\n${message.receivedAt}"
                )
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } },
        )
}

private fun highlight(body: String, query: String): AnnotatedString = buildAnnotatedString {
    append(body)
    if (query.isNotBlank())
        Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(body).forEach {
            addStyle(
                SpanStyle(
                    background = T.light.primaryContainer,
                    color = T.light.onPrimaryContainer,
                ),
                it.range.first,
                it.range.last + 1,
            )
        }
}

@Suppress("SetJavaScriptEnabled")
@Composable
private fun SafeHtml(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(T.messageHtmlHeight),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ) = true
                    }
            }
        },
        update = { view ->
            view.loadDataWithBaseURL(
                null,
                "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\"><style>body{font:16px/1.6 sans-serif;overflow-wrap:anywhere}img{max-width:100%}</style></head><body>$html</body></html>",
                "text/html",
                "UTF-8",
                null,
            )
        },
        onRelease = { it.destroy() },
    )
}
