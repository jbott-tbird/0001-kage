package org.foxred.kage.di

import android.content.Context
import androidx.room.Room
import org.foxred.kage.data.local.MIGRATION_1_2
import org.foxred.kage.data.local.MIGRATION_2_3
import org.foxred.kage.data.local.MIGRATION_3_4
import org.foxred.kage.data.local.MIGRATION_4_5
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.setup.RealAccountSetup
import org.foxred.kage.data.sync.AccountSessions
import org.foxred.kage.core.imap.AngusImapClient
import org.foxred.kage.core.smtp.AngusSmtpClient
import org.foxred.kage.core.mime.AngusMimeCodec
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.domain.repository.MailRepository

/** Application-scoped composition root. UI depends only on the repository interface. */
class AppContainer(context: Context) {
    private val database =
        Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, "kage-mail.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    private val credentials =
        org.foxred.kage.data.security.AndroidCredentialStore(context.applicationContext)
    private val sessions = AccountSessions(RemoteMailRepository.incomingServer(database), credentials) { AngusImapClient() }
    val remoteMailRepository = RemoteMailRepository(
        database, sessions, credentials,
        DurableOutbox(database, context.applicationContext, AngusMimeCodec()),
    )
    val realAccountSetup = RealAccountSetup(remoteMailRepository, { AngusImapClient() }, { server, authorization ->
        AngusSmtpClient().verifyConnection(server, authorization)
    })
    val mailRepository: MailRepository =
        RoomMailRepository(
            database,
            context.applicationContext,
            DemoMail(context.applicationContext),
            credentials,
            remoteMailRepository,
        )
}
