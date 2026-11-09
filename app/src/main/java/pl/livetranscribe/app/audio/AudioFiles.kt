package pl.livetranscribe.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object AudioFiles {
    const val SAMPLE_RATE = 16000

    /** Zapisuje PCM 16 bit mono jako plik WAV. */
    fun writeWav(file: File, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)                  // PCM
        bb.putShort(1)                  // mono
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)       // bajty na sekundę
        bb.putShort(2)                  // bajty na próbkę
        bb.putShort(16)                 // bity
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataLen)
        bb.asShortBuffer().put(pcm)
        FileOutputStream(file).use { it.write(bb.array()) }
    }

    /** Wczytuje próbki z pliku WAV zapisanego przez [writeWav]. */
    fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return ShortArray(0)
        val sb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ShortArray(sb.remaining())
        sb.get(out)
        return out
    }

    /** Surowe PCM (little endian) -> próbki. */
    fun readRawPcm(file: File): ShortArray {
        val bytes = file.readBytes()
        val n = bytes.size / 2
        if (n == 0) return ShortArray(0)
        val sb = ByteBuffer.wrap(bytes, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ShortArray(n)
        sb.get(out)
        return out
    }

    /**
     * Kompresuje WAV do M4A (AAC), ok. 8 razy mniej danych do wysłania.
     * Gdy kodowanie się nie uda, zwraca oryginalny WAV – transkrypcja i tak zadziała.
     */
    fun compressOrSame(wav: File): File {
        if (!wav.name.endsWith(".wav")) return wav
        val m4a = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
        if (m4a.exists() && m4a.length() > 0) return m4a
        return try {
            encodeAac(readWav(wav), SAMPLE_RATE, m4a)
            if (m4a.length() > 0) m4a else wav
        } catch (e: Exception) {
            m4a.delete()
            wav
        }
    }

    private fun encodeAac(pcm: ShortArray, sampleRate: Int, out: File, bitrate: Int = 32_000) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var offset = 0
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 60_000

            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("AAC encoder timeout")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: throw IllegalStateException("no input buffer")
                        buf.clear()
                        val n = min(buf.capacity() / 2, pcm.size - offset)
                        val pts = offset * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, n)
                            codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                            offset += n
                        }
                    }
                }
                var outIdx = codec.dequeueOutputBuffer(info, 10_000)
                while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIdx >= 0) {
                        val ob = codec.getOutputBuffer(outIdx) ?: throw IllegalStateException("no output buffer")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            codec.release()
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
        }
    }
}
