package org.foxred.kage.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities =
        [
            AccountEntity::class,
            FolderEntity::class,
            MessageEntity::class,
            AttachmentEntity::class,
            PreferencesEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun mailDao(): MailDao
}
