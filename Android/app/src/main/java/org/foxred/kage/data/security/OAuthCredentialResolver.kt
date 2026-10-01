// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.security

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.CredentialStore
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure
import org.foxred.kage.core.account.ServerProtocol

/** Resolve stored credentials before network use; one refresh at a time per account. */
class OAuthCredentialResolver(
    private val credentials: CredentialStore,
    private val now: () -> Instant = Instant::now,
    private val renewFromDevice: suspend (String) -> Authorization? = { null },
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** A server-rejected token must not be returned again merely because its clock has time left. */
    suspend fun invalidateRejectedToken(accountId: String, rejected: Authorization): Boolean =
        locks.computeIfAbsent(accountId) { Mutex() }.withLock {
            if (rejected.kind != Authorization.Kind.OAUTH2) return@withLock false
            var invalidated = false
            withContext(Dispatchers.IO) {
                for (protocol in listOf(ServerProtocol.IMAP, ServerProtocol.SMTP)) {
                    val current = credentials.authorization(accountId, protocol)
                    if (current?.kind == Authorization.Kind.OAUTH2 &&
                        current.secret == rejected.secret) {
                        credentials.save(accountId, protocol,
                            Authorization(current.secret, Authorization.Kind.OAUTH2,
                                now().minusSeconds(1)))
                        invalidated = true
                    }
                }
            }
            invalidated
        }

    suspend fun authorization(accountId: String, protocol: ServerProtocol): Authorization? =
        locks.computeIfAbsent(accountId) { Mutex() }.withLock {
            var current = withContext(Dispatchers.IO) {
                credentials.authorization(accountId, protocol)
            } ?: return@withLock null
            if (current.kind != Authorization.Kind.OAUTH2) return@withLock current
            // Pre-release builds could store an offline refresh token. Drop it as soon as
            // that credential is read; only Play services may renew a device grant.
            if (current.refreshToken != null) {
                current = Authorization(current.secret, Authorization.Kind.OAUTH2,
                    current.expiresAt)
                withContext(Dispatchers.IO) { credentials.save(accountId, protocol, current) }
            }
            val cutoff = now().plusSeconds(30)
            if (current.expiresAt?.isAfter(cutoff) == true) return@withLock current

            val otherProtocol = if (protocol == ServerProtocol.IMAP) ServerProtocol.SMTP
                else ServerProtocol.IMAP
            var other = withContext(Dispatchers.IO) {
                credentials.authorization(accountId, otherProtocol)
            }
            if (other?.kind == Authorization.Kind.OAUTH2 && other.refreshToken != null) {
                other = Authorization(other.secret, Authorization.Kind.OAUTH2, other.expiresAt)
                withContext(Dispatchers.IO) { credentials.save(accountId, otherProtocol, other) }
            }
            // Both Gmail protocols use the same grant. Adopt a token refreshed by the other side.
            if (other?.kind == Authorization.Kind.OAUTH2 &&
                other.refreshToken == current.refreshToken &&
                other.expiresAt?.isAfter(cutoff) == true) {
                withContext(Dispatchers.IO) { credentials.save(accountId, protocol, other) }
                return@withLock other
            }

            val renewed = renewFromDevice(accountId)
                ?: throw MailFailure(FailureKind.AUTHENTICATION,
                    "Google authorization expired; sign in again")
            if (renewed.kind != Authorization.Kind.OAUTH2 || renewed.refreshToken != null ||
                renewed.expiresAt?.isAfter(cutoff) != true)
                throw MailFailure(FailureKind.AUTHENTICATION,
                    "Google returned an unusable authorization; sign in again")
            withContext(Dispatchers.IO) {
                credentials.save(accountId, protocol, renewed)
                if (other?.kind == Authorization.Kind.OAUTH2 && other.refreshToken == null)
                    credentials.save(accountId, otherProtocol, renewed)
            }
            renewed
        }
}
