package pl.livetranscribe.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Notatka ze spotkania przygotowana przez model językowy. */
data class MeetingNote(
    val summary: String,
    val decisions: List<String>,
    val tasks: List<Task>,
    val openQuestions: List<String>,
) {
    data class Task(val what: String, val who: String, val due: String) {
        fun line(): String {
            val extra = listOf(who, due).filter { it.isNotBlank() }
            return if (extra.isEmpty()) what else "$what — ${extra.joinToString(", ")}"
        }
    }

    val isEmpty: Boolean get() = summary.isBlank() && decisions.isEmpty() && tasks.isEmpty() && openQuestions.isEmpty()

    fun toJson(): String = JSONObject()
        .put("streszczenie", summary)
        .put("decyzje", JSONArray(decisions))
        .put("zadania", JSONArray(tasks.map { JSONObject().put("zadanie", it.what).put("osoba", it.who).put("termin", it.due) }))
        .put("pytania_otwarte", JSONArray(openQuestions))
        .toString()

    /** Zwykły tekst – do skopiowania lub wysłania w wiadomości. */
    fun toPlainText(): String = buildString {
        if (summary.isNotBlank()) append("Podsumowanie\n").append(summary.trim()).append("\n\n")
        if (decisions.isNotEmpty()) append("Decyzje\n").append(decisions.joinToString("\n") { "• $it" }).append("\n\n")
        if (tasks.isNotEmpty()) append("Zadania\n").append(tasks.joinToString("\n") { "• " + it.line() }).append("\n\n")
        if (openQuestions.isNotEmpty()) append("Otwarte kwestie\n").append(openQuestions.joinToString("\n") { "• $it" })
    }.trim()

    companion object {
        fun fromJson(json: String?): MeetingNote? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                fun list(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return List(a.length()) { a.optString(it).trim() }.filter { it.isNotEmpty() && it != "null" }
                }
                val ta = o.optJSONArray("zadania") ?: JSONArray()
                val tasks = (0 until ta.length()).mapNotNull { i ->
                    val t = ta.opt(i)
                    when (t) {
                        is JSONObject -> MeetingNote.Task(
                            t.optString("zadanie").trim(),
                            t.optString("osoba").trim().takeUnless { it == "null" || it == "-" }.orEmpty(),
                            t.optString("termin").trim().takeUnless { it == "null" || it == "-" }.orEmpty(),
                        ).takeIf { it.what.isNotEmpty() }
                        is String -> MeetingNote.Task(t.trim(), "", "").takeIf { it.what.isNotEmpty() }
                        else -> null
                    }
                }
                MeetingNote(o.optString("streszczenie").trim(), list("decyzje"), tasks, list("pytania_otwarte"))
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Tworzy notatkę ze spotkania (streszczenie, decyzje, zadania, otwarte kwestie).
 * Długie transkrypcje są dzielone na części: każda dostaje własne notatki, które potem
 * łączone są w jedną notatkę. Dzięki temu działa to także dla wielogodzinnych spotkań
 * i mieści się w limitach darmowego planu.
 */
class Summarizer(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    suspend fun summarize(title: String, transcript: String, onProgress: (String) -> Unit): MeetingNote = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ConfigException("Notatka AI wymaga klucza API. Dodaj go w Ustawieniach.")
        val text = transcript.trim()
        if (text.length < 40) throw ConfigException("Transkrypcja jest za krótka, żeby przygotować notatkę.")
        val parts = split(text, PART_CHARS)
        val material: String
        if (parts.size == 1) {
            material = text
        } else {
            val notes = ArrayList<String>()
            for ((i, part) in parts.withIndex()) {
                onProgress("Analizuję część ${i + 1} z ${parts.size}…")
                notes.add(chat(MAP_SYSTEM, "Część ${i + 1} z ${parts.size} transkrypcji spotkania „$title”:\n\n$part", json = false, maxTokens = 1500))
            }
            material = notes.joinToString("\n\n") { it.trim() }.let { if (it.length > 60_000) it.take(60_000) else it }
        }
        onProgress("Przygotowuję notatkę…")
        val label = if (parts.size == 1) "Transkrypcja spotkania" else "Notatki z kolejnych części spotkania"
        val raw = chat(REDUCE_SYSTEM, "Tytuł: $title\n\n$label:\n\n$material", json = true, maxTokens = 3000)
        val note = MeetingNote.fromJson(extractJson(raw)) ?: throw ApiException(500, "Model zwrócił nieczytelną odpowiedź. Spróbuj ponownie.")
        if (note.isEmpty) throw ApiException(500, "Model nie przygotował notatki. Spróbuj ponownie.")
        note
    }

    private suspend fun chat(system: String, user: String, json: Boolean, maxTokens: Int): String {
        var useJson = json
        var attempt = 0
        while (true) {
            attempt++
            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
                .put("reasoning_effort", "low")
            // Adres Google (Gemini) zna klasyczne max_tokens; Groq i OpenAI – max_completion_tokens.
            body.put(if (baseUrl.contains("generativelanguage.googleapis.com")) "max_tokens" else "max_completion_tokens", maxTokens)
            if (useJson) body.put("response_format", JSONObject().put("type", "json_object"))
            val req = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response: Triple<Int, String, Long> = try {
                CloudEngine.client.newCall(req).execute().use { resp ->
                    Triple(resp.code, resp.body?.string().orEmpty(), resp.header("retry-after")?.toDoubleOrNull()?.toLong() ?: 0L)
                }
            } catch (e: IOException) {
                if (attempt >= 4) throw e
                delay(3_000L * attempt)
                continue
            }
            val (code, text, retryAfter) = response
            when {
                code in 200..299 -> {
                    val content = try {
                        JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                    } catch (e: Exception) {
                        ""
                    }
                    if (content.isNotBlank()) return content
                    if (attempt >= 3) throw ApiException(500, "Pusta odpowiedź modelu.")
                }
                code == 401 || code == 403 -> throw AuthException("Usługa odrzuciła klucz API. Sprawdź go w Ustawieniach.")
                code == 429 || code >= 500 -> {
                    // Limit darmowego planu (tokeny na minutę) – czekamy, ile każe serwer.
                    if (attempt >= 8) throw ApiException(code, "Przekroczono limit usługi. Spróbuj za kilka minut.")
                    delay((if (retryAfter > 0) retryAfter * 1000 else 8_000L * attempt).coerceIn(2_000L, 70_000L))
                }
                code == 400 && useJson -> useJson = false // model bez trybu JSON – prosimy o JSON w treści
                else -> throw ApiException(code, CloudEngine.errorMessage(text, code))
            }
        }
    }

    companion object {
        const val PART_CHARS = 9_000

        private const val MAP_SYSTEM =
            "Jesteś asystentem, który sporządza notatki ze spotkań po polsku. Otrzymasz fragment transkrypcji " +
                "(mogą w nim być błędy rozpoznawania mowy). Wypisz zwięźle w punktach: najważniejsze informacje, " +
                "podjęte decyzje, zadania (kto, co, do kiedy – jeśli padło) i otwarte kwestie. " +
                "Opieraj się wyłącznie na treści – niczego nie wymyślaj."

        private const val REDUCE_SYSTEM =
            "Jesteś asystentem, który przygotowuje profesjonalne notatki ze spotkań po polsku. " +
                "Na podstawie materiału przygotuj notatkę i zwróć WYŁĄCZNIE obiekt JSON o kluczach: " +
                "\"streszczenie\" (3–6 zdań: cel spotkania, główne ustalenia), " +
                "\"decyzje\" (lista krótkich zdań), " +
                "\"zadania\" (lista obiektów {\"zadanie\", \"osoba\", \"termin\"}; puste pole \"\" gdy nie padło), " +
                "\"pytania_otwarte\" (lista). Używaj wyłącznie faktów z materiału, nie wymyślaj osób ani terminów. " +
                "Popraw oczywiste błędy rozpoznawania mowy. Puste listy zostaw jako []."

        /** Dzieli tekst na części, tnąc na granicach akapitów lub zdań. */
        fun split(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(text.length, start + max)
                if (end < text.length) {
                    val window = text.substring(start, end)
                    val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! "))
                        .filter { it > max / 2 }.maxOrNull()
                    if (cut != null) end = start + cut + 1
                }
                out.add(text.substring(start, end).trim())
                start = end
            }
            return out.filter { it.isNotEmpty() }
        }

        fun extractJson(raw: String): String? {
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            return if (a >= 0 && b > a) raw.substring(a, b + 1) else null
        }
    }
}
