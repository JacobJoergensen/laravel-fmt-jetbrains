package io.github.jacobjoergensen.laravelfmt

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

interface LaravelFmtLanguageServer : LanguageServer {

    /** Answers `lf`, `crlf` or `auto`; servers before it existed reply with method-not-found. */
    @JsonRequest("laravel-fmt/endOfLine")
    fun endOfLine(params: EndOfLineParams): CompletableFuture<String>

    /** Formats [FormatParams.text], not the server's copy; only servers advertising [FORMAT_CAPABILITY] have it. */
    @JsonRequest("laravel-fmt/format")
    fun format(params: FormatParams): CompletableFuture<FormatResult>

    data class EndOfLineParams(val textDocument: TextDocumentIdentifier)

    data class FormatParams(val textDocument: TextDocumentIdentifier, val text: String, val range: Range? = null)

    /** `endOfLine` is what `laravel-fmt/endOfLine` would answer. */
    data class FormatResult(val edits: List<TextEdit>, val endOfLine: String)

    companion object {
        const val FORMAT_CAPABILITY = "laravelFmtFormat"
    }
}
