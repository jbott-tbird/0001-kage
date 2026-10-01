// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.CredentialStore
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.data.security.OAuthCredentialResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class OAuthCredentialResolverTest {
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    @Test
    fun missingDeviceGrantRequiresSignInAndLeavesStoredCredentialUntouched() = runBlocking {
        val expired = Authorization("old-access", Authorization.Kind.OAUTH2,
            time.minusSeconds(1))
        val store = MemoryCredentials().apply { save("account", ServerProtocol.IMAP, expired) }
        val resolver = OAuthCredentialResolver(store, now = { time })
        try {
            resolver.authorization("account", ServerProtocol.IMAP)
            fail("Expected sign-in failure")
        } catch (failure: MailFailure) {
            assertEquals(FailureKind.AUTHENTICATION, failure.kind)
            assertFalse(failure.message.orEmpty().contains("old-access"))
        }
        assertSame(expired, store.authorization("account", ServerProtocol.IMAP))
    }

    @Test
    fun playServicesRenewsAccessOnlyGrantOnceForBothProtocols() = runBlocking {
        val expired = Authorization("old-access", Authorization.Kind.OAUTH2,
            time.minusSeconds(1))
        val renewed = Authorization("new-access", Authorization.Kind.OAUTH2,
            time.plusSeconds(3000))
        val store = MemoryCredentials().apply {
            save("account", ServerProtocol.IMAP, expired)
            save("account", ServerProtocol.SMTP, expired)
        }
        var calls = 0
        val resolver = OAuthCredentialResolver(store,
            now = { time }, renewFromDevice = {
                calls++
                renewed
            })

        val resolved = listOf(ServerProtocol.IMAP, ServerProtocol.SMTP).map { protocol ->
            async(Dispatchers.Default) { resolver.authorization("account", protocol) }
        }.awaitAll()
        assertEquals(1, calls)
        assertEquals(listOf("new-access", "new-access"), resolved.map { it?.secret })
        assertSame(renewed, store.authorization("account", ServerProtocol.IMAP))
        assertSame(renewed, store.authorization("account", ServerProtocol.SMTP))
    }

    @Test
    fun serverRejectedTokenExpiresBothCopiesWithoutReplacingANewerGrant() = runBlocking {
        val rejected = Authorization("rejected", Authorization.Kind.OAUTH2,
            time.plusSeconds(1200))
        val newer = Authorization("newer", Authorization.Kind.OAUTH2,
            time.plusSeconds(1200))
        val store = MemoryCredentials().apply {
            save("account", ServerProtocol.IMAP, newer)
            save("account", ServerProtocol.SMTP, rejected)
        }
        val resolver = OAuthCredentialResolver(store,
            now = { time }, renewFromDevice = { newer })

        assertEquals(true, resolver.invalidateRejectedToken("account", rejected))
        assertSame(newer, store.authorization("account", ServerProtocol.IMAP))
        assertEquals(true, store.authorization("account", ServerProtocol.SMTP)!!.isExpired(time))
        assertEquals("newer", resolver.authorization("account", ServerProtocol.SMTP)?.secret)
        assertEquals(false, resolver.invalidateRejectedToken("account", rejected))
    }

    @Test
    fun failedDeviceRenewalLeavesTheOldGrantForExplicitRecovery() = runBlocking {
        val expired = Authorization("old-access", Authorization.Kind.OAUTH2,
            time.minusSeconds(1))
        val store = MemoryCredentials().apply { save("account", ServerProtocol.IMAP, expired) }
        val resolver = OAuthCredentialResolver(store, now = { time },
            renewFromDevice = { throw MailFailure(FailureKind.AUTHENTICATION, "Sign in again") })
        try {
            resolver.authorization("account", ServerProtocol.IMAP)
            fail("Expected rejected renewal")
        } catch (failure: MailFailure) {
            assertEquals(FailureKind.AUTHENTICATION, failure.kind)
        }
        assertSame(expired, store.authorization("account", ServerProtocol.IMAP))
    }

    @Test
    fun preReleaseRefreshTokenIsDiscardedBeforeReturningAValidAccessToken() = runBlocking {
        val legacy = Authorization("legacy-access", Authorization.Kind.OAUTH2,
            time.plusSeconds(1200), "offline-refresh-secret")
        val store = MemoryCredentials().apply {
            save("account", ServerProtocol.IMAP, legacy)
            save("account", ServerProtocol.SMTP, legacy)
        }
        val resolver = OAuthCredentialResolver(store, now = { time },
            renewFromDevice = { error("Valid access token should not renew") })

        assertEquals("legacy-access", resolver.authorization("account", ServerProtocol.IMAP)?.secret)
        assertEquals(null, store.authorization("account", ServerProtocol.IMAP)?.refreshToken)
        assertEquals("legacy-access", resolver.authorization("account", ServerProtocol.SMTP)?.secret)
        assertEquals(null, store.authorization("account", ServerProtocol.SMTP)?.refreshToken)
    }

    @Test
    fun expiredPreReleaseRefreshTokenUsesDeviceRenewal() = runBlocking {
        val legacy = Authorization("old-access", Authorization.Kind.OAUTH2,
            time.minusSeconds(1), "offline-refresh-secret")
        val renewed = Authorization("new-access", Authorization.Kind.OAUTH2,
            time.plusSeconds(3000))
        val store = MemoryCredentials().apply {
            save("account", ServerProtocol.IMAP, legacy)
            save("account", ServerProtocol.SMTP, legacy)
        }
        val resolver = OAuthCredentialResolver(store, now = { time },
            renewFromDevice = { renewed })

        assertSame(renewed, resolver.authorization("account", ServerProtocol.IMAP))
        assertEquals(null, store.authorization("account", ServerProtocol.IMAP)?.refreshToken)
        assertEquals(null, store.authorization("account", ServerProtocol.SMTP)?.refreshToken)
    }

    private class MemoryCredentials : CredentialStore {
        private val values = mutableMapOf<Pair<String, ServerProtocol>, Authorization>()
        override fun authorization(accountId: String, protocol: ServerProtocol) =
            synchronized(values) { values[accountId to protocol] }
        override fun save(accountId: String, protocol: ServerProtocol, authorization: Authorization) {
            synchronized(values) { values[accountId to protocol] = authorization }
        }
        override fun removeAccount(accountId: String) {
            synchronized(values) { values.keys.removeAll { it.first == accountId } }
        }
        override fun clear() { synchronized(values) { values.clear() } }
    }
}
