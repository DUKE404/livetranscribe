package pl.livetranscribe.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.livetranscribe.app.R
import pl.livetranscribe.app.data.SessionEntity
import pl.livetranscribe.app.service.RecStatus

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onStart: () -> Unit,
    onOpen: (Long) -> Unit,
    onTab: (String) -> Unit,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val free = remember(sessions.size) { vm.freeSpace() }
    val active = rec.status != RecStatus.IDLE

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Logo(36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("LiveTranscribe", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { onTab("settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ustawienia")
                    }
                },
            )
        },
        bottomBar = { BottomBar("home", onTab) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Zamień mowę na tekst", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Spotkania, wykłady i rozmowy – na żywo, także przy wygaszonym ekranie.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (active) "WRÓĆ DO TRANSKRYPCJI" else "ROZPOCZNIJ TRANSKRYPCJĘ",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            if (settings.engine == "local") "Tryb lokalny: dźwięk nie opuszcza telefonu"
                            else "Tryb chmurowy: dźwięk jest wysyłany do ${settings.host}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Wolne miejsce w telefonie: ${formatBytes(free)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ostatnie transkrypcje", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (sessions.size > 5) TextButton(onClick = { onTab("history") }) { Text("Wszystkie") }
                }
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "Nie masz jeszcze żadnych transkrypcji. Dotknij dużego przycisku, aby zacząć.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sessions.take(5), key = { it.id }) { s ->
                SessionRow(s, onClick = { onOpen(s.id) })
            }
        }
    }
}

@Composable
fun HistoryScreen(
    vm: MainViewModel,
    onOpen: (id: Long, pdf: Boolean, edit: Boolean) -> Unit,
    onTab: (String) -> Unit,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<SessionEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Historia", fontWeight = FontWeight.Bold) }) },
        bottomBar = { BottomBar("history", onTab) },
    ) { pad ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Historia jest pusta.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.id }) { s ->
                    val live = rec.sessionId == s.id
                    SessionRow(s, onClick = { onOpen(s.id, false, false) }) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Więcej")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Otwórz") }, onClick = { menu = false; onOpen(s.id, false, false) })
                                DropdownMenuItem(text = { Text("Zmień nazwę") }, onClick = { menu = false; renameTarget = s })
                                DropdownMenuItem(text = { Text("Edytuj tekst") }, enabled = !live, onClick = { menu = false; onOpen(s.id, false, true) })
                                DropdownMenuItem(text = { Text("Generuj PDF") }, enabled = !live, onClick = { menu = false; onOpen(s.id, true, false) })
                                DropdownMenuItem(text = { Text("Usuń") }, enabled = !live, onClick = { menu = false; deleteTarget = s })
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { s ->
        TextInputDialog(
            title = "Zmień nazwę",
            initial = s.title,
            confirm = "Zapisz",
            onDismiss = { renameTarget = null },
        ) { name ->
            vm.rename(s.id, name)
            renameTarget = null
        }
    }
    deleteTarget?.let { s ->
        ConfirmDialog(
            title = "Usunąć dokument?",
            text = "„${s.title}” zostanie trwale usunięty z aplikacji.",
            confirm = "Usuń",
            destructive = true,
            onDismiss = { deleteTarget = null },
        ) {
            vm.delete(s.id)
            deleteTarget = null
        }
    }
}
