package io.github.jacobjoergensen.laravelfmt

import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

interface LaravelFmtLanguageServer : LanguageServer {

    /** Answers `lf`, `crlf` or `auto`; servers before it existed reply with method-not-found. */
    @JsonRequest("laravel-fmt/endOfLine")
    fun endOfLine(params: EndOfLineParams): CompletableFuture<String>

    data class EndOfLineParams(val textDocument: TextDocumentIdentifier)
}
