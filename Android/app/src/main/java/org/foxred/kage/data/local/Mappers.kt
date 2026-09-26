package org.foxred.kage.data.local

import org.foxred.kage.domain.model.*

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
    )

fun Folder.entity() = FolderEntity(id, accountId, name, role, parentId)

fun FolderEntity.domain() = Folder(
    id, accountId, name, role, parentId,
    serverUnreadCount, serverTotalCount,
    remotePath == null || CoreRoomMapper.mailbox(this).selectable,
)

fun Attachment.entity() =
    AttachmentEntity(id, messageId, filename, mimeType, sizeBytes, cached, asset, localFile)

fun AttachmentEntity.domain() =
    Attachment(id, messageId, filename, mimeType, sizeBytes, cached, asset, localFile)

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
        receivedAt,
        isRead,
        isNew,
        flagged,
        pinned,
        draft,
        relatedGroup,
        preview = preview,
        bodyDownloaded = bodyDownloaded,
    )

fun MessageEntity.domain(attachments: List<Attachment>) =
    Message(
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
