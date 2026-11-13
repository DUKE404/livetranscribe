package pl.livetranscribe.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import pl.livetranscribe.app.service.RecStatus

private val languages = listOf(
    "pl" to "Polski",
    "auto" to "Wykrywaj automatycznie",
    "en" to "Angielski",
    "de" to "Niemiecki",
    "uk" to "Ukraiński",
    "fr" to "Francuski",
    "es" to "Hiszpański",
)
private val engines = listOf(
    "cloud" to "Chmura – najwyższa jakość, wymaga internetu",
    "local" to "Lokalnie na telefonie – bez internetu, niższa jakość",
)
private val providers = listOf(
    "groq" to "Groq (Whisper large-v3) – ma darmowy plan",
    "gemini" to "Google Gemini – darmowy, rozpoznaje rozmówców",
    "openai" to "OpenAI – płatne, umie rozpoznawać rozmówców",
    "custom" to "Inna usługa zgodna z API OpenAI",
)
private val qualities = listOf(
    "fast" to "Szybka – tekst pojawia się najszybciej",
    "balanced" to "Zrównoważona",
    "accurate" to "Dokładna – dłuższe fragmenty, tekst z opóźnieniem",
)
private val fontSizes = listOf("small" to "Mała", "medium" to "Średnia", "large" to "Duża")
private val themes = listOf("system" to "Jak w systemie", "light" to "Jasny", "dark" to "Ciemny")

