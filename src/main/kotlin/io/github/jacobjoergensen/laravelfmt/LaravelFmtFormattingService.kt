package io.github.jacobjoergensen.laravelfmt

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

/**
 * Replaces PhpStorm's LSP formatter for laravel-fmt's files, which gives no sign that it formatted one.
 * [LaravelFmtLineEndings] needs that sign.
 */
class LaravelFmtFormattingService : AsyncDocumentFormattingService() {

    override fun getFeatures(): Set<FormattingService.Feature> = setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false

        return file.project.service<LaravelFmtServerService>().formats(virtualFile) && runningServer(file.project) != null
    }

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val context = request.context
        val file = context.virtualFile ?: return null
        val server = runningServer(context.project) ?: return null
        val text = request.documentText
        val ranges = lspRanges(text, request.formattingRanges)
        val settings = context.codeStyleSettings
        val options = FormattingOptions(settings.getIndentSize(file.fileType), !settings.useTabCharacter(file.fileType))

        return object : FormattingTask {
            @Volatile
            private var cancelled = false

            override fun run() {
                val document = server.getDocumentIdentifier(file)

                val edits = if (ranges == null) {
                    server.sendRequestSync(FORMAT_TIMEOUT_MS) { it.textDocumentService.formatting(DocumentFormattingParams(document, options)) }
                } else {
                    rangeEdits(document, ranges)
                }

                request.onTextReady(edits?.let { applyEdits(text, it) })

                // Null when the request failed or timed out.
                if (ranges == null && edits != null && !cancelled) {
                    context.project.service<LaravelFmtLineEndings>().afterFormat(file) {
                        server.sendRequestSync(END_OF_LINE_TIMEOUT_MS) { lsp ->
                            (lsp as LaravelFmtLanguageServer).endOfLine(LaravelFmtLanguageServer.EndOfLineParams(document))
                        }
                    }
                }
            }

            // A change two ranges touch comes back from both.
            private fun rangeEdits(document: TextDocumentIdentifier, ranges: List<Range>): List<TextEdit>? {
                val edits = mutableListOf<TextEdit>()

                for (range in ranges) {
                    if (cancelled) return null

                    edits += server.sendRequestSync(FORMAT_TIMEOUT_MS) {
                        it.textDocumentService.rangeFormatting(DocumentRangeFormattingParams(document, options, range))
                    } ?: return null
                }

                return edits.distinct().takeUnless(::overlapping)
            }

            override fun cancel(): Boolean {
                cancelled = true

                return true
            }
        }
    }

    /** Not saved first: the server formats the editor's text, not the file. */
    override fun prepareForFormatting(document: Document, formattingContext: FormattingContext) = Unit

    override fun getNotificationGroupId(): String = LaravelFmtNotifications.GROUP_ID

    override fun getName(): String = LaravelFmtBundle.message("settings.display.name")

    companion object {
        /** Under the 30 seconds after which the formatting framework reports a timeout of its own. */
        private const val FORMAT_TIMEOUT_MS = 25_000

        private const val END_OF_LINE_TIMEOUT_MS = 2000

        private fun runningServer(project: Project): LspServer? =
            LspServerManager.getInstance(project).getServersForProvider(LaravelFmtLspServerSupportProvider::class.java)
                .firstOrNull { it.state == LspServerState.Running || it.state == LspServerState.Initializing }

        /** LSP positions count UTF-16 units, the same as Java string offsets. */
        fun applyEdits(text: String, edits: List<TextEdit>): String {
            val lineStarts = lineStarts(text)

            fun offset(position: Position): Int =
                (lineStarts.getOrElse(position.line) { text.length } + position.character).coerceAtMost(text.length)

            val result = StringBuilder(text)

            edits.sortedWith(byStart).asReversed().forEach {
                result.replace(offset(it.range.start), offset(it.range.end), StringUtil.convertLineSeparators(it.newText))
            }

            return result.toString()
        }

        private val byPosition = compareBy<Position>({ it.line }, { it.character })

        private val byStart = compareBy<TextEdit, Position>(byPosition) { it.range.start }

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
}
