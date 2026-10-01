// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.security

import android.app.PendingIntent
import android.content.Intent
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.OAuthConfiguration

sealed interface GoogleAuthorizationStep {
    data class Consent(val pendingIntent: PendingIntent) : GoogleAuthorizationStep
    data class Granted(val credential: Authorization) : GoogleAuthorizationStep
}

/** The UI can exercise consent and grant handling without a live Google account. */
interface GoogleAuthorizationGateway {
    val configured: Boolean
    val configuration: OAuthConfiguration
    suspend fun authorize(email: String?, selectAccount: Boolean = false): GoogleAuthorizationStep
    fun complete(intent: Intent): Authorization
    suspend fun clearCachedToken(token: String)
}
