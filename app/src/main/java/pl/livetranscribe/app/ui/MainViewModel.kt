package pl.livetranscribe.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.livetranscribe.app.App
import pl.livetranscribe.app.data.AppSettings
import pl.livetranscribe.app.data.ChunkEntity
import pl.livetranscribe.app.data.SessionEntity
import pl.livetranscribe.app.data.SessionStatus
import pl.livetranscribe.app.engine.CloudEngine
import pl.livetranscribe.app.engine.LocalModel
import pl.livetranscribe.app.pdf.PdfActions
import pl.livetranscribe.app.pdf.PdfGenerator
import pl.livetranscribe.app.service.RecState
import pl.livetranscribe.app.service.RecStatus
import pl.livetranscribe.app.service.RecordingBus
import pl.livetranscribe.app.service.RecordingService
import pl.livetranscribe.app.service.TranscriptionQueue
import java.io.File

/** ViewModel (MVVM): ekrany czytają stan z przepływów i wołają akcje; logika żyje poniżej. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val dao = app.db.dao()

    val settings: StateFlow<AppSettings> = app.settings.flow
    val sessions: StateFlow<List<SessionEntity>> =
        dao.sessionsFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rec: StateFlow<RecState> = RecordingBus.state
    val elapsedMs: StateFlow<Long> = RecordingBus.elapsedMs
    val level: StateFlow<Float> = RecordingBus.level
    val queueProblem: StateFlow<String?> = TranscriptionQueue.stalled

    val modelProgress = MutableStateFlow<Float?>(null)
    val modelInstalled = MutableStateFlow(LocalModel.isInstalled(app))
    val modelError = MutableStateFlow<String?>(null)

    // ---------- ustawienia ----------

    fun update(block: (AppSettings) -> AppSettings) {
        app.settings.update(block)
        retryQueue()
    }

    fun apiKey(provider: String = settings.value.provider): String = app.settings.apiKey(provider)

    fun saveApiKey(provider: String, key: String): Boolean {
        val ok = app.settings.setApiKey(provider, key)
        retryQueue()
        return ok
    }

    suspend fun checkKey(): String? {
        val s = settings.value
        return CloudEngine.checkKey(s.baseUrl, app.settings.apiKey(s.provider))
    }

    fun retryQueue() {
        TranscriptionQueue.stalled.value = null
        TranscriptionQueue.kick()
    }

    fun freeSpace(): Long = app.filesDir.usableSpace

    fun downloadModel() {
        if (modelProgress.value != null) return
        modelProgress.value = 0f
        modelError.value = null
        viewModelScope.launch {
            try {
                LocalModel.download(app) { modelProgress.value = it }
                modelInstalled.value = true
                retryQueue()
            } catch (e: Exception) {
                modelError.value = "Nie udało się pobrać modelu: ${e.message ?: "brak połączenia"}"
            } finally {
                modelProgress.value = null
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch(Dispatchers.IO) {
            LocalModel.delete(app)
            modelInstalled.value = false
        }
    }

    // ---------- nagrywanie ----------

    fun start(context: Context) = RecordingService.start(context)
    fun pause(context: Context) = RecordingService.send(context, RecordingService.ACTION_PAUSE)
    fun resume(context: Context) = RecordingService.send(context, RecordingService.ACTION_RESUME)
    fun stop(context: Context) = RecordingService.send(context, RecordingService.ACTION_STOP)
    fun discard(context: Context) = RecordingService.send(context, RecordingService.ACTION_DISCARD)

    fun clearRecError() {
        RecordingBus.state.value = RecordingBus.state.value.copy(error = null)
    }

    val isRecordingActive: Boolean get() = rec.value.status != RecStatus.IDLE

    // ---------- dokumenty ----------

    fun sessionFlow(id: Long): Flow<SessionEntity?> = dao.sessionFlow(id)
    fun chunksFlow(id: Long): Flow<List<ChunkEntity>> = dao.chunksFlow(id)

    fun compose(chunks: List<ChunkEntity>, timestamps: Boolean, speakers: Boolean): String =
        app.repo.compose(chunks, timestamps, speakers)

    private suspend fun invalidatePdf(id: Long) {
        dao.session(id)?.pdfPath?.let { File(it).delete() }
        dao.setPdfPath(id, null)
    }

    fun rename(id: Long, title: String) = viewModelScope.launch {
        dao.setTitle(id, title.trim().ifEmpty { "Transkrypcja" })
        invalidatePdf(id)
    }

    /** Edycja tekstu: identyfikator edytowanego dokumentu (0 = brak) i bieżący szkic. */
    var editingId by mutableStateOf(0L)
        private set
    var draft by mutableStateOf("")

    fun startEdit(id: Long, text: String) {
        draft = text
        editingId = id
    }

    fun cancelEdit() {
        editingId = 0L
        draft = ""
    }

    fun saveEdit(id: Long, text: String) = viewModelScope.launch {
        dao.setEditedText(id, text)
        invalidatePdf(id)
    }

    fun restoreOriginal(id: Long) = viewModelScope.launch {
        dao.setEditedText(id, null)
        invalidatePdf(id)
    }

    fun retryFailed(id: Long) = viewModelScope.launch {
        dao.retryFailed(id)
        if (dao.pendingCount(id) > 0 && dao.session(id)?.status == SessionStatus.DONE) {
            dao.setStatus(id, SessionStatus.PROCESSING)
        }
        retryQueue()
    }

    fun delete(id: Long) = viewModelScope.launch { app.repo.deleteSession(id) }

    fun deleteHistory(onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repo.deleteHistory() }
        onDone()
    }

    fun deleteEverything(onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repo.deleteEverything() }
        modelInstalled.value = false
        onDone()
    }

    /** Generuje PDF i zapamiętuje go przy dokumencie. Zwraca plik albo rzuca wyjątek. */
    suspend fun generatePdf(id: Long, timestamps: Boolean, speakers: Boolean): File = withContext(Dispatchers.Default) {
        val session = dao.session(id) ?: throw IllegalStateException("Dokument nie istnieje")
        val text = app.repo.textOf(session, dao.chunks(id), timestamps, speakers)
        val s = settings.value
        val dir = File(app.repo.pdfDir(), id.toString())
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, PdfActions.safeName(session.title) + ".pdf")
        val fontSize = when (s.pdfFontSize) { "small" -> 10f; "large" -> 13.5f; else -> 11.5f }
        PdfGenerator.generate(file, session.title, session.createdAt, session.durationMs, text, PdfGenerator.Options(fontSize, s.pdfHeader))
        dao.setPdfPath(id, file.absolutePath)
        file
    }

    fun audioFiles(chunks: List<ChunkEntity>): List<File> =
        chunks.mapNotNull { c -> c.audioPath?.let { File(it) }?.takeIf { it.exists() && it.name.endsWith(".m4a") } }
}
