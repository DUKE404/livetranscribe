package pl.livetranscribe.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.livetranscribe.app.data.ChunkStatus
import pl.livetranscribe.app.data.SessionStatus
import pl.livetranscribe.app.engine.MeetingNote
import pl.livetranscribe.app.pdf.PdfActions
import java.io.File

@Composable
fun DocumentScreen(
    vm: MainViewModel,
    id: Long,
    autoPdf: Boolean,
    autoEdit: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val session by remember(id) { vm.sessionFlow(id) }.collectAsStateWithLifecycle(initialValue = null)
    val chunks by remember(id) { vm.chunksFlow(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val problem by vm.queueProblem.collectAsStateWithLifecycle()

    // Szkic edycji trzymamy w ViewModelu: przetrwa obrót ekranu i nie obciąża zapisu stanu.
    val editing = vm.editingId == id
    var menu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showPdfOptions by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var autoHandled by rememberSaveable { mutableStateOf(false) }

    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val live = rec.sessionId == id
    val importing = s.status == SessionStatus.IMPORTING
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val importErrors by vm.importErrors.collectAsStateWithLifecycle()
    val importError = importErrors[id]
    val pending = chunks.count { it.status == ChunkStatus.PENDING }
    val failed = chunks.count { it.status == ChunkStatus.FAILED }
    val text = remember(s.editedText, chunks, settings.timestamps, settings.speakers) {
        s.editedText ?: vm.compose(chunks, settings.timestamps, settings.speakers)
    }
    val pdfFile = remember(s.pdfPath) { s.pdfPath?.let { File(it) }?.takeIf { it.exists() } }
    val audio = remember(chunks) { vm.audioFiles(chunks) }
    val hasSpeakers = remember(chunks) { chunks.any { it.segmentsJson.contains("\"p\"") } }
    val note = remember(s.summaryJson) { MeetingNote.fromJson(s.summaryJson) }
    val noteSteps by vm.noteProgress.collectAsStateWithLifecycle()
    val noteStep = noteSteps[id]
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        if (!autoHandled) {
            autoHandled = true
            if (autoPdf) showPdfOptions = true
            if (autoEdit && !live && !importing) vm.startEdit(id, text)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "Edycja tekstu" else s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (editing) {
                        IconButton(onClick = { vm.cancelEdit() }) { Icon(Icons.Filled.Close, contentDescription = "Anuluj") }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć") }
                    }
                },
                actions = {
                    if (editing) {
                        TextButton(onClick = {
                            vm.saveEdit(id, vm.draft)
                            vm.cancelEdit()
                            scope.launch { snackbar.showSnackbar("Zapisano zmiany") }
                        }) { Text("ZAPISZ", fontWeight = FontWeight.Bold) }
                    } else {
                        IconButton(onClick = { vm.startEdit(id, text) }, enabled = !live && !importing) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edytuj tekst")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Więcej") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Zmień nazwę") }, onClick = { menu = false; showRename = true })
                                if (s.editedText != null && chunks.any { it.status == ChunkStatus.DONE }) {
                                    DropdownMenuItem(
                                        text = { Text("Przywróć tekst sprzed edycji") },
                                        onClick = { menu = false; vm.restoreOriginal(id) },
                                    )
                                }
                                if (audio.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Udostępnij nagranie audio") },
                                        onClick = {
                                            menu = false
                                            try { PdfActions.shareAudio(context, audio) } catch (_: Exception) { }
                                        },
                                    )
                                }
                                DropdownMenuItem(text = { Text("Usuń dokument") }, enabled = !live, onClick = { menu = false; showDelete = true })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!editing) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        if (pdfFile == null) {
                            Button(
                                onClick = { showPdfOptions = true },
                                enabled = !generating && !live && !importing,
                                modifier = Modifier.fillMaxWidth().height(58.dp),
                            ) {
                                if (generating) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text("GENERUJ PDF", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text(
                                "PDF gotowy: ${pdfFile.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showDownload = true }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                Text("POBIERZ PDF", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        try {
                                            PdfActions.share(context, pdfFile, s.title)
                                        } catch (e: Exception) {
                                            scope.launch { snackbar.showSnackbar("Nie udało się otworzyć menu udostępniania") }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text("UDOSTĘPNIJ PDF", fontWeight = FontWeight.SemiBold) }
                                FilledTonalButton(
                                    onClick = {
                                        if (!PdfActions.open(context, pdfFile)) {
                                            scope.launch { snackbar.showSnackbar("Brak aplikacji do otwierania PDF. Użyj „Udostępnij” lub „Pobierz”.") }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text("OTWÓRZ PDF", fontWeight = FontWeight.SemiBold) }
                            }
                            TextButton(onClick = { showPdfOptions = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text("Generuj PDF ponownie")
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(horizontal = 20.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = vm.draft,
                    onValueChange = { vm.draft = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "${formatDate(s.createdAt)}  ·  ${formatDuration(s.durationMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    StatusChip(s.status)
                }
                if (live) {
                    InfoBanner("Ten dokument jest właśnie nagrywany. Edycja i PDF będą dostępne po zakończeniu.")
                    Spacer(Modifier.height(8.dp))
                }
                if (importing) {
                    val p = importProgress[id]
                    InfoBanner(
                        if (p == null) "Wczytuję plik nagrania…"
                        else "Wczytuję plik nagrania – ${(p * 100).toInt()}%. Tekst pojawia się na bieżąco.",
                    )
                    if (p == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    } else {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (importError != null) {
                    InfoBanner(importError, error = true, action = "OK", onAction = { vm.dismissImportError(id) })
                    Spacer(Modifier.height(8.dp))
                }
                if (pending > 0 && !live && (!importing || problem != null)) {
                    InfoBanner(
                        if (problem != null) "${problem} (w kolejce: $pending)" else "Kończę transkrypcję – pozostało fragmentów: $pending",
                        error = problem != null,
                        action = if (problem != null) "Ponów" else null,
                        onAction = { vm.retryQueue() },
                    )
                    if (problem == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    Spacer(Modifier.height(8.dp))
                }
                if (failed > 0) {
                    InfoBanner(
                        "Nie udało się przetworzyć fragmentów: $failed",
                        error = true,
                        action = "Ponów",
                        onAction = { vm.retryFailed(id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    if (!live && !importing && (note != null || (s.status == SessionStatus.DONE && text.isNotBlank()))) {
                        NoteCard(
                            note = note,
                            progress = noteStep,
                            onCreate = { vm.createNote(id) { msg -> scope.launch { snackbar.showSnackbar(msg) } } },
                            onCopy = {
                                note?.let { clipboard.setText(AnnotatedString(it.toPlainText())) }
                                scope.launch { snackbar.showSnackbar("Skopiowano notatkę") }
                            },
                            onDelete = { vm.deleteNote(id) },
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("Transkrypcja", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (text.isBlank()) {
                        Text(
                            if (pending > 0 || s.status != SessionStatus.DONE) "Tekst pojawi się po przetworzeniu nagrania."
                            else "Ten dokument nie zawiera tekstu (w nagraniu nie wykryto mowy).",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp) }
                    }
                }
            }
        }
    }

    // ---------- okna dialogowe ----------

    if (showRename) {
        TextInputDialog(title = "Zmień nazwę", initial = s.title, confirm = "Zapisz", onDismiss = { showRename = false }) {
            vm.rename(id, it)
            showRename = false
        }
    }
    if (showDelete) {
        ConfirmDialog(
            title = "Usunąć dokument?",
            text = "„${s.title}” zostanie trwale usunięty z aplikacji. Pliki PDF zapisane wcześniej w folderze Download pozostaną.",
            confirm = "Usuń",
            destructive = true,
            onDismiss = { showDelete = false },
        ) {
            showDelete = false
            onBack()
            vm.delete(id)
        }
    }
    if (showPdfOptions) {
        val edited = s.editedText != null
        var ts by remember { mutableStateOf(settings.timestamps) }
        var sp by remember { mutableStateOf(settings.speakers && hasSpeakers) }
        var withNote by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showPdfOptions = false },
            title = { Text("Generuj PDF") },
            text = {
                Column {
                    if (edited) {
                        Text("Dokument zostanie utworzony z tekstu po Twoich poprawkach.")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Znaczniki czasu", Modifier.weight(1f))
                            Switch(checked = ts, onCheckedChange = { ts = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (hasSpeakers) "Rozmówcy" else "Rozmówcy (brak danych w tym nagraniu)",
                                Modifier.weight(1f),
                            )
                            Switch(checked = sp, onCheckedChange = { sp = it }, enabled = hasSpeakers)
                        }
                    }
                    if (note != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Notatka AI na początku", Modifier.weight(1f))
                            Switch(checked = withNote, onCheckedChange = { withNote = it })
                        }
                    }
                    if (pending > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Uwaga: $pending fragmentów czeka jeszcze na transkrypcję i nie trafi do tego PDF.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPdfOptions = false
                    generating = true
                    scope.launch {
                        try {
                            vm.generatePdf(id, ts, sp, withNote)
                            snackbar.showSnackbar("PDF gotowy")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Nie udało się utworzyć PDF: ${e.message}")
                        } finally {
                            generating = false
                        }
                    }
                }) { Text("Generuj") }
            },
            dismissButton = { TextButton(onClick = { showPdfOptions = false }) { Text("Anuluj") } },
        )
    }
    if (showDownload && pdfFile != null) {
        TextInputDialog(
            title = "Nazwa pliku",
            initial = pdfFile.nameWithoutExtension,
            confirm = "Pobierz",
            suffix = ".pdf",
            onDismiss = { showDownload = false },
        ) { name ->
            showDownload = false
            scope.launch {
                try {
                    val where = withContext(Dispatchers.IO) { PdfActions.saveToDownloads(context, pdfFile, name) }
                    snackbar.showSnackbar("Zapisano: $where", duration = SnackbarDuration.Long)
                } catch (e: Exception) {
                    snackbar.showSnackbar("Nie udało się zapisać pliku: ${e.message}")
                }
            }
        }
    }
}

/** Notatka AI: streszczenie, decyzje, zadania i otwarte kwestie – albo przycisk, który ją tworzy. */
@Composable
private fun NoteCard(
    note: MeetingNote?,
    progress: String?,
    onCreate: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Notatka ze spotkania", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            when {
                progress != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progress, style = MaterialTheme.typography.bodyMedium)
                }
                note == null -> {
                    Text(
                        "Sztuczna inteligencja przygotuje podsumowanie, listę decyzji i zadań (kto, co, do kiedy). " +
                            "Notatka trafi też na początek PDF.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Text("UTWÓRZ NOTATKĘ AI", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    if (note.summary.isNotBlank()) {
                        Text(note.summary, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                    }
                    NoteList("Decyzje", note.decisions)
                    NoteList("Zadania", note.tasks.map { it.line() })
                    NoteList("Otwarte kwestie", note.openQuestions)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Przygotowane przez AI – sprawdź przed wysłaniem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = onCopy) { Text("Kopiuj") }
                        TextButton(onClick = onCreate) { Text("Utwórz ponownie") }
                        TextButton(onClick = onDelete) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteList(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    for (item in items) {
        Row(Modifier.padding(top = 4.dp)) {
            Text("•", modifier = Modifier.width(16.dp))
            SelectionContainer { Text(item, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
