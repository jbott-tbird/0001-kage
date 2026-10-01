// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.account.auth

import android.util.Patterns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.foxred.kage.core.account.Account
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.ConnectionSecurity
import org.foxred.kage.core.account.EmailAddress
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.data.setup.ServerSuggestions
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

/** App-password setup. Validation makes no account or credential changes until both servers pass. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealSetupScreen(vm: MailViewModel, close: () -> Unit, finish: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var incomingHost by rememberSaveable { mutableStateOf("") }
    var outgoingHost by rememberSaveable { mutableStateOf("") }
    var incomingPort by rememberSaveable { mutableStateOf("993") }
    var outgoingPort by rememberSaveable { mutableStateOf("465") }
    var incomingTls by rememberSaveable { mutableStateOf(true) }
    var outgoingTls by rememberSaveable { mutableStateOf(true) }
    var incomingUser by rememberSaveable { mutableStateOf("") }
    var outgoingUser by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var discovering by remember { mutableStateOf(false) }

    fun updateEmail(value: String) {
        email = value
        if (!manual) {
            val suggestion = ServerSuggestions.gmail(value)
            incomingHost = suggestion?.first?.hostname.orEmpty()
            outgoingHost = suggestion?.second?.hostname.orEmpty()
            incomingPort = suggestion?.first?.port?.toString() ?: "993"
            outgoingPort = suggestion?.second?.port?.toString() ?: "465"
            incomingUser = value.trim()
            outgoingUser = value.trim()
        }
    }

    LaunchedEffect(email, manual) {
        discovering = false
        if (manual || !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() ||
            ServerSuggestions.gmail(email) != null) return@LaunchedEffect
        delay(600)
        discovering = true
        try {
            vm.realAccountSetup?.suggest(email)?.let { (imap, smtp) ->
                incomingHost = imap.hostname
                incomingPort = imap.port.toString()
                incomingUser = imap.username
                incomingTls = imap.security == ConnectionSecurity.TLS
                outgoingHost = smtp.hostname
                outgoingPort = smtp.port.toString()
                outgoingUser = smtp.username
                outgoingTls = smtp.security == ConnectionSecurity.TLS
            }
        } finally {
            discovering = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Connect a real account") }, navigationIcon = {
            MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, close)
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(T.xl),
            verticalArrangement = Arrangement.spacedBy(T.lg),
        ) {
            Text("Sign in with an app password", style = MaterialTheme.typography.headlineSmall)
            Text("Gmail settings fill automatically. For another provider, enter its IMAP and SMTP settings manually.")
            OutlinedTextField(email, ::updateEmail, label = { Text("Email address") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("App password") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { manual = !manual }) {
                Text(if (manual) "Use suggested settings" else "Edit server settings")
            }
            if (discovering) Text("Looking up provider settings…", style = MaterialTheme.typography.bodySmall)
            if (manual || ServerSuggestions.gmail(email) == null) {
                ServerEntry("Incoming IMAP", incomingHost, { incomingHost = it; manual = true }, incomingPort,
                    { incomingPort = it; manual = true }, incomingUser, { incomingUser = it; manual = true }, incomingTls,
                    { incomingTls = it; manual = true })
                ServerEntry("Outgoing SMTP", outgoingHost, { outgoingHost = it; manual = true }, outgoingPort,
                    { outgoingPort = it; manual = true }, outgoingUser, { outgoingUser = it; manual = true }, outgoingTls,
                    { outgoingTls = it; manual = true })
            } else {
                Text("IMAP: $incomingHost:$incomingPort · SMTP: $outgoingHost:$outgoingPort",
                    style = MaterialTheme.typography.bodySmall)
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val valid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() &&
                password.isNotBlank() && incomingHost.isNotBlank() && outgoingHost.isNotBlank() &&
                incomingUser.isNotBlank() && outgoingUser.isNotBlank() &&
                incomingPort.toIntOrNull() in 1..65535 && outgoingPort.toIntOrNull() in 1..65535
            Button(onClick = {
                saving = true
                failure = null
                scope.launch {
                    try {
                        val address = email.trim().lowercase()
                        check(vm.mailbox.value.accounts.none { it.address.equals(address, true) }) {
                            "This account already exists."
                        }
                        val secret = Authorization(password)
                        val account = Account(
                            UUID.randomUUID().toString(), address.substringBefore('@'),
                            listOf(EmailAddress(address)),
                            Server(incomingHost.trim(), incomingPort.toInt(), ServerProtocol.IMAP,
                                if (incomingTls) ConnectionSecurity.TLS else ConnectionSecurity.STARTTLS,
                                incomingUser.trim()),
                            Server(outgoingHost.trim(), outgoingPort.toInt(), ServerProtocol.SMTP,
                                if (outgoingTls) ConnectionSecurity.TLS else ConnectionSecurity.STARTTLS,
                                outgoingUser.trim()),
                        )
                        val inboxId = checkNotNull(vm.realAccountSetup?.add(account, secret, secret)) {
                            "Real account setup is unavailable"
                        }
                        vm.repository.updatePreferences(
                            vm.repository.mailbox.first().preferences.copy(selectedFolder = inboxId, started = true)
                        )
                        password = ""
                        withContext(Dispatchers.Main.immediate) { finish() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = error.message ?: "Could not connect. Check the server settings and try again."
                    } finally {
                        saving = false
                    }
                }
            }, enabled = valid && !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "Checking IMAP and SMTP…" else "Connect account")
            }
        }
    }
}

@Composable
private fun ServerEntry(
    title: String,
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    tls: Boolean, onTls: (Boolean) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(host, onHost, label = { Text("$title server") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, onPort, label = { Text("Port") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(username, onUsername, label = { Text("Username") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(T.lg)) {
        FilterChip(tls, onClick = { onTls(true) }, label = { Text("SSL/TLS") })
        FilterChip(!tls, onClick = { onTls(false) }, label = { Text("STARTTLS") })
    }
}
