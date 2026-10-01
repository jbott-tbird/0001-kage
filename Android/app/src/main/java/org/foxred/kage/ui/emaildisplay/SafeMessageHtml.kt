// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.emaildisplay

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.Locale
import org.foxred.kage.domain.model.Attachment
import org.foxred.kage.domain.usecase.SearchText
import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/** Cleans untrusted email markup and embeds only previously cached, bounded CID image parts. */
object SafeMessageHtml {
    private const val MAX_INLINE_BYTES = 2L * 1024 * 1024
    private const val MAX_TOTAL_INLINE_BYTES = 8L * 1024 * 1024
    private val imageTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    fun searchableText(html: String): String = SearchText.fromHtml(html)

    fun render(context: Context, html: String, attachments: List<Attachment>): String {
        val document = Jsoup.parseBodyFragment(html)
        val directory = File(context.filesDir, "attachments").canonicalFile
        val byCid = attachments.filter { it.inline && it.cached && it.contentId != null &&
            it.mimeType.lowercase(Locale.ROOT) in imageTypes }
            .associateBy { normalizeCid(it.contentId.orEmpty()) }
        var total = 0L
        document.select("img[src]").forEach { image ->
            val source = image.attr("src")
            if (!source.startsWith("cid:", ignoreCase = true)) {
                image.remove()
                return@forEach
            }
            val part = byCid[normalizeCid(Uri.decode(source.substring(4)))]
            val localName = part?.localFile
            val file = localName?.let { File(directory, it).canonicalFile }
            if (file == null || file.parentFile != directory || !file.isFile ||
                file.length() > MAX_INLINE_BYTES || total + file.length() > MAX_TOTAL_INLINE_BYTES) {
                image.remove()
                return@forEach
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.size > MAX_INLINE_BYTES ||
                total + bytes.size > MAX_TOTAL_INLINE_BYTES) {
                image.remove()
                return@forEach
            }
            total += bytes.size
            image.attr("src", "data:${part.mimeType.lowercase(Locale.ROOT)};base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        // Every other resource URL is removed; HTML attributes and tags are allowlisted below.
        val safelist = Safelist.relaxed()
            .removeProtocols("img", "src", "http", "https")
            .addProtocols("img", "src", "data")
            .addAttributes(":all", "dir")
        val cleaned = Cleaner(safelist).clean(document)
        // Newsletter tables often declare desktop widths; let the reader's viewport size them.
        cleaned.select("table, colgroup, col, thead, tbody, tfoot, tr, th, td").forEach {
            it.removeAttr("width")
        }
        cleaned.outputSettings().prettyPrint(false)
        return cleaned.body().html()
    }

    private fun normalizeCid(value: String): String =
        value.trim().removeSurrounding("<", ">").lowercase(Locale.ROOT)
}
