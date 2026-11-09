package pl.livetranscribe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
}

@Composable
private fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()

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
