// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.foxred.kage.core.account.AuthenticationType
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.CredentialProvider
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.MailStore
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.core.demo.DemoMailStore
import org.foxred.kage.data.sync.AccountSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountSessionsTest {
    @Test
    fun canceledCredentialSwitchKeepsOldSessionClosedUntilItsOperationSettles() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canceledStore = CountDownLatch(1)
        val openings = AtomicInteger()
        val credentials = object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }
        val sessions = AccountSessions(
            incomingServer = { Server("example.test", 993, ServerProtocol.IMAP, username = "user") },
            credentials = credentials,
            factory = {
                openings.incrementAndGet()
                val delegate = DemoMailStore()
                object : MailStore by delegate {
                    override fun cancel() { canceledStore.countDown() }
                }
            },
        )
        try {
            val active = async(Dispatchers.IO) {
                sessions.withStore("account") { store ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    store.mailboxes()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val switch = async(Dispatchers.Default) {
                sessions.withDisconnected("account") { fail("Canceled switch entered") }
            }
            assertTrue(canceledStore.await(5, TimeUnit.SECONDS))
            switch.cancel()
            assertNull(withTimeoutOrNull(100) { switch.join(); true })
            assertEquals(1, openings.get())
            release.countDown()
            active.await()
            switch.join()
            sessions.withStore("account") { it.mailboxes() }
            assertEquals(2, openings.get())
        } finally {
            release.countDown()
            sessions.closeAll()
        }
    }

    @Test
    fun credentialSwitchClosesOldConnectionAndNextCallCreatesANewOne() = runBlocking {
        val opened = mutableListOf<TrackingStore>()
        val credentials = object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) =
                Authorization("password")
        }
        val sessions = AccountSessions(
            incomingServer = { Server("example.test", 993, ServerProtocol.IMAP, username = "user") },
            credentials = credentials,
            factory = { TrackingStore().also(opened::add) },
        )
        sessions.withStore("account") { it.mailboxes() }
        sessions.withDisconnected("account") { assertTrue(opened.single().closed) }
        sessions.withStore("account") { it.mailboxes() }
        assertEquals(2, opened.size)
        sessions.closeAll()
    }

    @Test
    fun expiredOAuthSessionIsClosedAndReconnectsWithTheReplacementToken() = runBlocking {
        var time = Instant.parse("2026-01-01T00:00:00Z")
        var token = Authorization("first", Authorization.Kind.OAUTH2, time.plusSeconds(60))
        val opened = mutableListOf<TrackingStore>()
        val credentials = object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) = token
        }
        val sessions = AccountSessions(
            incomingServer = { Server("example.test", 993, ServerProtocol.IMAP,
                username = "user", authenticationType = AuthenticationType.OAUTH2) },
            credentials = credentials,
            factory = { TrackingStore().also(opened::add) },
            now = { time },
        )

        assertEquals("first", sessions.withStore("account") { (it as TrackingStore).token })
        time = time.plusSeconds(30)
        assertEquals("first", sessions.withStore("account") { (it as TrackingStore).token })
        assertEquals(1, opened.size)

        time = time.plusSeconds(30)
        try {
            sessions.withStore("account") { fail("Expired token was reused") }
            fail("Expected authentication failure")
        } catch (failure: MailFailure) {
            assertEquals(FailureKind.AUTHENTICATION, failure.kind)
        }
        assertTrue(opened.single().closed)
        assertEquals(1, opened.size)

        token = Authorization("replacement", Authorization.Kind.OAUTH2, time.plusSeconds(60))
        assertEquals("replacement", sessions.withStore("account") { (it as TrackingStore).token })
        assertEquals(2, opened.size)
        sessions.closeAll()
    }

    @Test
    fun rejectedOAuthConnectInvalidatesTheUsedTokenBeforeRetry() = runBlocking {
        val rejected = Authorization("rejected", Authorization.Kind.OAUTH2,
            Instant.parse("2026-01-01T01:00:00Z"))
        val credentials = object : CredentialProvider {
            override fun authorization(accountId: String, protocol: ServerProtocol) = rejected
        }
        val invalidations = mutableListOf<Authorization>()
        val sessions = AccountSessions(
            incomingServer = { Server("example.test", 993, ServerProtocol.IMAP,
                username = "user", authenticationType = AuthenticationType.OAUTH2) },
            credentials = credentials,
            factory = {
                val delegate = DemoMailStore()
                object : MailStore by delegate {
                    override fun connect(server: Server, authorization: Authorization) {
                        throw MailFailure(FailureKind.AUTHENTICATION, "IMAP authentication failed")
                    }
                }
            },
            now = { Instant.parse("2026-01-01T00:00:00Z") },
            onRejectedAuthorization = { _, authorization -> invalidations += authorization },
        )
        try {
            sessions.withStore("account") { fail("Rejected connection entered") }
            fail("Expected authentication failure")
        } catch (failure: MailFailure) {
            assertEquals(FailureKind.AUTHENTICATION, failure.kind)
        }
        assertEquals(listOf(rejected), invalidations)
        sessions.closeAll()
    }

    private class TrackingStore(private val delegate: DemoMailStore = DemoMailStore()) : MailStore by delegate {
        var token = ""
            private set
        var closed = false
            private set

        override fun connect(server: Server, authorization: Authorization) {
            token = authorization.secret
            delegate.connect(server, authorization)
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
