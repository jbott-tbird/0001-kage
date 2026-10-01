// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.domain.usecase

import jakarta.mail.internet.InternetAddress
import java.util.Locale
import org.foxred.kage.domain.model.Attachment
import org.foxred.kage.domain.model.Message

/** Initial fields for a draft. The compose screen may edit every field before saving. */
data class PreparedCompose(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val html: String? = null,
)

object ComposePreparation {
    fun prepare(mode: String, source: Message?, accountAddress: String, draftId: String): PreparedCompose {
        if (source == null) return PreparedCompose()
        if (mode == "draft") return PreparedCompose(
            to = source.to, cc = source.cc, bcc = source.bcc, subject = source.subject,
            body = source.body.ifBlank { source.html?.let(SearchText::fromHtml).orEmpty() },
            inReplyTo = source.inReplyTo, references = source.references,
            attachments = source.attachments, html = source.html,
        )
        val quoted = source.body.ifBlank { source.html?.let(SearchText::fromHtml).orEmpty() }
        val quote = "\n\nOn ${source.receivedAt}, ${source.sender} wrote:\n$quoted"
        if (mode == "forward") return PreparedCompose(
            subject = prefixed("Fwd:", source.subject), body = quote,
            attachments = source.attachments.map {
                it.copy(id = "$draftId-${it.id}", messageId = draftId)
            },
        )
        if (mode !in setOf("reply", "replyAll")) return PreparedCompose()
        val replyTarget = source.replyToAddress.ifBlank { source.senderAddress }
        val selfSent = source.senderAddress.equals(accountAddress, ignoreCase = true) &&
            addresses(replyTarget).none { !it.equals(accountAddress, ignoreCase = true) }
        val originalTo = if (selfSent) addresses(source.to)
            .filterNot { it.equals(accountAddress, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) } else emptyList()
        val originalCc = if (selfSent) addresses(source.cc)
            .filterNot { it.equals(accountAddress, ignoreCase = true) ||
                originalTo.any { to -> to.equals(it, ignoreCase = true) } }
            .distinctBy { it.lowercase(Locale.ROOT) } else emptyList()
        val to = if (selfSent) {
            if (mode == "replyAll" && originalTo.isNotEmpty()) originalTo.joinToString(", ")
            else originalTo.firstOrNull() ?: originalCc.firstOrNull().orEmpty()
        } else replyTarget
        val cc = if (mode == "replyAll") {
            if (selfSent) {
                (if (originalTo.isEmpty()) originalCc.drop(1) else originalCc)
                    .joinToString(", ")
            } else {
                // Reply-To may contain display names or several addresses; compare mailbox values.
                val excluded = addresses(to).mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
                    .apply { add(accountAddress.lowercase(Locale.ROOT)) }
                addresses(source.to, source.cc)
                    .filterNot { it.lowercase(Locale.ROOT) in excluded }
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .joinToString(", ")
            }
        } else ""
        return PreparedCompose(
            to = to, cc = cc, subject = prefixed("Re:", source.subject), body = quote,
            inReplyTo = source.rfcMessageId,
            references = (source.references + listOfNotNull(source.rfcMessageId)).distinct(),
        )
    }

    private fun prefixed(prefix: String, subject: String): String =
        if (subject.startsWith(prefix, ignoreCase = true)) subject else "$prefix $subject"

    /** An edited plain-text body must not keep HTML containing the old text. */
    fun htmlForBody(initialBody: String, initialHtml: String?, body: String): String? =
        initialHtml.takeIf { body == initialBody }

    private fun addresses(vararg headers: String): List<String> = headers.flatMap { header ->
        fun expanded(values: Array<InternetAddress>): List<String> = values.flatMap { address ->
            if (address.isGroup) expanded(address.getGroup(false))
            else listOf(address.address)
        }
        if (header.isBlank()) emptyList()
        else runCatching { expanded(InternetAddress.parse(header, false)) }
            .recoverCatching { expanded(InternetAddress.parse(header.replace(';', ','), false)) }
            .getOrDefault(emptyList())
            .filter(String::isNotBlank)
    }
}