@Composable
fun SettingsScreen(vm: MainViewModel, onTab: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val s by vm.settings.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val modelInstalled by vm.modelInstalled.collectAsStateWithLifecycle()
    val modelProgress by vm.modelProgress.collectAsStateWithLifecycle()
    val modelError by vm.modelError.collectAsStateWithLifecycle()
    val busy = rec.status != RecStatus.IDLE

    var confirmHistory by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Ustawienia", fontWeight = FontWeight.Bold) }) },
        bottomBar = { BottomBar("settings", onTab) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Section("Transkrypcja")
            Choice("Język transkrypcji", languages, s.language) { v -> vm.update { it.copy(language = v) } }
            if (s.engine == "local" && s.language != "pl") {
                Note("Silnik lokalny rozpoznaje tylko język polski.")
            }
            Choice("Silnik transkrypcji", engines, s.engine, enabled = !busy) { v -> vm.update { it.copy(engine = v) } }
            Note(
                if (s.engine == "local") "Dźwięk jest przetwarzany wyłącznie w telefonie i nigdzie nie jest wysyłany."
                else "Dźwięk jest wysyłany szyfrowanym połączeniem (HTTPS) do: ${s.host.ifBlank { "—" }}. Przetwarza go wybrana usługa.",
            )
            Choice("Jakość rozpoznawania", qualities, s.quality, enabled = !busy) { v -> vm.update { it.copy(quality = v) } }

            if (s.engine == "cloud") {
                Section("Usługa w chmurze")
                Choice("Dostawca", providers, s.provider, enabled = !busy) { v -> vm.update { it.copy(provider = v) } }
                if (s.provider == "custom") {
                    var url by remember { mutableStateOf(s.customBaseUrl) }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; vm.update { st -> st.copy(customBaseUrl = it) } },
                        label = { Text("Adres API (https://…/v1)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                if (s.provider == "gemini") {
                    Note(
                        "Gemini zapisuje też wtrącenia i oznacza, kto mówi. Tekst pojawia się co 30–60 s. " +
                            "Darmowy plan ma dzienny limit (obecnie ok. 500 fragmentów, czyli kilka godzin nagrań – sprawdzisz go na aistudio.google.com/rate-limit). " +
                            "Google może wykorzystywać przesłane w nim nagrania do ulepszania swoich usług.",
                    )
                }
                ApiKeyField(vm, s.provider, snackbar)
                Note(
                    when (s.provider) {
                        "gemini" -> "Darmowy klucz: aistudio.google.com → Get API key → Create API key (wystarczy konto Google)."
                        "openai" -> "Klucz utworzysz na platform.openai.com → API keys."
                        "groq" -> "Darmowy klucz: console.groq.com → API Keys → Create API Key."
                        else -> "Klucz od wybranej usługi."
                    },
                )
                var model by remember(s.provider) { mutableStateOf(s.customModel) }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; vm.update { st -> st.copy(customModel = it) } },
                    label = { Text("Model (puste = automatycznie)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Note("Używany model: ${s.model}")
            } else {
                Section("Silnik lokalny")
                when {
                    modelProgress != null -> {
                        Text("Pobieranie modelu… ${((modelProgress ?: 0f) * 100).toInt()}%")
                        LinearProgressIndicator(progress = { modelProgress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                    modelInstalled -> {
                        Text("Model języka polskiego jest pobrany.")
                        OutlinedButton(onClick = { vm.deleteModel() }, enabled = !busy, modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("Usuń model")
                        }
                    }
                    else -> {
                        Text("Tryb lokalny wymaga jednorazowego pobrania modelu języka polskiego (ok. 50 MB).")
                        Button(onClick = { vm.downloadModel() }, modifier = Modifier.padding(vertical = 6.dp)) { Text("Pobierz model") }
                    }
                }
                modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Note("Silnik lokalny nie stawia przecinków i jest mniej dokładny niż chmura. Sprawdza się, gdy nie ma internetu albo nagranie nie może opuścić telefonu.")
            }

            Section("Słownik nazw")
            var glossary by remember { mutableStateOf(s.glossary) }
            OutlinedTextField(
                value = glossary,
                onValueChange = { glossary = it; vm.update { st -> st.copy(glossary = it) } },
                label = { Text("Nazwiska, firmy, produkty, skróty") },
                placeholder = { Text("np. Kowalczyk, Allegro, KPI, Jira") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Note("Oddziel przecinkami. Model częściej zapisze te słowa poprawnie.")

            Section("Tekst")
            Toggle("Automatyczne zapisywanie", "Każda transkrypcja trafia do historii bez pytania", s.autosave) { v -> vm.update { it.copy(autosave = v) } }
            Toggle("Znaczniki czasu", "Np. [12:05] przed każdym fragmentem", s.timestamps) { v -> vm.update { it.copy(timestamps = v) } }
            Toggle(
                "Rozpoznawanie rozmówców",
                when {
                    s.diarizationAvailable && s.provider == "gemini" -> "Etykiety „Rozmówca A/B”; Gemini stara się zachować te same litery w kolejnych fragmentach"
                    s.diarizationAvailable -> "Etykiety „Rozmówca A/B” są nadawane osobno w każdym fragmencie nagrania, więc ta sama osoba może dostać różne litery"
                    else -> "Dostępne w chmurze z Google Gemini (darmowy) lub OpenAI"
                },
                s.speakers && s.diarizationAvailable,
                enabled = s.diarizationAvailable && !busy,
            ) { v -> vm.update { it.copy(speakers = v) } }

            Section("Dokument PDF")
            Choice("Rozmiar czcionki", fontSizes, s.pdfFontSize) { v -> vm.update { it.copy(pdfFontSize = v) } }
            Toggle("Nagłówek na każdej stronie", "Tytuł dokumentu u góry kolejnych stron", s.pdfHeader) { v -> vm.update { it.copy(pdfHeader = v) } }

            Section("Wygląd")
            Choice("Motyw", themes, s.theme) { v -> vm.update { it.copy(theme = v) } }

            Section("Prywatność i dane")
            Toggle(
                "Zachowuj nagrania audio",
                "Wyłączone: dźwięk jest usuwany zaraz po zamianie na tekst. Włączone: zostaje w prywatnej pamięci aplikacji",
                s.keepAudio,
            ) { v -> vm.update { it.copy(keepAudio = v) } }
            Note("Nagrania, teksty i klucz API są przechowywane w prywatnej pamięci aplikacji, niedostępnej dla innych aplikacji, i nie trafiają do kopii zapasowej w chmurze. Klucz API jest dodatkowo szyfrowany.")
            OutlinedButton(onClick = { confirmHistory = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Usuń historię transkrypcji")
            }
            OutlinedButton(
                onClick = { confirmAll = true },
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Usuń wszystkie dane aplikacji") }
            if (busy) Note("Usuwanie i zmiana silnika są zablokowane podczas nagrywania.")

            Section("O aplikacji")
            val version = remember {
                try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            }
            Note("LiveTranscribe $version")
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmHistory) {
        ConfirmDialog(
            title = "Usunąć całą historię?",
            text = "Wszystkie transkrypcje, nagrania i PDF-y w aplikacji zostaną trwale usunięte. Pliki zapisane w folderze Download pozostaną.",
            confirm = "Usuń",
            destructive = true,
            onDismiss = { confirmHistory = false },
        ) {
            confirmHistory = false
            vm.deleteHistory { scope.launch { snackbar.showSnackbar("Historia usunięta") } }
        }
    }
    if (confirmAll) {
        ConfirmDialog(
            title = "Usunąć wszystkie dane?",
            text = "Znikną transkrypcje, nagrania, klucze API, pobrany model i ustawienia. Tej operacji nie można cofnąć.",
            confirm = "Usuń wszystko",
            destructive = true,
            onDismiss = { confirmAll = false },
        ) {
            confirmAll = false
            vm.deleteEverything { scope.launch { snackbar.showSnackbar("Wszystkie dane usunięte") } }
        }
    }
}

@Composable
private fun ApiKeyField(vm: MainViewModel, provider: String, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var key by remember(provider) { mutableStateOf(vm.apiKey(provider)) }
    var visible by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = key,
        onValueChange = { key = it.trim() },
        label = { Text("Klucz API") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Ukryj" else "Pokaż") } },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !checking,
            onClick = {
                if (!vm.saveApiKey(provider, key)) {
                    scope.launch { snackbar.showSnackbar("Telefon nie pozwolił zapisać klucza w szyfrowanym magazynie") }
                } else {
                    checking = true
                    scope.launch {
                        val problem = vm.checkKey()
                        checking = false
                        snackbar.showSnackbar(if (problem == null) "Klucz zapisany i działa" else "Klucz zapisany. $problem")
                    }
                }
            },
        ) { Text(if (checking) "Sprawdzam…" else "Zapisz i sprawdź klucz") }
    }
    Note("Klucz jest przechowywany tylko w tym telefonie, w zaszyfrowanej postaci.")
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Choice(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    for ((value, text) in options) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(value); open = false }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = { onSelect(value); open = false })
                            Spacer(Modifier.width(6.dp))
                            Text(text)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Zamknij") } },
        )
    }
}
