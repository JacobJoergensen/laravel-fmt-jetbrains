package io.github.jacobjoergensen.laravelfmt

import com.intellij.formatting.service.DocumentMerger
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

class LaravelFmtDocumentMergerTest : BasePlatformTestCase() {

    private val unformatted = $$"<?php\nfunction  foo( $a ){return $a;}\n$x = 1;\n$y  =  2;\n"

    // Lines 1 and 3 change, line 2 doesn't.
    private val edits = listOf(
        TextEdit(Range(Position(1, 0), Position(2, 0)), $$"function foo($a)\n{\n    return $a;\n}\n"),
        TextEdit(Range(Position(3, 0), Position(4, 0)), $$"$y = 2;\n"),
    )

    private val formatted = LspTextEdits.apply(unformatted, LspTextEdits.replacements(unformatted, edits))

    fun `test a fold on a line laravel-fmt didn't change survives formatting`() {
        val document = configure()
        val fold = foldUnchangedLine(document)
        expectFormat(document)

        assertTrue(merge(document))
        assertEquals(formatted, document.text)
        assertTrue(fold.isValid)
    }

    fun `test a document laravel-fmt didn't format is left to the framework`() {
        val document = configure()

        assertFalse(merge(document))
        assertEquals(unformatted, document.text)
    }

    fun `test a document edited since formatting started is left to the framework`() {
        val document = configure()
        expectFormat(document)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "\n") }

        assertFalse(merge(document))
    }

    fun `test another formatter's result is left to the framework`() {
        val document = configure()
        expectFormat(document)

        assertFalse(merge(document, "<?php\n"))
        assertEquals(unformatted, document.text)
    }

    private fun configure(): Document = myFixture.configureByText("Example.php", unformatted).fileDocument

    private fun foldUnchangedLine(document: Document): FoldRegion {
        val start = document.text.indexOf($$"$x = 1;")
        var fold: FoldRegion? = null

        myFixture.editor.foldingModel.runBatchFoldingOperation {
            fold = myFixture.editor.foldingModel.addFoldRegion(start, start + $$"$x = 1;".length, "...")
        }

        return fold!!
    }

    private fun expectFormat(document: Document) {
        val replacements = LspTextEdits.replacements(unformatted, edits)

        document.putUserData(PENDING_EDITS, LaravelFmtDocumentMerger.Pending(document.modificationStamp, formatted, replacements))
    }

    private fun merge(document: Document, text: String = formatted): Boolean =
        WriteCommandAction.runWriteCommandAction(project, Computable { DocumentMerger.EP_NAME.extensionList.any { it.updateDocument(document, text) } })
}
