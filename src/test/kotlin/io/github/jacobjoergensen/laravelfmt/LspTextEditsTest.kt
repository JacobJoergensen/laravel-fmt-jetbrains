package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.util.TextRange
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LspTextEditsTest {

    private val text = "<?php\n\$a  =  1;\necho 'x';\n\$b  =  2;\n"

    @Test
    fun `a selection of the whole file formats the whole file`() {
        assertNull(LspTextEdits.lspRanges(text, listOf(TextRange(0, text.length))))
    }

    @Test
    fun `each changed block is formatted on its own, not the whole file`() {
        val ranges = LspTextEdits.lspRanges(text, listOf(TextRange(6, 15), TextRange(28, 35)))

        assertEquals(listOf(Range(Position(1, 0), Position(1, 9)), Range(Position(3, 2), Position(3, 9))), ranges)
    }

    @Test
    fun `two ranges that split one change differently are left unformatted`() {
        assertTrue(LspTextEdits.overlapping(listOf(edit(1, 3, "\$a = 1;\n"), edit(2, 4, "echo 'x';\n"))))
    }

    @Test
    fun `changes that meet end to start are both applied`() {
        assertFalse(LspTextEdits.overlapping(listOf(edit(1, 2, "\$a = 1;\n"), edit(2, 3, "echo 'x';\n"))))
    }

    private fun edit(fromLine: Int, toLine: Int, newText: String) =
        TextEdit(Range(Position(fromLine, 0), Position(toLine, 0)), newText)
}
