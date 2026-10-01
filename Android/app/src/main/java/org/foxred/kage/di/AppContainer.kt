// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.di

import android.content.Context
import org.foxred.kage.data.local.buildMailDatabase
import org.foxred.kage.data.local.CoreRoomMapper
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.repository.DurableOutbox
import org.foxred.kage.data.background.BackgroundMailRunner
import org.foxred.kage.data.security.OAuthCredentialResolver
import org.foxred.kage.data.security.GooglePlayAuthorization
import org.foxred.kage.data.security.GoogleAuthorizationStep
import org.foxred.kage.data.security.googleAuthorizationAccountName
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
    val googleAuthorization = GooglePlayAuthorization(context.applicationContext)
    private val oauthCredentials = OAuthCredentialResolver(credentials, renewFromDevice = { accountId ->
        val row = database.remoteMailDao().account(accountId) ?: return@OAuthCredentialResolver null
        val email = googleAuthorizationAccountName(
            CoreRoomMapper.account(row, database.remoteMailDao().servers(accountId)))
        (googleAuthorization.authorize(email) as? GoogleAuthorizationStep.Granted)?.credential
    })
    private val sessions = AccountSessions(RemoteMailRepository.incomingServer(database),
        credentials, { AngusImapClient() }, authorization = oauthCredentials::authorization,
        onRejectedAuthorization = { accountId, rejected ->
            if (oauthCredentials.invalidateRejectedToken(accountId, rejected))
                googleAuthorization.clearCachedToken(rejected.secret)
        })
    val remoteMailRepository = RemoteMailRepository(
        database, sessions, credentials,
        DurableOutbox(database, context.applicationContext, AngusMimeCodec()),
        authorization = oauthCredentials::authorization,
        clearCachedAccessToken = googleAuthorization::clearCachedToken,
        onRejectedAuthorization = { accountId, rejected ->
            if (oauthCredentials.invalidateRejectedToken(accountId, rejected))
                googleAuthorization.clearCachedToken(rejected.secret)
        },
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
    val backgroundMailRunner = BackgroundMailRunner(mailRepository, remoteMailRepository,
        database.remoteMailDao())
}
