// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.foxred.kage.core.account.Account
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.AuthenticationType
import org.foxred.kage.core.account.ConnectionSecurity
import org.foxred.kage.core.account.MailStore
import org.foxred.kage.core.account.Mailbox
import org.foxred.kage.core.account.MailboxRole
import org.foxred.kage.core.account.OAuthConfiguration
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.data.repository.RemoteMailRepository
import org.foxred.kage.data.security.validateGoogleMailAuthorization
import org.foxred.kage.data.security.googleAuthorizationAccountName

/** Server suggestions are deliberately limited to providers with known app-password settings. */
object ServerSuggestions {
    /** Explicit Google authorization can also serve a Google Workspace address. */
    fun google(address: String): Pair<Server, Server> {
        val normalized = address.trim().lowercase()
        require(normalized.contains('@') && !normalized.endsWith('@')) { "Enter an email address" }
        return Server("imap.gmail.com", 993, ServerProtocol.IMAP, ConnectionSecurity.TLS, normalized) to
            Server("smtp.gmail.com", 465, ServerProtocol.SMTP, ConnectionSecurity.TLS, normalized)
    }

    fun gmail(address: String): Pair<Server, Server>? {
        val normalized = address.trim().lowercase()
        if (normalized.substringAfterLast('@', "") !in setOf("gmail.com", "googlemail.com")) return null
        return google(normalized)
    }
}

class SetupConnectionException(val protocol: ServerProtocol, cause: Throwable) :
    Exception("${protocol.name} sign-in failed: ${cause.message ?: "Check the server settings and app password."}", cause)

/** Checks each server before storing either credential or any account row. */
class RealAccountSetup(
    private val repository: RemoteMailRepository,
    private val incomingFactory: () -> MailStore,
    private val verifyOutgoing: (Server, Authorization) -> Unit,
    private val autoconfig: ProviderAutoconfig = ProviderAutoconfig(),
) {
    suspend fun googleAccountName(accountId: String): String =
        googleAuthorizationAccountName(checkNotNull(repository.account(accountId)) {
            "Account was removed"
        })

    suspend fun suggest(address: String): Pair<Server, Server>? =
        ServerSuggestions.gmail(address) ?: autoconfig.discover(address)

    suspend fun add(account: Account, incoming: Authorization, outgoing: Authorization): String? =
        withContext(Dispatchers.IO) {
            require(account.identities.isNotEmpty()) { "Enter an email address" }
            require(account.incomingServer.protocol == ServerProtocol.IMAP)
            require(account.outgoingServer.protocol == ServerProtocol.SMTP)
            require(account.incomingServer.port in 1..65535 && account.outgoingServer.port in 1..65535)
            if (account.incomingServer.authenticationType == AuthenticationType.OAUTH2 ||
                account.outgoingServer.authenticationType == AuthenticationType.OAUTH2 ||
                incoming.kind == Authorization.Kind.OAUTH2 ||
                outgoing.kind == Authorization.Kind.OAUTH2)
                validateGoogleMailAuthorization(account, incoming, outgoing)
            val mailboxes = verify(account, incoming, outgoing)
            repository.addAccount(account, incoming, outgoing, mailboxes)
            repository.inboxFolder(account.id)
        }

    /** Verify both new grants before replacing the existing account's saved credentials. */
    suspend fun replaceAuthorization(
        accountId: String,
        incoming: Authorization,
        outgoing: Authorization,
        oauthConfiguration: OAuthConfiguration? = null,
    ) = withContext(Dispatchers.IO) {
        require(incoming.kind == outgoing.kind && incoming.kind != Authorization.Kind.NONE)
        val account = checkNotNull(repository.account(accountId)) { "Account was removed" }
        val authenticationType = if (incoming.kind == Authorization.Kind.OAUTH2)
            AuthenticationType.OAUTH2 else AuthenticationType.PASSWORD
        val updated = account.copy(
            incomingServer = account.incomingServer.copy(authenticationType = authenticationType),
            outgoingServer = account.outgoingServer.copy(authenticationType = authenticationType),
            authConfig = oauthConfiguration,
        )
        if (authenticationType == AuthenticationType.OAUTH2)
            validateGoogleMailAuthorization(updated, incoming, outgoing)
        verify(updated, incoming, outgoing)
        repository.replaceAccountAuthorization(accountId, incoming, outgoing, oauthConfiguration)
    }

    private suspend fun verify(
        account: Account, incoming: Authorization, outgoing: Authorization,
    ): List<Mailbox> {
        val store = incomingFactory()
        val mailboxes: List<Mailbox>
        try {
            try {
                store.connect(account.incomingServer, incoming)
                mailboxes = store.mailboxes()
                check(mailboxes.any { it.role == MailboxRole.INBOX && it.selectable }) {
                    "No selectable Inbox was found"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw SetupConnectionException(ServerProtocol.IMAP, failure)
            }
        } finally {
            store.close()
        }
        currentCoroutineContext().ensureActive()
        try {
            verifyOutgoing(account.outgoingServer, outgoing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw SetupConnectionException(ServerProtocol.SMTP, failure)
        }
        currentCoroutineContext().ensureActive()
        return mailboxes
    }
}
