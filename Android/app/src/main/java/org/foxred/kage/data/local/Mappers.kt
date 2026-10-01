// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import org.foxred.kage.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

fun Account.entity() =
    AccountEntity(
        id,
        name,
        address,
        incoming,
        outgoing,
        incomingPort,
        outgoingPort,
        security,
        outgoingSecurity,
        requireAuth,
        mode = mode,
    )

fun AccountEntity.domain() =
    Account(
        id,
        name,
        address,
        incoming,
        outgoing,
        incomingPort,
        outgoingPort,
        security,
        outgoingSecurity,
        requireAuth,
        mode,
        mode == "REAL" && oauthConfigurationJson != null,
    )

fun Folder.entity() = FolderEntity(id, accountId, name, role, parentId)

fun FolderEntity.domain() = Folder(
    id, accountId, name, role, parentId,
    serverUnreadCount, serverTotalCount,
    remotePath == null || CoreRoomMapper.mailbox(this).selectable,
)

fun Attachment.entity() =
    AttachmentEntity(id, messageId, filename, mimeType, sizeBytes, cached, asset, localFile,
        contentId = contentId, inline = inline)

fun AttachmentEntity.domain() =
    Attachment(id, messageId, filename, mimeType, sizeBytes, cached, asset, localFile,
        contentId, inline)

fun Message.entity() =
    MessageEntity(
        id,
        accountId,
        folderId,
        sender,
        senderAddress,
        to,
        cc,
        bcc,
        subject,
        body,
        html,
        mailTimestampKey(receivedAt),
        isRead,
        isNew,
        flagged,
        pinned,
        draft,
        relatedGroup,
        preview = preview,
        envelopeJson = JSONObject()
            .put("messageId", rfcMessageId)
            .put("replyToAddress", replyToAddress)
            .put("inReplyTo", inReplyTo)
            .put("references", JSONArray(references))
            .toString(),
        bodyDownloaded = bodyDownloaded,
    )

fun MessageEntity.domain(attachments: List<Attachment>): Message {
    val envelope = JSONObject(envelopeJson)
    fun firstId(key: String): String? = when (val value = envelope.opt(key)) {
        is JSONArray -> value.optString(0).takeIf { it.isNotBlank() }
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }
    val references = envelope.optJSONArray("references")?.let { values ->
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()
    val replyTo = envelope.optString("replyToAddress").takeIf { it.isNotBlank() }
        ?: envelope.optJSONArray("replyTo")?.optJSONObject(0)?.optString("address").orEmpty()
    val draftSync = if (!draft) null else when (envelope.optString("draftUploadPhase")) {
        "UNCERTAIN" -> DraftSyncState.UNCERTAIN
        "IN_FLIGHT", "APPENDED" -> DraftSyncState.SYNCING
        else -> if (envelope.optBoolean("localDraftDirty") || uid == null)
            DraftSyncState.DEVICE_ONLY else DraftSyncState.SYNCED
    }
    return Message(
        id,
        accountId,
        folderId,
        sender,
        senderAddress,
        to,
        cc,
        bcc,
        subject,
        body,
        html,
        receivedAt,
        isRead,
        isNew,
        flagged,
        pinned,
        draft,
        relatedGroup,
        attachments,
        preview.ifBlank { body.replace('\n', ' ') },
        bodyDownloaded,
        firstId("messageId"),
        replyTo,
        firstId("inReplyTo"),
        references,
        draftSync,
        envelope.optString("draftUploadError").takeIf { it.isNotBlank() },
    )
}

/** A list row deliberately has no body; the reader observes the full message by ID. */
fun MessageListRow.summary(): Message = Message(
    id = id,
    accountId = accountId,
    folderId = folderId,
    sender = sender,
    senderAddress = "",
    to = "",
    subject = subject,
    body = "",
    receivedAt = receivedAt,
    isRead = isRead,
    isNew = isNew,
    flagged = flagged,
    pinned = pinned,
    draft = draft,
    relatedGroup = relatedGroup,
    preview = preview,
    bodyDownloaded = bodyDownloaded,
    attachmentCount = attachmentCount,
)

fun Preferences.entity() =
    PreferencesEntity(
        selectedFolder = selectedFolder,
        unified = unified,
        threads = threads,
        automaticAttachments = automaticAttachments,
        offline = offline,
        started = started,
    )

fun PreferencesEntity.domain() =
    Preferences(selectedFolder, unified, threads, automaticAttachments, offline, started)
