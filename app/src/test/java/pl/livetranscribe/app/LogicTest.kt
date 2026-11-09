package pl.livetranscribe.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.livetranscribe.app.audio.Chunker
import pl.livetranscribe.app.engine.ChunkText
import pl.livetranscribe.app.engine.Seg
import pl.livetranscribe.app.engine.TextComposer
import pl.livetranscribe.app.pdf.Paginator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
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
}
