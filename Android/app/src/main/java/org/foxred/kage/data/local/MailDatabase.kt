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
    version = 2,
    exportSchema = true,
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun mailDao(): MailDao
}

/** Preserve existing installed prototypes when adding attachment storage and SMTP preferences. */
val MIGRATION_1_2 =
    object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN requireAuth INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE attachments ADD COLUMN localFile TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN preview TEXT NOT NULL DEFAULT ''")
        }
    }
