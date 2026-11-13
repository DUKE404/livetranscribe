package pl.livetranscribe.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Zamienia dźwięk odczytany z pliku (dowolna częstotliwość, mono lub kilka kanałów)
 * na 16 kHz mono 16 bit – format, w którym aplikacja zapisuje fragmenty do transkrypcji.
 *
 * Działa strumieniowo, blok po bloku, więc nawet kilkugodzinne nagranie nie trafia w całości do pamięci.
 * Przy zmniejszaniu częstotliwości najpierw działa filtr dolnoprzepustowy (antyaliasing),
 * a potem interpolacja liniowa.
 *
 * Klasa nie zależy od Androida, dzięki czemu jest testowana zwykłymi testami jednostkowymi.
 */
class PcmConverter(
    private val inRate: Int,
    private val channels: Int,
    private val outRate: Int = AudioFiles.SAMPLE_RATE,
) {
    init {
        require(inRate > 0 && channels > 0 && outRate > 0) { "Nieprawidłowy format dźwięku" }
    }

    /** Ile próbek wejściowych przypada na jedną wyjściową. */
    private val step = inRate.toDouble() / outRate

    /** Pozycja następnej próbki wyjściowej względem początku bieżącego bloku (-1 = ostatnia próbka poprzedniego). */
    private var t = 0.0
    private var prev = 0f

    // Filtr Butterwortha 4. rzędu (dwa biquady) z odcięciem nieco poniżej połowy docelowej częstotliwości.
    private val filters: Array<Biquad>? =
        if (inRate > outRate) {
            val cutoff = 0.45 * outRate
            arrayOf(Biquad.lowpass(inRate, cutoff, 0.5412), Biquad.lowpass(inRate, cutoff, 1.3066))
        } else {
            null
        }

    private var mono = FloatArray(0)

    /**
     * [input]: próbki przeplatane (L, R, L, R…) w zakresie -1..1, [frames]: liczba próbek na kanał.
     * Zwraca gotowe próbki 16 kHz mono.
     */
    fun process(input: FloatArray, frames: Int): ShortArray {
        if (frames <= 0) return ShortArray(0)
        if (mono.size < frames) mono = FloatArray(frames)
        val m = mono
        // Kanały -> mono (średnia).
        if (channels == 1) {
            System.arraycopy(input, 0, m, 0, frames)
        } else {
            var k = 0
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += input[k++]
                m[i] = sum / channels
            }
        }
        filters?.let { fs -> for (f in fs) f.run(m, frames) }

        // Interpolacja liniowa między sąsiednimi próbkami wejściowymi.
        val out = ShortArray(((frames - t) / step).toInt() + 2)
        var o = 0
        while (t < frames - 1) {
            val i = floor(t).toInt()
            val frac = (t - i).toFloat()
            val a = if (i < 0) prev else m[i]
            val b = m[i + 1]
            out[o++] = toShort(a + (b - a) * frac)
            t += step
        }
        t -= frames
        prev = m[frames - 1]
        return if (o == out.size) out else out.copyOf(o)
    }

    private fun toShort(v: Float): Short {
        val c = if (v > 1f) 1f else if (v < -1f) -1f else v
        return (c * 32767f).toInt().toShort()
    }

    /** Filtr dwubiegunowy (wzory z „Audio EQ Cookbook”), postać transponowana II. */
    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f

        fun run(x: FloatArray, n: Int) {
            for (i in 0 until n) {
                val v = x[i]
                val y = b0 * v + z1
                z1 = b1 * v - a1 * y + z2
                z2 = b2 * v - a2 * y
                x[i] = y
            }
        }

        companion object {
            fun lowpass(rate: Int, cutoff: Double, q: Double): Biquad {
                val w0 = 2 * PI * cutoff / rate
                val alpha = sin(w0) / (2 * q)
                val cw = cos(w0)
                val a0 = 1 + alpha
                return Biquad(
                    ((1 - cw) / 2 / a0).toFloat(),
                    ((1 - cw) / a0).toFloat(),
                    ((1 - cw) / 2 / a0).toFloat(),
                    (-2 * cw / a0).toFloat(),
                    ((1 - alpha) / a0).toFloat(),
                )
            }
        }
    }
}
