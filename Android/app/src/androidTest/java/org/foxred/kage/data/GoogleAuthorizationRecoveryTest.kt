// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.data.security.AndroidCredentialStore
import org.foxred.kage.data.security.OAuthCredentialResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleAuthorizationRecoveryTest {
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private val credentials by lazy {
        AndroidCredentialStore(ApplicationProvider.getApplicationContext(), "google-recovery-test")
    }

    @Before fun start() { credentials.clear() }
    @After fun stop() { credentials.clear() }

    @Test fun expiredEncryptedGrantRenewsBothProtocolsWithoutAnOfflineSecret() = runBlocking {
        val expired = Authorization("expired-access", Authorization.Kind.OAUTH2,
            time.minusSeconds(1))
        val renewed = Authorization("fresh-access", Authorization.Kind.OAUTH2,
            time.plusSeconds(3000))
        credentials.save("account", ServerProtocol.IMAP, expired)
        credentials.save("account", ServerProtocol.SMTP, expired)
        var calls = 0
        val resolver = OAuthCredentialResolver(credentials, now = { time },
            renewFromDevice = {
                calls++
                renewed
            })

        assertEquals("fresh-access", resolver.authorization("account", ServerProtocol.IMAP)?.secret)
        assertEquals("fresh-access", resolver.authorization("account", ServerProtocol.SMTP)?.secret)
        assertEquals(1, calls)
        assertNull(credentials.authorization("account", ServerProtocol.IMAP)?.refreshToken)
        assertNull(credentials.authorization("account", ServerProtocol.SMTP)?.refreshToken)
    }

    @Test fun failedRenewalKeepsEncryptedGrantForExplicitReauthorization() = runBlocking {
        val expired = Authorization("expired-access", Authorization.Kind.OAUTH2,
            time.minusSeconds(1))
        credentials.save("account", ServerProtocol.IMAP, expired)
        val resolver = OAuthCredentialResolver(credentials, now = { time },
            renewFromDevice = { null })

        try {
            resolver.authorization("account", ServerProtocol.IMAP)
            fail("Expected reauthorization request")
        } catch (failure: MailFailure) {
            assertEquals(FailureKind.AUTHENTICATION, failure.kind)
        }
        assertEquals("expired-access",
            credentials.authorization("account", ServerProtocol.IMAP)?.secret)
    }
}
