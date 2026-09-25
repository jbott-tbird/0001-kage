package org.foxred.kage.data.seed

import android.content.Context
import org.foxred.kage.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Exported by web/scripts/export-android-fixtures.cjs from the web app's evaluated state. */
class DemoMail(private val context: Context) {
    private val snapshot by lazy {
        JSONObject(context.assets.open("demo-mail.json").bufferedReader().use { it.readText() })
    }
    val accounts: List<Account>
        get() =
            snapshot.getJSONArray("accounts").objects().map {
                Account(it.getString("id"), it.getString("name"), it.getString("address"))
            }

    private fun source(account: Account): JSONObject =
        if (accounts.any { it.id == account.id }) snapshot
        else snapshot.getJSONObject("addedAccount")

    private fun sourceId(account: Account) =
        if (accounts.any { it.id == account.id }) account.id else "template"

    private fun remap(value: String, from: String, to: String) =
        if (from == to) value else value.replaceFirst("$from-", "$to-")

    fun folders(account: Account): List<Folder> {
        val from = sourceId(account)
        val order = listOf("inbox", "drafts", "sent", "archive", "spam", "trash", "custom")
        return source(account)
            .getJSONArray("folders")
            .objects()
            .filter { it.getString("accountId") == from }
            .map {
                Folder(
                    remap(it.getString("id"), from, account.id),
                    account.id,
                    it.getString("name"),
                    it.getString("role"),
                    it.nullable("parentId")?.let { parent -> remap(parent, from, account.id) },
                )
            }
            .sortedBy { order.indexOf(it.role) }
    }

    fun messages(account: Account): List<Message> {
        val from = sourceId(account)
        val originalAddress = if (from == "template") "demo@example.net" else account.address
        return source(account)
            .getJSONArray("messages")
            .objects()
            .filter { it.getString("accountId") == from }
            .map { m ->
                val id = remap(m.getString("id"), from, account.id)
                val sender = m.getJSONObject("from")
                Message(
                    id = id,
                    accountId = account.id,
                    folderId = remap(m.getString("folderId"), from, account.id),
                    sender =
                        if (from == "template" && sender.getString("address") == originalAddress)
                            account.name
                        else sender.getString("name"),
                    senderAddress =
                        sender.getString("address").replace(originalAddress, account.address),
                    to = m.addresses("to").replace(originalAddress, account.address),
                    cc = m.addresses("cc"),
                    bcc = m.addresses("bcc"),
                    subject = m.getString("subject"),
                    preview = m.optString("preview", m.getString("bodyText").replace('\n', ' ')),
                    body = m.getString("bodyText"),
                    html = m.nullable("bodyHtml"),
                    receivedAt = m.getString("receivedAt"),
                    isRead = m.getBoolean("isRead"),
                    isNew = m.getBoolean("isNewSinceLastVisit"),
                    flagged = m.optBoolean("isFlagged"),
                    pinned = m.optBoolean("isPinned"),
                    draft = m.optBoolean("isDraft"),
                    relatedGroup = m.nullable("relatedGroupId"),
                    attachments =
                        m.getJSONArray("attachments").objects().map { a ->
                            val mime = a.getString("mimeType")
                            Attachment(
                                remap(a.getString("id"), from, account.id),
                                id,
                                a.getString("filename"),
                                mime,
                                a.getLong("sizeBytes"),
                                cached = a.getString("downloadState") == "available",
                                asset =
                                    if (mime == "application/pdf") "sample-ticket.pdf"
                                    else "sample-image.png",
                            )
                        },
                )
            }
    }
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.nullable(key: String) = if (isNull(key)) null else getString(key)

private fun JSONObject.addresses(key: String): String =
    optJSONArray(key)
        ?.let { array -> (0 until array.length()).joinToString(", ") { array.getString(it) } }
        .orEmpty()
