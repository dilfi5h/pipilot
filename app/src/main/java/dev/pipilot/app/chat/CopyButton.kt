package dev.pipilot.app.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Per-item copy button shared by message bubbles, tool cards, code blocks and formula blocks.
 * Wrapped in DisableSelection so tapping it does not start a text selection in the parent bubble.
 */
@Composable
internal fun CopyItemButton(
    text: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String = "Copy",
) {
    val clipboard = LocalClipboardManager.current
    DisableSelection {
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(text)) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = contentDescription,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
        }
    }
}
