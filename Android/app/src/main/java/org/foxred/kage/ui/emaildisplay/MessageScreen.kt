package org.foxred.kage.ui.emaildisplay

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(vm: MailViewModel, id: String, back: () -> Unit, compose: (String) -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val bodyLoad by vm.messageLoad.collectAsStateWithLifecycle()
    val transfers by vm.attachmentTransfers.collectAsStateWithLifecycle()
    val message = mail.messages.find { it.id == id }
    var bodyRetry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, message?.bodyDownloaded, mail.preferences.offline, bodyRetry) {
        if (message != null && !message.bodyDownloaded && !mail.preferences.offline)
            vm.loadBody(id)
        else if (message?.bodyDownloaded == true && !mail.preferences.offline)
            vm.prefetchInlineImages(id)
    }
    var details by remember { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeMatch by rememberSaveable { mutableIntStateOf(0) }
    var options by remember { mutableStateOf(false) }
    var expandedThreads by rememberSaveable { mutableStateOf(listOf<String>()) }
    val bodyRequester = remember { BringIntoViewRequester() }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var html by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    if (message == null) {
        Column(Modifier.safeDrawingPadding().padding(T.xl)) {
            Text("Message not found")
            TextButton(onClick = back) { Text("Back to inbox") }
        }
        return
    }
    val htmlText = remember(message.html) { message.html?.let(SafeMessageHtml::searchableText).orEmpty() }
    val findBody = remember(message.body, htmlText) {
        when {
            htmlText.isBlank() || htmlText == message.body -> message.body
            message.body.isBlank() -> htmlText
            else -> message.body + "\n\n" + htmlText
        }
    }
    val matches =
        remember(findBody, query) {
            if (query.isBlank()) emptyList()
            else
                Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
                    .findAll(findBody)
                    .map { it.range }
                    .toList()
        }
    val colors = MaterialTheme.colorScheme
    val highlighted =
        remember(findBody, matches, activeMatch, colors) {
            highlight(
                findBody,
                matches,
                activeMatch,
                colors.primaryContainer,
                colors.onPrimaryContainer,
                colors.primary,
                colors.onPrimary,
            )
        }
    LaunchedEffect(query) { activeMatch = 0 }
    LaunchedEffect(activeMatch, matches, textLayout) {
        val range = matches.getOrNull(activeMatch)
        val layout = textLayout
        if (range != null && layout != null && range.first < layout.layoutInput.text.length)
            bodyRequester.bringIntoView(layout.getBoundingBox(range.first))
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Message") },
                    navigationIcon = {
                        MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, back)
                    },
                    actions = {
                        MailIconButton("Find in message", Icons.Outlined.Search) {
                            finding = !finding
                            if (!finding) query = ""
                        }
                        MailIconButton("Archive message", Icons.Outlined.Archive) {
                            vm.action(success = back) { vm.repository.move(id, "archive") }
                        }
                        Box {
                            MailIconButton("Message options", Icons.Outlined.MoreHoriz) {
                                options = true
                            }
                            DropdownMenu(options, { options = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (message.isRead) "Mark unread" else "Mark read")
                                    },
                                    onClick = {
                                        vm.action { vm.repository.markRead(id, !message.isRead) }
                                        options = false
                                    },
                                )
                            }
                        }
                    },
                )
                if (finding) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Find in this message") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = T.lg),
                        trailingIcon = {
                            MailIconButton("Close find", Icons.Outlined.Close) {
                                finding = false
                                query = ""
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = T.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (matches.isEmpty()) "0 matches"
                            else "${activeMatch + 1} / ${matches.size}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                activeMatch = (activeMatch - 1 + matches.size) % matches.size
                            },
                            enabled = matches.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.KeyboardArrowUp, "Previous match")
                        }
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                activeMatch = (activeMatch + 1) % matches.size
                            },
                            enabled = matches.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.KeyboardArrowDown, "Next match")
                        }
                    }
                }
            }
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
            // Fill the reader width so content-based Start aligns RTL paragraphs to the right.
            Text(
                message.subject.ifBlank { "(No subject)" },
                modifier = Modifier.fillMaxWidth(),
                style =
                    MaterialTheme.typography.headlineSmall.copy(
                        textDirection = TextDirection.Content,
                        textAlign = TextAlign.Start,
                    ),
            )
            Text(
                message.sender,
                modifier = Modifier.fillMaxWidth(),
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        textDirection = TextDirection.Content,
                        textAlign = TextAlign.Start,
                    ),
            )
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
            if (!message.bodyDownloaded) {
                when {
                    mail.preferences.offline -> Text("Body unavailable offline. Reconnect to download it.")
                    bodyLoad.messageId == id && bodyLoad.loading ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(T.sm)) {
                            CircularProgressIndicator(Modifier.size(T.xl))
                            Text("Loading message…")
                        }
                    bodyLoad.messageId == id && bodyLoad.error != null -> {
                        Text(bodyLoad.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { bodyRetry++ }) { Text("Retry message") }
                    }
                    else -> Text("Loading message…")
                }
            } else if (message.html != null && query.isBlank()) {
                TextButton(onClick = { html = !html }) {
                    Text(if (html) "Show plain text" else "Show formatted message")
                }
                if (html) SafeHtml(message.html, message.attachments)
                else
                    Text(
                        message.body.ifBlank { htmlText },
                        modifier = Modifier.fillMaxWidth(),
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                textDirection = TextDirection.Content,
                                textAlign = TextAlign.Start,
                            ),
                    )
            } else if (findBody.isBlank())
                Text(
                    "This message has no text body.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else
                Text(
                    highlighted,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            textDirection = TextDirection.Content,
                            textAlign = TextAlign.Start,
                        ),
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bodyRequester),
                    onTextLayout = { textLayout = it },
                )
            if (message.attachments.isNotEmpty())
                Text(
                    "${message.attachments.size} attachments",
                    style = MaterialTheme.typography.titleMedium,
                )
            message.attachments.forEach { attachment ->
                val transfer = transfers[attachment.id]
                OutlinedCard(
                    onClick = {
                        vm.downloadAttachment(attachment.id) { path ->
                            val uri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.attachments",
                                    File(path),
                                )
                            val intent =
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(
                                        uri,
                                        if (
                                            attachment.localFile == null &&
                                                attachment.asset.endsWith(".png")
                                        )
                                            "image/png"
                                        else attachment.mimeType,
                                    )
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                vm.notice.value =
                                    "Attachment downloaded. No compatible viewer is installed."
                            }
                        }
                    },
                    enabled = transfer?.loading != true,
                ) {
                    ListItem(
                        headlineContent = { Text(attachment.filename) },
                        supportingContent = {
                            Column {
                                Text(when {
                                    transfer?.loading == true ->
                                        "Downloading ${transfer.bytes / 1000} / ${attachment.sizeBytes / 1000} KB"
                                    attachment.cached ->
                                        "${attachment.sizeBytes / 1000} KB · Available offline"
                                    else ->
                                        "${attachment.sizeBytes / 1000} KB · Download on demand"
                                })
                                transfer?.error?.let {
                                    Text("$it · Tap to retry", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Outlined.AttachFile, null) },
                        trailingContent = {
                            if (transfer?.loading == true)
                                TextButton(onClick = { vm.cancelAttachment(attachment.id) }) {
                                    Text("Cancel")
                                }
                        },
                    )
                }
            }
            if (mail.preferences.threads && message.relatedGroup != null)
                mail.messages
                    .filter { it.relatedGroup == message.relatedGroup && it.id != id }
                    .forEach { related ->
                        val expanded = related.id in expandedThreads
                        ElevatedCard(
                            onClick = {
                                expandedThreads =
                                    if (expanded) expandedThreads - related.id
                                    else expandedThreads + related.id
                            }
                        ) {
                            Column(Modifier.padding(T.lg)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        related.sender,
                                        Modifier.weight(1f),
                                        style =
                                            MaterialTheme.typography.titleMedium.copy(
                                                textDirection = TextDirection.Content,
                                                textAlign = TextAlign.Start,
                                            ),
                                    )
                                    Icon(
                                        if (expanded) Icons.Outlined.ExpandLess
                                        else Icons.Outlined.ExpandMore,
                                        "${if (expanded) "Collapse" else "Expand"} related message",
                                    )
                                }
                                Text(related.receivedAt, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (expanded) related.body else related.preview,
                                    modifier = Modifier.fillMaxWidth(),
                                    style =
                                        MaterialTheme.typography.bodyLarge.copy(
                                            textDirection = TextDirection.Content,
                                            textAlign = TextAlign.Start,
                                        ),
                                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
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

private fun highlight(
    body: String,
    matches: List<IntRange>,
    active: Int,
    background: Color,
    foreground: Color,
    activeBackground: Color,
    activeForeground: Color,
): AnnotatedString = buildAnnotatedString {
    append(body)
    matches.forEachIndexed { index, range ->
        addStyle(
            SpanStyle(
                background = if (index == active) activeBackground else background,
                color = if (index == active) activeForeground else foreground,
            ),
            range.first,
            range.last + 1,
        )
    }
}

@Suppress("SetJavaScriptEnabled")
@Composable
private fun SafeHtml(html: String, attachments: List<org.foxred.kage.domain.model.Attachment>) {
    val emailStyle = T.emailCss(MaterialTheme.colorScheme, LocalDensity.current.fontScale)
    val context = LocalContext.current
    val safeHtml by produceState<String?>(null, html, attachments) {
        value = withContext(Dispatchers.IO) {
            SafeMessageHtml.render(context, html, attachments)
        }
    }
    val content = safeHtml
    if (content == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(T.messageHtmlHeight),
        factory = { context ->
            MessageWebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
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
            val page = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\">" +
                "<style>$emailStyle</style></head><body dir='auto'>$content</body></html>"
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}
