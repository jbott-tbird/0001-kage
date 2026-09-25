package org.foxred.kage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.foxred.kage.ui.theme.DesignTokens as T

@Composable
fun MailIconButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
}

fun folderIcon(role: String): ImageVector =
    when (role) {
        "inbox" -> Icons.Outlined.Inbox
        "drafts" -> Icons.Outlined.Drafts
        "sent" -> Icons.AutoMirrored.Outlined.Send
        "archive" -> Icons.Outlined.Archive
        "spam" -> Icons.Outlined.Report
        "trash" -> Icons.Outlined.Delete
        else -> Icons.Outlined.Folder
    }

@Composable
fun Avatar(name: String) {
    Box(
        Modifier.size(T.avatar).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
