// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.sync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
) {
    private class Session {
        val lock = Mutex()
        @Volatile var store: MailStore? = null
        @Volatile var closed = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun <T> withStore(accountId: String, block: (MailStore) -> T): T {
        val session = sessions.computeIfAbsent(accountId) { Session() }
        return session.lock.withLock {
            if (session.closed) throw MailFailure(FailureKind.CANCELLED, "Account was removed")
            withContext(Dispatchers.IO) {
                val store = session.store ?: connect(accountId).also { session.store = it }
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

    private suspend fun connect(accountId: String): MailStore {
        val server = incomingServer(accountId)
        val authorization =
            credentials.authorization(accountId, ServerProtocol.IMAP)
                ?: throw MailFailure(FailureKind.AUTHENTICATION, "Sign in again to this account")
        val store = factory(accountId)
        try {
            interruptible(store) { store.connect(server, authorization) }
        } catch (error: Throwable) {
            runCatching { store.close() }
            throw error
        }
        return store
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

    private companion object {
        val RECONNECT =
            setOf(FailureKind.CANCELLED, FailureKind.CONNECTION, FailureKind.AUTHENTICATION)
    }
}
