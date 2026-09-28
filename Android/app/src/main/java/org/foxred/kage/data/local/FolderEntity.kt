package org.foxred.kage.data.local

import androidx.room.*

@Entity(
    tableName = "folders",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index("accountId"),
            Index(value = ["id", "accountId"], unique = true),
            Index(value = ["accountId", "remotePath"], unique = true),
        ],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val name: String,
    val role: String,
    val parentId: String?,
    val remotePath: String? = null,
    @ColumnInfo(defaultValue = "'/'") val delimiter: String = "/",
    @ColumnInfo(defaultValue = "1") val subscribed: Boolean = true,
    @ColumnInfo(defaultValue = "'[]'") val attributesJson: String = "[]",
    val rightsJson: String? = null,
    val uidValidity: Long? = null,
    val uidNext: Long? = null,
    val serverUnreadCount: Int? = null,
    val serverTotalCount: Int? = null,
    val lastVisitedUid: Long? = null,
)
