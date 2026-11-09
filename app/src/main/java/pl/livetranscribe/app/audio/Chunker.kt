package pl.livetranscribe.app.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dzieli ciągły strumień PCM (16 bit, mono) na fragmenty do transkrypcji.
 *
 * Zasady:
 *  - fragment zaczyna się dopiero, gdy pojawi się mowa (cisza między wypowiedziami jest pomijana),
 *  - fragment kończy się na pauzie w mowie, więc zdania nie są przecinane w połowie słowa,
 *  - jeżeli ktoś mówi bez przerwy dłużej niż [maxChunkMs], cięcie wypada w najcichszym
 *    miejscu ostatnich sekund, a reszta przechodzi do następnego fragmentu,
 *  - fragmenty nie nachodzą na siebie i nie mają dziur: nic się nie powtarza ani nie ginie.
 *
 * Klasa nie zależy od Androida, dzięki czemu jest testowana zwykłymi testami jednostkowymi.
 */
class Chunker(
    private val sampleRate: Int = 16000,
    private val minChunkMs: Int = 10_000,
    private val maxChunkMs: Int = 25_000,
    private val silenceMs: Int = 500,
    private val longSilenceMs: Int = 1_500,
) {
    class Chunk(val pcm: ShortArray, val startMs: Long, val endMs: Long)

    private val frameLen = sampleRate / 50 // 20 ms
    private val frame = ShortArray(frameLen)
    private var frameFill = 0
    private var framesSeen = 0L

    private var buf = ShortArray(sampleRate * 12)
    private var size = 0
    private val energies = ArrayList<Float>()
    private val preRoll = ArrayDeque<Pair<ShortArray, Float>>()

    private var inChunk = false
    private var chunkStartSample = 0L
    private var speechFrames = 0
    private var trailingSilent = 0
    private var noiseFloor = 60f

    /** Liczba wszystkich przyjętych próbek (czas nagrania bez pauz). */
    var totalSamples = 0L
        private set

    /** True, gdy nie trwa żaden fragment (cisza). */
    val isIdle: Boolean get() = !inChunk

    fun feed(samples: ShortArray, len: Int): List<Chunk> {
        val out = ArrayList<Chunk>(1)
        var i = 0
        while (i < len) {
            val n = min(frameLen - frameFill, len - i)
            System.arraycopy(samples, i, frame, frameFill, n)
            frameFill += n
            i += n
            if (frameFill == frameLen) {
                onFrame(out)
                frameFill = 0
                framesSeen++
            }
        }
        totalSamples += len
        return out
    }

    /** Kończy bieżący fragment (pauza / koniec nagrania). */
    fun flush(): Chunk? {
        frameFill = 0
        preRoll.clear()
        if (!inChunk || size == 0) {
            reset()
            return null
        }
        val out = ArrayList<Chunk>(1)
        emit(size, out)
        reset()
        return out.firstOrNull()
    }

    private fun reset() {
        inChunk = false
        size = 0
        energies.clear()
        speechFrames = 0
        trailingSilent = 0
    }

    private fun onFrame(out: MutableList<Chunk>) {
        val rms = rms(frame)
        val threshold = max(MIN_SPEECH_RMS, noiseFloor * 2f)
        val speech = rms > threshold

        // Szum tła: szybko w dół, bardzo wolno w górę.
        noiseFloor = if (rms < noiseFloor) rms else noiseFloor + (rms - noiseFloor) * 0.002f
        noiseFloor = noiseFloor.coerceIn(10f, 1500f)

        if (!inChunk) {
            preRoll.addLast(frame.copyOf() to rms)
            if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            if (speech) {
                inChunk = true
                chunkStartSample = (framesSeen - (preRoll.size - 1)) * frameLen
                for ((f, e) in preRoll) append(f, e)
                preRoll.clear()
                speechFrames = 1
                trailingSilent = 0
            }
            return
        }

        append(frame, rms)
        if (speech) {
            speechFrames++
            trailingSilent = 0
        } else {
            trailingSilent++
        }

        val durMs = size * 1000L / sampleRate
        val trailMs = trailingSilent * 20
        when {
            durMs >= minChunkMs && trailMs >= silenceMs -> emit(size, out)
            trailMs >= longSilenceMs -> emit(size, out)
            durMs >= maxChunkMs -> emit(quietestCut(), out)
        }
    }

    private fun append(f: ShortArray, energy: Float) {
        if (size + frameLen > buf.size) buf = buf.copyOf(buf.size * 2)
        System.arraycopy(f, 0, buf, size, frameLen)
        size += frameLen
        energies.add(energy)
    }

    /** Miejsce cięcia przy mowie bez pauz: najcichsza ramka z ostatnich 5 s. */
    private fun quietestCut(): Int {
        val n = energies.size
        val from = max(n - 250, n / 2)
        val to = n - 10
        var best = to
        var bestE = Float.MAX_VALUE
        for (i in from until to) {
            if (energies[i] < bestE) {
                bestE = energies[i]
                best = i
            }
        }
        return (best + 1) * frameLen
    }

    private fun emit(cutSamples: Int, out: MutableList<Chunk>) {
        val whole = cutSamples >= size
        var pcmLen = cutSamples
        if (whole && trailingSilent > KEEP_TRAILING_FRAMES) {
            // Zostaw tylko 200 ms ciszy na końcu.
            pcmLen = size - (trailingSilent - KEEP_TRAILING_FRAMES) * frameLen
        }
        // Pojedyncze stuknięcie to nie mowa – nie wysyłamy go do transkrypcji.
        if (pcmLen > 0 && (!whole || speechFrames >= MIN_SPEECH_FRAMES)) {
            val startMs = chunkStartSample * 1000 / sampleRate
            val endMs = (chunkStartSample + pcmLen) * 1000 / sampleRate
            out.add(Chunk(buf.copyOf(pcmLen), startMs, endMs))
        }
        if (whole) {
            reset()
        } else {
            val rest = size - cutSamples
            System.arraycopy(buf, cutSamples, buf, 0, rest)
            size = rest
            val cutFrames = cutSamples / frameLen
            val restEnergies = ArrayList(energies.subList(cutFrames, energies.size))
            energies.clear()
            energies.addAll(restEnergies)
            chunkStartSample += cutSamples
            speechFrames = MIN_SPEECH_FRAMES
            trailingSilent = 0
        }
    }

    private fun rms(f: ShortArray): Float {
        var sum = 0.0
        for (s in f) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / f.size).toFloat()
    }

    companion object {
        const val MIN_SPEECH_RMS = 45f
        const val PRE_ROLL_FRAMES = 15      // 300 ms przed początkiem mowy
        const val KEEP_TRAILING_FRAMES = 10 // 200 ms ciszy na końcu
        const val MIN_SPEECH_FRAMES = 8     // 160 ms
    }
}
