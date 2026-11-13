package pl.livetranscribe.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import pl.livetranscribe.app.R
import pl.livetranscribe.app.data.SessionEntity
import pl.livetranscribe.app.data.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LightColors = lightColorScheme(
    primary = Color(0xFF2456E6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001A5C),
    secondary = Color(0xFF0B8F7E),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9ECF5),
    error = Color(0xFFD93025),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF00256E),
    primaryContainer = Color(0xFF16387F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF5FD6C4),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    surfaceVariant = Color(0xFF232733),
    error = Color(0xFFFF8A80),
)

@Composable
fun LiveTranscribeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

// ---------- formatowanie ----------

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDate(ts: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL")).format(Date(ts))

fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1) "%.1f GB".format(Locale("pl", "PL"), gb)
    else "%.0f MB".format(Locale("pl", "PL"), bytes / (1024.0 * 1024.0))
}

fun statusLabel(status: String): String = when (status) {
    SessionStatus.RECORDING -> "Nagrywanie"
    SessionStatus.PAUSED -> "Pauza"
    SessionStatus.PROCESSING -> "Przetwarzanie"
    SessionStatus.IMPORTING -> "Wczytywanie"
    else -> "Gotowa"
}

// ---------- wspólne elementy ----------

@Composable
fun Logo(size: Dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

@Composable
fun BottomBar(current: String, onSelect: (String) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = current == "home",
            onClick = { if (current != "home") onSelect("home") },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Start") },
        )
        NavigationBarItem(
            selected = current == "history",
            onClick = { if (current != "history") onSelect("history") },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Historia") },
        )
        NavigationBarItem(
            selected = current == "settings",
            onClick = { if (current != "settings") onSelect("settings") },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Ustawienia") },
        )
    }
}

@Composable
fun StatusChip(status: String) {
    val done = status == SessionStatus.DONE
    val bg = if (done) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
    val fg = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
    Text(
        statusLabel(status),
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun SessionRow(session: SessionEntity, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatDate(session.createdAt)}  ·  ${formatDuration(session.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    StatusChip(session.status)
                }
            }
            if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    suffix: String? = null,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                suffix = if (suffix != null) { { Text(suffix) } } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
fun InfoBanner(text: String, error: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
