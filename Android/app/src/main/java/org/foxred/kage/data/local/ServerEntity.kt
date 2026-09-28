package org.foxred.kage.data.local

import androidx.room.*

/** Server configuration contains no password or OAuth tokens; secrets live outside Room. */
@Entity(
    tableName = "servers",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["accountId", "protocol"], unique = true)],
)
data class ServerEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val protocol: String,
    val hostname: String,
    val port: Int,
    val security: String,
    val username: String,
    val authenticationType: String,
)
