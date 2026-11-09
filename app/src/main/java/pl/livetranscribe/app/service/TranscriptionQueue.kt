package pl.livetranscribe.app.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.livetranscribe.app.App
import pl.livetranscribe.app.audio.AudioFiles
import pl.livetranscribe.app.data.ChunkEntity
import pl.livetranscribe.app.data.ChunkStatus
import pl.livetranscribe.app.data.SessionStatus
import pl.livetranscribe.app.engine.ApiException
import pl.livetranscribe.app.engine.AuthException
import pl.livetranscribe.app.engine.CloudEngine
import pl.livetranscribe.app.engine.ConfigException
import pl.livetranscribe.app.engine.SegJson
import pl.livetranscribe.app.engine.TextComposer
import pl.livetranscribe.app.engine.TranscribeRequest
import pl.livetranscribe.app.engine.TranscriptionEngine
import pl.livetranscribe.app.engine.VoskEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Kolejka fragmentów czekających na transkrypcję.
 *
 * Każdy fragment jest najpierw zapisany na dysku i w bazie, a dopiero potem przetwarzany.
 * Dzięki temu brak internetu, błąd serwera czy zamknięcie aplikacji niczego nie kasują:
 * fragmenty czekają i są przetwarzane po kolei, gdy tylko to możliwe.
 */
object TranscriptionQueue {

    private val running = AtomicBoolean(false)

    /** Powód wstrzymania kolejki (brak sieci, zły klucz…) albo null, gdy wszystko działa. */
    val stalled = MutableStateFlow<String?>(null)

    fun kick() {
        if (!running.compareAndSet(false, true)) return
        App.appScope.launch {
            var retry = false
            try {
                retry = loop()
            } catch (e: Exception) {
                stalled.value = "Błąd kolejki: ${e.message}"
            } finally {
                running.set(false)
            }
            // Fragment mógł dojść w chwili kończenia pętli.
            if (retry && App.instance.db.dao().nextPending() != null) kick()
        }
    }

    /** Zwraca true, jeśli pętla skończyła się normalnie (kolejka pusta). */
    private suspend fun loop(): Boolean {
        val app = App.instance
        val dao = app.db.dao()
        var backoff = 2_000L
        while (true) {
            val chunk = dao.nextPending() ?: return true
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
                if (local) {
                    engine = VoskEngine(app)
                    upload = audio
                } else {
                    engine = CloudEngine(settings.baseUrl, app.settings.apiKey(settings.provider), settings.model)
                    upload = AudioFiles.compressOrSame(audio)
                }
                val language = if (settings.language == "auto") null else settings.language
                val prompt = dao.previousText(chunk.sessionId, chunk.idx)?.takeLast(200)
                val result = engine.transcribe(TranscribeRequest(upload, language, prompt))
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
            } catch (e: ConfigException) {
                stalled.value = e.message
                return false
            } catch (e: AuthException) {
                stalled.value = e.message
                return false
            } catch (e: IOException) {
                stalled.value = "Brak połączenia z internetem – fragmenty czekają w kolejce"
                delay(backoff)
                backoff = min(backoff * 2, 30_000L)
            } catch (e: ApiException) {
                if (e.code == 429 || e.code >= 500) {
                    stalled.value = "Serwer chwilowo niedostępny (${e.code}) – ponawiam"
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

    private suspend fun closeSessionIfDone(sessionId: Long) {
        val dao = App.instance.db.dao()
        val session = dao.session(sessionId) ?: return
        if (session.status == SessionStatus.PROCESSING && dao.pendingCount(sessionId) == 0) {
            dao.setStatus(sessionId, SessionStatus.DONE)
        }
    }
}
