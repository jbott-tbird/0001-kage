// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.sync

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.foxred.kage.core.account.*

/**
 * One IMAP session per account. Operations on an account are serialized; accounts proceed
 * independently. Coroutine cancellation interrupts the blocking store call, and any session that
 * fails with a connection-level error is discarded so the next caller reconnects.
 */
class AccountSessions(
    private val incomingServer: suspend (accountId: String) -> Server,
    private val credentials: CredentialProvider,
    private val factory: (accountId: String) -> MailStore,
    private val now: () -> Instant = Instant::now,
    private val authorization: suspend (String, ServerProtocol) -> Authorization? =
        { accountId, protocol -> credentials.authorization(accountId, protocol) },
    private val onRejectedAuthorization: suspend (String, Authorization) -> Unit = { _, _ -> },
) {
    private class Session {
        val lock = Mutex()
        @Volatile var store: MailStore? = null
        @Volatile var expiresAt: Instant? = null
        @Volatile var closed = false
    }

    private data class ConnectedStore(val store: MailStore, val expiresAt: Instant?)

    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun <T> withStore(accountId: String, block: (MailStore) -> T): T {
        val session = sessions.computeIfAbsent(accountId) { Session() }
        return session.lock.withLock {
            if (session.closed) throw MailFailure(FailureKind.CANCELLED, "Account was removed")
            withContext(Dispatchers.IO) {
                // A long-lived IMAP connection must not outlive the token that opened it.
                if (session.expiresAt?.isAfter(now()) == false) discard(session)
                val store = session.store ?: connect(accountId).also {
                    session.store = it.store
                    session.expiresAt = it.expiresAt
                }.store
                if (session.closed) {
                    discard(session)
                    throw MailFailure(FailureKind.CANCELLED, "Account was removed")
                }
                try {
                    interruptible(store) { block(store) }
                } catch (failure: MailFailure) {
                    if (failure.kind in RECONNECT) discard(session)
                    throw failure
                }
            }
        }
    }

    private suspend fun connect(accountId: String): ConnectedStore {
        val server = incomingServer(accountId)
        val authorization =
            authorization(accountId, ServerProtocol.IMAP)
                ?: throw MailFailure(FailureKind.AUTHENTICATION, "Sign in again to this account")
        if (authorization.isExpired(now()))
            throw MailFailure(FailureKind.AUTHENTICATION, "Authorization expired; sign in again")
        val store = factory(accountId)
        try {
            interruptible(store) { store.connect(server, authorization) }
        } catch (error: Throwable) {
            runCatching { store.close() }
            if (error is MailFailure && error.kind == FailureKind.AUTHENTICATION &&
                authorization.kind == Authorization.Kind.OAUTH2)
                withContext(NonCancellable) {
                    runCatching { withTimeout(5_000) {
                        onRejectedAuthorization(accountId, authorization)
                    } }.exceptionOrNull()?.let(error::addSuppressed)
                }
            throw error
        }
        return ConnectedStore(store, authorization.expiresAt)
    }

    /** Runs a blocking call; cancellation of the caller cancels the store's socket. */
    private suspend fun <T> interruptible(store: MailStore, block: () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) store.cancel()
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private fun discard(session: Session) {
        session.store?.let { runCatching { it.close() } }
        session.store = null
        session.expiresAt = null
    }

    /** Cancels in-flight work immediately and prevents new work for a removed account. */
    fun close(accountId: String) {
        val session = sessions.remove(accountId) ?: return
        session.closed = true
        session.store?.let { runCatching { it.close() } }
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }

    /** Stop old-token work and reject new work until an account credential change finishes. */
    suspend fun <T> withDisconnected(accountId: String, block: suspend () -> T): T {
        val session = sessions.computeIfAbsent(accountId) { Session() }
        session.closed = true
        session.store?.cancel()
        return try {
            session.lock.withLock {
                discard(session)
                block()
            }
        } finally {
            // Cancellation while waiting for the lock must not expose a fresh session while
            // the old operation still owns the account connection.
            withContext(NonCancellable) {
                session.lock.withLock {
                    discard(session)
                    sessions.remove(accountId, session)
                }
            }
        }
    }

    private companion object {
        val RECONNECT =
            setOf(FailureKind.CANCELLED, FailureKind.CONNECTION, FailureKind.AUTHENTICATION)
    }
}
