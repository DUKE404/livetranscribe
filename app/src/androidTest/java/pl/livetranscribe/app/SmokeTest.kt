package pl.livetranscribe.app

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import pl.livetranscribe.app.audio.AudioFiles
import pl.livetranscribe.app.data.ChunkEntity
import pl.livetranscribe.app.data.ChunkStatus
import pl.livetranscribe.app.data.SessionEntity
import pl.livetranscribe.app.data.SessionStatus
import pl.livetranscribe.app.pdf.PdfActions
import pl.livetranscribe.app.pdf.PdfGenerator
import pl.livetranscribe.app.service.RecStatus
import pl.livetranscribe.app.service.RecordingBus
import pl.livetranscribe.app.service.AudioImport
import pl.livetranscribe.app.service.TranscriptionQueue
import pl.livetranscribe.app.audio.UnsupportedAudioException
import java.io.DataOutputStream
import java.io.FileOutputStream
import pl.livetranscribe.app.engine.MeetingNote
import pl.livetranscribe.app.engine.Summarizer
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Testy na prawdziwym Androidzie (emulator w GitHub Actions): uruchomienie aplikacji,
 * nawigacja, usługa nagrywania, kodowanie dźwięku, kolejka, PDF, zapis do Download.
 * Zrzuty ekranu i wygenerowany PDF trafiają do Download/, skąd pobiera je skrypt CI.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ctx.applicationContext as App

    private fun saveToDownloads(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LiveTranscribeTest")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use(write)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        saveToDownloads("$name.png", "image/png") { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun note(name: String, text: String) {
        saveToDownloads("$name.txt", "text/plain") { it.write(text.toByteArray()) }
    }

    private fun waitForText(text: String, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun a_screensOpen() {
        compose.onNodeWithText("ROZPOCZNIJ TRANSKRYPCJĘ").assertIsDisplayed()
        shot("01_start")
        compose.onNodeWithText("Historia").performClick()
        shot("02_historia")
        compose.onNodeWithText("Ustawienia").performClick()
        compose.onNodeWithText("Silnik transkrypcji").assertIsDisplayed()
        shot("03_ustawienia")
    }

    @Test
    fun b_recordingLifecycle() {
        compose.onNodeWithText("ROZPOCZNIJ TRANSKRYPCJĘ").performClick()
        // Jednorazowe przypomnienie o uprzedzeniu uczestników (RODO).
        waitForText("Rozumiem")
        compose.onNodeWithText("Rozumiem").performClick()
        // Bez klucza API aplikacja ostrzega, ale pozwala nagrywać.
        waitForText("Nagrywaj mimo to")
        shot("04_brak_klucza")
        compose.onNodeWithText("Nagrywaj mimo to").performClick()

        compose.waitUntil(15_000) {
            val st = RecordingBus.state.value
            st.sessionId != 0L || st.error != null
        }
        Thread.sleep(3_000)
        val st = RecordingBus.state.value
        note("nagrywanie", "status=${st.status} error=${st.error} elapsedMs=${RecordingBus.elapsedMs.value}")
        shot("05_nagrywanie")
        assertTrue("usługa nie wystartowała: $st", st.sessionId != 0L)
        val sessionId = st.sessionId

        if (st.status == RecStatus.RECORDING) {
            // Mikrofon emulatora działa: sprawdź, że czas płynie, oraz pauzę i wznowienie.
            assertTrue("czas nie płynie", RecordingBus.elapsedMs.value >= 1_500)
            compose.onNodeWithText("PAUZA").performClick()
            compose.waitUntil(10_000) { RecordingBus.state.value.status == RecStatus.PAUSED }
            shot("06_pauza")
            compose.onNodeWithText("WZNÓW").performClick()
            compose.waitUntil(10_000) { RecordingBus.state.value.status == RecStatus.RECORDING }
        }
        compose.onNodeWithText("ZAKOŃCZ").performClick()
        waitForText("Zakończ")
        compose.onNodeWithText("Zakończ").performClick()
        compose.waitUntil(40_000) { RecordingBus.state.value.status == RecStatus.IDLE }

        waitForText("GENERUJ PDF")
        shot("07_dokument")
        val saved = runBlocking { app.db.dao().session(sessionId) }
        assertNotNull("sesja nie została zapisana", saved)
        note("sesja", "status=${saved?.status} durationMs=${saved?.durationMs}")

        // Generowanie PDF z interfejsu: trzy przyciski muszą się pojawić.
        compose.onNodeWithText("GENERUJ PDF").performClick()
        waitForText("Generuj")
        compose.onNodeWithText("Generuj").performClick()
        waitForText("POBIERZ PDF", 20_000)
        compose.onNodeWithText("UDOSTĘPNIJ PDF").assertIsDisplayed()
        compose.onNodeWithText("OTWÓRZ PDF").assertIsDisplayed()
        shot("08_pdf_gotowy")
    }

    @Test
    fun c_pdfGenerationAndFiles() {
        val text = buildString {
            repeat(70) { i ->
                append("[%02d:%02d] ".format(i, 7))
                append("Zażółć gęślą jaźń. Na spotkaniu zespołu omówiono budżet, harmonogram i ryzyka projektu, a także następne kroki. ".repeat(3))
                append("\n\n")
            }
            append("Rozmówca A: Dziękuję wszystkim za udział.\n\nRozmówca B: Do zobaczenia w przyszłym tygodniu.")
        }
        val pdf = File(ctx.filesDir, "pdf/test/Test_LiveTranscribe.pdf")
        val pages = PdfGenerator.generate(
            pdf, "Spotkanie zespołu – zażółć gęślą jaźń ŁÓDŹ", System.currentTimeMillis(), 7_265_000, text, PdfGenerator.Options(),
            MeetingNote(
                "Zespół omówił budżet i harmonogram projektu. Ustalono priorytety na kolejny kwartał.",
                listOf("Budżet zostaje bez zmian", "Start wdrożenia 1 grudnia"),
                listOf(MeetingNote.Task("Przygotować harmonogram", "Anna", "piątek"), MeetingNote.Task("Wysłać ofertę", "", "")),
                listOf("Czy potrzebny jest dodatkowy tester?"),
            ),
        )
        assertTrue("za mało stron: $pages", pages >= 3)
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use {
            assertEquals(pages, it.pageCount)
        }
        val where = PdfActions.saveToDownloads(ctx, pdf, "Test LiveTranscribe")
        assertTrue(where, where.startsWith("Download/LiveTranscribe/"))

        // Plik musi być czytelny przez FileProvider (tak dostają go Gmail czy WhatsApp).
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", pdf)
        ctx.contentResolver.openInputStream(uri)!!.use { assertEquals('%'.code, it.read()) }
        note("pdf", "pages=$pages bytes=${pdf.length()} saved=$where")
    }

    @Test
    fun f_noteParsingAndMigration() {
        val raw = "Oto notatka:\n{\"streszczenie\": \"Krótko.\", \"decyzje\": [\"A\"], " +
            "\"zadania\": [{\"zadanie\": \"Zrobić X\", \"osoba\": \"Ola\", \"termin\": \"\"}, \"Zadzwonić\"], \"pytania_otwarte\": []}"
        val note = MeetingNote.fromJson(Summarizer.extractJson(raw))
        assertNotNull(note)
        assertEquals("Krótko.", note!!.summary)
        assertEquals(2, note.tasks.size)
        assertEquals("Zrobić X — Ola", note.tasks[0].line())
        assertEquals(note, MeetingNote.fromJson(note.toJson()))
        val parts = Summarizer.split("Zdanie. ".repeat(5000), Summarizer.PART_CHARS)
        assertTrue(parts.size >= 4)
        assertTrue(parts.all { it.length <= Summarizer.PART_CHARS })

        // Migracja bazy z wersji 1 (pierwsze wydanie) do 2: kolumna notatki dochodzi bez utraty danych.
        val f = ctx.getDatabasePath("migration-test.db").apply { delete(); parentFile?.mkdirs() }
        val raw1 = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null)
        raw1.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, durationMs INTEGER NOT NULL, status TEXT NOT NULL, engine TEXT NOT NULL, editedText TEXT, pdfPath TEXT)")
        raw1.execSQL("INSERT INTO sessions (title, createdAt, durationMs, status, engine) VALUES ('Stara', 1, 2, 'DONE', 'cloud')")
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx).name("migration-test.db")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build(),
        )
        raw1.version = 1
        raw1.close()
        val db = helper.writableDatabase
        pl.livetranscribe.app.data.MIGRATION_1_2.migrate(db)
        db.query("SELECT title, summaryJson FROM sessions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Stara", c.getString(0))
            assertTrue(c.isNull(1))
        }
        helper.close()
        f.delete()
    }

    @Test
    fun d_audioEncoding() {
        val pcm = ShortArray(16000 * 3) { (sin(2 * PI * 440 * it / 16000.0) * 8000).toInt().toShort() }
        val wav = File(ctx.cacheDir, "test_tone.wav")
        File(ctx.cacheDir, "test_tone.m4a").delete()
        AudioFiles.writeWav(wav, pcm)
        assertEquals(44L + pcm.size * 2, wav.length())
        assertEquals(pcm.size, AudioFiles.readWav(wav).size)

        val m4a = AudioFiles.compressOrSame(wav)
        note("audio", "plik=${m4a.name} bajty=${m4a.length()} (wav=${wav.length()})")
        assertTrue("kompresja AAC nie zadziałała", m4a.name.endsWith(".m4a"))
        assertTrue("podejrzany rozmiar: ${m4a.length()}", m4a.length() in 2_000..60_000)
        val ex = MediaExtractor()
        ex.setDataSource(m4a.absolutePath)
        val durationUs = ex.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
        ex.release()
        assertTrue("czas trwania: $durationUs", abs(durationUs - 3_000_000) < 300_000)
    }

    @Test
    fun e_queueKeepsAudioWhenItCannotTranscribe() {
        val dao = app.db.dao()
        val id = runBlocking {
            dao.insertSession(
                SessionEntity(title = "Test kolejki", createdAt = System.currentTimeMillis(), durationMs = 2_000, status = SessionStatus.PROCESSING, engine = "cloud"),
            )
        }
        val dir = File(ctx.filesDir, "audio/$id").apply { mkdirs() }
        val wav = File(dir, "fragment_00000.wav")
        AudioFiles.writeWav(wav, ShortArray(32_000) { (sin(it / 10.0) * 5000).toInt().toShort() })
        runBlocking {
            dao.insertChunk(ChunkEntity(sessionId = id, idx = 0, startMs = 0, endMs = 2_000, audioPath = wav.absolutePath, status = ChunkStatus.PENDING))
        }
        TranscriptionQueue.kick()
        val deadline = System.currentTimeMillis() + 10_000
        while (TranscriptionQueue.stalled.value == null && System.currentTimeMillis() < deadline) Thread.sleep(200)

        // Brak klucza API: kolejka staje z komunikatem, a fragment i nagranie zostają nietknięte.
        assertNotNull("kolejka powinna zgłosić problem", TranscriptionQueue.stalled.value)
        assertEquals(1, runBlocking { dao.pendingCount(id) })
        assertTrue(wav.exists())

        // Po przetworzeniu fragmentu tekst składa się w dokument.
        runBlocking {
            val chunk = dao.chunks(id).first()
            dao.updateChunk(chunk.copy(status = ChunkStatus.DONE, text = "Dzień dobry, zaczynamy."))
            assertEquals("Dzień dobry, zaczynamy.", app.repo.compose(dao.chunks(id), timestamps = false, speakers = false))
            assertEquals("[00:00] Dzień dobry, zaczynamy.", app.repo.compose(dao.chunks(id), timestamps = true, speakers = false))
            app.repo.deleteSession(id)
            assertEquals(null, dao.session(id))
        }
        assertTrue(!dir.exists())
        TranscriptionQueue.stalled.value = null
    }

    /** WAV o dowolnej częstotliwości i liczbie kanałów – jak z dyktafonu, nie jak z aplikacji. */
    private fun writeWav(file: File, rate: Int, channels: Int, seconds: List<Pair<Int, Boolean>>) {
        val frames = seconds.sumOf { it.first } * rate
        val dataLen = frames * channels * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            fun i32(v: Int) { o.write(v and 0xFF); o.write(v shr 8 and 0xFF); o.write(v shr 16 and 0xFF); o.write(v shr 24 and 0xFF) }
            fun i16(v: Int) { o.write(v and 0xFF); o.write(v shr 8 and 0xFF) }
            o.writeBytes("RIFF"); i32(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(channels); i32(rate); i32(rate * channels * 2); i16(channels * 2); i16(16)
            o.writeBytes("data"); i32(dataLen)
            var n = 0L
            for ((sec, loud) in seconds) {
                repeat(sec * rate) {
                    val env = 0.55 + 0.45 * sin(2 * PI * 3.0 * n / rate)
                    val v = if (loud) (sin(2 * PI * 220.0 * n / rate) * 9000 * env).toInt() else ((n * 7919) % 11 - 5).toInt()
                    repeat(channels) { i16(v) }
                    n++
                }
            }
        }
    }

    private fun waitImported(id: Long, timeoutMs: Long = 240_000): SessionEntity {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = runBlocking { app.db.dao().session(id) }!!
            if (s.status != SessionStatus.IMPORTING) return s
            Thread.sleep(300)
        }
        throw AssertionError("wczytywanie pliku nie skończyło się w czasie")
    }

    @Test
    fun g_importAudioFile() {
        val dao = app.db.dao()
        // 1) WAV 44,1 kHz stereo: 70 s mowy, 1 s ciszy, 60 s mowy -> dwa fragmenty, cięcie w pauzie.
        val wav = File(ctx.cacheDir, "Dyktafon 2026-11-13.wav")
        writeWav(wav, 44_100, 2, listOf(70 to true, 1 to false, 60 to true))
        val id = runBlocking { AudioImport.start(ctx, Uri.fromFile(wav)) }
        val s = waitImported(id)
        val chunks = runBlocking { dao.chunks(id) }
        note("import_wav", "status=${s.status} durationMs=${s.durationMs} chunks=" + chunks.joinToString { "${it.startMs}-${it.endMs} ${File(it.audioPath ?: "").name}" } + " error=${AudioImport.errors.value[id]}")
        assertEquals("Dyktafon 2026-11-13", s.title)
        assertEquals(null, AudioImport.errors.value[id])
        assertEquals(2, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(chunks[0].endMs, chunks[1].startMs)
        assertTrue("cięcie poza pauzą: ${chunks[0].endMs}", chunks[0].endMs in 70_000..71_000)
        assertTrue("koniec: ${chunks[1].endMs}", abs(chunks[1].endMs - 131_000) <= 50)
        assertTrue("czas trwania: ${s.durationMs}", abs(s.durationMs - 131_000) <= 50)
        assertTrue(chunks.all { it.status == ChunkStatus.PENDING && File(it.audioPath!!).exists() })
        assertTrue("kopia pliku nie została usunięta", !AudioImport.sourceDir(ctx, id).exists())
        shot("09_import_wczytany")

        // 2) M4A (AAC) – najczęstszy format dyktafonów w telefonach.
        val pcm = ShortArray(16_000 * 20) { (sin(2 * PI * 330 * it / 16000.0) * 7000 * (0.55 + 0.45 * sin(2 * PI * 3.0 * it / 16000.0))).toInt().toShort() }
        val src = File(ctx.cacheDir, "notatka.wav")
        File(ctx.cacheDir, "notatka.m4a").delete()
        AudioFiles.writeWav(src, pcm)
        val m4a = AudioFiles.compressOrSame(src)
        assertTrue(m4a.name.endsWith(".m4a"))
        val id2 = runBlocking { AudioImport.start(ctx, Uri.fromFile(m4a)) }
        val s2 = waitImported(id2)
        val c2 = runBlocking { dao.chunks(id2) }
        note("import_m4a", "durationMs=${s2.durationMs} chunks=" + c2.joinToString { "${it.startMs}-${it.endMs}" })
        assertEquals(1, c2.size)
        assertTrue("czas trwania m4a: ${s2.durationMs}", abs(s2.durationMs - 20_000) <= 300)

        // 3) Plik, który nie jest nagraniem: czytelny komunikat, żaden dokument nie powstaje.
        val before = runBlocking { dao.allSessions().size }
        val txt = File(ctx.cacheDir, "lista.txt").apply { writeText("to nie jest nagranie ".repeat(200)) }
        try {
            runBlocking { AudioImport.start(ctx, Uri.fromFile(txt)) }
            throw AssertionError("plik tekstowy nie powinien zostać przyjęty")
        } catch (e: UnsupportedAudioException) {
            note("import_txt", "komunikat=${e.message}")
        }
        assertEquals(before, runBlocking { dao.allSessions().size })

        runBlocking {
            app.repo.deleteSession(id)
            app.repo.deleteSession(id2)
        }
        assertTrue(!app.repo.audioDir(id).exists())
        TranscriptionQueue.stalled.value = null
    }
}
