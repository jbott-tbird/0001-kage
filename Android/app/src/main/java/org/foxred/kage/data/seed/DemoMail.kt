package org.foxred.kage.data.seed

import android.content.Context
import org.foxred.kage.domain.model.*
import org.json.JSONArray

/** Bundled fixtures are the only source of mail until a protocol transport is added. */
class DemoMail(private val context: Context) {
    val accounts =
        listOf(
            Account("personal", "Rhea Thunderbird", "rhea@example.com"),
            Account("work", "Rhea · Work", "rhea@example.org"),
            Account("community", "Rhea · Community", "rhea@community.example.net"),
        )

    fun folders(account: Account): List<Folder> =
        listOf(
                "inbox" to "Inbox",
                "drafts" to "Drafts",
                "sent" to "Sent",
                "archive" to "Archive",
                "spam" to "Spam",
                "trash" to "Trash",
                "travel" to "Travel",
                "receipts" to "Receipts",
                "projects" to "Projects",
                "design" to "Design",
            )
            .map { (role, name) ->
                Folder(
                    "${account.id}-$role",
                    account.id,
                    name,
                    if (role in listOf("travel", "receipts", "projects", "design")) "custom"
                    else role,
                    if (role == "design") "${account.id}-projects" else null,
                )
            }

    fun messages(account: Account): List<Message> {
        val array =
            JSONArray(context.assets.open("demo-mail.json").bufferedReader().use { it.readText() })
        val result =
            (0 until array.length())
                .map { i ->
                    val m = array.getJSONObject(i)
                    val id = "${account.id}-${m.getString("id")}"
                    val sender = m.getJSONObject("from")
                    val att = m.getJSONArray("attachments")
                    Message(
                        id,
                        account.id,
                        "${account.id}-${if (m.optBoolean("isDraft")) "drafts" else "inbox"}",
                        sender.optString("name").ifBlank { sender.getString("address") },
                        sender.getString("address"),
                        account.address,
                        cc =
                            m.optJSONArray("cc")
                                ?.let {
                                    (0 until it.length()).joinToString(", ") { n ->
                                        it.getString(n)
                                    }
                                }
                                .orEmpty(),
                        subject = m.getString("subject"),
                        body = m.getString("bodyText"),
                        html = if (m.isNull("bodyHtml")) null else m.getString("bodyHtml"),
                        receivedAt = m.getString("receivedAt"),
                        isRead = m.optBoolean("isRead"),
                        isNew = m.optBoolean("isNewSinceLastVisit"),
                        flagged = m.optBoolean("isFlagged") || i == 1,
                        pinned = m.optBoolean("isPinned") || i == 0,
                        draft = m.optBoolean("isDraft"),
                        relatedGroup =
                            if (m.isNull("relatedGroupId")) null
                            else "${account.id}-${m.getString("relatedGroupId")}",
                        attachments =
                            (0 until att.length()).map { n ->
                                Attachment(
                                    "$id-$n",
                                    id,
                                    "ticket.pdf",
                                    "application/pdf",
                                    context.assets.open("sample-ticket.pdf").use {
                                        it.readBytes().size.toLong()
                                    },
                                )
                            },
                    )
                }
                .toMutableList()
        listOf(
                "sent" to "Re: Saturday lunch",
                "archive" to "Your books have been renewed",
                "travel" to "Your October trip",
                "receipts" to "Receipt for your book order",
                "design" to "Message list design review",
            )
            .forEachIndexed { i, (folder, subject) ->
                result +=
                    Message(
                        "${account.id}-folder-$i",
                        account.id,
                        "${account.id}-$folder",
                        "Roc Thunderbird",
                        "roc@example.net",
                        account.address,
                        subject = subject,
                        body =
                            "Hi Rhea,\n\nHere are the details for $subject. This is a sample email to help explore the app.\n\nRoc",
                        receivedAt = "2026-09-22T10:00:00Z",
                        isRead = true,
                    )
            }
        return result
    }
}
