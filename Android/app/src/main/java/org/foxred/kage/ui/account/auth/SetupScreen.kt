package org.foxred.kage.ui.account.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import org.foxred.kage.domain.model.Account
import org.foxred.kage.ui.MailViewModel
import org.foxred.kage.ui.shared.MailIconButton
import org.foxred.kage.ui.theme.DesignTokens as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: MailViewModel, close: () -> Unit, finish: () -> Unit, connectReal: () -> Unit) {
    val mail by vm.mailbox.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var email by rememberSaveable { mutableStateOf("") }
    // Credentials are intentionally kept out of saved state and the database.
    var password by remember { mutableStateOf("sample-password") }
    var reveal by remember { mutableStateOf(false) }
    var incoming by rememberSaveable { mutableStateOf("imap.example.com") }
    var outgoing by rememberSaveable { mutableStateOf("smtp.example.com") }
    var incomingPort by rememberSaveable { mutableStateOf("993") }
    var outgoingPort by rememberSaveable { mutableStateOf("465") }
    var security by rememberSaveable { mutableStateOf("SSL/TLS") }
    var requireAuth by rememberSaveable { mutableStateOf(true) }
    var outgoingSecurity by rememberSaveable { mutableStateOf("SSL/TLS") }
    var saving by remember { mutableStateOf(false) }
    val titles = listOf("Account information", "Account type", "Server settings", "Setup complete")
    androidx.activity.compose.BackHandler(step in 1..2) { step-- }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[step]) },
                navigationIcon = {
                    MailIconButton("Back", Icons.AutoMirrored.Outlined.ArrowBack) {
                        if (step in 1..2) step-- else close()
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(T.xl),
            verticalArrangement = Arrangement.spacedBy(T.lg),
        ) {
            LinearProgressIndicator(
                progress = { (step + 1) / 4f },
                modifier = Modifier.fillMaxWidth(),
            )
            when (step) {
                0 -> {
                    Text("Set up a demo account", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = connectReal) { Text("Connect a real account instead") }
                    OutlinedTextField(
                        email,
                        { email = it },
                        label = { Text("Email address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Password") },
                        visualTransformation =
                            if (reveal) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            MailIconButton("Show or hide password", Icons.Outlined.Visibility) {
                                reveal = !reveal
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "The demo password is prefilled. No server connection is made and your password is never stored.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { step++ },
                        enabled =
                            android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() &&
                                password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Next")
                    }
                }
                1 -> {
                    ListItem(
                        headlineContent = { Text("IMAP / SMTP") },
                        supportingContent = { Text("Receive and send email with your provider") },
                        leadingContent = { RadioButton(true, onClick = {}) },
                    )
                    ListItem(
                        headlineContent = { Text("JMAP") },
                        supportingContent = { Text("Planned for v2.0") },
                        leadingContent = { RadioButton(false, onClick = null, enabled = false) },
                    )
                    Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) {
                        Text("Next")
                    }
                }
                2 -> {
                    ServerFields(
                        "Incoming · IMAP",
                        incoming,
                        { incoming = it },
                        incomingPort,
                        { incomingPort = it },
                        security,
                        { security = it },
                    )
                    ServerFields(
                        "Outgoing · SMTP",
                        outgoing,
                        { outgoing = it },
                        outgoingPort,
                        { outgoingPort = it },
                        outgoingSecurity,
                        { outgoingSecurity = it },
                    )
                    ListItem(
                        headlineContent = { Text("Require SMTP authentication") },
                        trailingContent = {
                            Switch(requireAuth, onCheckedChange = { requireAuth = it })
                        },
                    )
                    Button(
                        onClick = {
                            if (mail.accounts.any { it.address.equals(email.trim(), true) }) {
                                vm.error.value =
                                    "This account already exists. Go back and change the email address."
                            } else {
                                saving = true
                                vm.action {
                                    try {
                                        vm.repository.addAccount(
                                            Account(
                                                UUID.randomUUID().toString(),
                                                email.substringBefore('@'),
                                                email.trim(),
                                                incoming,
                                                outgoing,
                                                incomingPort.toIntOrNull() ?: 0,
                                                outgoingPort.toIntOrNull() ?: 0,
                                                security,
                                                outgoingSecurity,
                                                requireAuth,
                                            )
                                        )
                                        password = ""
                                        step = 3
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                }
                3 -> {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        Modifier.size(T.touchTarget),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("You’re all set.", style = MaterialTheme.typography.headlineLarge)
                    Text(email)
                    Text("Your demo mailbox is ready with sample messages and folders.")
                    Button(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
                }
            }
        }
    }
}

@Composable
private fun ServerFields(
    title: String,
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    security: String,
    onSecurity: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        host,
        onHost,
        label = { Text("$title server") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        port,
        onPort,
        label = { Text("$title port") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
    )
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Security: $security")
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            listOf("SSL/TLS", "STARTTLS", "None").forEach { value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        onSecurity(value)
                        expanded = false
                    },
                )
            }
        }
    }
}
