// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import androidx.room.*

/** MIME source is held in a private file; envelope metadata preserves Bcc independently. */
@Entity(
    tableName = "outbox",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = MessageEntity::class,
                parentColumns = ["id", "accountId"],
                childColumns = ["draftId", "accountId"],
                onDelete = ForeignKey.NO_ACTION,
            ),
        ],
    indices =
        [
            Index(value = ["accountId", "messageId"], unique = true),
            Index(value = ["draftId", "accountId"]),
            Index(value = ["accountId", "state"]),
        ],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val draftId: String?,
    val messageId: String,
    val rawMessagePath: String,
    val envelopeJson: String,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
