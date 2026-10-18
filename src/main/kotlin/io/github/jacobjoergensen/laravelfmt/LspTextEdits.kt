package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/** LSP positions count UTF-16 units, the same as Java string offsets. */
object LspTextEdits {

    data class Replacement(val start: Int, val end: Int, val text: String)

    private val byPosition = compareBy<Position>({ it.line }, { it.character })

    private val byStart = compareBy<TextEdit, Position>(byPosition) { it.range.start }

    fun applyEdits(text: String, edits: List<TextEdit>): String = apply(text, replacements(text, edits))

    /** Last first, so earlier offsets stay valid. */
    fun replacements(text: String, edits: List<TextEdit>): List<Replacement> {
        val lineStarts = lineStarts(text)

        fun offset(position: Position): Int =
            (lineStarts.getOrElse(position.line) { text.length } + position.character).coerceAtMost(text.length)

        return edits.sortedWith(byStart).asReversed().map {
            Replacement(offset(it.range.start), offset(it.range.end), StringUtil.convertLineSeparators(it.newText))
        }
    }

    fun apply(text: String, replacements: List<Replacement>): String =
        StringBuilder(text).apply { replacements.forEach { replace(it.start, it.end, it.text) } }.toString()

    /** Two ranges can split the same change differently, and applying both would garble it. */
    fun overlapping(edits: List<TextEdit>): Boolean =
        edits.sortedWith(byStart).zipWithNext().any { (a, b) ->
            a.range.start == b.range.start || byPosition.compare(b.range.start, a.range.end) < 0
        }

    /** Null for the whole file. */
    fun lspRanges(text: String, ranges: List<TextRange>): List<Range>? {
        if (ranges.isEmpty() || ranges.singleOrNull() == TextRange(0, text.length)) return null

        val lineStarts = lineStarts(text)

        fun position(offset: Int): Position {
            val line = lineStarts.indexOfLast { it <= offset }

            return Position(line, offset - lineStarts[line])
        }

        return ranges.map { Range(position(it.startOffset), position(it.endOffset)) }
    }

    private fun lineStarts(text: String): List<Int> =
        listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
}
