package org.foxred.kage.core.account

/** Attachments live on Email so metadata remains available before fetching body content. */
data class EmailBody(val text: String?, val html: String?) {
    val preview: String?
        get() = text?.replace(Regex("\\s+"), " ")?.trim()?.take(200)
}
