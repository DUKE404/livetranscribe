package pl.livetranscribe.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pliku nie da się odczytać (nieznany format, brak dźwięku, uszkodzenie). */
class UnsupportedAudioException(message: String) : Exception(message)

/**
 * Odczytuje dźwięk z pliku dowolnego formatu obsługiwanego przez telefon
 * (MP3, M4A/AAC, WAV, OGG/Opus, FLAC, AMR, 3GP, a także ścieżkę dźwiękową z filmu MP4)
 * i oddaje go blokami jako 16 kHz mono 16 bit.
 */
object AudioDecoder {

    class Info(val durationMs: Long, val mime: String)

    /** Sprawdza plik przed wczytaniem: czy ma ścieżkę dźwiękową i czy telefon ma do niej dekoder. */
    fun probe(file: File): Info {
        val ex = MediaExtractor()
        try {
            try {
                ex.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                throw UnsupportedAudioException("Nie rozpoznano formatu pliku. Wybierz nagranie dźwiękowe (np. MP3, M4A, WAV).")
            }
            val track = audioTrack(ex) ?: throw UnsupportedAudioException("Ten plik nie zawiera dźwięku.")
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            try {
                MediaCodec.createDecoderByType(mime).release()
            } catch (e: Exception) {
                throw UnsupportedAudioException("Ten telefon nie potrafi odczytać dźwięku w tym formacie ($mime).")
            }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            return Info(durationUs / 1000, mime)
        } finally {
            ex.release()
        }
    }

    /**
     * Dekoduje cały plik. [onPcm] dostaje kolejne bloki 16 kHz mono, [onProgress] postęp 0..1
     * (gdy plik podaje czas trwania). Przerywa się po anulowaniu korutyny.
     */
    suspend fun decode(
        file: File,
        onProgress: suspend (Float) -> Unit,
        onPcm: suspend (ShortArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            val track = audioTrack(ex) ?: throw UnsupportedAudioException("Ten plik nie zawiera dźwięku.")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME).orEmpty())
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var converter: PcmConverter? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0

            while (true) {
                ensureActive()
                var progressed = false
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx) ?: throw IllegalStateException("Brak bufora dekodera")
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, ex.sampleTime.coerceAtLeast(0), 0)
                            ex.advance()
                        }
                        progressed = true
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 5_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    converter = null
                    progressed = true
                } else if (outIdx >= 0) {
                    progressed = true
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var pcm: ShortArray? = null
                    val ob = dec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val floats = toFloats(ob.slice().order(ByteOrder.LITTLE_ENDIAN), encoding)
                        val conv = converter ?: PcmConverter(rate, channels).also { converter = it }
                        pcm = conv.process(floats, floats.size / channels)
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (pcm != null && pcm.isNotEmpty()) onPcm(pcm)
                    if (durationUs > 0 && info.size > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    if (eos) break
                }
                idle = if (progressed) 0 else idle + 1
                // Dekoder, który przez dłuższy czas nic nie oddaje, uznajemy za zawieszony.
                if (idle > 3_000) throw IllegalStateException("Dekoder przestał odpowiadać")
            }
            onProgress(1f)
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            ex.release()
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /** Bufor dekodera (PCM w różnych formatach) -> próbki -1..1, kanały nadal przeplatane. */
    private fun toFloats(b: ByteBuffer, encoding: Int): FloatArray = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val fb = b.asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        }
        AudioFormat.ENCODING_PCM_8BIT -> FloatArray(b.remaining()) { ((b.get().toInt() and 0xFF) - 128) / 128f }
        ENCODING_PCM_24BIT_PACKED -> FloatArray(b.remaining() / 3) {
            val lo = b.get().toInt() and 0xFF
            val mid = b.get().toInt() and 0xFF
            val hi = b.get().toInt() // ze znakiem
            ((hi shl 16) or (mid shl 8) or lo) / 8_388_608f
        }
        ENCODING_PCM_32BIT -> {
            val ib = b.asIntBuffer()
            FloatArray(ib.remaining()) { ib.get() / 2_147_483_648f }
        }
        else -> {
            val sb = b.asShortBuffer()
            FloatArray(sb.remaining()) { sb.get() / 32768f }
        }
    }

    // Stałe z Androida 12 (API 31); dekodery na starszych systemach ich nie zwracają.
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22
}
