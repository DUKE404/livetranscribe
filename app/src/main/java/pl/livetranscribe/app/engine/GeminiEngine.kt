package pl.livetranscribe.app.engine

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.livetranscribe.app.audio.AudioDecoder
import pl.livetranscribe.app.audio.AudioFiles
import java.io.File

/**
 * Transkrypcja przez Google Gemini (darmowy klucz z aistudio.google.com).
 *
 * Gemini słucha fragmentu jak człowiek: zapisuje także krótkie wtrącenia i wypowiedzi nakładające się
 * na siebie, a przy okazji oznacza, kto mówi (A, B, C…). Koniec poprzedniego fragmentu z oznaczeniami
 * trafia do polecenia, żeby te same osoby zachowywały litery w kolejnych fragmentach.
 */
class GeminiEngine(
    private val apiKey: String,
    private val models: List<String> = MODELS,
    private val language: String? = "pl",
) : TranscriptionEngine {

    override suspend fun transcribe(req: TranscribeRequest): TranscribeResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ConfigException("Brak klucza API. Dodaj go w Ustawieniach.")
        val wav = wavBytes(req.audio)
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", instruction(language, req.context, req.glossary)))
                    .put(
                        JSONObject().put("type", "input_audio").put(
                            "input_audio",
                            JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav"),
                        ),
                    ),
            ),
        )

        var last: String? = null
        var schema = true
        var m = 0
        while (m < models.size) {
            val body = JSONObject().put("model", models[m]).put("messages", messages).put("temperature", 0)
            if (schema) {
                body.put(
                    "response_format",
                    JSONObject().put("type", "json_schema")
                        .put("json_schema", JSONObject().put("name", "transkrypcja").put("schema", SCHEMA)),
                )
            }
            val request = Request.Builder()
                .url("$OPENAI_URL/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val (code, text) = CloudEngine.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            if (code in 200..299) {
                val segs = parseResponse(text)
                return@withContext TranscribeResult(segs.joinToString(" ") { it.text }, segs)
            }
            val msg = errorMessage(text)
            val modelMissing = code == 404 ||
                (code == 400 && msg.contains("model", true) && (msg.contains("not found", true) || msg.contains("not supported", true)))
            when {
                modelMissing -> { last = msg; m++ } // model wycofany lub niedostępny – następny z listy
                code == 429 && DAILY.containsMatchIn(text) && m < models.size - 1 -> { last = msg; m++ } // dzienny limit modelu
                code == 429 && DAILY.containsMatchIn(text) -> throw ApiException(
                    429,
                    "Wyczerpany dzienny limit darmowego planu Gemini. Nagranie czeka w telefonie i dokończy się po odnowieniu " +
                        "limitu (następnego dnia) – albo przełącz dostawcę na Groq w Ustawieniach.",
                )
                code == 429 -> throw ApiException(429, "Limit zapytań Gemini na minutę – ponowię za chwilę.")
                code == 401 || code == 403 || (code == 400 && msg.contains("api key", true)) ->
                    throw AuthException("Google odrzucił klucz API. Sprawdź go w Ustawieniach.")
                // Model nie przyjmuje wymuszonego formatu – ten sam model jeszcze raz, JSON tylko z polecenia.
                code == 400 && schema && Regex("response_format|schema|json", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> schema = false
                else -> throw ApiException(code, if (msg.isBlank()) "Błąd usługi Gemini ($code)" else "Błąd usługi Gemini ($code): $msg")
            }
        }
        throw ApiException(400, "Żaden model Gemini nie jest dostępny dla tego klucza" + (last?.let { ": $it" } ?: "."))
    }

    /** Gemini dostaje WAV; skompresowane fragmenty (M4A z wczytanych plików) są najpierw dekodowane. */
    private suspend fun wavBytes(file: File): ByteArray {
        if (file.name.endsWith(".wav")) return file.readBytes()
        val blocks = ArrayList<ShortArray>()
        AudioDecoder.decode(file, onProgress = {}) { blocks.add(it) }
        val pcm = ShortArray(blocks.sumOf { it.size })
        var at = 0
        for (b in blocks) {
            System.arraycopy(b, 0, pcm, at, b.size)
            at += b.size
        }
        val tmp = File.createTempFile("gemini", ".wav", file.parentFile)
        return try {
            AudioFiles.writeWav(tmp, pcm)
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** Adres Google zgodny z OpenAI: transkrypcja (input_audio), notatki AI i sprawdzanie klucza. */
        const val OPENAI_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
        /**
         * Najpierw Flash-Lite: w darmowym planie ma wielokrotnie większy dzienny limit (ok. 500 zapytań wobec
         * ok. 20 dla Flash, stan na jesień 2026). Każdy model ma osobny limit – po wyczerpaniu dziennego
         * przechodzimy do następnego.
         */
        val MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-2.5-flash")
        private val DAILY = Regex("PerDay|per[ _-]?day|daily", RegexOption.IGNORE_CASE)

        private val LANGS = mapOf("pl" to "polski", "en" to "angielski", "de" to "niemiecki", "uk" to "ukraiński", "fr" to "francuski", "es" to "hiszpański")

        private val SCHEMA: JSONObject
            get() = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "segments",
                        JSONObject().put("type", "array").put(
                            "items",
                            JSONObject().put("type", "object")
                                .put("properties", JSONObject().put("speaker", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string")))
                                .put("required", JSONArray().put("speaker").put("text")),
                        ),
                    ),
                )
                .put("required", JSONArray().put("segments"))

        fun instruction(language: String?, context: String?, glossary: String?): String {
            val lang = LANGS[language]
            return listOfNotNull(
                "Jesteś stenografem. Przepisz dosłownie wszystko, co mówią wszystkie osoby w tym nagraniu" +
                    (if (lang != null) " (język: $lang)" else "") +
                    " – także krótkie wtrącenia, potakiwania i wypowiedzi, które nakładają się na siebie.",
                "Nie streszczaj, nie poprawiaj stylu, nie tłumacz i nie dopisuj niczego, czego nie słychać. Postaw interpunkcję.",
                "Każdą wypowiedź przypisz do osoby: A, B, C… Nową literę daj tylko wtedy, gdy mówi inny głos niż wcześniej.",
                context?.takeIf { it.isNotBlank() }?.let { "Koniec poprzedniego fragmentu tej samej rozmowy – te same osoby zachowują swoje litery:\n\"\"\"${it.takeLast(600)}\"\"\"" },
                glossary?.takeIf { it.isNotBlank() }?.let { "Nazwy, które mogą paść w rozmowie: ${it.take(300)}." },
                "Jeśli w nagraniu nie ma mowy (cisza, szum, muzyka), zwróć pustą listę wypowiedzi.",
                "Odpowiedz wyłącznie w formacie JSON: {\"segments\":[{\"speaker\":\"A\",\"text\":\"…\"}]}",
            ).joinToString("\n")
        }

        /** Odpowiedź chat/completions -> odcinki z rozmówcami. */
        fun parseResponse(json: String): List<Seg> {
            val o = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
            val content = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            return parseSegments(content)
        }

        /** Komunikat błędu Google (obiekt albo tablica z obiektem). */
        fun errorMessage(body: String): String = try {
            val t = body.trim()
            val o = if (t.startsWith("[")) JSONArray(t).optJSONObject(0) else JSONObject(t)
            o?.optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }

        /** Tekst modelu (JSON, czasem w bloku kodu) -> odcinki. */
        fun parseSegments(raw: String): List<Seg> {
            var s = raw.trim()
            Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(s)?.let { s = it.groupValues[1].trim() }
            val list: JSONArray = try {
                if (s.startsWith("[")) JSONArray(s) else JSONObject(s).optJSONArray("segments") ?: JSONArray()
            } catch (e: Exception) {
                val i = s.indexOf('{')
                val j = s.lastIndexOf('}')
                if (i < 0 || j <= i) return emptyList()
                try { JSONObject(s.substring(i, j + 1)).optJSONArray("segments") ?: JSONArray() } catch (e2: Exception) { return emptyList() }
            }
            val out = ArrayList<Seg>()
            for (k in 0 until list.length()) {
                val x = list.optJSONObject(k) ?: continue
                val text = x.optString("text").trim()
                if (text.isEmpty()) continue
                val speaker = x.optString("speaker").trim()
                    .replace(Regex("^(rozmówca|osoba|speaker)\\s*", RegexOption.IGNORE_CASE), "")
                    .uppercase().take(3).ifEmpty { null }
                out.add(Seg(0, 0, speaker, text))
            }
            return out
        }
    }
}
