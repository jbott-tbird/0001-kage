// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.compose

import android.graphics.Typeface
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.foxred.kage.ui.theme.DesignTokens as T

/** Native styled editing keeps selection, spell-check, and keyboard behavior in Android. */
@Composable
internal fun RichMessageEditor(
    initialText: String,
    initialHtml: String?,
    enabled: Boolean,
    onChange: (String, String) -> Unit,
) {
    var editor by remember { mutableStateOf<EditText?>(null) }
    var hasSelection by remember { mutableStateOf(false) }
    var formatting by rememberSaveable { mutableStateOf(false) }
    var linkSelection by remember { mutableStateOf<IntRange?>(null) }
    var link by rememberSaveable { mutableStateOf("") }
    val changed by rememberUpdatedState(onChange)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    fun publish() {
        editor?.text?.let { changed(it.toString(), Html.toHtml(it, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)) }
    }
    fun apply(span: Any) {
        val view = editor ?: return
        val start = minOf(view.selectionStart, view.selectionEnd)
        val end = maxOf(view.selectionStart, view.selectionEnd)
        if (start < 0 || start == end) return
        view.text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        publish()
    }
    Column {
        TextButton(onClick = { formatting = !formatting }) {
            Icon(Icons.Outlined.TextFormat, null)
            Text(if (formatting) "Hide formatting" else "Formatting")
        }
        if (formatting) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                IconButton(onClick = { apply(StyleSpan(Typeface.BOLD)) }, enabled = enabled && hasSelection) {
                    Icon(Icons.Outlined.FormatBold, "Bold selected text")
                }
                IconButton(onClick = { apply(StyleSpan(Typeface.ITALIC)) }, enabled = enabled && hasSelection) {
                    Icon(Icons.Outlined.FormatItalic, "Italicize selected text")
                }
                IconButton(onClick = { apply(UnderlineSpan()) }, enabled = enabled && hasSelection) {
                    Icon(Icons.Outlined.FormatUnderlined, "Underline selected text")
                }
                IconButton(onClick = {
                    editor?.let {
                        linkSelection = minOf(it.selectionStart, it.selectionEnd)..maxOf(it.selectionStart, it.selectionEnd)
                        link = "https://"
                    }
                }, enabled = enabled && hasSelection) {
                    Icon(Icons.Outlined.Link, "Link selected text")
                }
                IconButton(onClick = {
                    editor?.let {
                        clearMessageFormatting(it.text, minOf(it.selectionStart, it.selectionEnd),
                            maxOf(it.selectionStart, it.selectionEnd))
                        publish()
                    }
                }, enabled = enabled && hasSelection) {
                    Icon(Icons.Outlined.FormatClear, "Clear selected text formatting")
                }
            }
            Text("Select text to format it.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().heightIn(min = T.bodyMinHeight).padding(T.sm),
                factory = { context ->
                    object : EditText(context) {
                        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                            super.onSelectionChanged(selStart, selEnd)
                            hasSelection = selStart >= 0 && selEnd >= 0 && selStart != selEnd
                        }
                    }.apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        gravity = Gravity.TOP or Gravity.START
                        background = null
                        hint = "Message"
                        contentDescription = "Message"
                        textSize = 16f
                        // Import HTML only at creation; recomposition must not reset the cursor.
                        setText(messageEditorText(initialText, initialHtml))
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) { publish() }
                        })
                        editor = this
                    }
                },
                update = {
                    it.isEnabled = enabled
                    it.setTextColor(textColor)
                    it.setHintTextColor(hintColor)
                },
            )
        }
    }
    if (linkSelection != null) {
        val validLink = runCatching {
            val uri = java.net.URI(link.trim())
            uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
        AlertDialog(
            onDismissRequest = { linkSelection = null },
            title = { Text("Add link") },
            text = {
                OutlinedTextField(link, { link = it }, label = { Text("Web address") },
                    singleLine = true, supportingText = { Text("Use an https:// or http:// address.") })
            },
            confirmButton = {
                TextButton(enabled = validLink && enabled, onClick = {
                    val range = linkSelection
                    editor?.let { view ->
                        if (range != null && range.first >= 0 && range.last <= view.length()) {
                            view.setSelection(range.first, range.last)
                            clearMessageFormatting(view.text, range.first, range.last, linksOnly = true)
                            apply(URLSpan(link.trim()))
                        }
                    }
                    linkSelection = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { linkSelection = null }) { Text("Cancel") } },
        )
    }
}

/** Split intersecting spans so clearing a selection preserves neighboring formatting. */
internal fun clearMessageFormatting(text: Editable, start: Int, end: Int, linksOnly: Boolean = false) {
    if (start < 0 || end <= start) return
    for (span in text.getSpans(start, end, Any::class.java)) {
        fun copy(): Any? = when (span) {
            is URLSpan -> URLSpan(span.url)
            is StyleSpan -> if (linksOnly) null else StyleSpan(span.style)
            is UnderlineSpan -> if (linksOnly) null else UnderlineSpan()
            else -> null
        }
        val before = copy() ?: continue
        val spanStart = text.getSpanStart(span)
        val spanEnd = text.getSpanEnd(span)
        text.removeSpan(span)
        if (spanStart < start) text.setSpan(before, spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (spanEnd > end) text.setSpan(copy()!!, end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Html adds a final paragraph newline; keep the plain-text part's intentional line endings. */
internal fun messageEditorText(plain: String, html: String?): CharSequence {
    if (html == null) return plain
    val styled = SpannableStringBuilder(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT))
    if (styled.toString().trimEnd('\n') == plain.trimEnd('\n')) {
        while (styled.endsWith("\n")) styled.delete(styled.length - 1, styled.length)
        styled.append(plain.takeLastWhile { it == '\n' })
    }
    return styled
}
