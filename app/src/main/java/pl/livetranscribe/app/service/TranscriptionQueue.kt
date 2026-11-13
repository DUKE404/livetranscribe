package pl.livetranscribe.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import pl.livetranscribe.app.App
import pl.livetranscribe.app.MainActivity
import pl.livetranscribe.app.R
import pl.livetranscribe.app.audio.AudioFiles
import pl.livetranscribe.app.data.ChunkEntity
import pl.livetranscribe.app.data.ChunkStatus
import pl.livetranscribe.app.data.SessionStatus
import pl.livetranscribe.app.engine.ApiException
import pl.livetranscribe.app.engine.AuthException
import pl.livetranscribe.app.engine.CloudEngine
import pl.livetranscribe.app.engine.ConfigException
import pl.livetranscribe.app.engine.GeminiEngine
import pl.livetranscribe.app.engine.SegJson
import pl.livetranscribe.app.engine.TextComposer
import pl.livetranscribe.app.engine.TranscribeRequest
import pl.livetranscribe.app.engine.TranscriptionEngine
import pl.livetranscribe.app.engine.VoskEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Kolejka fragmentów czekających na transkrypcję.
 *
 * Każdy fragment jest najpierw zapisany na dysku i w bazie, a dopiero potem przetwarzany.
 * Dzięki temu brak internetu, błąd serwera czy zamknięcie aplikacji niczego nie kasują.
 * Gdy internetu brakuje dłużej, dokończenie przejmuje WorkManager: system uruchomi
 * [TranscribeWorker], gdy sieć wróci – także przy zamkniętej aplikacji.
 */
object TranscriptionQueue {

    enum class Outcome { EMPTY, NETWORK, BLOCKED }

    private val lock = Mutex()

    /** Powód wstrzymania kolejki (brak sieci, zły klucz…) albo null, gdy wszystko działa. */
    val stalled = MutableStateFlow<String?>(null)

    fun kick() {
        App.appScope.launch {
            if (!lock.tryLock()) return@launch
            var outcome = Outcome.BLOCKED
            try {
                outcome = loop()
            } catch (e: Exception) {
                stalled.value = "Błąd kolejki: ${e.message}"
            } finally {
                lock.unlock()
            }
            when (outcome) {
                // Fragment mógł dojść w chwili kończenia pętli.
                Outcome.EMPTY -> if (App.instance.db.dao().nextPending() != null) kick()
                Outcome.NETWORK -> scheduleBackground(App.instance)
                Outcome.BLOCKED -> Unit
            }
        }
    }

    /** Wywoływane przez [TranscribeWorker]: przetwarza kolejkę w bieżącym wątku. */
    suspend fun drain(): Outcome {
        lock.lock()
        return try {
            loop()
        } finally {
            lock.unlock()
        }
    }

    /** Zleca systemowi dokończenie transkrypcji, gdy będzie dostęp do internetu. */
    fun scheduleBackground(context: Context) {
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (_: Exception) {
        }
    }

