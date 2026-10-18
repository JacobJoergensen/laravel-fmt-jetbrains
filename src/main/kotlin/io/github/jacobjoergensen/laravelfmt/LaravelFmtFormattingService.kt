package io.github.jacobjoergensen.laravelfmt

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit

/**
 * Replaces PhpStorm's LSP formatter for laravel-fmt's files, which gives no sign that it formatted one.
 * [LaravelFmtLineEndings] needs that sign.
 */
class LaravelFmtFormattingService : AsyncDocumentFormattingService() {

    override fun getFeatures(): Set<FormattingService.Feature> = setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        val project = file.project

        // PhpStorm doesn't start servers in a project that isn't trusted.
        if (!project.service<LaravelFmtServerService>().formats(virtualFile) || !TrustedProjects.isProjectTrusted(project)) return false

        // Only opening a file starts the server, and a commit can reformat files that never were.
        return servers(project).any { it.state == LspServerState.Running || it.state == LspServerState.Initializing } ||
            LaravelFmtLspServerSupportProvider.start(project) {
                LspServerManager.getInstance(project).ensureServerStarted(LaravelFmtLspServerSupportProvider::class.java, it)
            }
    }

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val context = request.context
        val project = context.project
        val file = context.virtualFile ?: return null
        // PhpStorm only sends the server the files open in an editor, so the rest are sent for this request alone.
        val unopened = !FileEditorManager.getInstance(project).isFileOpen(file)
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: 0
        val text = request.documentText
        val ranges = lspRanges(text, request.formattingRanges)
        val settings = context.codeStyleSettings
        val options = FormattingOptions(settings.getIndentSize(file.fileType), !settings.useTabCharacter(file.fileType))

        return object : FormattingTask {
            @Volatile
            private var cancelled = false

            override fun run() {
                val server = awaitServer() ?: return request.onTextReady(null)
                val textDocument = server.getDocumentIdentifier(file)

                if (unopened) {
                    val item = TextDocumentItem(textDocument.uri, server.descriptor.getLanguageId(file), 0, text)

                    server.sendNotification { it.textDocumentService.didOpen(DidOpenTextDocumentParams(item)) }
                }

                val edits = try {
                    if (ranges == null) {
                        server.sendRequestSync(FORMAT_TIMEOUT_MS) { it.textDocumentService.formatting(DocumentFormattingParams(textDocument, options)) }
                    } else {
                        rangeEdits(server, textDocument, ranges)
                    }
                } finally {
                    if (unopened) {
                        server.sendNotification { it.textDocumentService.didClose(DidCloseTextDocumentParams(textDocument)) }
                    }
                }

                val replacements = edits?.let { replacements(text, it) }
                val formatted = replacements?.let { apply(text, it) }

                if (replacements != null && formatted != null) {
                    document?.putUserData(LaravelFmtDocumentMerger.PENDING, LaravelFmtDocumentMerger.Pending(stamp, formatted, replacements))
                }

                request.onTextReady(formatted)

                // Null when the request failed or timed out.
                if (ranges == null && edits != null && !cancelled) {
                    project.service<LaravelFmtLineEndings>().afterFormat(file) {
                        server.sendRequestSync(END_OF_LINE_TIMEOUT_MS) { lsp ->
                            (lsp as LaravelFmtLanguageServer).endOfLine(LaravelFmtLanguageServer.EndOfLineParams(textDocument))
                        }
                    }
                }
            }

            // Requests are dropped until the server is running, and canFormat may only just have started it.
            private fun awaitServer(): LspServer? {
                val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS

                while (!cancelled && System.currentTimeMillis() < deadline) {
                    val servers = servers(project)

                    servers.firstOrNull { it.state == LspServerState.Running }?.let { return it }

                    // Only a stopped server is left, and ensureServerStarted won't replace it.
                    if (servers.isNotEmpty() && servers.none { it.state == LspServerState.Initializing }) return null

                    Thread.sleep(STARTUP_POLL_MS)
                }

                return null
            }

            // A change two ranges touch comes back from both.
            private fun rangeEdits(server: LspServer, textDocument: TextDocumentIdentifier, ranges: List<Range>): List<TextEdit>? {
                val edits = mutableListOf<TextEdit>()

                for (range in ranges) {
                    if (cancelled) return null

                    edits += server.sendRequestSync(FORMAT_TIMEOUT_MS) {
                        it.textDocumentService.rangeFormatting(DocumentRangeFormattingParams(textDocument, options, range))
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

    data class Replacement(val start: Int, val end: Int, val text: String)

    companion object {
        /** Under the 30 seconds after which the formatting framework reports a timeout of its own. */
        private const val FORMAT_TIMEOUT_MS = 25_000

        private const val END_OF_LINE_TIMEOUT_MS = 2000

        private const val STARTUP_TIMEOUT_MS = 10_000

        private const val STARTUP_POLL_MS = 50L

        private fun servers(project: Project): Collection<LspServer> =
            LspServerManager.getInstance(project).getServersForProvider(LaravelFmtLspServerSupportProvider::class.java)

        fun applyEdits(text: String, edits: List<TextEdit>): String = apply(text, replacements(text, edits))

        /** Last first, so earlier offsets stay valid. LSP positions count UTF-16 units, the same as Java string offsets. */
        fun replacements(text: String, edits: List<TextEdit>): List<Replacement> {
            val lineStarts = lineStarts(text)

            fun offset(position: Position): Int =
                (lineStarts.getOrElse(position.line) { text.length } + position.character).coerceAtMost(text.length)

            return edits.sortedWith(byStart).asReversed().map {
                Replacement(offset(it.range.start), offset(it.range.end), StringUtil.convertLineSeparators(it.newText))
            }
        }

        private fun apply(text: String, replacements: List<Replacement>): String =
            StringBuilder(text).apply { replacements.forEach { replace(it.start, it.end, it.text) } }.toString()

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
