package org.foxred.kage.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Keep cached mail while adding durable scan and CONDSTORE/QRESYNC checkpoints. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN lastSeenPassId TEXT")
        db.execSQL("ALTER TABLE sync_cursors ADD COLUMN fullPassId TEXT")
        db.execSQL("ALTER TABLE sync_cursors ADD COLUMN highestModSeq INTEGER")
    }
}
