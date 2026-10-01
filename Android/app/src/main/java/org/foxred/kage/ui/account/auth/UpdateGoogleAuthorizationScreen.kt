// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.account.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.data.security.GoogleAuthorizationStep
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

/** Checks both servers before replacing a Google grant; the existing cache and Outbox stay put. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateGoogleAuthorizationScreen(vm: MailViewModel, accountId: String, back: () -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val account = mail.accounts.firstOrNull { it.id == accountId }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    suspend fun save(grant: Authorization) {
        val setup = checkNotNull(vm.realAccountSetup) { "Real account setup is unavailable" }
        val google = checkNotNull(vm.googleAuthorization)
        try {
            setup.replaceAuthorization(accountId, grant, grant, google.configuration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            try {
                withTimeout(5_000) { google.clearCachedToken(grant.secret) }
            } catch (_: TimeoutCancellationException) {
                // Keep the account's original grant and show the verification failure.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the account's original grant and show the verification failure.
            }
            throw failure
        }
        vm.notice.value = "Google authorization updated. Cached mail is still available."
        back()
    }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            saving = false
            if (result.resultCode != Activity.RESULT_OK) failure = "Google sign-in was canceled"
        } else scope.launch {
            try {
                save(checkNotNull(vm.googleAuthorization).complete(result.data!!))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.message ?: "Could not update Google authorization"
            } finally {
                saving = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Google authorization") }, navigationIcon = {
            MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, back)
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(T.xl),
            verticalArrangement = Arrangement.spacedBy(T.lg)) {
            if (account == null || account.mode != "REAL" ||
                account.incoming != "imap.gmail.com" || account.outgoing != "smtp.gmail.com") {
                Text("This account cannot use Google sign-in here.")
                TextButton(onClick = back) { Text("Back to Settings") }
            } else {
                Text(account.address, style = MaterialTheme.typography.titleLarge)
                Text("Choose this Google account again. Kage checks IMAP and SMTP before replacing its saved authorization.")
                if (mail.preferences.offline)
                    Text("Turn off Offline preview and connect to the mail server first.")
                if (vm.googleAuthorization?.configured != true)
                    Text("Google sign-in needs an Android OAuth client ID configured for this build.")
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    saving = true
                    failure = null
                    scope.launch {
                        var pendingConsent = false
                        try {
                            val login = checkNotNull(vm.realAccountSetup)
                                .googleAccountName(accountId)
                            when (val step = checkNotNull(vm.googleAuthorization)
                                .authorize(login)) {
                                is GoogleAuthorizationStep.Granted -> save(step.credential)
                                is GoogleAuthorizationStep.Consent -> {
                                    pendingConsent = true
                                    consent.launch(IntentSenderRequest.Builder(
                                        step.pendingIntent.intentSender).build())
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = error.message ?: "Could not start Google sign-in"
                        } finally {
                            if (!pendingConsent) saving = false
                        }
                    }
                }, enabled = !saving && !mail.preferences.offline &&
                    vm.googleAuthorization?.configured == true,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (saving) "Checking IMAP and SMTP…" else "Continue with Google")
                }
            }
        }
    }
}
