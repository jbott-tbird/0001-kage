package org.foxred.kage.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import org.foxred.kage.R
import org.foxred.kage.ui.theme.DesignTokens as T

@Composable
fun WelcomeScreen(setup: () -> Unit, connectReal: () -> Unit, explore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(T.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(T.xl),
        ) {
            Spacer(Modifier.weight(1f))
            Image(
                painterResource(R.drawable.thunderbird_logo),
                "Thunderbird",
                Modifier.size(T.logo),
            )
            Text("A calmer home for your email", style = MaterialTheme.typography.headlineMedium)
            Text(
                "An open source, privacy focused email experience.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = setup, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
            OutlinedButton(onClick = connectReal, modifier = Modifier.fillMaxWidth()) {
                Text("Connect a real account")
            }
            TextButton(onClick = explore) { Text("Explore the demo inbox") }
            Text("Local demo · no email is sent", style = MaterialTheme.typography.bodySmall)
        }
    }
}