    private suspend fun loop(): Outcome {
        val app = App.instance
        val dao = app.db.dao()
        var backoff = 2_000L
        var netFails = 0
        var serverFails = 0
        while (true) {
            val chunk = dao.nextPending() ?: return Outcome.EMPTY
            val session = dao.session(chunk.sessionId)
            val audio = chunk.audioPath?.let { File(it) }
            if (session == null || audio == null || !audio.exists()) {
                dao.updateChunk(chunk.copy(status = ChunkStatus.FAILED, error = "Brak pliku nagrania"))
                continue
            }
            val settings = app.settings.current
            val local = session.engine == "local"
            try {
                val engine: TranscriptionEngine
                val upload: File
                val language = if (settings.language == "auto") null else settings.language
                if (local) {
                    engine = VoskEngine(app)
                    upload = audio
                } else if (settings.provider == "gemini") {
                    // Gemini dostaje nieskompresowany dźwięk (WAV) – najlepiej słyszy wtedy ciche wtrącenia.
                    engine = GeminiEngine(app.settings.apiKey(settings.provider), settings.geminiModels, language)
                    upload = audio
                } else {
                    engine = CloudEngine(settings.baseUrl, app.settings.apiKey(settings.provider), settings.model)
                    upload = AudioFiles.compressOrSame(audio)
                }
                val previous = dao.previousChunk(chunk.sessionId, chunk.idx)
                val prompt = TextComposer.buildPrompt(settings.glossary, previous?.text)
                val result = engine.transcribe(
                    TranscribeRequest(upload, language, prompt, context = speakerContext(previous), glossary = settings.glossary),
                )
                val text = TextComposer.clean(result.text)
                val kept = finishAudio(audio, upload, settings.keepAudio)
                dao.updateChunk(
                    chunk.copy(
                        status = ChunkStatus.DONE,
                        text = text,
                        segmentsJson = if (text.isEmpty()) "" else SegJson.toJson(result.segments),
                        audioPath = kept,
                        error = null,
                    ),
                )
                onChunkDone(chunk, text)
                stalled.value = null
                backoff = 2_000L
                netFails = 0
                serverFails = 0
            } catch (e: ConfigException) {
                stalled.value = e.message
                return Outcome.BLOCKED
            } catch (e: AuthException) {
                stalled.value = e.message
                return Outcome.BLOCKED
            } catch (e: IOException) {
                netFails++
                stalled.value = "Brak połączenia z internetem – nagranie czeka w telefonie i dokończy się samo"
                if (netFails >= 3) return Outcome.NETWORK
                delay(backoff)
                backoff = min(backoff * 2, 30_000L)
            } catch (e: ApiException) {
                if (e.code == 429 || e.code >= 500) {
                    serverFails++
                    stalled.value = "Serwer chwilowo niedostępny (${e.code}) – ponawiam"
                    if (serverFails >= 6) return Outcome.NETWORK
                    delay(backoff)
                    backoff = min(backoff * 2, 30_000L)
                } else {
                    fail(chunk, e.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(chunk, e.message)
            }
        }
    }

    /** Koniec poprzedniego fragmentu z oznaczeniami osób („A: …”) – dla Gemini. */
    private fun speakerContext(c: ChunkEntity?): String? {
        if (c == null) return null
        val segs = SegJson.fromJson(c.segmentsJson).filter { it.text.isNotBlank() }
        val text = if (segs.any { !it.speaker.isNullOrBlank() }) {
            segs.joinToString("\n") { s -> (s.speaker?.let { "$it: " } ?: "") + s.text }
        } else {
            c.text
        }
        return text.takeLast(500)
    }

    private suspend fun fail(chunk: ChunkEntity, message: String?) {
        val dao = App.instance.db.dao()
        val attempts = chunk.attempts + 1
        if (attempts >= 3) {
            dao.updateChunk(chunk.copy(status = ChunkStatus.FAILED, attempts = attempts, error = message))
            closeSessionIfDone(chunk.sessionId)
        } else {
            dao.updateChunk(chunk.copy(attempts = attempts, error = message))
            delay(1_500)
        }
    }

    /** Po transkrypcji: usuń nagranie albo zostaw skompresowaną kopię (zależnie od ustawień). */
    private fun finishAudio(wav: File, upload: File, keep: Boolean): String? {
        if (!keep) {
            wav.delete()
            if (upload != wav) upload.delete()
            return null
        }
        val m4a = if (upload != wav) upload else AudioFiles.compressOrSame(wav)
        if (m4a != wav) wav.delete()
        return m4a.absolutePath
    }

    private suspend fun onChunkDone(chunk: ChunkEntity, text: String) {
        val dao = App.instance.db.dao()
        val session = dao.session(chunk.sessionId) ?: return
        // Tekst był już ręcznie edytowany: dopisz nowy fragment na końcu, nie ruszaj poprawek.
        val edited = session.editedText
        if (edited != null && text.isNotEmpty()) {
            dao.setEditedText(session.id, if (edited.isBlank()) text else edited.trimEnd() + "\n\n" + text)
            dao.setPdfPath(session.id, null)
        }
        closeSessionIfDone(chunk.sessionId)
    }

    internal suspend fun closeSessionIfDone(sessionId: Long) {
        val dao = App.instance.db.dao()
        val session = dao.session(sessionId) ?: return
        if (session.status == SessionStatus.PROCESSING && dao.pendingCount(sessionId) == 0) {
            dao.setStatus(sessionId, SessionStatus.DONE)
            if (!App.instance.inForeground) notifyDone(App.instance, session.id, session.title)
        }
    }

    // ---------- powiadomienie o gotowej transkrypcji ----------

    private const val WORK_NAME = "transcription-queue"
    private const val DONE_CHANNEL = "done"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(DONE_CHANNEL, "Gotowe transkrypcje", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Informuje, że transkrypcja dokończyła się w tle"
        nm.createNotificationChannel(ch)
    }

    private fun notifyDone(context: Context, sessionId: Long, title: String) {
        val open = PendingIntent.getActivity(
            context, sessionId.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SESSION, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Transkrypcja gotowa")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(2000 + (sessionId % 1000).toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}

/** Dokańcza transkrypcję w tle, gdy wróci internet – także przy zamkniętej aplikacji. */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = App.instance.db.dao()
        if (dao.nextPending() == null) return Result.success()
        return when (TranscriptionQueue.drain()) {
            TranscriptionQueue.Outcome.EMPTY -> Result.success()
            TranscriptionQueue.Outcome.NETWORK -> Result.retry()
            // Zły klucz / brak modelu: czekamy, aż użytkownik poprawi ustawienia.
            TranscriptionQueue.Outcome.BLOCKED -> Result.success()
        }
    }
}
