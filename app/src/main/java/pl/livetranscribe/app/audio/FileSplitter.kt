package pl.livetranscribe.app.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dzieli wczytany plik nagrania (16 kHz mono) na kolejne fragmenty do transkrypcji.
 *
 * Inaczej niż [Chunker] przy nagrywaniu na żywo, tu nic nie jest pomijane: fragmenty przylegają
 * do siebie i razem obejmują całe nagranie, także ciche wypowiedzi z dalszych miejsc sali.
 * Cięcie wypada w najcichszym miejscu (pauzie) między [minChunkMs] a [maxChunkMs], więc zdania
 * nie są przecinane w połowie słowa. Dłuższe fragmenty niż przy nagrywaniu na żywo dają modelowi
 * więcej kontekstu i oznaczają mniej zapytań do usługi.
 *
 * Pomijane są tylko fragmenty, w których praktycznie nie ma dźwięku (np. przerwa w nagraniu),
 * bo na samej ciszy modele potrafią „zmyślać” tekst. Czas mimo to płynie dalej.
 *
 * [startMs]: początek pierwszego fragmentu – przy wznowieniu przerwanego wczytywania.
 * Klasa nie zależy od Androida, dzięki czemu jest testowana zwykłymi testami jednostkowymi.
 */
class FileSplitter(
    private val sampleRate: Int = AudioFiles.SAMPLE_RATE,
    minChunkMs: Int = 60_000,
    maxChunkMs: Int = 120_000,
    startMs: Long = 0,
) {
    private val frameLen = sampleRate / 50 // 20 ms
    private val minFrames = minChunkMs / 20
    private val maxSamples = maxChunkMs / 20 * frameLen

    private val buf = ShortArray(maxSamples)
    private var size = 0
    private var startSample = startMs * sampleRate / 1000

    /** Czas (ms) od początku nagrania do końca dotychczas przyjętych próbek. */
    val positionMs: Long get() = (startSample + size) * 1000 / sampleRate

    fun feed(samples: ShortArray, len: Int = samples.size): List<Chunker.Chunk> {
        val out = ArrayList<Chunker.Chunk>(1)
        var i = 0
        while (i < len) {
            val n = min(maxSamples - size, len - i)
            System.arraycopy(samples, i, buf, size, n)
            size += n
            i += n
            if (size == maxSamples) cut(out)
        }
        return out
    }

    /** Koniec pliku: oddaje resztę jako ostatni fragment. */
    fun flush(): Chunker.Chunk? {
        val out = ArrayList<Chunker.Chunk>(1)
        if (size > 0) emit(size, out)
        return out.firstOrNull()
    }

    private fun cut(out: MutableList<Chunker.Chunk>) {
        val frames = size / frameLen
        val rms = FloatArray(frames) { frameRms(it * frameLen) }
        // Najcichsze okno 300 ms – prawdziwa pauza, a nie pojedyncza cicha chwila między sylabami.
        val half = WINDOW_FRAMES / 2
        var best = frames
        var bestSum = Float.MAX_VALUE
        for (c in max(minFrames, 1) until frames) {
            var sum = 0f
            for (k in max(0, c - half)..min(frames - 1, c + half)) sum += rms[k]
            if (sum < bestSum) {
                bestSum = sum
                best = c
            }
        }
        emit(best * frameLen, out)
    }

    private fun emit(cutSamples: Int, out: MutableList<Chunker.Chunk>) {
        val startMs = startSample * 1000 / sampleRate
        val endMs = (startSample + cutSamples) * 1000 / sampleRate
        if (hasSound(cutSamples)) out.add(Chunker.Chunk(buf.copyOf(cutSamples), startMs, endMs))
        System.arraycopy(buf, cutSamples, buf, 0, size - cutSamples)
        size -= cutSamples
        startSample += cutSamples
    }

    /** Czy we fragmencie jest cokolwiek poza ciszą lub jednostajnym szumem. */
    private fun hasSound(len: Int): Boolean {
        val frames = len / frameLen
        if (frames == 0) return false
        val rms = FloatArray(frames) { frameRms(it * frameLen) }
        val floor = rms.sortedArray()[frames / 10]
        val threshold = max(SILENT_RMS, floor * 2f)
        var loud = 0
        for (e in rms) if (e > threshold) loud++
        return loud >= min(MIN_SOUND_FRAMES, max(1, frames / 4))
    }

    private fun frameRms(from: Int): Float {
        var sum = 0.0
        for (i in from until from + frameLen) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / frameLen).toFloat()
    }

    companion object {
        const val WINDOW_FRAMES = 15      // 300 ms
        const val SILENT_RMS = 20f        // ok. -64 dBFS: poniżej tego to cisza cyfrowa
        const val MIN_SOUND_FRAMES = 25   // 0,5 s wyraźnego dźwięku
    }
}
