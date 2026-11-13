package pl.livetranscribe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.livetranscribe.app.audio.Chunker
import pl.livetranscribe.app.audio.FileSplitter
import pl.livetranscribe.app.audio.PcmConverter
import pl.livetranscribe.app.engine.ChunkText
import pl.livetranscribe.app.engine.GeminiEngine
import pl.livetranscribe.app.engine.Seg
import pl.livetranscribe.app.engine.TextComposer
import pl.livetranscribe.app.pdf.Paginator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class LogicTest {

    private val sr = 16000
    private val rnd = Random(7)

    private fun speech(ms: Int): ShortArray = ShortArray(sr * ms / 1000) { i ->
        // „Mowa”: ton z modulacją głośności (sylaby), żeby istniały cichsze miejsca.
        val env = 0.55 + 0.45 * sin(2 * PI * 3.0 * i / sr)
        (sin(2 * PI * 220.0 * i / sr) * 6000 * env).toInt().toShort()
    }

    private fun silence(ms: Int): ShortArray = ShortArray(sr * ms / 1000) { (rnd.nextInt(11) - 5).toShort() }

    private fun run(chunker: Chunker, vararg parts: ShortArray): List<Chunker.Chunk> {
        val out = ArrayList<Chunker.Chunk>()
        for (p in parts) {
            var i = 0
            while (i < p.size) { // podawanie blokami po 100 ms, jak z mikrofonu
                val n = minOf(1600, p.size - i)
                out.addAll(chunker.feed(p.copyOfRange(i, i + n), n))
                i += n
            }
        }
        chunker.flush()?.let { out.add(it) }
        return out
    }

    @Test
    fun silenceProducesNoChunks() {
        val chunks = run(Chunker(sr, 2000, 6000), silence(8000))
        assertEquals(0, chunks.size)
    }

    @Test
    fun cutsOnPauses() {
        val chunks = run(Chunker(sr, 2000, 10000), silence(1000), speech(3000), silence(1200), speech(3000), silence(800))
        assertEquals(2, chunks.size)
        assertTrue("początek 1: ${chunks[0].startMs}", abs(chunks[0].startMs - 1000) <= 400)
        assertTrue("koniec 1: ${chunks[0].endMs}", abs(chunks[0].endMs - 4000) <= 400)
        assertTrue("początek 2: ${chunks[1].startMs}", abs(chunks[1].startMs - 5200) <= 400)
        assertTrue(chunks[1].startMs >= chunks[0].endMs)
    }

    @Test
    fun longSpeechIsSplitWithoutGapsOrOverlap() {
        val chunks = run(Chunker(sr, 3000, 8000), speech(30000))
        assertTrue("liczba fragmentów: ${chunks.size}", chunks.size >= 4)
        for (c in chunks) assertTrue("fragment za długi", c.endMs - c.startMs <= 8100)
        for (i in 1 until chunks.size) assertEquals(chunks[i - 1].endMs, chunks[i].startMs)
        val total = chunks.sumOf { it.pcm.size }
        assertTrue("suma próbek: $total", abs(total - sr * 30) <= sr / 25)
    }

    @Test
    fun timeKeepsRunningAcrossFlush() {
        val c = Chunker(sr, 2000, 6000)
        val a = run(c, speech(2500))
        val b = run(c, speech(2500))
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertTrue(b[0].startMs >= a[0].endMs)
        assertEquals(5000L, c.totalSamples * 1000 / sr)
    }

    @Test
    fun composePlainTimestampsAndSpeakers() {
        val chunks = listOf(
            ChunkText(0, "Dzień dobry wszystkim."),
            ChunkText(65_000, "Zaczynamy spotkanie."),
        )
        assertEquals("Dzień dobry wszystkim. Zaczynamy spotkanie.", TextComposer.compose(chunks, false, false))
        assertEquals(
            "[00:00] Dzień dobry wszystkim.\n\n[01:05] Zaczynamy spotkanie.",
            TextComposer.compose(chunks, true, false),
        )
        val diar = listOf(
            ChunkText(
                3_600_000, "Cześć. Hej. Co słychać?",
                listOf(Seg(0, 1000, "A", "Cześć."), Seg(1000, 2000, "B", "Hej."), Seg(2000, 3000, "B", "Co słychać?")),
            ),
        )
        assertEquals(
            "[1:00:00] Rozmówca A: Cześć.\n\n[1:00:01] Rozmówca B: Hej. Co słychać?",
            TextComposer.compose(diar, true, true),
        )
    }

    @Test
    fun promptCombinesGlossaryAndContext() {
        assertEquals(null, TextComposer.buildPrompt("", null))
        assertEquals("Słownik: Kowalczyk, KPI. Ostatnie zdanie.", TextComposer.buildPrompt(" Kowalczyk ,KPI;Kowalczyk", "Ostatnie zdanie."))
        assertEquals("Poprzedni tekst.", TextComposer.buildPrompt("  ", "Poprzedni tekst."))
        assertTrue(TextComposer.buildPrompt("A", "x".repeat(1000))!!.length < 200)
    }

    @Test
    fun cleanDropsKnownHallucinations() {
        assertEquals("", TextComposer.clean("Napisy stworzone przez społeczność Amara.org"))
        assertEquals("To jest zdanie.", TextComposer.clean("  To jest   zdanie. "))
    }

    @Test
    fun paginatorPlacesEveryLineOnce() {
        val blocks = List(40) { IntArray(1 + it % 9) { 17 } }
        val pages = Paginator.paginate(blocks, firstPageHeight = 500, otherPageHeight = 700, paragraphSpacing = 8)
        assertTrue(pages.size > 1)
        val seen = HashMap<Int, Int>()
        for ((p, page) in pages.withIndex()) {
            val limit = if (p == 0) 500 else 700
            for (s in page) {
                assertTrue("wyjście poza stronę", s.y + s.height <= limit)
                assertEquals(seen.getOrDefault(s.block, 0), s.from)
                seen[s.block] = s.to
            }
        }
        for ((b, lines) in blocks.withIndex()) assertEquals(lines.size, seen[b])
    }

    // ---------- wczytywanie plików nagrań ----------

    private fun splitAll(s: FileSplitter, vararg parts: ShortArray): List<Chunker.Chunk> {
        val out = ArrayList<Chunker.Chunk>()
        for (p in parts) {
            var i = 0
            while (i < p.size) { // bloki o „nierównej” długości, jak z dekodera
                val n = minOf(4410, p.size - i)
                out.addAll(s.feed(p.copyOfRange(i, i + n)))
                i += n
            }
        }
        s.flush()?.let { out.add(it) }
        return out
    }

    @Test
    fun fileSplitterCoversWholeRecordingWithoutGaps() {
        val parts = ArrayList<ShortArray>()
        repeat(12) { parts.add(speech(17_000)); parts.add(silence(700)) } // ok. 3,5 min mowy z krótkimi pauzami
        val total = parts.sumOf { it.size }
        val s = FileSplitter(sr, 30_000, 60_000)
        val chunks = splitAll(s, *parts.toTypedArray())
        assertTrue("liczba fragmentów: ${chunks.size}", chunks.size in 4..8)
        assertEquals(0L, chunks.first().startMs)
        for (i in 1 until chunks.size) assertEquals("dziura lub nakładanie", chunks[i - 1].endMs, chunks[i].startMs)
        for (c in chunks.dropLast(1)) assertTrue("długość ${c.endMs - c.startMs}", c.endMs - c.startMs in 30_000..60_000)
        assertEquals("nic nie może zginąć", total, chunks.sumOf { it.pcm.size })
        assertEquals(total * 1000L / sr, s.positionMs)
    }

    @Test
    fun fileSplitterCutsInsidePause() {
        val chunks = splitAll(FileSplitter(sr, 30_000, 60_000), speech(40_000), silence(1_000), speech(40_000))
        assertEquals(2, chunks.size)
        val cut = chunks[0].endMs
        assertTrue("cięcie w $cut ms zamiast w pauzie 40000–41000", cut in 40_000..41_000)
    }

    @Test
    fun fileSplitterSkipsSilenceButKeepsTime() {
        val s = FileSplitter(sr, 30_000, 60_000)
        val chunks = splitAll(s, silence(70_000), speech(5_000))
        assertEquals(1, chunks.size)
        assertTrue("start ${chunks[0].startMs}", chunks[0].startMs >= 30_000)
        assertEquals(75_000L, chunks[0].endMs)
        assertEquals(0, splitAll(FileSplitter(sr, 30_000, 60_000), silence(90_000)).size)
    }

    @Test
    fun fileSplitterResumesFromOffset() {
        val chunks = splitAll(FileSplitter(sr, 30_000, 60_000, startMs = 120_000), speech(10_000))
        assertEquals(1, chunks.size)
        assertEquals(120_000L, chunks[0].startMs)
        assertEquals(130_000L, chunks[0].endMs)
    }

    private fun tone(rate: Int, channels: Int, hz: Double, ms: Int, amp: Float = 0.5f): FloatArray {
        val frames = rate * ms / 1000
        return FloatArray(frames * channels) { i -> (sin(2 * PI * hz * (i / channels) / rate) * amp).toFloat() }
    }

    private fun convert(conv: PcmConverter, input: FloatArray, channels: Int, block: Int = 1152): ShortArray {
        val out = ArrayList<Short>()
        var f = 0
        val frames = input.size / channels
        while (f < frames) {
            val n = minOf(block, frames - f)
            out.addAll(conv.process(input.copyOfRange(f * channels, (f + n) * channels), n).toList())
            f += n
        }
        return out.toShortArray()
    }

    private fun rms(x: ShortArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until x.size) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (x.size - from))
    }

    @Test
    fun converterResamplesStereo48kTo16kMono() {
        val out = convert(PcmConverter(48_000, 2), tone(48_000, 2, 440.0, 2_000), 2)
        assertTrue("liczba próbek: ${out.size}", abs(out.size - 32_000) <= 2)
        val expected = 0.5 * 32767 / sqrt(2.0)
        assertTrue("głośność: ${rms(out, 800)}", abs(rms(out, 800) - expected) < expected * 0.05)
    }

    @Test
    fun converterUpsamples8kAndHandles44k() {
        val up = convert(PcmConverter(8_000, 1), tone(8_000, 1, 300.0, 1_000), 1, block = 160)
        assertTrue("8 kHz: ${up.size}", abs(up.size - 16_000) <= 2)
        val cd = convert(PcmConverter(44_100, 1), tone(44_100, 1, 1_000.0, 1_000), 1)
        assertTrue("44,1 kHz: ${cd.size}", abs(cd.size - 16_000) <= 2)
    }

    @Test
    fun converterFiltersOutHighFrequencies() {
        // Ton 12 kHz nie mieści się w 16 kHz – bez filtra wróciłby jako zniekształcenie 4 kHz.
        val out = convert(PcmConverter(48_000, 1), tone(48_000, 1, 12_000.0, 1_000), 1)
        assertTrue("przeciek: ${rms(out, 800)}", rms(out, 800) < 0.5 * 32767 * 0.05)
    }

    @Test
    fun hallucinationFilterDropsInventedPhrasesButKeepsSpeech() {
        fun h(text: String, start: Double, end: Double, ns: Double, lp: Double, cr: Double = 1.2) =
            TextComposer.isHallucination(text, start, end, ns, lp, cr)
        // Zmyślenia z ciszy / szumu (jak na zgłoszonym PDF)
        assertTrue(h("Dzięki za uwagę!", 0.0, 10.0, 0.45, -0.6))
        assertTrue(!h("Następna stacja: Rondo Daszyńskiego.", 0.0, 3.0, 0.05, -0.35)) // zapowiedź w metrze to mowa
        assertTrue(h("Dzień dobry.", 0.0, 14.0, 0.05, -0.3))      // 2 słowa „rozciągnięte” na 14 s ciszy
        assertTrue(h("...", 0.0, 12.0, 0.0, -0.1))
        assertTrue(h("Napisy wykonane przez społeczność", 0.0, 5.0, 0.2, -0.4))
        assertTrue(h("Omówiliśmy budżet na przyszły rok.", 0.0, 8.0, 0.7, -0.9))
        assertTrue(h("tak tak tak tak tak tak tak tak tak tak", 0.0, 8.0, 0.0, -0.2, 3.1))
        // Prawdziwa mowa zostaje
        assertTrue(!h("Dzień dobry.", 0.0, 1.2, 0.02, -0.2))
        assertTrue(!h("Dziękuję za uwagę, to wszystko na dziś.", 0.0, 3.0, 0.01, -0.25))
        assertTrue(!h("Omówiliśmy budżet.", 0.0, 2.0, 0.7, -0.3))
        assertTrue(!h("Dzień dobry", 0.0, 1.0, 0.0, 0.0))          // modele bez tych pól (OpenAI)
        // Czyjeś „Dzięki.” wtrącone w rozmowę (nie jedyna wypowiedź we fragmencie) zostaje
        assertTrue(!TextComposer.isHallucination("Dzięki.", 4.0, 5.0, 0.3, -0.6, 1.0, alone = false))
        assertTrue(TextComposer.isHallucination("Dzięki.", 0.0, 9.0, 0.3, -0.6, 1.0, alone = true))
        assertEquals("", TextComposer.clean("…"))
        assertEquals("Tak.", TextComposer.clean("Tak."))
    }

    @Test
    fun geminiResponseWithSpeakersAndInterjection() {
        val inner = """{"segments":[{"speaker":"A","text":"Proponuję przesunąć termin na piątek."},""" +
            """{"speaker":"B","text":"Nie, piątek odpada."},{"speaker":"Rozmówca A","text":"To czwartek?"},{"speaker":"B","text":" "}]}"""
        val api = org.json.JSONObject().put(
            "candidates",
            org.json.JSONArray().put(
                org.json.JSONObject().put(
                    "content",
                    org.json.JSONObject().put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", inner))),
                ),
            ),
        ).toString()
        val segs = GeminiEngine.parseResponse(api)
        assertEquals(3, segs.size)
        assertEquals(listOf("A", "B", "A"), segs.map { it.speaker })
        assertEquals("Nie, piątek odpada.", segs[1].text)
        // Wypowiedzi różnych osób w dokumencie: „Rozmówca A: …”, „Rozmówca B: …”
        val doc = TextComposer.compose(listOf(ChunkText(0, segs.joinToString(" ") { it.text }, segs)), false, true)
        assertTrue(doc, doc.contains("Rozmówca B: Nie, piątek odpada."))
        // JSON w bloku kodu, pusta lista, śmieci
        assertEquals(1, GeminiEngine.parseSegments("```json\n{\"segments\":[{\"speaker\":\"a\",\"text\":\"Tak.\"}]}\n```").size)
        assertEquals(0, GeminiEngine.parseSegments("{\"segments\":[]}").size)
        assertEquals(0, GeminiEngine.parseSegments("nie wiem").size)
        val prompt = GeminiEngine.instruction("pl", "A: Dzień dobry.\nB: Cześć.", "Kowalczyk")
        assertTrue(prompt.contains("polski") && prompt.contains("B: Cześć.") && prompt.contains("Kowalczyk"))
    }
}
