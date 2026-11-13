package pl.livetranscribe.app.data

import android.content.Context
import pl.livetranscribe.app.audio.AudioFiles
import pl.livetranscribe.app.audio.Chunker
import pl.livetranscribe.app.engine.ChunkText
import pl.livetranscribe.app.engine.LocalModel
import pl.livetranscribe.app.engine.SegJson
import pl.livetranscribe.app.engine.TextComposer
import pl.livetranscribe.app.service.AudioImport
import pl.livetranscribe.app.service.RecordingService
import java.io.File

/** Jedno miejsce dostępu do danych: baza, pliki nagrań i PDF. */
class Repository(
    private val context: Context,
    val dao: AppDao,
    private val settings: SettingsStore,
) {
    fun audioDir(sessionId: Long) = File(context.filesDir, "audio/$sessionId")
    fun pdfDir() = File(context.filesDir, "pdf").apply { mkdirs() }

    /** Tekst złożony z przetworzonych fragmentów. */
    fun compose(chunks: List<ChunkEntity>, timestamps: Boolean, speakers: Boolean): String =
        TextComposer.compose(
            chunks.filter { it.status == ChunkStatus.DONE }
                .map { ChunkText(it.startMs, it.text, SegJson.fromJson(it.segmentsJson)) },
            timestamps, speakers,
        )

    /** Tekst dokumentu: poprawiony ręcznie albo złożony z fragmentów. */
    fun textOf(session: SessionEntity, chunks: List<ChunkEntity>, timestamps: Boolean, speakers: Boolean): String =
        session.editedText ?: compose(chunks, timestamps, speakers)

    suspend fun deleteSession(id: Long) {
        AudioImport.cancel(context, id)
        val s = dao.session(id)
        s?.pdfPath?.let { File(it).delete() }
        audioDir(id).deleteRecursively()
        RecordingService.tailFile(context, id).delete()
        dao.deleteSession(id)
    }

    suspend fun deleteHistory() {
        AudioImport.cancelAll(context)
        dao.deleteAllSessions()
        File(context.filesDir, "audio").deleteRecursively()
        File(context.filesDir, "pdf").deleteRecursively()
    }

    /** Usuwa wszystko: historię, nagrania, PDF-y w aplikacji, klucze API, model lokalny, ustawienia. */
    suspend fun deleteEverything() {
        deleteHistory()
        settings.clearSecrets()
        LocalModel.delete(context)
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        settings.update { AppSettings() }
    }

    /**
     * Po nagłym zamknięciu aplikacji: sesje, które „wciąż nagrywają”, zostają zamknięte,
     * a ostatni niedokończony fragment jest odzyskiwany z kopii na dysku.
     */
    suspend fun recoverInterrupted() {
        for (s in dao.allSessions()) {
            if (s.status == SessionStatus.RECORDING || s.status == SessionStatus.PAUSED) {
                recoverTail(s)
                val last = dao.lastEndMs(s.id) ?: 0L
                if (last > s.durationMs) dao.setDuration(s.id, last)
                dao.setStatus(s.id, SessionStatus.PROCESSING)
            }
        }
        // Przerwane wczytywanie pliku: wznów (albo zamknij, gdy kopii pliku już nie ma).
        AudioImport.recover(context)
        for (s in dao.allSessions()) {
            if (s.status == SessionStatus.PROCESSING && dao.pendingCount(s.id) == 0) {
                dao.setStatus(s.id, SessionStatus.DONE)
            }
        }
    }

    private suspend fun recoverTail(s: SessionEntity) {
        val tail = RecordingService.tailFile(context, s.id)
        try {
            if (!tail.exists() || tail.length() < AudioFiles.SAMPLE_RATE) return // mniej niż 0,5 s
            val pcm = AudioFiles.readRawPcm(tail)
            val chunker = Chunker(AudioFiles.SAMPLE_RATE, 60_000, 120_000)
            val found = ArrayList(chunker.feed(pcm, pcm.size))
            chunker.flush()?.let { found.add(it) }
            val offset = dao.lastEndMs(s.id) ?: 0L
            var idx = dao.chunkCount(s.id)
            val dir = audioDir(s.id).apply { mkdirs() }
            for (c in found) {
                val file = File(dir, "fragment_%05d.wav".format(idx))
                AudioFiles.writeWav(file, c.pcm)
                dao.insertChunk(
                    ChunkEntity(
                        sessionId = s.id, idx = idx,
                        startMs = offset + c.startMs, endMs = offset + c.endMs,
                        audioPath = file.absolutePath, status = ChunkStatus.PENDING,
                    ),
                )
                idx++
            }
        } catch (_: Exception) {
        } finally {
            tail.delete()
        }
    }
}
