package pl.livetranscribe.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import pl.livetranscribe.app.service.RecStatus
import pl.livetranscribe.app.service.RecordingBus
import pl.livetranscribe.app.ui.DocumentScreen
import pl.livetranscribe.app.ui.HistoryScreen
import pl.livetranscribe.app.ui.HomeScreen
import pl.livetranscribe.app.ui.LiveTranscribeTheme
import pl.livetranscribe.app.ui.MainViewModel
import pl.livetranscribe.app.ui.RecordingScreen
import pl.livetranscribe.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Po obrocie ekranu ten sam intent nie może wczytać pliku drugi raz.
        if (savedInstanceState == null) handleIntent(intent)
        // Powitanie przy każdym otwarciu aplikacji z ikony.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) {
            Toast.makeText(this, "Miłego dnia maluszku\u00a0❤️", Toast.LENGTH_LONG).show()
        }
        setContent {
            val vm: MainViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            LiveTranscribeTheme(dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav(vm)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Plik udostępniony z innej aplikacji (np. dyktafonu): wczytaj go do transkrypcji.
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) sharedAudio.value = uri
            return
        }
        val id = intent?.getLongExtra(EXTRA_SESSION, 0L) ?: 0L
        if (id > 0) {
            openSession.value = id
            intent?.removeExtra(EXTRA_SESSION)
        }
    }

    companion object {
        const val EXTRA_SESSION = "session_id"
        val openSession = MutableStateFlow<Long?>(null)
        val sharedAudio = MutableStateFlow<Uri?>(null)
    }
}

@Composable
private fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Plik udostępniony z innej aplikacji.
    LaunchedEffect(Unit) {
        MainActivity.sharedAudio.collect { uri ->
            if (uri != null) {
                MainActivity.sharedAudio.value = null
                vm.importAudio(uri)
            }
        }
    }

    // Wynik wczytania pliku: otwórz nowy dokument albo pokaż, co poszło nie tak.
    LaunchedEffect(Unit) {
        vm.importResults.collect { r ->
            when (r) {
                is MainViewModel.ImportResult.Opened -> nav.navigate("doc/${r.id}")
                is MainViewModel.ImportResult.Failed -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    if (vm.importBusy) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Wczytuję plik…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("Kopiuję i sprawdzam nagranie. Przy dużych plikach może to potrwać chwilę.")
                }
            },
        )
    }

    // Dotknięcie powiadomienia „Transkrypcja gotowa”: otwórz ten dokument.
    LaunchedEffect(Unit) {
        MainActivity.openSession.collect { id ->
            if (id != null && id > 0) {
                MainActivity.openSession.value = null
                nav.navigate("doc/$id")
            }
        }
    }

    // Aplikacja otwarta w trakcie nagrywania (np. z powiadomienia): od razu pokaż ekran transkrypcji.
    LaunchedEffect(Unit) {
        if (RecordingBus.state.value.status != RecStatus.IDLE) nav.navigate("record")
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = vm,
                onStart = { nav.navigate(if (vm.isRecordingActive) "record" else "record?auto=true") },
                onOpen = { id -> nav.navigate("doc/$id") },
                onTab = { nav.switchTab(it) },
            )
        }
        composable("history") {
            HistoryScreen(
                vm = vm,
                onOpen = { id, pdf, edit -> nav.navigate("doc/$id?pdf=$pdf&edit=$edit") },
                onTab = { nav.switchTab(it) },
            )
        }
        composable("settings") {
            SettingsScreen(vm = vm, onTab = { nav.switchTab(it) })
        }
        composable(
            route = "record?auto={auto}",
            arguments = listOf(navArgument("auto") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            RecordingScreen(
                vm = vm,
                autoStart = entry.arguments?.getBoolean("auto") ?: false,
                onBack = { nav.popBackStack() },
                onOpenDocument = { id, pdf ->
                    nav.navigate("doc/$id?pdf=$pdf") { popUpTo("home") }
                },
                onSettings = { nav.switchTab("settings") },
            )
        }
        composable(
            route = "doc/{id}?pdf={pdf}&edit={edit}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("pdf") { type = NavType.BoolType; defaultValue = false },
                navArgument("edit") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            DocumentScreen(
                vm = vm,
                id = entry.arguments?.getLong("id") ?: 0L,
                autoPdf = entry.arguments?.getBoolean("pdf") ?: false,
                autoEdit = entry.arguments?.getBoolean("edit") ?: false,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/** Przełączanie zakładek dolnego paska bez odkładania ich na stos. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
