// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.account.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

/** Password stays in composition memory and is never placed in saved navigation state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateAppPasswordScreen(vm: MailViewModel, accountId: String, back: () -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    val account = mail.accounts.firstOrNull { it.id == accountId }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Update app password") }, navigationIcon = {
            MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, back)
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(T.xl),
            verticalArrangement = Arrangement.spacedBy(T.lg)) {
            if (account == null || account.mode != "REAL" || account.usesOAuth) {
                Text("This account cannot use an app password here.")
                TextButton(onClick = back) { Text("Back to Settings") }
            } else {
                Text(account.address, style = MaterialTheme.typography.titleLarge)
                Text("Kage checks IMAP and SMTP before replacing the saved password. Cached mail, drafts, and Outbox messages stay with this account.")
                OutlinedTextField(password, { password = it }, label = { Text("New app password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions.Default,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth())
                if (mail.preferences.offline)
                    Text("Turn off Offline preview and connect to the mail server first.")
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val replacement = password
                    saving = true
                    failure = null
                    scope.launch {
                        try {
                            val setup = checkNotNull(vm.realAccountSetup) {
                                "Real account setup is unavailable"
                            }
                            val credential = Authorization(replacement)
                            setup.replaceAuthorization(accountId, credential, credential)
                            password = ""
                            vm.notice.value = "App password updated. Cached mail is still available."
                            back()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = error.message ?: "Could not update the app password. Try again."
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = password.isNotBlank() && !saving && !mail.preferences.offline,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (saving) "Checking IMAP and SMTP…" else "Save app password")
                }
            }
        }
    }
}
