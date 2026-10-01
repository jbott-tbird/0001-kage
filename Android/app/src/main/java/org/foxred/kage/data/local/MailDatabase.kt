// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities =
        [
            AccountEntity::class,
            FolderEntity::class,
            MessageEntity::class,
            AttachmentEntity::class,
            PreferencesEntity::class,
            ServerEntity::class,
            SyncCursorEntity::class,
            HistoryCursorEntity::class,
            PendingOperationEntity::class,
            OutboxEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun mailDao(): MailDao

    abstract fun remoteMailDao(): RemoteMailDao
}

// This filename separates the first complete schema from incompatible prototype databases.
fun buildMailDatabase(context: Context, name: String = "kage-mail-main.db"): MailDatabase =
    Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, name)
        .build()
