// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import org.foxred.kage.core.account.*
import org.json.JSONArray
import org.json.JSONObject

/** Explicit wire/domain ↔ persistence mapping. Repository policy owns merging and pending intent. */
object CoreRoomMapper {
    private fun strings(values: Collection<String>) = JSONArray(values.toList())
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun addresses(values: List<EmailAddress>) = JSONArray().apply {
        values.forEach { put(JSONObject().put("address", it.address).put("name", it.name)) }
    }
    private fun JSONObject.addresses(key: String): List<EmailAddress> =
        optJSONArray(key)?.let { array -> (0 until array.length()).map {
            array.getJSONObject(it).let { EmailAddress(it.getString("address"), it.optString("name")) }
        } } ?: emptyList()
    private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.instant(key: String) = optional(key)?.let(Instant::parse)
    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun id(vararg parts: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(JSONArray(parts.toList()).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun folderId(accountId: String, path: String) = "remote-folder-" + id(accountId, path)
    fun messageId(folderId: String, identity: MessageIdentity) = "remote-message-" + id(folderId, identity.uidValidity, identity.uid)

    fun server(accountId: String, value: Server) = ServerEntity(value.id, accountId, value.protocol.name,
        value.hostname, value.port, value.security.name, value.username, value.authenticationType.name)
    fun server(value: ServerEntity) = Server(value.hostname, value.port, ServerProtocol.valueOf(value.protocol),
        ConnectionSecurity.valueOf(value.security), value.username, AuthenticationType.valueOf(value.authenticationType), value.id)

    fun account(value: Account, mode: String = "REAL"): AccountEntity {
        require(value.incomingServer.protocol == ServerProtocol.IMAP && value.outgoingServer.protocol == ServerProtocol.SMTP)
        val primary = requireNotNull(value.emailAddress) { "Account identity is required" }
        val config = value.authConfig?.let {
            JSONObject().put("clientId", it.clientId).put("authorizationEndpoint", it.authorizationEndpoint.toString())
                .put("tokenEndpoint", it.tokenEndpoint.toString()).put("redirectUri", it.redirectUri.toString())
                .put("scopes", strings(it.scopes)).toString()
        }
        return AccountEntity(value.id, value.name, primary.address, value.incomingServer.hostname,
            value.outgoingServer.hostname, value.incomingServer.port, value.outgoingServer.port,
            if (value.incomingServer.security == ConnectionSecurity.TLS) "SSL/TLS" else "STARTTLS",
            if (value.outgoingServer.security == ConnectionSecurity.TLS) "SSL/TLS" else "STARTTLS",
            value.outgoingServer.authenticationType != AuthenticationType.NONE, mode, addresses(value.identities).toString(),
            when (val policy = value.deletePolicy) {
                DeletePolicy.Never -> "never"
                DeletePolicy.OnDelete -> "on-delete"
                DeletePolicy.MarkAsRead -> "mark-as-read"
                is DeletePolicy.After -> "after:${policy.days}"
            }, value.avatarColor, config)
    }
    fun account(value: AccountEntity, servers: List<ServerEntity>): Account {
        require(servers.all { it.accountId == value.id })
        val incoming = server(servers.single { it.protocol == "IMAP" })
        val outgoing = server(servers.single { it.protocol == "SMTP" })
        val identities = JSONObject().put("identities", JSONArray(value.identitiesJson)).addresses("identities")
            .ifEmpty { listOf(EmailAddress(value.address, value.name)) }
        val policy = when {
            value.deletePolicy == "never" -> DeletePolicy.Never
            value.deletePolicy == "on-delete" -> DeletePolicy.OnDelete
            value.deletePolicy == "mark-as-read" -> DeletePolicy.MarkAsRead
            value.deletePolicy.startsWith("after:") -> DeletePolicy.After(value.deletePolicy.substringAfter(':').toInt())
            else -> error("Unsupported stored delete policy")
        }
        val config = value.oauthConfigurationJson?.let {
            val json = JSONObject(it)
            OAuthConfiguration(json.getString("clientId"), URI(json.getString("authorizationEndpoint")),
                URI(json.getString("tokenEndpoint")), URI(json.getString("redirectUri")), json.getJSONArray("scopes").strings())
        }
        return Account(value.id, value.name, identities, incoming, outgoing, policy, value.avatarColor, config)
    }

    fun folder(accountId: String, value: Mailbox): FolderEntity {
        val parent = value.name.substringBeforeLast(value.delimiter, "").takeIf { it.isNotEmpty() }
        val rights = value.rights
        val encoded = JSONObject().putNullable("mayReadItems", rights.mayReadItems)
            .putNullable("mayAddItems", rights.mayAddItems).putNullable("mayRemoveItems", rights.mayRemoveItems)
            .putNullable("maySetSeen", rights.maySetSeen).putNullable("maySetKeywords", rights.maySetKeywords)
            .putNullable("mayCreateChild", rights.mayCreateChild).putNullable("mayRename", rights.mayRename)
            .putNullable("mayDelete", rights.mayDelete)
        val attributes = value.attributes + if (value.selectable) emptySet() else setOf("\\Noselect")
        return FolderEntity(folderId(accountId, value.name), accountId, value.name.substringAfterLast(value.delimiter),
            value.role?.name?.lowercase() ?: "folder", parent?.let { folderId(accountId, it) }, value.name,
            value.delimiter.toString(), value.isSubscribed, strings(attributes).toString(), encoded.toString(),
            serverUnreadCount = value.unreadEmails, serverTotalCount = value.totalEmails)
    }
    fun mailbox(value: FolderEntity): Mailbox {
        val rights = value.rightsJson?.let(::JSONObject) ?: JSONObject()
        fun permission(key: String) = if (rights.isNull(key)) null else rights.getBoolean(key)
        val attributes = JSONArray(value.attributesJson).strings().toSet()
        return Mailbox(requireNotNull(value.remotePath), value.delimiter.single(), attributes.none { it.equals("\\Noselect", true) },
            attributes, value.subscribed, value.serverUnreadCount, value.serverTotalCount,
            MailboxRights(permission("mayReadItems"), permission("mayAddItems"), permission("mayRemoveItems"),
                permission("maySetSeen"), permission("maySetKeywords"), permission("mayCreateChild"),
                permission("mayRename"), permission("mayDelete")))
    }

    fun email(value: Email, folder: FolderEntity): MessageEntity {
        val identity = requireNotNull(value.identity)
        require(identity.mailbox == folder.remotePath)
        val metadata = JSONObject().put("from", addresses(value.from)).put("sender", addresses(value.sender))
            .put("replyTo", addresses(value.replyTo)).put("to", addresses(value.to)).put("cc", addresses(value.cc))
            .put("bcc", addresses(value.bcc)).putNullable("messageId", value.messageId)
            .put("messageIds", strings(value.messageIds)).put("threadIds", strings(value.threadIds))
            .put("inReplyTo", strings(value.inReplyTo)).put("references", strings(value.references))
            .putNullable("sentAt", value.sentAt?.toString()).putNullable("receivedAt", value.receivedAt?.toString())
            .putNullable("blobId", value.blobId).put("hasText", value.body.text != null)
        val from = value.from.firstOrNull()
        return MessageEntity(messageId(folder.id, identity), folder.accountId, folder.id,
            from?.name?.ifEmpty { from.address } ?: "", from?.address.orEmpty(),
            value.to.joinToString(", ") { it.address }, value.cc.joinToString(", ") { it.address },
            value.bcc.joinToString(", ") { it.address }, value.subject, value.body.text.orEmpty(), value.body.html,
            (value.receivedAt ?: Instant.EPOCH).toString(), value.read, false, value.flagged, false,
            folder.role == "drafts", null, value.body.preview.orEmpty(), identity.uidValidity, identity.uid,
            envelopeJson = metadata.toString(), bodyDownloaded = value.bodyDownloaded)
    }
    fun email(value: MessageEntity, folder: FolderEntity, attachments: List<AttachmentEntity>): Email {
        require(value.folderId == folder.id && value.accountId == folder.accountId)
        require(attachments.all { it.messageId == value.id })
        val data = JSONObject(value.envelopeJson)
        fun list(key: String) = data.optJSONArray(key)?.strings().orEmpty()
        return Email(MessageIdentity(requireNotNull(folder.remotePath), requireNotNull(value.uidValidity), requireNotNull(value.uid)),
            data.optional("messageId"), value.subject, data.addresses("from"), data.addresses("to"), data.addresses("cc"),
            data.instant("receivedAt"), EmailBody(if (data.optBoolean("hasText")) value.body else null, value.html),
            attachments.map { EmailAttachment(requireNotNull(it.partId), it.filename, it.mimeType, it.sizeBytes, it.contentId, it.inline) },
            value.isRead, value.flagged, data.addresses("sender"), data.addresses("replyTo"), data.addresses("bcc"),
            data.instant("sentAt"), list("messageIds"), list("threadIds"), list("inReplyTo"), list("references"),
            data.optional("blobId"), value.bodyDownloaded)
    }
    fun attachment(messageId: String, value: EmailAttachment) = AttachmentEntity(
        "remote-part-" + id(messageId, value.partId), messageId, value.filename, value.mediaType, value.size,
        false, "", partId = value.partId, contentId = value.contentId, inline = value.inline)
}
