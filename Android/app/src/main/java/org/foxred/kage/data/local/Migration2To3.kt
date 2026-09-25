package org.foxred.kage.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Preserve prototypes while adding remote identity and durable mail work. No secrets enter Room.
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Rebuilding the parent messages table may trigger attachment cascades. Preserve those
            // rows
            // inside the migration transaction and restore them after the parent is recreated.
            db.execSQL("""ALTER TABLE `accounts` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'DEMO'""")
            db.execSQL(
                """ALTER TABLE `accounts` ADD COLUMN `identitiesJson` TEXT NOT NULL DEFAULT '[]'"""
            )
            db.execSQL(
                """ALTER TABLE `accounts` ADD COLUMN `deletePolicy` TEXT NOT NULL DEFAULT 'never'"""
            )
            db.execSQL(
                """ALTER TABLE `accounts` ADD COLUMN `avatarColor` TEXT NOT NULL DEFAULT 'user-blue'"""
            )
            db.execSQL("""ALTER TABLE `accounts` ADD COLUMN `oauthConfigurationJson` TEXT""")
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `remotePath` TEXT""")
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `delimiter` TEXT NOT NULL DEFAULT '/'""")
            db.execSQL(
                """ALTER TABLE `folders` ADD COLUMN `subscribed` INTEGER NOT NULL DEFAULT 1"""
            )
            db.execSQL(
                """ALTER TABLE `folders` ADD COLUMN `attributesJson` TEXT NOT NULL DEFAULT '[]'"""
            )
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `rightsJson` TEXT""")
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `uidValidity` INTEGER""")
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `uidNext` INTEGER""")
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `serverUnreadCount` INTEGER""")
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `serverTotalCount` INTEGER""")
            db.execSQL("""ALTER TABLE `folders` ADD COLUMN `lastVisitedUid` INTEGER""")
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_folders_accountId` ON `folders` (`accountId`)"""
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_id_accountId` ON `folders` (`id`, `accountId`)"""
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_accountId_remotePath` ON `folders` (`accountId`, `remotePath`)"""
            )
            db.execSQL("""CREATE TEMP TABLE migration_attachments AS SELECT * FROM attachments""")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `messages_new` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `folderId` TEXT NOT NULL, `sender` TEXT NOT NULL, `senderAddress` TEXT NOT NULL, `to` TEXT NOT NULL, `cc` TEXT NOT NULL, `bcc` TEXT NOT NULL, `subject` TEXT NOT NULL, `body` TEXT NOT NULL, `html` TEXT, `receivedAt` TEXT NOT NULL, `isRead` INTEGER NOT NULL, `isNew` INTEGER NOT NULL, `flagged` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `draft` INTEGER NOT NULL, `relatedGroup` TEXT, `preview` TEXT NOT NULL DEFAULT '', `uidValidity` INTEGER, `uid` INTEGER, `remoteEmailId` TEXT, `envelopeJson` TEXT NOT NULL DEFAULT '{}', `bodyDownloaded` INTEGER NOT NULL DEFAULT 1, `rawMessagePath` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`folderId`, `accountId`) REFERENCES `folders`(`id`, `accountId`) ON UPDATE NO ACTION ON DELETE CASCADE )"""
            )
            db.execSQL(
                """INSERT INTO messages_new (`id`, `accountId`, `folderId`, `sender`, `senderAddress`, `to`, `cc`, `bcc`, `subject`, `body`, `html`, `receivedAt`, `isRead`, `isNew`, `flagged`, `pinned`, `draft`, `relatedGroup`, `preview`) SELECT `id`, `accountId`, `folderId`, `sender`, `senderAddress`, `to`, `cc`, `bcc`, `subject`, `body`, `html`, `receivedAt`, `isRead`, `isNew`, `flagged`, `pinned`, `draft`, `relatedGroup`, `preview` FROM messages"""
            )
            db.execSQL("""DROP TABLE messages""")
            db.execSQL("""ALTER TABLE messages_new RENAME TO messages""")
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_messages_accountId` ON `messages` (`accountId`)"""
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_messages_folderId` ON `messages` (`folderId`)"""
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_messages_folderId_accountId` ON `messages` (`folderId`, `accountId`)"""
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_folderId_uidValidity_uid` ON `messages` (`folderId`, `uidValidity`, `uid`)"""
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_id_accountId` ON `messages` (`id`, `accountId`)"""
            )
            db.execSQL("DELETE FROM attachments")
            db.execSQL(
                """INSERT INTO attachments (`id`, `messageId`, `filename`, `mimeType`, `sizeBytes`, `cached`, `asset`, `localFile`) SELECT `id`, `messageId`, `filename`, `mimeType`, `sizeBytes`, `cached`, `asset`, `localFile` FROM migration_attachments"""
            )
            db.execSQL("""DROP TABLE migration_attachments""")
            db.execSQL("""ALTER TABLE `attachments` ADD COLUMN `partId` TEXT""")
            db.execSQL("""ALTER TABLE `attachments` ADD COLUMN `contentId` TEXT""")
            db.execSQL(
                """ALTER TABLE `attachments` ADD COLUMN `inline` INTEGER NOT NULL DEFAULT 0"""
            )
            db.execSQL(
                """ALTER TABLE `attachments` ADD COLUMN `downloadState` TEXT NOT NULL DEFAULT 'NOT_DOWNLOADED'"""
            )
            db.execSQL(
                """ALTER TABLE `attachments` ADD COLUMN `downloadedBytes` INTEGER NOT NULL DEFAULT 0"""
            )
            db.execSQL(
                """UPDATE attachments SET downloadState = CASE WHEN cached = 1 THEN 'DOWNLOADED' ELSE 'NOT_DOWNLOADED' END, downloadedBytes = CASE WHEN cached = 1 THEN sizeBytes ELSE 0 END"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `servers` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `protocol` TEXT NOT NULL, `hostname` TEXT NOT NULL, `port` INTEGER NOT NULL, `security` TEXT NOT NULL, `username` TEXT NOT NULL, `authenticationType` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"""
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_servers_accountId_protocol` ON `servers` (`accountId`, `protocol`)"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sync_cursors` (`folderId` TEXT NOT NULL, `uidValidity` INTEGER NOT NULL, `beforeUid` INTEGER, `sinceEpochMillis` INTEGER NOT NULL, `lastCompletedAt` INTEGER, PRIMARY KEY(`folderId`), FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `pending_operations` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `messageId` TEXT, `mailbox` TEXT NOT NULL, `uidValidity` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `kind` TEXT NOT NULL, `desiredValue` INTEGER, `targetMailbox` TEXT, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`messageId`, `accountId`) REFERENCES `messages`(`id`, `accountId`) ON UPDATE NO ACTION ON DELETE NO ACTION )"""
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_pending_operations_accountId` ON `pending_operations` (`accountId`)"""
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_pending_operations_messageId_accountId` ON `pending_operations` (`messageId`, `accountId`)"""
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_pending_operations_accountId_state` ON `pending_operations` (`accountId`, `state`)"""
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `draftId` TEXT, `messageId` TEXT NOT NULL, `rawMessagePath` TEXT NOT NULL, `envelopeJson` TEXT NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`draftId`, `accountId`) REFERENCES `messages`(`id`, `accountId`) ON UPDATE NO ACTION ON DELETE NO ACTION )"""
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_accountId_messageId` ON `outbox` (`accountId`, `messageId`)"""
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_outbox_draftId_accountId` ON `outbox` (`draftId`, `accountId`)"""
            )
            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `index_outbox_accountId_state` ON `outbox` (`accountId`, `state`)"""
            )
            db.execSQL(
                """INSERT INTO servers (id, accountId, protocol, hostname, port, security, username, authenticationType) SELECT 'legacy-imap:' || id, id, 'IMAP', incoming, incomingPort, CASE security WHEN 'SSL/TLS' THEN 'TLS' WHEN 'STARTTLS' THEN 'STARTTLS' ELSE 'NONE' END, address, 'PASSWORD' FROM accounts"""
            )
            db.execSQL(
                """INSERT INTO servers (id, accountId, protocol, hostname, port, security, username, authenticationType) SELECT 'legacy-smtp:' || id, id, 'SMTP', outgoing, outgoingPort, CASE outgoingSecurity WHEN 'SSL/TLS' THEN 'TLS' WHEN 'STARTTLS' THEN 'STARTTLS' ELSE 'NONE' END, address, CASE WHEN requireAuth = 1 THEN 'PASSWORD' ELSE 'NONE' END FROM accounts"""
            )
        }
    }
