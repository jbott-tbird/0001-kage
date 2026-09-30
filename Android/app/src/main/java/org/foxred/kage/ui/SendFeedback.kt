package org.foxred.kage.ui

import org.foxred.kage.domain.model.OutboxCounts

/** Observe durable outcomes, including sends that complete between UI frames. */
internal class SendFeedback {
    private var previous: OutboxCounts? = null

    fun update(current: OutboxCounts): String? {
        val before = previous
        previous = current
        // Existing history on launch must not be announced as newly sent mail.
        if (before == null) return null
        val messages = buildList {
            val sent = current.sent - before.sent
            if (sent > 0) add(if (sent == 1L) "Email sent." else "$sent emails sent.")
            if (current.failed > before.failed)
                add("Email couldn’t be sent. Check Outbox for details.")
            if (current.uncertain > before.uncertain)
                add("Delivery couldn’t be confirmed. Check Outbox before retrying.")
        }
        return messages.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
