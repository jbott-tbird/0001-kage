package org.foxred.kage.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.foxred.kage.R
import org.foxred.kage.ui.theme.DesignTokens as T

@Composable
fun WelcomeScreen(setupDemo: () -> Unit, connectReal: () -> Unit, explore: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
            Color(0xFFE0F2FF), Color(0xFFF6FBFF), Color(0xFFE8F1FF))))
            .safeDrawingPadding().padding(T.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Image(painterResource(R.drawable.thunderbird_logo), "Thunderbird",
            Modifier.size(180.dp))
        Spacer(Modifier.height(T.lg))
        Text("An open source, privacy focused and ad-free email app.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Button(onClick = connectReal, shape = MaterialTheme.shapes.small,
            modifier = Modifier.width(160.dp).height(52.dp)) {
            Text("Get started")
        }
        Spacer(Modifier.weight(0.55f))
        TextButton(onClick = setupDemo) { Text("Set up a demo account") }
        TextButton(onClick = explore) { Text("Explore the demo inbox") }
        Text("The demo uses local sample mail; connected accounts use real mail.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
