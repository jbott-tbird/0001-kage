package org.foxred.kage.di

import android.content.Context
import org.foxred.kage.data.local.buildMailDatabase
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
    private val database = buildMailDatabase(context)
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
