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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
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

/** Under the 30 seconds after which the formatting framework reports a timeout of its own. */
private const val FORMAT_TIMEOUT_MS = 25_000

private const val END_OF_LINE_TIMEOUT_MS = 2000

private const val STARTUP_TIMEOUT_MS = 10_000

private const val STARTUP_POLL_MS = 50L

/**
 * Replaces PhpStorm's LSP formatter for laravel-fmt's files, which gives no sign that it formatted one.
 * [LaravelFmtLineEndings] needs that sign.
 */
class LaravelFmtFormattingService : AsyncDocumentFormattingService() {

    override fun getFeatures(): Set<FormattingService.Feature> = setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        val project = file.project
        val server = project.service<LaravelFmtServerService>()

        // PhpStorm doesn't start servers in a project that isn't trusted.
        if (!server.formats(virtualFile) || !TrustedProjects.isProjectTrusted(project)) return false

        // Called often and on the EDT, so it only checks for a binary. The task starts the server.
        return server.resolve(server.appFor(virtualFile))?.path != null
    }

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val file = request.context.virtualFile ?: return null

        return LaravelFmtFormattingTask(request, request.context.project, file)
    }

    /** Not saved first: the server formats the editor's text, not the file. */
    override fun prepareForFormatting(document: Document, formattingContext: FormattingContext) = Unit

    override fun getNotificationGroupId(): String = LaravelFmtNotifications.GROUP_ID

    override fun getName(): String = LaravelFmtBundle.message("settings.display.name")
}

/** [run] is called on a pooled thread. */
private class LaravelFmtFormattingTask(
    private val request: AsyncFormattingRequest,
    private val project: Project,
    private val file: VirtualFile,
) : AsyncDocumentFormattingService.FormattingTask {

    // PhpStorm only sends the server the files open in an editor, so the rest are sent for this request alone.
    private val unopened = !FileEditorManager.getInstance(project).isFileOpen(file)
    private val document = FileDocumentManager.getInstance().getCachedDocument(file)
    private val stamp = document?.modificationStamp ?: 0
    private val text = request.documentText
    private val ranges = LspTextEdits.lspRanges(text, request.formattingRanges)

    private val options = request.context.codeStyleSettings.let {
        FormattingOptions(it.getIndentSize(file.fileType), !it.useTabCharacter(file.fileType))
    }

    @Volatile
    private var cancelled = false

    override fun run() {
        val server = awaitServer() ?: return request.onTextReady(null)
        val textDocument = server.getDocumentIdentifier(file)

        if (unopened) {
            val item = TextDocumentItem(textDocument.uri, server.descriptor.getLanguageId(file), 0, text)

            server.sendNotification { it.textDocumentService.didOpen(DidOpenTextDocumentParams(item)) }
        }

        try {
            format(server, textDocument)
        } finally {
            if (unopened) {
                server.sendNotification { it.textDocumentService.didClose(DidCloseTextDocumentParams(textDocument)) }
            }
        }
    }

    override fun cancel(): Boolean {
        cancelled = true

        return true
    }

    private fun format(server: LspClient, textDocument: TextDocumentIdentifier) {
        val edits = if (ranges == null) {
            server.sendRequestSync(FORMAT_TIMEOUT_MS) { it.textDocumentService.formatting(DocumentFormattingParams(textDocument, options)) }
        } else {
            rangeEdits(server, textDocument, ranges)
        }

        request.onTextReady(formatted(edits))

        // Null when the request failed or timed out. Empty edits also come back for a file that doesn't parse;
        // the server then answers auto, but only while it still holds the file, so this comes before didClose.
        if (ranges == null && edits != null && !cancelled) {
            project.service<LaravelFmtLineEndings>().afterFormat(file) {
                server.sendRequestSync(END_OF_LINE_TIMEOUT_MS) { lsp ->
                    (lsp as LaravelFmtLanguageServer).endOfLine(LaravelFmtLanguageServer.EndOfLineParams(textDocument))
                }
            }
        }
    }

    /** Also stores the edits for [LaravelFmtDocumentMerger]. */
    private fun formatted(edits: List<TextEdit>?): String? {
        val replacements = edits?.let { LspTextEdits.replacements(text, it) } ?: return null
        val formatted = LspTextEdits.apply(text, replacements)

        document?.putUserData(PENDING_EDITS, LaravelFmtDocumentMerger.Pending(stamp, formatted, replacements))

        return formatted
    }

    /**
     * Starts the server if needed, since only opening a file starts one and a commit can reformat files that
     * never were, then waits for it: requests are dropped until it runs. The app is looked up again off the
     * EDT, since canFormat may have seen an out-of-date lookup.
     */
    private fun awaitServer(): LspClient? {
        val app = project.service<LaravelFmtServerService>().appFor(file)

        if (servers(project, app).isEmpty() && !start(project, app)) return null

        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS

        while (!cancelled && System.currentTimeMillis() < deadline) {
            val servers = servers(project, app)

            servers.firstOrNull { it.state == LspServerState.Running }?.let { return it }

            // Only a stopped server is left, and ensureClientStarted won't replace it.
            if (servers.isNotEmpty() && servers.none { it.state == LspServerState.Initializing }) return null

            Thread.sleep(STARTUP_POLL_MS)
        }

        return null
    }

    // A change two ranges touch comes back from both.
    private fun rangeEdits(server: LspClient, textDocument: TextDocumentIdentifier, ranges: List<Range>): List<TextEdit>? {
        val edits = mutableListOf<TextEdit>()

        for (range in ranges) {
            if (cancelled) return null

            edits += server.sendRequestSync(FORMAT_TIMEOUT_MS) {
                it.textDocumentService.rangeFormatting(DocumentRangeFormattingParams(textDocument, options, range))
            } ?: return null
        }

        return edits.distinct().takeUnless(LspTextEdits::overlapping)
    }
}

private fun start(project: Project, app: String?): Boolean =
    startLaravelFmtServer(project, app) {
        LspClientManager.getInstance(project).ensureClientStarted(LaravelFmtLspIntegrationProvider::class.java, it)
    }

private fun servers(project: Project, app: String?): List<LspClient> =
    LspClientManager.getInstance(project).getClients(LaravelFmtLspIntegrationProvider::class.java)
        .filter { (it.descriptor as? LaravelFmtLspClientDescriptor)?.app == app }
