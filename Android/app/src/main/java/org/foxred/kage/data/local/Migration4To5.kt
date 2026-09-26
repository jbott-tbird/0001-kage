package org.foxred.kage.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Save the target generation and UID boundary before a non-atomic COPY fallback. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN moveTargetUidValidity INTEGER")
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN moveTargetUidNext INTEGER")
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN moveSourceMessageId TEXT")
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN moveMode TEXT")
        // Older moves have no target boundary. Retrying one could create a duplicate copy.
        db.execSQL("UPDATE pending_operations SET moveMode = 'LEGACY_UNCERTAIN' " +
            "WHERE kind = 'MOVE' AND attempts > 0")
    }
}
