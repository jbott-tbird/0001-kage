// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.account

/**
 * Blocking secret-store boundary. Call on an I/O dispatcher; never persist secrets in mail rows.
 */
interface CredentialProvider {
    fun authorization(accountId: String, protocol: ServerProtocol): Authorization?
}

interface CredentialStore : CredentialProvider {
    fun save(accountId: String, protocol: ServerProtocol, authorization: Authorization)

    fun removeAccount(accountId: String)

    fun clear()
}

enum class CredentialFailureReason {
    UNREADABLE,
    STORAGE_UNAVAILABLE,
}

/** Intentionally omits underlying exception text, which can contain decoded credential data. */
class CredentialFailure(val reason: CredentialFailureReason) :
    Exception(
        when (reason) {
            CredentialFailureReason.UNREADABLE ->
                "Stored credentials are unavailable; sign in again"
            CredentialFailureReason.STORAGE_UNAVAILABLE -> "Credential storage is unavailable"
        }
    )
