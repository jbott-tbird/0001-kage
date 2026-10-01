// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import java.net.URI
import org.foxred.kage.core.account.Account
import org.foxred.kage.core.account.AuthenticationType
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.ConnectionSecurity
import org.foxred.kage.core.account.EmailAddress
import org.foxred.kage.core.account.OAuthConfiguration
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.data.security.validateGoogleMailAuthorization
import org.foxred.kage.data.security.googleAuthorizationAccountName
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GoogleMailAuthorizationPolicyTest {
    private val grant = Authorization("access-token", Authorization.Kind.OAUTH2)
    private val config = OAuthConfiguration("client-id",
        URI("https://accounts.google.com/o/oauth2/v2/auth"),
        URI("https://oauth2.googleapis.com/token"), URI("http://localhost/callback"),
        listOf("https://mail.google.com/"))
    private val account = Account("a", "A", listOf(EmailAddress("google-user@example.test")),
        Server("imap.gmail.com", 993, ServerProtocol.IMAP, username = "google-user@example.test",
            authenticationType = AuthenticationType.OAUTH2),
        Server("smtp.gmail.com", 465, ServerProtocol.SMTP, username = "google-user@example.test",
            authenticationType = AuthenticationType.OAUTH2), authConfig = config)

    @Test fun accountRenewalUsesImapLoginRatherThanFirstDisplayAlias() {
        val withAlias = account.copy(
            identities = listOf(EmailAddress("alias@example.test"),
                EmailAddress("primary@example.test")),
            incomingServer = account.incomingServer.copy(username = "primary@example.test"),
        )
        assertEquals("primary@example.test", googleAuthorizationAccountName(withAlias))
    }

    @Test fun rejectsAStoredMailboxHostThatCouldReceiveTheGoogleToken() {
        validateGoogleMailAuthorization(account, grant, grant)
        val changed = account.copy(incomingServer = account.incomingServer.copy(
            hostname = "imap.example.test"))
        try {
            validateGoogleMailAuthorization(changed, grant, grant)
            fail("Expected host rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsAnUnexpectedAuthorizationEndpointOrMissingMailScope() {
        for (changed in listOf(
            account.copy(authConfig = config.copy(
                authorizationEndpoint = URI("https://example.test/authorize"))),
            account.copy(authConfig = config.copy(scopes = listOf("openid"))),
        )) {
            try {
                validateGoogleMailAuthorization(changed, grant, grant)
                fail("Expected configuration rejection")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun acceptsOnlyGmailTlsPortPairs() {
        validateGoogleMailAuthorization(account.copy(outgoingServer =
            account.outgoingServer.copy(port = 587, security = ConnectionSecurity.STARTTLS)),
            grant, grant)
        for (changed in listOf(
            account.copy(incomingServer = account.incomingServer.copy(port = 143)),
            account.copy(incomingServer = account.incomingServer.copy(
                security = ConnectionSecurity.STARTTLS)),
            account.copy(outgoingServer = account.outgoingServer.copy(port = 2525)),
            account.copy(outgoingServer = account.outgoingServer.copy(
                port = 587, security = ConnectionSecurity.TLS)),
            account.copy(outgoingServer = account.outgoingServer.copy(
                port = 465, security = ConnectionSecurity.STARTTLS)),
        )) {
            try {
                validateGoogleMailAuthorization(changed, grant, grant)
                fail("Expected Gmail port and TLS configuration rejection")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun rejectsADeviceStoredRefreshToken() {
        val offline = Authorization("access-token", Authorization.Kind.OAUTH2,
            refreshToken = "offline-refresh-secret")
        try {
            validateGoogleMailAuthorization(account, offline, offline)
            fail("Expected device refresh-token rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
