// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.security

import android.accounts.Account as AndroidAccount
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Task
import java.net.URI
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.foxred.kage.BuildConfig
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.OAuthConfiguration

private const val GOOGLE_MAIL_SCOPE = "https://mail.google.com/"

internal fun googleReportedExpirySeconds(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

/** Leave a minute for connection setup, but never outlive a reported short token. */
internal fun googleAccessLifetimeSeconds(reportedSeconds: Long?): Long = when {
    reportedSeconds == null -> 3_000L
    reportedSeconds <= 60L -> 1L
    else -> minOf(reportedSeconds - 60L, 3_000L)
}

/** Google Play services owns consent and token caching; Kage stores only its short-lived access token. */
class GooglePlayAuthorization(context: Context) : GoogleAuthorizationGateway {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    override val configured: Boolean get() = BuildConfig.GOOGLE_ANDROID_CLIENT_ID.isNotBlank()

    override val configuration: OAuthConfiguration get() = OAuthConfiguration(
        BuildConfig.GOOGLE_ANDROID_CLIENT_ID,
        URI("https://accounts.google.com/o/oauth2/v2/auth"),
        URI("https://oauth2.googleapis.com/token"),
        URI("org.foxred.kage:/oauth/google"),
        listOf(GOOGLE_MAIL_SCOPE),
    )

    override suspend fun authorize(email: String?, selectAccount: Boolean): GoogleAuthorizationStep {
        check(configured) { "Google sign-in needs the Android OAuth client ID in the build configuration" }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GOOGLE_MAIL_SCOPE)))
            .apply {
                if (selectAccount) setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
                else if (!email.isNullOrBlank())
                    setAccount(AndroidAccount(email, "com.google"))
            }
            .build()
        val result = try {
            client.authorize(request).awaitResult()
        } catch (error: ApiException) {
            if (error.statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
                throw IllegalStateException(
                    "Google sign-in is unavailable for this device or build. Update Google Play services and check the Android OAuth client package name and signing certificate.",
                    error,
                )
            }
            throw error
        }
        return if (result.hasResolution())
            GoogleAuthorizationStep.Consent(checkNotNull(result.pendingIntent))
        else GoogleAuthorizationStep.Granted(grant(result))
    }

    override fun complete(intent: Intent): Authorization =
        grant(client.getAuthorizationResultFromIntent(intent))

    override suspend fun clearCachedToken(token: String) {
        if (token.isNotBlank())
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).awaitResult()
    }

    private fun grant(result: AuthorizationResult): Authorization {
        check(GOOGLE_MAIL_SCOPE in result.grantedScopes) {
            "Google Mail access was not granted"
        }
        val token = result.accessToken?.takeIf(String::isNotBlank)
            ?: error("Google did not return an access token")
        // Access tokens normally live for an hour. Renew sooner; Play services supplies
        // the next token from its own cache or asks the user to sign in again.
        val reportedSeconds = googleReportedExpirySeconds(
            result.tokenResponseParams?.get("expires_in"))
        val lifetime = googleAccessLifetimeSeconds(reportedSeconds)
        return Authorization(token, Authorization.Kind.OAUTH2, Instant.now().plusSeconds(lifetime))
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isCanceled) {
            continuation.cancel()
            return@addOnCompleteListener
        }
        val failure = task.exception
        if (failure != null) continuation.resumeWithException(failure)
        else continuation.resume(task.result)
    }
}
