package org.foxred.kage.data.local

import androidx.room.*

@Entity(tableName = "preferences")
data class PreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val selectedFolder: String = "personal-inbox",
    val unified: Boolean = false,
    val threads: Boolean = false,
    val automaticAttachments: Boolean = false,
    val offline: Boolean = false,
    val started: Boolean = false,
)
