package pl.livetranscribe.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.livetranscribe.app.App
import pl.livetranscribe.app.MainActivity
import pl.livetranscribe.app.R
import pl.livetranscribe.app.audio.AudioDecoder
import pl.livetranscribe.app.audio.AudioFiles
import pl.livetranscribe.app.audio.Chunker
import pl.livetranscribe.app.audio.FileSplitter
import pl.livetranscribe.app.audio.UnsupportedAudioException
import pl.livetranscribe.app.data.ChunkEntity
import pl.livetranscribe.app.data.ChunkStatus
import pl.livetranscribe.app.data.SessionEntity
import pl.livetranscribe.app.data.SessionStatus
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Transkrypcja gotowego pliku nagrania (np. z dyktafonu).
 *
 * 1. [start]: plik jest kopiowany do aplikacji i sprawdzany, powstaje dokument w stanie IMPORTING.
 * 2. [ImportWorker] (WorkManager, z powiadomieniem): dźwięk jest dekodowany, dzielony na fragmenty
 *    w pauzach i każdy fragment trafia do tej samej kolejki co nagrania na żywo – z tymi samymi
 *    zabezpieczeniami: brak internetu czy zamknięcie aplikacji niczego nie kasują.
 * 3. Po przerwaniu (np. system zamknął aplikację) wczytywanie wznawia się od końca ostatniego
 *    zapisanego fragmentu, więc nic się nie powtarza ani nie ginie.
 */
object AudioImport {

    /** Typy plików w oknie wyboru: dźwięk, filmy (ścieżka dźwiękowa) i OGG zgłaszane jako „application”. */
    val MIME_TYPES = arrayOf("audio/*", "video/*", "application/ogg")

    /** Postęp wczytywania: id dokumentu -> 0..1. */
    val progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** Problem z odczytem części pliku: id dokumentu -> komunikat. */
    val errors = MutableStateFlow<Map<Long, String>>(emptyMap())

    private const val KEY_SESSION = "session"
    private const val CHANNEL = "import"
    private const val TAG = "audio-import"
    private const val SPARE_BYTES = 100L * 1024 * 1024

    fun sourceDir(context: Context, sessionId: Long) = File(context.filesDir, "import/$sessionId")

    private fun sourceFile(context: Context, sessionId: Long): File? =
        sourceDir(context, sessionId).listFiles()?.firstOrNull { it.name.startsWith("source") && it.length() > 0 }

    private fun workName(sessionId: Long) = "import-$sessionId"

    /**
     * Kopiuje wybrany plik do aplikacji, sprawdza, czy da się go odczytać, i zakłada dokument.
     * Zwraca id dokumentu. Rzuca [UnsupportedAudioException] z komunikatem dla użytkownika.
     */
    suspend fun start(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val app = context.applicationContext as App
        val resolver = app.contentResolver
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')

        val dir = File(app.filesDir, "import").apply { mkdirs() }
        val free = dir.usableSpace
        if (size > 0 && size + SPARE_BYTES > free) {
            throw UnsupportedAudioException("Za mało miejsca w telefonie: plik ma ${mb(size)}, wolne jest ${mb(free)}.")
        }
        val ext = extension(name)
        val tmp = File(dir, "incoming_${System.nanoTime()}$ext")
        try {
            try {
                val input = resolver.openInputStream(uri) ?: throw FileNotFoundException()
                input.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
            } catch (e: SecurityException) {
                throw UnsupportedAudioException("Brak dostępu do pliku. Wybierz go ponownie.")
            } catch (e: FileNotFoundException) {
                throw UnsupportedAudioException("Nie udało się otworzyć pliku.")
            } catch (e: IOException) {
                tmp.delete()
                throw UnsupportedAudioException(
                    if (dir.usableSpace < SPARE_BYTES) "Za mało miejsca w telefonie, aby wczytać ten plik."
                    else "Nie udało się skopiować pliku: ${e.message ?: "błąd odczytu"}",
                )
            }
            if (tmp.length() == 0L) throw UnsupportedAudioException("Plik jest pusty.")
            val info = AudioDecoder.probe(tmp)

            val settings = app.settings.current
            val now = System.currentTimeMillis()
            val id = app.db.dao().insertSession(
                SessionEntity(
                    title = title(name, now),
                    createdAt = now,
                    durationMs = info.durationMs,
                    status = SessionStatus.IMPORTING,
                    engine = settings.engine,
                ),
            )
            val src = File(sourceDir(app, id).apply { mkdirs() }, "source$ext")
            if (!tmp.renameTo(src)) {
                tmp.copyTo(src, overwrite = true)
            }
            progress.update { it + (id to 0f) }
            enqueue(app, id)
            id
        } finally {
            tmp.delete()
        }
    }

