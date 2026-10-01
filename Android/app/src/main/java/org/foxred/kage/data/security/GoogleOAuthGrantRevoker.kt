// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.security

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import org.foxred.kage.core.account.FailureKind
import org.foxred.kage.core.account.MailFailure

fun interface OAuthGrantRevoker {
    fun revoke(token: String)
}

/** Google's endpoint accepts either a refresh token or a current access token. */
class GoogleOAuthGrantRevoker(
    private val connection: () -> HttpsURLConnection = {
        ENDPOINT.toURL().openConnection() as HttpsURLConnection
    },
) : OAuthGrantRevoker {
    override fun revoke(token: String) {
        require(token.isNotBlank()) { "Google authorization must be renewed before revocation" }
        val request = try {
            connection()
        } catch (_: IOException) {
            throw unavailable()
        }
        try {
            request.requestMethod = "POST"
            request.instanceFollowRedirects = false
            request.connectTimeout = 15_000
            request.readTimeout = 15_000
            request.doOutput = true
            request.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val form = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name())
            request.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            when (request.responseCode) {
                200 -> return
                400 -> {
                    // An already invalid grant has the same result as a successful revoke.
                    val body = request.errorStream?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 4096) return@use null
                            output.write(buffer, 0, count)
                        }
                        output.toString(StandardCharsets.UTF_8.name())
                    }
                    if (body != null && INVALID_TOKEN.containsMatchIn(body))
                        return
                    throw rejected()
                }
                else -> throw unavailable()
            }
        } catch (failure: MailFailure) {
            throw failure
        } catch (_: IOException) {
            throw unavailable()
        } catch (_: Exception) {
            throw rejected()
        } finally {
            request.disconnect()
        }
    }

    private fun unavailable() = MailFailure(FailureKind.CONNECTION,
        "Could not revoke Google access; connect to the internet and try again")

    private fun rejected() = MailFailure(FailureKind.AUTHENTICATION,
        "Google could not revoke this authorization; remove it from your Google Account settings")

    private companion object {
        val ENDPOINT = URI("https://oauth2.googleapis.com/revoke")
        val INVALID_TOKEN = Regex("\"error\"\\s*:\\s*\"invalid_token\"")
    }
}
