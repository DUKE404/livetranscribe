package pl.livetranscribe.app.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tworzy wielostronicowy dokument PDF (A4) z transkrypcji.
 * Tekst jest rysowany systemowymi czcionkami, więc polskie znaki są zawsze poprawne.
 */
object PdfGenerator {

    class Options(val fontSize: Float = 11.5f, val headerOnEachPage: Boolean = true)

    private const val PAGE_W = 595   // A4 w punktach
    private const val PAGE_H = 842
    private const val MARGIN_X = 56
    private const val TOP = 66
    private const val BOTTOM = 66
    private const val CONTENT_W = PAGE_W - 2 * MARGIN_X

    private val INK = Color.rgb(27, 27, 31)
    private val GRAY = Color.rgb(110, 114, 125)
    private val LINE = Color.rgb(214, 218, 228)
    private val ACCENT = Color.rgb(36, 86, 230)

    private val timestampRegex = Regex("^\\[\\d{1,2}:\\d{2}(:\\d{2})?]")
    private val speakerRegex = Regex("^Rozmówca [^:]{1,20}:")

    /** Zwraca liczbę stron. */
    fun generate(out: File, title: String, createdAt: Long, durationMs: Long, text: String, opts: Options): Int {
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = opts.fontSize
            color = INK
            typeface = Typeface.SANS_SERIF
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 21f
            color = INK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f
            color = GRAY
            typeface = Typeface.SANS_SERIF
        }
        val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            color = GRAY
            typeface = Typeface.SANS_SERIF
        }
        val linePaint = Paint().apply { color = LINE; strokeWidth = 0.8f }
        val accentPaint = Paint().apply { color = ACCENT }

        val paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("(Brak tekstu)") }
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }

        val titleLayout = layout(title.ifBlank { "Transkrypcja" }, titlePaint, 1.15f)
        val meta = "Data nagrania: ${SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("pl", "PL")).format(Date(createdAt))}\n" +
            "Czas trwania: ${durationText(durationMs)}\n" +
            "Liczba słów: $words"
        val metaLayout = layout(meta, metaPaint, 1.35f)
        val headBlock = 6 + titleLayout.height + 10 + metaLayout.height + 16 + 20

        val layouts = paragraphs.map { layout(styled(it), bodyPaint, 1.42f) }
        val lineHeights = layouts.map { l -> IntArray(l.lineCount) { l.getLineBottom(it) - l.getLineTop(it) } }
        val pages = Paginator.paginate(
            lineHeights,
            firstPageHeight = PAGE_H - BOTTOM - TOP - headBlock,
            otherPageHeight = PAGE_H - BOTTOM - TOP,
            paragraphSpacing = (opts.fontSize * 0.85f).toInt(),
        )

        val doc = PdfDocument()
        try {
            for ((i, slices) in pages.withIndex()) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, i + 1).create())
                val c = page.canvas
                var areaTop = TOP
                if (i == 0) {
                    c.drawRect(MARGIN_X.toFloat(), TOP.toFloat(), MARGIN_X + 36f, TOP + 3f, accentPaint)
                    var y = TOP + 6
                    draw(c, titleLayout, MARGIN_X, y)
                    y += titleLayout.height + 10
                    draw(c, metaLayout, MARGIN_X, y)
                    y += metaLayout.height + 16
                    c.drawLine(MARGIN_X.toFloat(), y.toFloat(), (PAGE_W - MARGIN_X).toFloat(), y.toFloat(), linePaint)
                    areaTop = TOP + headBlock
                } else if (opts.headerOnEachPage) {
                    val short = TextUtils.ellipsize(title, smallPaint, CONTENT_W.toFloat(), TextUtils.TruncateAt.END).toString()
                    c.drawText(short, MARGIN_X.toFloat(), 42f, smallPaint)
                    c.drawLine(MARGIN_X.toFloat(), 50f, (PAGE_W - MARGIN_X).toFloat(), 50f, linePaint)
                }
                for (s in slices) {
                    val l = layouts[s.block]
                    val top = l.getLineTop(s.from)
                    val bottom = l.getLineBottom(s.to - 1)
                    c.save()
                    c.translate(MARGIN_X.toFloat(), (areaTop + s.y - top).toFloat())
                    c.clipRect(0f, top.toFloat(), CONTENT_W.toFloat(), bottom.toFloat())
                    l.draw(c)
                    c.restore()
                }
                // Stopka: nazwa aplikacji i numer strony.
                val footY = PAGE_H - 38f
                c.drawLine(MARGIN_X.toFloat(), footY - 14f, (PAGE_W - MARGIN_X).toFloat(), footY - 14f, linePaint)
                c.drawText("LiveTranscribe", MARGIN_X.toFloat(), footY, smallPaint)
                val pageLabel = "Strona ${i + 1} z ${pages.size}"
                c.drawText(pageLabel, PAGE_W - MARGIN_X - smallPaint.measureText(pageLabel), footY, smallPaint)
                doc.finishPage(page)
            }
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return pages.size
    }

    private fun layout(text: CharSequence, paint: TextPaint, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, CONTENT_W)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .build()

    private fun draw(c: Canvas, l: StaticLayout, x: Int, y: Int) {
        c.save()
        c.translate(x.toFloat(), y.toFloat())
        l.draw(c)
        c.restore()
    }

    /** Wyróżnia znacznik czasu (szary, pogrubiony) i etykietę rozmówcy (pogrubiona). */
    private fun styled(paragraph: String): CharSequence {
        val sb = SpannableStringBuilder(paragraph)
        var pos = 0
        timestampRegex.find(paragraph)?.let { m ->
            sb.setSpan(ForegroundColorSpan(GRAY), 0, m.value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, m.value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = m.value.length
            while (pos < paragraph.length && paragraph[pos] == ' ') pos++
        }
        speakerRegex.find(paragraph.substring(pos))?.let { m ->
            sb.setSpan(StyleSpan(Typeface.BOLD), pos, pos + m.value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    fun durationText(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return buildString {
            if (h > 0) append(h).append(" godz. ")
            if (h > 0 || m > 0) append(m).append(" min ")
            append(s).append(" s")
        }
    }
}
