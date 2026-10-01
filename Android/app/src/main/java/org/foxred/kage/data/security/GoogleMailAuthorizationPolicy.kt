// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.security

import java.net.URI
import java.util.Locale
import org.foxred.kage.core.account.Account
import org.foxred.kage.core.account.AuthenticationType
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.ConnectionSecurity

/** The IMAP login is authoritative when a Google mailbox also has display aliases. */
fun googleAuthorizationAccountName(account: Account): String = account.incomingServer.username

/** Keep a Google mail grant on Google's IMAP, SMTP and OAuth endpoints. */
fun validateGoogleMailAuthorization(
    account: Account,
    incoming: Authorization,
    outgoing: Authorization,
) {
    val config = requireNotNull(account.authConfig) { "Google authorization settings are required" }
    require(incoming.kind == Authorization.Kind.OAUTH2 &&
        outgoing.kind == Authorization.Kind.OAUTH2 &&
        incoming.refreshToken.isNullOrBlank() && outgoing.refreshToken.isNullOrBlank() &&
        account.incomingServer.authenticationType == AuthenticationType.OAUTH2 &&
        account.outgoingServer.authenticationType == AuthenticationType.OAUTH2 &&
        account.incomingServer.hostname.lowercase(Locale.ROOT) == "imap.gmail.com" &&
        account.incomingServer.port == 993 &&
        account.incomingServer.security == ConnectionSecurity.TLS &&
        account.outgoingServer.hostname.lowercase(Locale.ROOT) == "smtp.gmail.com" &&
        ((account.outgoingServer.port == 465 &&
            account.outgoingServer.security == ConnectionSecurity.TLS) ||
            (account.outgoingServer.port == 587 &&
                account.outgoingServer.security == ConnectionSecurity.STARTTLS)) &&
        config.authorizationEndpoint == URI("https://accounts.google.com/o/oauth2/v2/auth") &&
        config.tokenEndpoint == URI("https://oauth2.googleapis.com/token") &&
        config.clientId.isNotBlank() &&
        "https://mail.google.com/" in config.scopes) {
        "This account is not configured for Google IMAP and SMTP"
    }
}
