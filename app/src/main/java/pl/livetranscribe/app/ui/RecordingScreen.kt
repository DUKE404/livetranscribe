package pl.livetranscribe.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.livetranscribe.app.R
import pl.livetranscribe.app.data.ChunkStatus
import pl.livetranscribe.app.service.RecStatus

@Composable
fun RecordingScreen(
    vm: MainViewModel,
    autoStart: Boolean,
    onBack: () -> Unit,
    onOpenDocument: (id: Long, pdf: Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val rec by vm.rec.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedMs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val problem by vm.queueProblem.collectAsStateWithLifecycle()
    val modelInstalled by vm.modelInstalled.collectAsStateWithLifecycle()

    var lastSid by rememberSaveable { mutableStateOf(0L) }
    var autoHandled by rememberSaveable { mutableStateOf(false) }
    var showNoKey by remember { mutableStateOf(false) }
    var showNoModel by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var showDenied by remember { mutableStateOf(false) }
    // null = brak okna; true = po zakończeniu otwórz dokument; false = zostań na ekranie
    var confirmFinish by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(rec.sessionId) { if (rec.sessionId != 0L) lastSid = rec.sessionId }
    val sid = if (rec.sessionId != 0L) rec.sessionId else lastSid
    val chunks by remember(sid) { vm.chunksFlow(sid) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val text = remember(chunks, settings.timestamps, settings.speakers) {
        vm.compose(chunks, settings.timestamps, settings.speakers)
    }
    val pending = chunks.count { it.status == ChunkStatus.PENDING }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.start(context) else showDenied = true
    }

    val launchRecording: () -> Unit = {
        val wanted = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) vm.start(context) else permissionLauncher.launch(wanted.toTypedArray())
    }

    val proceedStart: () -> Unit = {
        when {
            settings.engine == "local" && !modelInstalled -> showNoModel = true
            settings.engine == "cloud" && vm.apiKey().isBlank() -> showNoKey = true
            else -> launchRecording()
        }
    }

    val tryStart: () -> Unit = {
        vm.clearRecError()
        if (!settings.consentShown) showConsent = true else proceedStart()
    }

    LaunchedEffect(Unit) {
        if (autoStart && !autoHandled) {
            autoHandled = true
            if (rec.status == RecStatus.IDLE) tryStart()
        }
    }

    val statusText = when (rec.status) {
        RecStatus.RECORDING -> if (problem != null) "Nagrywanie – transkrypcja czeka" else "Nagrywanie i transkrypcja"
        RecStatus.PAUSED -> "Pauza"
        RecStatus.FINISHING -> "Kończenie transkrypcji…"
        RecStatus.IDLE -> if (lastSid != 0L) "Transkrypcja zakończona" else "Gotowy do nagrywania"
    }
    val statusColor = when (rec.status) {
        RecStatus.RECORDING -> MaterialTheme.colorScheme.error
        RecStatus.PAUSED -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transkrypcja", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(statusText, style = MaterialTheme.typography.titleSmall, color = statusColor)
            }
            Text(
                if (settings.engine == "local") "Lokalnie – dźwięk zostaje w telefonie"
                else "Chmura – dźwięk jest wysyłany do ${settings.host}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                formatDuration(elapsed),
                fontSize = 46.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
            )
            LevelMeter(vm, active = rec.status == RecStatus.RECORDING)
            Spacer(Modifier.height(10.dp))

            rec.error?.let {
                InfoBanner(it, error = true)
                Spacer(Modifier.height(8.dp))
            }
            if (problem != null && (rec.status != RecStatus.IDLE || pending > 0)) {
                InfoBanner(problem ?: "", error = true, action = "Ponów", onAction = { vm.retryQueue() })
                Spacer(Modifier.height(8.dp))
            }

            // Tekst na żywo
            val scroll = rememberScrollState()
            LaunchedEffect(text.length, pending) { scroll.animateScrollTo(scroll.maxValue) }
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                    if (text.isEmpty() && pending == 0) {
                        Text(
                            if (rec.status == RecStatus.IDLE) "Tu pojawi się tekst transkrypcji."
                            else "Słucham… Tekst pojawi się po pierwszych zdaniach.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp) }
                    }
                    if (pending > 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (pending == 1) "Przetwarzam fragment…" else "W kolejce: $pending fragmentów",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // Sterowanie
            val recordingOrPaused = rec.status == RecStatus.RECORDING || rec.status == RecStatus.PAUSED
            if (rec.status == RecStatus.IDLE && lastSid != 0L) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onOpenDocument(lastSid, true) }, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("GENERUJ PDF", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { onOpenDocument(lastSid, false) }, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("OTWÓRZ DOKUMENT")
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isStop = recordingOrPaused
                FilledIconButton(
                    onClick = { if (isStop) confirmFinish = false else if (rec.status == RecStatus.IDLE) tryStart() },
                    enabled = rec.status != RecStatus.FINISHING,
                    modifier = Modifier.size(84.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isStop) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        painterResource(if (isStop) R.drawable.ic_stop else R.drawable.ic_mic),
                        contentDescription = if (isStop) "Stop" else "Start",
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    when {
                        rec.status == RecStatus.FINISHING -> "CZEKAJ…"
                        isStop -> "STOP"
                        else -> "START"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { vm.pause(context) },
                    enabled = rec.status == RecStatus.RECORDING,
                    modifier = Modifier.weight(1f).height(54.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PAUZA", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = { vm.clearRecError(); vm.resume(context) },
                    enabled = rec.status == RecStatus.PAUSED,
                    modifier = Modifier.weight(1f).height(54.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("WZNÓW", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { confirmFinish = true },
                    enabled = recordingOrPaused,
                    modifier = Modifier.weight(1f).height(54.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text("ZAKOŃCZ", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // ---------- okna dialogowe ----------

    confirmFinish?.let { openDoc ->
        val finish: () -> Unit = {
            val id = rec.sessionId
            vm.stop(context)
            confirmFinish = null
            if (openDoc && id != 0L) onOpenDocument(id, false)
        }
        AlertDialog(
            onDismissRequest = { confirmFinish = null },
            title = { Text("Zakończyć transkrypcję?") },
            text = {
                Text(
                    if (settings.autosave) "Nagrywanie zostanie zatrzymane, a dokument zapisany w historii."
                    else "Automatyczne zapisywanie jest wyłączone. Zapisać ten dokument w historii?",
                )
            },
            confirmButton = { TextButton(onClick = finish) { Text(if (settings.autosave) "Zakończ" else "Zakończ i zapisz") } },
            dismissButton = {
                Row {
                    if (!settings.autosave) {
                        TextButton(onClick = {
                            vm.discard(context)
                            lastSid = 0L
                            confirmFinish = null
                        }) { Text("Odrzuć", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { confirmFinish = null }) { Text("Anuluj") }
                }
            },
        )
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Zanim zaczniesz nagrywać") },
            text = {
                Text(
                    "Uprzedź uczestników rozmowy, że ją nagrywasz i zamieniasz na tekst – tego wymagają przepisy (RODO) i dobre obyczaje.\n\n" +
                        if (settings.engine == "local") "W trybie lokalnym dźwięk nie opuszcza telefonu."
                        else "W trybie chmurowym fragmenty nagrania są wysyłane szyfrowanym połączeniem do ${settings.host} (serwery mogą być poza UE) i podlegają regulaminowi tej usługi. Nagrania i teksty przechowuje tylko Twój telefon.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    vm.update { it.copy(consentShown = true) }
                    proceedStart()
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
                    "Tryb chmurowy wymaga klucza API. Możesz dodać go teraz w Ustawieniach albo nagrywać mimo to – " +
                        "nagranie zostanie zachowane, a tekst pojawi się po dodaniu klucza.",
                )
            },
            confirmButton = { TextButton(onClick = { showNoKey = false; onSettings() }) { Text("Ustawienia") } },
            dismissButton = { TextButton(onClick = { showNoKey = false; launchRecording() }) { Text("Nagrywaj mimo to") } },
        )
    }
    if (showNoModel) {
        AlertDialog(
            onDismissRequest = { showNoModel = false },
            title = { Text("Brak modelu lokalnego") },
            text = { Text("Tryb lokalny wymaga jednorazowego pobrania modelu języka polskiego (ok. 50 MB). Zrobisz to w Ustawieniach.") },
            confirmButton = { TextButton(onClick = { showNoModel = false; onSettings() }) { Text("Ustawienia") } },
            dismissButton = { TextButton(onClick = { showNoModel = false }) { Text("Anuluj") } },
        )
    }
    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false },
            title = { Text("Potrzebny dostęp do mikrofonu") },
            text = {
                Text(
                    "Bez uprawnienia do mikrofonu transkrypcja nie zadziała. Jeśli okno z pytaniem już się nie pojawia, " +
                        "włącz mikrofon w: Ustawienia telefonu → Aplikacje → LiveTranscribe → Uprawnienia.",
                )
            },
            confirmButton = { TextButton(onClick = { showDenied = false }) { Text("OK") } },
        )
    }
}

/** Wizualizacja poziomu dźwięku: przesuwające się słupki z ostatnich kilku sekund. */
@Composable
private fun LevelMeter(vm: MainViewModel, active: Boolean) {
    val bars = 44
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        vm.level.collect { v ->
            history.add(v)
            if (history.size > bars) history.removeAt(0)
        }
    }
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val slot = size.width / bars
        val barWidth = slot * 0.6f
        val offset = bars - history.size
        for (i in 0 until bars) {
            val v = if (i >= offset && active) history[i - offset] else 0f
            val h = (size.height * (0.06f + 0.94f * v)).coerceAtMost(size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}