    fun enqueue(context: Context, sessionId: Long) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(KEY_SESSION to sessionId))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(sessionId), ExistingWorkPolicy.KEEP, request)
    }

    /** Usunięcie dokumentu w trakcie wczytywania: zatrzymaj pracę i usuń kopię pliku. */
    fun cancel(context: Context, sessionId: Long) {
        try { WorkManager.getInstance(context).cancelUniqueWork(workName(sessionId)) } catch (_: Exception) { }
        sourceDir(context, sessionId).deleteRecursively()
        progress.update { it - sessionId }
        errors.update { it - sessionId }
    }

    fun cancelAll(context: Context) {
        try { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) } catch (_: Exception) { }
        File(context.filesDir, "import").deleteRecursively()
        progress.value = emptyMap()
        errors.value = emptyMap()
    }

    /** Po ponownym uruchomieniu aplikacji: dokończ przerwane wczytywanie. */
    suspend fun recover(context: Context) {
        // Kopiowanie przerwane przed utworzeniem dokumentu: plik tymczasowy jest już niepotrzebny.
        File(context.filesDir, "import").listFiles()?.filter { it.isFile && it.name.startsWith("incoming_") }?.forEach { it.delete() }
        val dao = (context.applicationContext as App).db.dao()
        for (s in dao.allSessions()) {
            if (s.status != SessionStatus.IMPORTING) continue
            if (sourceFile(context, s.id) != null) enqueue(context, s.id) else dao.setStatus(s.id, SessionStatus.PROCESSING)
        }
    }

    // ---------- praca w tle ----------

    /** Dekoduje plik i zapisuje fragmenty. Wznawia od końca ostatniego zapisanego fragmentu. */
    internal suspend fun process(worker: ImportWorker, sessionId: Long) {
        val app = App.instance
        val dao = app.db.dao()
        val session = dao.session(sessionId) ?: return
        if (session.status != SessionStatus.IMPORTING) return
        val src = sourceFile(app, sessionId)
        if (src == null) {
            finish(sessionId, null, null)
            return
        }
        worker.showProgress(session.title, progress.value[sessionId] ?: 0f)

        val resumeMs = dao.lastEndMs(sessionId) ?: 0L
        var toSkip = resumeMs * AudioFiles.SAMPLE_RATE / 1000
        var idx = dao.chunkCount(sessionId)
        val splitter = FileSplitter(AudioFiles.SAMPLE_RATE, startMs = resumeMs)
        val audioDir = app.repo.audioDir(sessionId).apply { mkdirs() }
        val cloud = session.engine != "local"
        var lastUpdate = 0L
        var lastNotice = 0L

        suspend fun save(c: Chunker.Chunk) {
            // Dokument usunięty w trakcie: przerwij.
            if (dao.session(sessionId) == null) throw SessionGone()
            val wav = File(audioDir, "fragment_%05d.wav".format(idx))
            // Po wznowieniu mogła zostać niedokończona kopia tego fragmentu z przerwanej próby.
            File(audioDir, "fragment_%05d.m4a".format(idx)).delete()
            AudioFiles.writeWav(wav, c.pcm)
            var file = wav
            if (cloud) {
                // Do chmury i tak trafia AAC – kompresja od razu oszczędza miejsce przy długich plikach.
                val m4a = AudioFiles.compressOrSame(wav)
                if (m4a != wav) {
                    wav.delete()
                    file = m4a
                }
            }
            dao.insertChunk(
                ChunkEntity(
                    sessionId = sessionId, idx = idx, startMs = c.startMs, endMs = c.endMs,
                    audioPath = file.absolutePath, status = ChunkStatus.PENDING,
                ),
            )
            idx++
            TranscriptionQueue.kick()
        }

        var error: String? = null
        try {
            AudioDecoder.decode(
                src,
                onProgress = { p ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 500 || p >= 1f) {
                        lastUpdate = now
                        progress.update { it + (sessionId to p) }
                    }
                    // Powiadomienie rzadziej – każda zmiana to wywołanie systemu.
                    if (now - lastNotice >= 2_000) {
                        lastNotice = now
                        worker.showProgress(session.title, p)
                    }
                },
                onPcm = { pcm ->
                    var from = 0
                    if (toSkip > 0) {
                        from = min(toSkip, pcm.size.toLong()).toInt()
                        toSkip -= from
                    }
                    if (from < pcm.size) {
                        val part = if (from == 0) pcm else pcm.copyOfRange(from, pcm.size)
                        for (c in splitter.feed(part)) save(c)
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionGone) {
            app.repo.audioDir(sessionId).deleteRecursively()
            sourceDir(app, sessionId).deleteRecursively()
            progress.update { it - sessionId }
            return
        } catch (e: UnsupportedAudioException) {
            error = e.message
        } catch (e: Exception) {
            error = "Nie udało się odczytać całego pliku (${e.message ?: e.javaClass.simpleName}). " +
                "Tekst obejmuje tylko część nagrania do ${formatMs(splitter.positionMs)}."
        }
        splitter.flush()?.let { save(it) }
        finish(sessionId, splitter.positionMs.takeIf { it > 0 }, error)
    }

    private class SessionGone : Exception()

    /** Koniec wczytywania: dokument przechodzi do zwykłego przetwarzania kolejki. */
    private suspend fun finish(sessionId: Long, durationMs: Long?, error: String?) {
        val app = App.instance
        val dao = app.db.dao()
        sourceDir(app, sessionId).deleteRecursively()
        progress.update { it - sessionId }
        if (dao.session(sessionId) == null) return
        if (durationMs != null) dao.setDuration(sessionId, durationMs)
        if (error != null) errors.update { it + (sessionId to error) }
        dao.setStatus(sessionId, SessionStatus.PROCESSING)
        TranscriptionQueue.closeSessionIfDone(sessionId)
        TranscriptionQueue.kick()
    }

    /** Nieoczekiwany błąd: nie zostawiaj dokumentu „wczytywanego” w nieskończoność. */
    internal suspend fun fail(sessionId: Long, e: Exception) {
        try {
            finish(sessionId, null, "Wczytywanie pliku przerwane: ${e.message ?: e.javaClass.simpleName}")
        } catch (_: Exception) {
        }
    }

    // ---------- powiadomienie ----------

    fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "Wczytywanie nagrań", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Postęp transkrypcji plików wczytanych z telefonu"
        nm.createNotificationChannel(ch)
    }

    internal fun foregroundInfo(context: Context, sessionId: Long, title: String, text: String, percent: Int?): ForegroundInfo {
        val open = PendingIntent.getActivity(
            context, 5000 + (sessionId % 1000).toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SESSION, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (percent != null) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        return ForegroundInfo(3000 + (sessionId % 1000).toInt(), b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    // ---------- pomocnicze ----------

    private fun extension(name: String?): String {
        val e = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return if (e.matches(Regex("[a-z0-9]{1,5}"))) ".$e" else ""
    }

    private fun title(name: String?, now: Long): String {
        val base = name?.substringBeforeLast('.')?.replace('_', ' ')?.trim().orEmpty()
        return base.take(80).ifBlank { "Nagranie " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl")).format(Date(now)) }
    }

    private fun mb(bytes: Long) = "%.0f MB".format(Locale("pl", "PL"), bytes / (1024.0 * 1024.0))

    private fun formatMs(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
    }
}

/**
 * Wczytuje plik w tle (także po zablokowaniu ekranu), a potem dokańcza transkrypcję,
 * dopóki system pozwala aplikacji pracować. Działa jako usługa z powiadomieniem.
 */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val sessionId = inputData.getLong("session", 0L)
    private var foregroundAllowed = true

    override suspend fun doWork(): Result {
        if (sessionId <= 0) return Result.success()
        try {
            AudioImport.process(this, sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AudioImport.fail(sessionId, e)
            return Result.success()
        }

        // Plik wczytany – dokończ transkrypcję fragmentów, póki praca ma status pierwszoplanowej.
        val dao = App.instance.db.dao()
        val session = dao.session(sessionId) ?: return Result.success()
        if (dao.pendingCount(sessionId) > 0) {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        val left = dao.pendingCount(sessionId)
                        if (left == 0) break
                        show(session.title, "Transkrypcja – pozostało fragmentów: $left", null)
                        delay(3_000)
                    }
                }
                val outcome = TranscriptionQueue.drain()
                ticker.cancel()
                if (outcome == TranscriptionQueue.Outcome.NETWORK) TranscriptionQueue.scheduleBackground(applicationContext)
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        AudioImport.foregroundInfo(applicationContext, sessionId, "LiveTranscribe", "Wczytywanie nagrania…", null)

    internal suspend fun showProgress(title: String, p: Float) {
        show(title, "Wczytywanie nagrania – ${(p * 100).toInt()}%", (p * 100).toInt())
    }

    private suspend fun show(title: String, text: String, percent: Int?) {
        if (!foregroundAllowed) return
        try {
            setForeground(AudioImport.foregroundInfo(applicationContext, sessionId, title, text, percent))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android nie pozwala uruchomić usługi pierwszoplanowej z tła – praca trwa dalej bez powiadomienia.
            foregroundAllowed = false
        }
    }
}
