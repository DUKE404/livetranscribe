package pl.livetranscribe.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.livetranscribe.app.App
import pl.livetranscribe.app.MainActivity
import pl.livetranscribe.app.R
import pl.livetranscribe.app.audio.AudioFiles
import pl.livetranscribe.app.audio.Chunker
import pl.livetranscribe.app.data.ChunkEntity
import pl.livetranscribe.app.data.ChunkStatus
import pl.livetranscribe.app.data.SessionEntity
import pl.livetranscribe.app.data.SessionStatus
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

enum class RecStatus { IDLE, RECORDING, PAUSED, FINISHING }

data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val sessionId: Long = 0,
    val error: String? = null,
)

/** Stan nagrywania widoczny dla interfejsu. */
object RecordingBus {
    val state = MutableStateFlow(RecState())
    val elapsedMs = MutableStateFlow(0L)
    val level = MutableStateFlow(0f)
}

/**
 * Usługa pierwszoplanowa: nagrywa i transkrybuje także przy wygaszonym ekranie,
 * zablokowanym telefonie i po przejściu do innej aplikacji.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ops = Mutex()
    private var recordJob: Job? = null
    private var chunker: Chunker? = null
    private var sessionId = 0L
    private var nextIdx = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val app: App get() = application as App
    private val status: RecStatus get() = RecordingBus.state.value.status

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_PAUSE -> scope.launch { pause() }
            ACTION_RESUME -> scope.launch { resume() }
            ACTION_STOP -> scope.launch { finish(discard = false) }
            ACTION_DISCARD -> scope.launch { finish(discard = true) }
            else -> if (status == RecStatus.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (status != RecStatus.IDLE) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            val n = buildNotification(RecStatus.RECORDING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            RecordingBus.state.value = RecState(error = "System nie pozwolił uruchomić nagrywania w tle. Otwórz aplikację i spróbuj ponownie.")
            stopSelf()
            return
        }
        if (!granted) {
            RecordingBus.state.value = RecState(error = "Brak uprawnienia do mikrofonu.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveTranscribe:recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
        // Stan ustawiamy od razu, żeby drugi START nie utworzył drugiej sesji.
        RecordingBus.state.value = RecState(RecStatus.RECORDING, 0)
        RecordingBus.elapsedMs.value = 0
        scope.launch {
            ops.withLock {
                val settings = app.settings.current
                val now = System.currentTimeMillis()
                sessionId = app.db.dao().insertSession(
                    SessionEntity(
                        title = "Transkrypcja " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl")).format(Date(now)),
                        createdAt = now,
                        status = SessionStatus.RECORDING,
                        engine = settings.engine,
                    ),
                )
                nextIdx = 0
                chunker = Chunker(AudioFiles.SAMPLE_RATE, settings.chunkMinMs, settings.chunkMaxMs)
                RecordingBus.state.value = RecState(RecStatus.RECORDING, sessionId)
                startLoop()
            }
        }
    }

    private fun startLoop() {
        val chunker = chunker ?: return
        recordJob = scope.launch(Dispatchers.IO) {
            val sr = AudioFiles.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = max(minBuf, sr * 2)
            val recorder = createRecorder(bufBytes)
            if (recorder == null) {
                onRecorderError("Nie udało się uruchomić mikrofonu. Sprawdź, czy inna aplikacja go nie używa.")
                return@launch
            }
            val tail = RandomAccessFile(tailFile(this@RecordingService, sessionId), "rw")
            try {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    onRecorderError("Mikrofon jest zajęty przez inną aplikację.")
                    return@launch
                }
                val buf = ShortArray(sr / 10) // 100 ms
                val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                tail.seek(tail.length())
                while (isActive) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) {
                        RecordingBus.level.value = level(buf, n)
                        // Kopia bieżącego fragmentu na dysku – do odzyskania po nagłym zamknięciu.
                        bytes.clear()
                        bytes.asShortBuffer().put(buf, 0, n)
                        tail.write(bytes.array(), 0, n * 2)
                        val chunks = chunker.feed(buf, n)
                        if (chunks.isNotEmpty() || chunker.isIdle) tail.setLength(0)
                        // Zapis fragmentu nie może zostać przerwany w połowie przez pauzę lub stop.
                        for (c in chunks) withContext(NonCancellable) { saveChunk(c) }
                        RecordingBus.elapsedMs.value = chunker.totalSamples * 1000 / sr
                    } else if (n < 0) {
                        onRecorderError("Nagrywanie zostało przerwane przez system (kod $n).")
                        break
                    }
                }
            } catch (e: SecurityException) {
                onRecorderError("Brak dostępu do mikrofonu.")
            } finally {
                try { recorder.stop() } catch (_: Exception) { }
                recorder.release()
                try { tail.close() } catch (_: Exception) { }
                RecordingBus.level.value = 0f
            }
        }
    }

    private fun createRecorder(bufBytes: Int): AudioRecord? {
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, AudioFiles.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Błąd mikrofonu: zachowaj to, co nagrano, i przejdź w pauzę, żeby dało się wznowić. */
    private fun onRecorderError(message: String) {
        scope.launch {
            ops.withLock {
                if (status != RecStatus.RECORDING) return@withLock
                flushChunk()
                app.db.dao().setStatus(sessionId, SessionStatus.PAUSED)
                RecordingBus.state.value = RecState(RecStatus.PAUSED, sessionId, message)
                updateNotification()
            }
        }
    }

    private suspend fun saveChunk(c: Chunker.Chunk) {
        val dir = File(filesDir, "audio/$sessionId").apply { mkdirs() }
        val idx = nextIdx++
        val file = File(dir, "fragment_%05d.wav".format(idx))
        AudioFiles.writeWav(file, c.pcm)
        app.db.dao().insertChunk(
            ChunkEntity(
                sessionId = sessionId,
                idx = idx,
                startMs = c.startMs,
                endMs = c.endMs,
                audioPath = file.absolutePath,
                status = ChunkStatus.PENDING,
            ),
        )
        TranscriptionQueue.kick()
    }

    private suspend fun flushChunk() {
        chunker?.flush()?.let { saveChunk(it) }
        tailFile(this, sessionId).delete()
    }

    private suspend fun pause() = ops.withLock {
        if (status == RecStatus.IDLE) stopSelf()
        if (status != RecStatus.RECORDING) return@withLock
        recordJob?.cancelAndJoin()
        flushChunk()
        app.db.dao().setStatus(sessionId, SessionStatus.PAUSED)
        app.db.dao().setDuration(sessionId, RecordingBus.elapsedMs.value)
        RecordingBus.state.value = RecState(RecStatus.PAUSED, sessionId)
        updateNotification()
    }

    private suspend fun resume() = ops.withLock {
        if (status == RecStatus.IDLE) stopSelf()
        if (status != RecStatus.PAUSED) return@withLock
        app.db.dao().setStatus(sessionId, SessionStatus.RECORDING)
        RecordingBus.state.value = RecState(RecStatus.RECORDING, sessionId)
        updateNotification()
        startLoop()
    }

    private suspend fun finish(discard: Boolean) = ops.withLock {
        if (status == RecStatus.IDLE || status == RecStatus.FINISHING) {
            if (status == RecStatus.IDLE) stopSelf()
            return@withLock
        }
        val dao = app.db.dao()
        RecordingBus.state.value = RecState(RecStatus.FINISHING, sessionId)
        recordJob?.cancelAndJoin()
        if (discard) {
            tailFile(this, sessionId).delete()
            app.repo.deleteSession(sessionId)
        } else {
            flushChunk()
            dao.setDuration(sessionId, RecordingBus.elapsedMs.value)
            dao.setStatus(sessionId, SessionStatus.PROCESSING)
            updateNotification()
            TranscriptionQueue.kick()
            // Poczekaj, aż ostatnie fragmenty zostaną przetworzone. Przy braku sieci nie czekamy
            // w nieskończoność: fragmenty zostają w kolejce i dokończą się później.
            var stalledSince = 0L
            while (dao.pendingCount(sessionId) > 0) {
                if (TranscriptionQueue.stalled.value != null) {
                    val now = System.currentTimeMillis()
                    if (stalledSince == 0L) stalledSince = now
                    if (now - stalledSince > 15_000) break
                } else {
                    stalledSince = 0L
                }
                delay(400)
            }
            if (dao.pendingCount(sessionId) == 0) dao.setStatus(sessionId, SessionStatus.DONE)
        }
        RecordingBus.state.value = RecState()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- powiadomienie ----------

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIFICATION_ID, buildNotification(status)) } catch (_: Exception) { }
    }

    private fun buildNotification(st: RecStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (st) {
            RecStatus.PAUSED -> "Transkrypcja wstrzymana" to "Dotknij, aby wrócić do aplikacji"
            RecStatus.FINISHING -> "Kończenie transkrypcji…" to "Przetwarzanie ostatnich fragmentów"
            else -> "Trwa transkrypcja" to "Mikrofon jest włączony – nagrywanie w toku"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (st == RecStatus.RECORDING) b.addAction(0, "Pauza", action(ACTION_PAUSE, 1))
        if (st == RecStatus.PAUSED) b.addAction(0, "Wznów", action(ACTION_RESUME, 2))
        if (st == RecStatus.RECORDING || st == RecStatus.PAUSED) b.addAction(0, "Zakończ", action(ACTION_STOP, 3))
        return b.build()
    }

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun level(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
        val rms = sqrt(sum / n)
        if (rms < 1.0) return 0f
        val db = 20 * log10(rms / 32768.0) // 0 dB = pełna skala
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "pl.livetranscribe.app.START"
        const val ACTION_PAUSE = "pl.livetranscribe.app.PAUSE"
        const val ACTION_RESUME = "pl.livetranscribe.app.RESUME"
        const val ACTION_STOP = "pl.livetranscribe.app.STOP"
        const val ACTION_DISCARD = "pl.livetranscribe.app.DISCARD"

        fun tailFile(context: Context, sessionId: Long) = File(context.filesDir, "audio/tail_$sessionId.pcm").apply { parentFile?.mkdirs() }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "Aktywna transkrypcja", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Informuje, że mikrofon jest włączony i trwa transkrypcja"
            nm.createNotificationChannel(ch)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun send(context: Context, action: String) {
            try {
                context.startService(Intent(context, RecordingService::class.java).setAction(action))
            } catch (_: Exception) {
            }
        }
    }
}
