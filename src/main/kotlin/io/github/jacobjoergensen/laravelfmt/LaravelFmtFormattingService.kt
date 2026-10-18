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
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
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
        val ranges = LspTextEdits.lspRanges(text, request.formattingRanges)
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

                val replacements = edits?.let { LspTextEdits.replacements(text, it) }
                val formatted = replacements?.let { LspTextEdits.apply(text, it) }

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

                return edits.distinct().takeUnless(LspTextEdits::overlapping)
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

        private const val STARTUP_TIMEOUT_MS = 10_000

        private const val STARTUP_POLL_MS = 50L

        private fun servers(project: Project): Collection<LspServer> =
            LspServerManager.getInstance(project).getServersForProvider(LaravelFmtLspServerSupportProvider::class.java)
    }
}
