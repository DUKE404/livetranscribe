package pl.livetranscribe.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.livetranscribe.app.data.AppDb
import pl.livetranscribe.app.data.MIGRATION_1_2
import pl.livetranscribe.app.data.Repository
import pl.livetranscribe.app.data.SettingsStore
import pl.livetranscribe.app.service.AudioImport
import pl.livetranscribe.app.service.RecordingService
import pl.livetranscribe.app.service.TranscriptionQueue

class App : Application() {
    lateinit var db: AppDb
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Room.databaseBuilder(this, AppDb::class.java, "livetranscribe.db").addMigrations(MIGRATION_1_2).build()
        settings = SettingsStore(this)
        repo = Repository(this, db.dao(), settings)
        RecordingService.createChannel(this)
        TranscriptionQueue.createChannel(this)
        AudioImport.createChannel(this)

        // Czy użytkownik ma aplikację na ekranie (wtedy nie pokazujemy powiadomień „gotowe”).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Proces dopiero wystartował, więc żadna sesja nie może teraz nagrywać:
        // wszystko, co tak wygląda w bazie, zostało przerwane i wymaga odzyskania.
        appScope.launch {
            try { repo.recoverInterrupted() } catch (_: Exception) { }
            TranscriptionQueue.kick()
            // Zapas: gdyby proces został zamknięty przed dokończeniem, system dokończy pracę sam.
            if (db.dao().nextPending() != null) TranscriptionQueue.scheduleBackground(this@App)
        }

        // Gdy wraca internet, dokańczamy fragmenty czekające w kolejce.
        try {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        TranscriptionQueue.kick()
                    }
                },
            )
        } catch (_: Exception) {
        }
    }

    @Volatile
    private var started = 0
    val inForeground: Boolean get() = started > 0

    companion object {
        lateinit var instance: App
            private set
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
