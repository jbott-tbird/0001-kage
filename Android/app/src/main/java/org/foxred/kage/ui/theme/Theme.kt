package org.foxred.kage.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun KageTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DesignTokens.dark else DesignTokens.light,
        typography = DesignTokens.typography,
        shapes = DesignTokens.shapes,
        content = content,
    )
}
