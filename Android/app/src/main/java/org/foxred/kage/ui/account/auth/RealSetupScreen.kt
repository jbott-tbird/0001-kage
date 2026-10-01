// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui.account.auth

import android.app.Activity
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import org.foxred.kage.core.account.Account
import org.foxred.kage.core.account.Authorization
import org.foxred.kage.core.account.AuthenticationType
import org.foxred.kage.core.account.ConnectionSecurity
import org.foxred.kage.core.account.EmailAddress
import org.foxred.kage.core.account.Server
import org.foxred.kage.core.account.ServerProtocol
import org.foxred.kage.data.setup.ServerSuggestions
import org.foxred.kage.data.security.GoogleAuthorizationStep
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

private enum class SetupStep { Account, ManualCredentials, Protocol, Servers, Complete }

/** Real-account onboarding; credentials are saved only after IMAP and SMTP validate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealSetupScreen(vm: MailViewModel, close: () -> Unit, finish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
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
    var googleFailure by remember { mutableStateOf<String?>(null) }
    var passwordFailure by remember { mutableStateOf<String?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var googlePendingAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var step by rememberSaveable { mutableStateOf(SetupStep.Account) }
    var connectedAddress by rememberSaveable { mutableStateOf("") }

    suspend fun saveGoogle(address: String, grant: Authorization) {
        check(vm.mailbox.value.accounts.none { it.address.equals(address, true) }) {
            "This account already exists. Reauthorize it from Settings."
        }
        val (imap, smtp) = ServerSuggestions.google(address)
        val client = checkNotNull(vm.googleAuthorization)
        val account = Account(
            UUID.randomUUID().toString(), address.substringBefore('@'),
            listOf(EmailAddress(address)),
            imap.copy(authenticationType = AuthenticationType.OAUTH2),
            smtp.copy(authenticationType = AuthenticationType.OAUTH2),
            authConfig = client.configuration,
        )
        val inboxId = try {
            checkNotNull(vm.realAccountSetup?.add(account, grant, grant)) {
                "Real account setup is unavailable"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A wrong selected Google account can pass consent but fail IMAP. Let the
            // next chooser request a fresh access token instead of reusing its cache.
            try {
                withTimeout(5_000) { client.clearCachedToken(grant.secret) }
            } catch (_: TimeoutCancellationException) {
                // Keep the server validation failure visible if cache clearing stalls.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the server validation failure visible if cache clearing fails.
            }
            throw failure
        }
        vm.repository.updatePreferences(
            vm.repository.mailbox.first().preferences.copy(selectedFolder = inboxId, started = true)
        )
        connectedAddress = address
        step = SetupStep.Complete
    }

    val googleConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val address = googlePendingAddress
        googlePendingAddress = null
        if (result.resultCode != Activity.RESULT_OK || result.data == null || address == null) {
            saving = false
            googleFailure = if (result.resultCode != Activity.RESULT_OK)
                "Google sign-in was canceled" else "Google sign-in did not return a result. Try again."
        } else {
            scope.launch {
                try {
                    val grant = checkNotNull(vm.googleAuthorization).complete(result.data!!)
                    saveGoogle(address, grant)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    googleFailure = error.message ?: "Could not connect this Google account"
                } finally {
                    saving = false
                }
            }
        }
    }

    fun updateEmail(value: String) {
        email = value
        googleFailure = null
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

    fun back() {
        if (saving) return
        step = when (step) {
            SetupStep.Account -> { close(); return }
            SetupStep.ManualCredentials -> SetupStep.Account
            SetupStep.Protocol -> SetupStep.ManualCredentials
            SetupStep.Servers -> SetupStep.Protocol
            SetupStep.Complete -> { finish(); return }
        }
    }
    BackHandler(step != SetupStep.Account) { back() }

    val validEmail = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val validManual = validEmail && password.isNotBlank() && incomingHost.isNotBlank() &&
        outgoingHost.isNotBlank() && incomingUser.isNotBlank() && outgoingUser.isNotBlank() &&
        incomingPort.toIntOrNull() in 1..65535 && outgoingPort.toIntOrNull() in 1..65535
    val title = when (step) {
        SetupStep.Account, SetupStep.ManualCredentials -> "Account information"
        SetupStep.Protocol -> "Choose email account type"
        SetupStep.Servers -> "Manual account setup"
        SetupStep.Complete -> "Setup complete"
    }

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(
        listOf(Color(0xFFE1F2FF), Color(0xFFF2F7FF), Color(0xFFE7F1FF))))) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                        navigationIcon = {
                            MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack, ::back)
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface),
                    )
                },
                bottomBar = {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = T.xl, vertical = T.lg),
                        verticalArrangement = Arrangement.spacedBy(T.sm),
                    ) {
                        when (step) {
                            SetupStep.Account -> {
                                if (vm.googleAuthorization?.configured != true) {
                                    Text("Google sign-in needs an Android OAuth client ID configured for this build.",
                                        color = MaterialTheme.colorScheme.error)
                                }
                                googleFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button(
                                    onClick = {
                                        val address = email.trim().lowercase()
                                        saving = true
                                        googleFailure = null
                                        keyboard?.hide()
                                        scope.launch {
                                            try {
                                                check(vm.mailbox.value.accounts.none {
                                                    it.address.equals(address, true)
                                                }) {
                                                    "This account already exists. Reauthorize it from Settings."
                                                }
                                                when (val authorization = checkNotNull(vm.googleAuthorization)
                                                    .authorize(address, selectAccount = true)) {
                                                    is GoogleAuthorizationStep.Granted ->
                                                        saveGoogle(address, authorization.credential)
                                                    is GoogleAuthorizationStep.Consent -> {
                                                        googlePendingAddress = address
                                                        googleConsent.launch(IntentSenderRequest.Builder(
                                                            authorization.pendingIntent.intentSender).build())
                                                    }
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                googleFailure = error.message ?: "Could not start Google sign-in"
                                            } finally {
                                                if (googlePendingAddress == null) saving = false
                                            }
                                        }
                                    },
                                    enabled = validEmail && !saving && vm.googleAuthorization?.configured == true,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().height(56.dp),
                                ) { Text(if (saving) "Connecting…" else "Continue with Google") }
                                TextButton(onClick = { step = SetupStep.ManualCredentials },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                    Text("Set up with an app password")
                                }
                            }
                            SetupStep.ManualCredentials -> {
                                Button(onClick = { keyboard?.hide(); step = SetupStep.Protocol },
                                    enabled = validEmail && password.isNotBlank(),
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Next") }
                            }
                            SetupStep.Protocol -> {
                                Button(onClick = { step = SetupStep.Servers },
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Next") }
                            }
                            SetupStep.Servers -> {
                                passwordFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button(onClick = {
                                    saving = true
                                    passwordFailure = null
                                    keyboard?.hide()
                                    scope.launch {
                                        try {
                                            val address = email.trim().lowercase()
                                            check(vm.mailbox.value.accounts.none {
                                                it.address.equals(address, true)
                                            }) { "This account already exists." }
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
                                                vm.repository.mailbox.first().preferences.copy(
                                                    selectedFolder = inboxId, started = true)
                                            )
                                            password = ""
                                            connectedAddress = address
                                            step = SetupStep.Complete
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            passwordFailure = error.message ?:
                                                "Could not connect. Check the server settings and try again."
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }, enabled = validManual && !saving,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Text(if (saving) "Checking IMAP and SMTP…" else "Connect account")
                                }
                            }
                            SetupStep.Complete -> {
                                Button(onClick = finish, shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                    Text("Finish")
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = T.xl, vertical = T.lg),
                    verticalArrangement = Arrangement.spacedBy(T.lg),
                ) {
                    when (step) {
                        SetupStep.Account -> {
                            Spacer(Modifier.height(T.xxl))
                            OnboardingField(email, ::updateEmail, "Email address",
                                "your.email@example.com", KeyboardType.Email)
                            Text("Choose your Google account to grant mail access.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SetupStep.ManualCredentials -> {
                            Spacer(Modifier.height(T.xxl))
                            OnboardingField(email, ::updateEmail, "Email address",
                                "your.email@example.com", KeyboardType.Email)
                            OnboardingField(password, { password = it }, "App password",
                                "Enter your app password", secret = true)
                            Text("Use an app password from your email provider. Your account is checked before it is saved.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SetupStep.Protocol -> {
                            Spacer(Modifier.height(T.xxl))
                            Text("Protocol", style = MaterialTheme.typography.titleLarge)
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(T.xl),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("IMAP", style = MaterialTheme.typography.titleLarge)
                                        Text("Internet Message Access Protocol")
                                    }
                                    RadioButton(selected = true, onClick = null)
                                }
                            }
                            Text("IMAP keeps mail synchronized across your devices.",
                                style = MaterialTheme.typography.bodyMedium)
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Row(Modifier.fillMaxWidth().padding(T.xl),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("JMAP", style = MaterialTheme.typography.titleLarge)
                                        Text("Not available yet")
                                    }
                                    RadioButton(selected = false, onClick = null, enabled = false)
                                }
                            }
                        }
                        SetupStep.Servers -> {
                            if (discovering) Text("Looking up provider settings…")
                            ServerEntry("Incoming IMAP", incomingHost, { incomingHost = it; manual = true },
                                incomingPort, { incomingPort = it; manual = true }, incomingUser,
                                { incomingUser = it; manual = true }, incomingTls,
                                { incomingTls = it; manual = true })
                            HorizontalDivider()
                            ServerEntry("Outgoing SMTP", outgoingHost, { outgoingHost = it; manual = true },
                                outgoingPort, { outgoingPort = it; manual = true }, outgoingUser,
                                { outgoingUser = it; manual = true }, outgoingTls,
                                { outgoingTls = it; manual = true })
                            Text("Contact your email provider if you need the server addresses or ports.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SetupStep.Complete -> {
                            Spacer(Modifier.height(120.dp))
                            Text("Account connected", style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.align(Alignment.CenterHorizontally))
                            Text(connectedAddress, modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ServerEntry(
    title: String,
    host: String, onHost: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    tls: Boolean, onTls: (Boolean) -> Unit,
) {
    Text(if (title == "Incoming IMAP") "Incoming Mail Server" else "Outgoing Mail Server",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OnboardingField(host, onHost, "$title server", "server.example.com")
    OnboardingField(port, onPort, "Port", "993", KeyboardType.Number)
    OnboardingField(username, onUsername, "Username", "your.email@example.com")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Use SSL/TLS", modifier = Modifier.weight(1f))
        Switch(checked = tls, onCheckedChange = onTls)
    }
}
