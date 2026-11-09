package pl.livetranscribe.app.pdf

/**
 * Rozkłada akapity (jako listy wysokości linii) na strony.
 * Akapit może przechodzić na kolejną stronę; żadna linia nie jest gubiona ani dublowana.
 */
object Paginator {

    /** Wycinek akapitu [block]: linie [from, to) rysowane od [y] (względem góry obszaru treści). */
    data class Slice(val block: Int, val from: Int, val to: Int, val y: Int, val height: Int)

    fun paginate(
        blocks: List<IntArray>,
        firstPageHeight: Int,
        otherPageHeight: Int,
        paragraphSpacing: Int,
    ): List<List<Slice>> {
        val pages = ArrayList<MutableList<Slice>>()
        var current = ArrayList<Slice>()
        pages.add(current)
        var avail = firstPageHeight
        var y = 0

        fun newPage() {
            current = ArrayList()
            pages.add(current)
            avail = otherPageHeight
            y = 0
        }

        for ((b, lines) in blocks.withIndex()) {
            var line = 0
            while (line < lines.size) {
                var end = line
                var h = 0
                while (end < lines.size && y + h + lines[end] <= avail) {
                    h += lines[end]
                    end++
                }
                if (end == line) {
                    if (y == 0) {
                        // Linia wyższa niż strona: umieść ją mimo wszystko, żeby nie zapętlić.
                        h = lines[line]
                        end = line + 1
                    } else {
                        newPage()
                        continue
                    }
                }
                current.add(Slice(b, line, end, y, h))
                y += h
                line = end
                if (line < lines.size) newPage()
            }
            y += paragraphSpacing
        }
        if (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.size - 1)
        return pages
    }
}
