package org.foxred.kage.core.account

data class EmailAttachment(
    val partId: String,
    val filename: String,
    val mediaType: String,
    val size: Long,
    val contentId: String? = null,
    val inline: Boolean = false,
)
