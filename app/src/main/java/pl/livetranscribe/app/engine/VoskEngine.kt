package pl.livetranscribe.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import pl.livetranscribe.app.audio.AudioFiles
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.min

/** Model języka polskiego dla silnika lokalnego (ok. 50 MB, pobierany raz). */
object LocalModel {
    const val NAME = "vosk-model-small-pl-0.22"
    const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"

    private var model: Model? = null

    fun dir(context: Context) = File(context.filesDir, "vosk/pl")
    private fun marker(context: Context) = File(dir(context), "READY")
    fun isInstalled(context: Context) = marker(context).exists()

    @Synchronized
    fun get(context: Context): Model {
        val cached = model
        if (cached != null) return cached
        if (!isInstalled(context)) throw ConfigException("Model lokalny nie jest pobrany. Pobierz go w Ustawieniach.")
        val m = try {
            Model(dir(context).absolutePath)
        } catch (e: Exception) {
            throw ConfigException("Nie udało się wczytać modelu lokalnego. Pobierz go ponownie w Ustawieniach.")
        }
        model = m
        return m
    }

    @Synchronized
    fun delete(context: Context) {
        try { model?.close() } catch (_: Exception) { }
        model = null
        dir(context).deleteRecursively()
    }

    /** Pobiera i rozpakowuje model. [onProgress]: 0..1. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = dir(context)
        target.deleteRecursively()
        target.mkdirs()
        val zip = File(context.cacheDir, "vosk-model.zip")
        try {
            CloudEngine.client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Serwer z modelem odpowiedział kodem ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Pusta odpowiedź serwera")
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(min(0.95f, done.toFloat() / total * 0.95f))
                        }
                    }
                }
            }
            val root = target.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    // Archiwum ma jeden katalog główny – pomijamy go.
                    val rel = entry.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val outFile = File(target, rel)
                    if (!outFile.canonicalPath.startsWith(root)) continue
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { zis.copyTo(it) }
                    }
                }
            }
            marker(context).writeText("ok")
            onProgress(1f)
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        } finally {
            zip.delete()
        }
    }
}

/**
 * Silnik lokalny: dźwięk nie opuszcza telefonu.
 * Jakość niższa niż w chmurze, a model nie stawia interpunkcji – dodajemy tylko
 * wielką literę i kropkę na granicach wypowiedzi.
 */
class VoskEngine(private val context: Context) : TranscriptionEngine {

    override suspend fun transcribe(req: TranscribeRequest): TranscribeResult = withContext(Dispatchers.Default) {
        if (!req.audio.name.endsWith(".wav")) throw ApiException(400, "Silnik lokalny wymaga nagrania WAV")
        val pcm = AudioFiles.readWav(req.audio)
        val model = LocalModel.get(context)
        val recognizer = try {
            Recognizer(model, AudioFiles.SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            throw ApiException(500, "Nie udało się uruchomić silnika lokalnego")
        }
        val parts = ArrayList<String>()
        try {
            val block = ShortArray(4000)
            var i = 0
            while (i < pcm.size) {
                val n = min(block.size, pcm.size - i)
                System.arraycopy(pcm, i, block, 0, n)
                if (recognizer.acceptWaveForm(block, n)) addText(recognizer.result, parts)
                i += n
            }
            addText(recognizer.finalResult, parts)
        } finally {
            recognizer.close()
        }
        TranscribeResult(parts.joinToString(" "), emptyList())
    }

    private fun addText(json: String, parts: MutableList<String>) {
        val t = try { JSONObject(json).optString("text").trim() } catch (e: Exception) { "" }
        if (t.isNotEmpty()) parts.add(t.replaceFirstChar { it.uppercase() } + ".")
    }
}
