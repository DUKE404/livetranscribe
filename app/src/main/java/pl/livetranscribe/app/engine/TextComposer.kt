package pl.livetranscribe.app.engine

/** Odcinek tekstu z czasem względem początku fragmentu i (opcjonalnie) rozmówcą. */
data class Seg(val startMs: Long, val endMs: Long, val speaker: String?, val text: String)

/** Przetworzony fragment nagrania. */
data class ChunkText(val startMs: Long, val text: String, val segs: List<Seg> = emptyList())

/**
 * Składa fragmenty w jeden czytelny tekst: akapity, znaczniki czasu, rozmówcy.
 * Czysta logika bez zależności od Androida.
 */
object TextComposer {

    private const val PARAGRAPH_CHARS = 600

    fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun compose(chunks: List<ChunkText>, timestamps: Boolean, speakers: Boolean): String {
        val items = chunks.filter { it.text.isNotBlank() }
        if (items.isEmpty()) return ""
        val hasSpeakers = speakers && items.any { c -> c.segs.any { !it.speaker.isNullOrBlank() } }
        return when {
            hasSpeakers -> composeSpeakers(items, timestamps)
            timestamps -> items.joinToString("\n\n") { "[${formatTime(it.startMs)}] ${it.text.trim()}" }
            else -> composeFlow(items)
        }
    }

    private fun composeFlow(items: List<ChunkText>): String {
        val out = StringBuilder()
        val para = StringBuilder()
        for (c in items) {
            if (para.isNotEmpty()) para.append(' ')
            para.append(c.text.trim())
            val last = para.last()
            if (para.length >= PARAGRAPH_CHARS && (last == '.' || last == '?' || last == '!' || last == '…')) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(para)
                para.setLength(0)
            }
        }
        if (para.isNotEmpty()) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(para)
        }
        return out.toString()
    }

    private fun composeSpeakers(items: List<ChunkText>, timestamps: Boolean): String {
        val paras = ArrayList<String>()
        for (c in items) {
            val labelled = c.segs.filter { it.text.isNotBlank() }
            if (labelled.isEmpty() || labelled.none { !it.speaker.isNullOrBlank() }) {
                paras.add(prefix(c.startMs, null, timestamps) + c.text.trim())
                continue
            }
            var speaker: String? = null
            var start = 0L
            val sb = StringBuilder()
            for (s in labelled) {
                if (s.speaker != speaker && sb.isNotEmpty()) {
                    paras.add(prefix(c.startMs + start, speaker, timestamps) + sb.toString().trim())
                    sb.setLength(0)
                }
                if (sb.isEmpty()) {
                    speaker = s.speaker
                    start = s.startMs
                }
                sb.append(s.text.trim()).append(' ')
            }
            if (sb.isNotEmpty()) paras.add(prefix(c.startMs + start, speaker, timestamps) + sb.toString().trim())
        }
        return paras.joinToString("\n\n")
    }

    private fun prefix(ms: Long, speaker: String?, timestamps: Boolean): String {
        val sb = StringBuilder()
        if (timestamps) sb.append('[').append(formatTime(ms)).append("] ")
        if (!speaker.isNullOrBlank()) sb.append(speakerLabel(speaker)).append(": ")
        return sb.toString()
    }

    fun speakerLabel(raw: String): String {
        val r = raw.trim()
        return if (r.length <= 2 || r.lowercase().startsWith("speaker")) {
            "Rozmówca " + r.removePrefix("speaker_").removePrefix("SPEAKER_").removePrefix("Speaker ").uppercase()
        } else r
    }

    /** Typowe „halucynacje” modeli Whisper na ciszy lub szumie. */
    private val junk = listOf(
        "amara.org", "napisy stworzone przez", "napisy wykonane przez", "subtitles by",
        "zeoranger", "napisy: ", "transkrypcja: ", "thanks for watching", "dziękuję za oglądanie",
        "dziękuję za uwagę i do zobaczenia",
    )

    /**
     * Podpowiedź dla modelu: słownik nazw + koniec poprzedniego fragmentu (ciągłość zdań).
     * Whisper czyta tylko ok. 220 tokenów podpowiedzi, więc całość jest przycinana.
     */
    fun buildPrompt(glossary: String, previous: String?): String? {
        val terms = glossary.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val sb = StringBuilder()
        if (terms.isNotEmpty()) sb.append("Słownik: ").append(terms.joinToString(", ").take(300)).append(". ")
        val prev = previous?.trim().orEmpty()
        if (prev.isNotEmpty()) sb.append(prev.takeLast(if (terms.isEmpty()) 300 else 160))
        return sb.toString().trim().ifEmpty { null }
    }

    fun clean(text: String): String {
        val t = text.trim().replace(Regex("[ \\t]+"), " ")
        if (t.length < 90) {
            val low = t.lowercase()
            if (junk.any { low.contains(it) }) return ""
        }
        return t
    }
}
