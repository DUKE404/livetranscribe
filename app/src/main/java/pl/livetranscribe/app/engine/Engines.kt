package pl.livetranscribe.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TranscribeRequest(
    val audio: File,
    /** Kod języka (np. "pl") albo null = wykrywanie automatyczne. */
    val language: String?,
    /** Koniec poprzedniego fragmentu – pomaga zachować ciągłość zdań. */
    val prompt: String?,
)

class TranscribeResult(val text: String, val segments: List<Seg>)

/** Brak konfiguracji (np. klucza API albo modelu lokalnego). */
class ConfigException(message: String) : Exception(message)

/** Serwer odrzucił klucz API. */
class AuthException(message: String) : Exception(message)

/** Inny błąd zwrócony przez serwer. */
class ApiException(val code: Int, message: String) : Exception(message)

/** Wspólny interfejs silników – dzięki niemu można przełączać chmurę i tryb lokalny. */
interface TranscriptionEngine {
    suspend fun transcribe(req: TranscribeRequest): TranscribeResult
}

object SegJson {
    fun toJson(segs: List<Seg>): String {
        if (segs.isEmpty()) return ""
        val arr = JSONArray()
        for (s in segs) {
            val o = JSONObject()
            o.put("s", s.startMs)
            o.put("e", s.endMs)
            if (s.speaker != null) o.put("p", s.speaker)
            o.put("t", s.text)
            arr.put(o)
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<Seg> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Seg(o.optLong("s"), o.optLong("e"), if (o.has("p")) o.optString("p") else null, o.optString("t"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Silnik chmurowy zgodny z API OpenAI (`/audio/transcriptions`).
 * Działa z OpenAI, Groq i innymi zgodnymi usługami. Połączenie wyłącznie przez HTTPS.
 */
class CloudEngine(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : TranscriptionEngine {

    override suspend fun transcribe(req: TranscribeRequest): TranscribeResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ConfigException("Brak klucza API. Dodaj go w Ustawieniach.")
        if (!baseUrl.startsWith("https://")) throw ConfigException("Adres API musi zaczynać się od https://")
        val diarize = model.contains("diarize")
        val format = when {
            diarize -> "diarized_json"
            model.startsWith("whisper") -> "verbose_json"
            else -> "json"
        }
        try {
            call(req, format, withPrompt = !diarize)
        } catch (e: ApiException) {
            // Część usług nie zna rozszerzonych formatów – ponów w najprostszym.
            if (e.code == 400 && format == "verbose_json") call(req, "json", withPrompt = false) else throw e
        }
    }

    private fun call(req: TranscribeRequest, format: String, withPrompt: Boolean): TranscribeResult {
        val type = if (req.audio.name.endsWith(".m4a")) "audio/mp4" else "audio/wav"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", req.audio.name, req.audio.asRequestBody(type.toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", format)
        val language = req.language
        if (!language.isNullOrBlank()) body.addFormDataPart("language", language)
        val prompt = req.prompt
        if (withPrompt && !prompt.isNullOrBlank()) body.addFormDataPart("prompt", prompt)
        if (format == "diarized_json") body.addFormDataPart("chunking_strategy", "auto")
        if (model.startsWith("whisper")) body.addFormDataPart("temperature", "0")

        val request = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.build())
            .build()

        return client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw AuthException("Serwer odrzucił klucz API. Sprawdź go w Ustawieniach.")
            }
            if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(text, resp.code))
            parse(text)
        }
    }

    private fun parse(json: String): TranscribeResult {
        val o = try { JSONObject(json) } catch (e: Exception) { return TranscribeResult(json.trim(), emptyList()) }
        val segs = ArrayList<Seg>()
        var dropped = 0
        val arr = o.optJSONArray("segments")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val t = s.optString("text").trim()
                if (t.isEmpty()) continue
                val start = s.optDouble("start", 0.0)
                val end = s.optDouble("end", 0.0)
                // Odcinki z ciszy lub szumu, które Whisper „dopowiedział” (np. „Dziękuję za uwagę”), są pomijane.
                if (TextComposer.isHallucination(
                        t, start, end,
                        s.optDouble("no_speech_prob", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                        s.optDouble("avg_logprob", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                        s.optDouble("compression_ratio", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    )
                ) {
                    dropped++
                    continue
                }
                val speaker = if (s.has("speaker") && !s.isNull("speaker")) s.optString("speaker") else null
                segs.add(Seg((start * 1000).toLong(), (end * 1000).toLong(), speaker, t))
            }
        }
        var text = o.optString("text").trim()
        if (dropped > 0 || (text.isEmpty() && segs.isNotEmpty())) text = segs.joinToString(" ") { it.text }
        return TranscribeResult(text, segs)
    }

    companion object {
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        fun errorMessage(body: String, code: Int): String {
            val msg = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
            return if (msg.isNullOrBlank()) "Błąd serwera ($code)" else "Błąd serwera ($code): $msg"
        }

        /** Sprawdza klucz bez wysyłania dźwięku (lista modeli). Zwraca null, gdy jest poprawny. */
        suspend fun checkKey(baseUrl: String, apiKey: String): String? = withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext "Wpisz klucz API."
            if (!baseUrl.startsWith("https://")) return@withContext "Adres API musi zaczynać się od https://"
            try {
                val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").get().build()
                client.newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> null
                        resp.code == 401 || resp.code == 403 -> "Klucz jest nieprawidłowy."
                        else -> "Serwer odpowiedział kodem ${resp.code}."
                    }
                }
            } catch (e: Exception) {
                "Brak połączenia z serwerem."
            }
        }
    }
}
