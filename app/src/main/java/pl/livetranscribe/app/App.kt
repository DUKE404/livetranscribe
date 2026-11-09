package pl.livetranscribe.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.livetranscribe.app.data.AppDb
import pl.livetranscribe.app.data.Repository
import pl.livetranscribe.app.data.SettingsStore
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
        db = Room.databaseBuilder(this, AppDb::class.java, "livetranscribe.db").build()
        settings = SettingsStore(this)
        repo = Repository(this, db.dao(), settings)
        RecordingService.createChannel(this)

        // Proces dopiero wystartował, więc żadna sesja nie może teraz nagrywać:
        // wszystko, co tak wygląda w bazie, zostało przerwane i wymaga odzyskania.
        appScope.launch {
            try { repo.recoverInterrupted() } catch (_: Exception) { }
            TranscriptionQueue.kick()
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

    companion object {
        lateinit var instance: App
            private set
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
