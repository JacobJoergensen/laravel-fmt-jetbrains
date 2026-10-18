package io.github.jacobjoergensen.laravelfmt

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
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
        val ranges = request.formattingRanges
        val wholeFile = ranges.isEmpty() || ranges.singleOrNull() == TextRange(0, text.length)
        // Several ranges, like Git's changed lines, format the whole file but leave its line separator alone.
        val range = ranges.singleOrNull()?.takeUnless { wholeFile }
        val settings = context.codeStyleSettings
        val options = FormattingOptions(settings.getIndentSize(file.fileType), !settings.useTabCharacter(file.fileType))

        return object : FormattingTask {
            @Volatile
            private var cancelled = false

            override fun run() {
                val document = server.getDocumentIdentifier(file)

                val edits = server.sendRequestSync(FORMAT_TIMEOUT_MS) { lsp ->
                    if (range == null) {
                        lsp.textDocumentService.formatting(DocumentFormattingParams(document, options))
                    } else {
                        lsp.textDocumentService.rangeFormatting(DocumentRangeFormattingParams(document, options, lspRange(text, range)))
                    }
                }

                request.onTextReady(edits?.let { applyEdits(text, it) })

                // Null when the request failed or timed out.
                if (wholeFile && edits != null && !cancelled) {
                    context.project.service<LaravelFmtLineEndings>().afterFormat(file) {
                        server.sendRequestSync(END_OF_LINE_TIMEOUT_MS) { lsp ->
                            (lsp as LaravelFmtLanguageServer).endOfLine(LaravelFmtLanguageServer.EndOfLineParams(document))
                        }
                    }
                }
            }

            override fun cancel(): Boolean {
                cancelled = true

                return true
            }
        }
    }

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

            edits.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })).asReversed().forEach {
                result.replace(offset(it.range.start), offset(it.range.end), StringUtil.convertLineSeparators(it.newText))
            }

            return result.toString()
        }

        private fun lspRange(text: String, range: TextRange): Range {
            val lineStarts = lineStarts(text)

            fun position(offset: Int): Position {
                val line = lineStarts.indexOfLast { it <= offset }

                return Position(line, offset - lineStarts[line])
            }

            return Range(position(range.startOffset), position(range.endOffset))
        }

        private fun lineStarts(text: String): List<Int> =
            listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    }
}
