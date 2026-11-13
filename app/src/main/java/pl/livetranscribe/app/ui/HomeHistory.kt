package pl.livetranscribe.app.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.livetranscribe.app.R
import pl.livetranscribe.app.data.SessionEntity
import pl.livetranscribe.app.data.SessionStatus
import pl.livetranscribe.app.service.AudioImport
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
    val context = LocalContext.current
    val modelInstalled by vm.modelInstalled.collectAsStateWithLifecycle()
    var showConsent by remember { mutableStateOf(false) }
    var showNoKey by remember { mutableStateOf(false) }
    var showNoModel by remember { mutableStateOf(false) }

    // Wybór pliku nagrania (np. z dyktafonu) w systemowym oknie plików.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }
    val openPicker: () -> Unit = {
        try {
            picker.launch(AudioImport.MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Brak aplikacji do wybierania plików.", Toast.LENGTH_LONG).show()
        }
    }
    val proceedImport: () -> Unit = {
        when {
            settings.engine == "local" && !modelInstalled -> showNoModel = true
            settings.engine == "cloud" && vm.apiKey().isBlank() -> showNoKey = true
            else -> openPicker()
        }
    }

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
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { if (!settings.consentShown) showConsent = true else proceedImport() },
                    enabled = !vm.importBusy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(painterResource(R.drawable.ic_audio_file), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("WCZYTAJ NAGRANIE Z PLIKU", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Np. z dyktafonu: MP3, M4A, WAV, OGG, OPUS, FLAC, AMR albo film MP4. " +
                        "Możesz też wybrać „Udostępnij → LiveTranscribe” w innej aplikacji.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Zanim wczytasz nagranie") },
            text = {
                Text(
                    "Upewnij się, że osoby nagrane w pliku wiedzą o nagraniu i jego zamianie na tekst – tego wymagają przepisy (RODO) i dobre obyczaje.\n\n" +
                        if (settings.engine == "local") "W trybie lokalnym dźwięk nie opuszcza telefonu."
                        else "W trybie chmurowym fragmenty nagrania są wysyłane szyfrowanym połączeniem do ${settings.host} (serwery mogą być poza UE) i podlegają regulaminowi tej usługi. Nagrania i teksty przechowuje tylko Twój telefon.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    vm.update { it.copy(consentShown = true) }
                    proceedImport()
                }) { Text("Rozumiem") }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Anuluj") } },
        )
    }
    if (showNoKey) {
        AlertDialog(
            onDismissRequest = { showNoKey = false },
            title = { Text("Brak klucza API") },
            text = {
                Text(
                    "Tryb chmurowy wymaga klucza API. Możesz dodać go teraz w Ustawieniach albo wczytać plik mimo to – " +
                        "nagranie zostanie zachowane, a tekst pojawi się po dodaniu klucza.",
                )
            },
            confirmButton = { TextButton(onClick = { showNoKey = false; onTab("settings") }) { Text("Ustawienia") } },
            dismissButton = { TextButton(onClick = { showNoKey = false; openPicker() }) { Text("Wczytaj mimo to") } },
        )
    }
    if (showNoModel) {
        AlertDialog(
            onDismissRequest = { showNoModel = false },
            title = { Text("Brak modelu lokalnego") },
            text = { Text("Tryb lokalny wymaga jednorazowego pobrania modelu języka polskiego (ok. 50 MB). Zrobisz to w Ustawieniach.") },
            confirmButton = { TextButton(onClick = { showNoModel = false; onTab("settings") }) { Text("Ustawienia") } },
            dismissButton = { TextButton(onClick = { showNoModel = false }) { Text("Anuluj") } },
        )
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
                    val busy = live || s.status == SessionStatus.IMPORTING
                    SessionRow(s, onClick = { onOpen(s.id, false, false) }) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Więcej")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Otwórz") }, onClick = { menu = false; onOpen(s.id, false, false) })
                                DropdownMenuItem(text = { Text("Zmień nazwę") }, onClick = { menu = false; renameTarget = s })
                                DropdownMenuItem(text = { Text("Edytuj tekst") }, enabled = !busy, onClick = { menu = false; onOpen(s.id, false, true) })
                                DropdownMenuItem(text = { Text("Generuj PDF") }, enabled = !busy, onClick = { menu = false; onOpen(s.id, true, false) })
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
