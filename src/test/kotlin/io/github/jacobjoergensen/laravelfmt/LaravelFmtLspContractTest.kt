package io.github.jacobjoergensen.laravelfmt

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Checks what the plugin relies on from a real `laravel-fmt lsp`: its capabilities, full text sync,
 * UTF-16 positions, formatting equal to the CLI, `laravel-fmt/endOfLine`, and a clean shutdown.
 *
 * Skipped unless `LARAVEL_FMT_BINARY` points at a binary, e.g. from `go build -o /tmp/laravel-fmt .`
 * in the laravel-fmt repo.
 */
class LaravelFmtLspContractTest {

    @TempDir
    lateinit var projectDir: Path

    private lateinit var binary: String
    private lateinit var process: Process
    private lateinit var server: LaravelFmtLanguageServer

    private val client = RecordingClient()

    private val unformatted = "<?php\nfunction  foo( \$a,\$b ){return \$a+\$b;}\n"

    @BeforeEach
    fun startServer() {
        binary = System.getenv("LARAVEL_FMT_BINARY").orEmpty()
        assumeTrue(binary.isNotEmpty() && File(binary).isFile, "LARAVEL_FMT_BINARY not set; skipping LSP contract test")

        process = ProcessBuilder(binary, "lsp")
            .directory(projectDir.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        val launcher = LSPLauncher.Builder<LaravelFmtLanguageServer>()
            .setLocalService(client)
            .setRemoteInterface(LaravelFmtLanguageServer::class.java)
            .setInput(process.inputStream)
            .setOutput(process.outputStream)
            .create()
        launcher.startListening()
        server = launcher.remoteProxy
    }

    @AfterEach
    fun stopServer() {
        if (::process.isInitialized && process.isAlive) {
            process.destroyForcibly()
        }
    }

    @Test
    fun `server advertises formatting only, with full sync and UTF-16 positions`() {
        val result = initialize()
        val capabilities = result.capabilities

        assertEquals("utf-16", capabilities.positionEncoding)
        assertEquals(TextDocumentSyncKind.Full, capabilities.textDocumentSync.left ?: capabilities.textDocumentSync.right.change)
        assertTrue(capabilities.documentFormattingProvider.left ?: true)
        assertTrue(capabilities.documentRangeFormattingProvider.left ?: true)
        assertNull(capabilities.completionProvider)
        assertNull(capabilities.hoverProvider)
    }

    @Test
    fun `document formatting matches the CLI and is idempotent`() {
        initialize()
        val file = write("app/Example.php", unformatted)
        open(file, "php", unformatted)

        val formatted = apply(unformatted, format(file))
        assertEquals(cliFormat(file, unformatted), formatted)

        // Full sync: send the formatted text as a whole-document change, then nothing is left to do.
        change(file, 2, formatted)
        assertEquals(emptyList<TextEdit>(), format(file))
    }

    @Test
    fun `a file is only formatted while it is open`() {
        initialize()
        val file = write("app/Example.php", unformatted)

        assertEquals(emptyList<TextEdit>(), format(file))

        open(file, "php", unformatted)

        assertNotEquals(emptyList<TextEdit>(), format(file))

        server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri(file))))

        assertEquals(emptyList<TextEdit>(), format(file))
    }

    @Test
    fun `range formatting only touches the requested lines`() {
        initialize()
        // The server applies whole line-level diff hunks that overlap the range, so the two messy
        // statements are kept apart by an already-formatted line to land in separate hunks.
        val text = "<?php\n\n\$a  =  1;\necho 'x';\n\$b  =  2;\n"
        val file = write("range.php", text)
        open(file, "php", text)

        val params = DocumentRangeFormattingParams(
            TextDocumentIdentifier(uri(file)),
            FormattingOptions(4, true),
            Range(Position(2, 0), Position(2, 9)),
        )
        val result = apply(text, server.textDocumentService.rangeFormatting(params).get(30, TimeUnit.SECONDS))

        assertTrue(result.contains("\$a = 1;"), result)
        assertTrue(result.contains("\$b  =  2;"), result)
    }

    @Test
    fun `blade files are accepted with the blade language id`() {
        initialize()
        val text = "<div>{{ \$name }}</div>\n"
        val file = write("resources/views/welcome.blade.php", text)
        open(file, "blade", text)

        val formatted = apply(text, format(file))
        assertEquals(cliFormat(file, text), formatted)
    }

    @Test
    fun `a broken config is reported once however often the file is formatted`() {
        initialize()
        write("laravel-fmt.json", "{")
        val file = write("app/Example.php", unformatted)
        open(file, "php", unformatted)

        assertEquals(emptyList<TextEdit>(), format(file))
        assertEquals(emptyList<TextEdit>(), format(file))
        assertEquals(listOf(MessageType.Error), client.shown.map { it.type })
    }

    @Test
    fun `the line ending the project's config asks for is reported`() {
        initialize()
        val file = write("app/Example.php", unformatted)
        open(file, "php", unformatted)

        assertEquals("lf", endOfLine(file))

        write("laravel-fmt.json", """{ "endOfLine": "crlf" }""")

        assertEquals("crlf", endOfLine(file))
    }

    @Test
    fun `a file that does not parse is logged without a popup`() {
        initialize()
        val text = "<?php\nfunction (\n"
        val file = write("broken.php", text)
        open(file, "php", text)

        assertEquals(emptyList<TextEdit>(), format(file))
        assertTrue(client.shown.isEmpty(), "shown: ${client.shown}")
        assertEquals(listOf(MessageType.Warning), client.logged.map { it.type })
    }

    @Test
    fun `shutdown then exit ends the process with code 0`() {
        initialize()
        server.shutdown().get(10, TimeUnit.SECONDS)
        server.exit()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "server did not exit")
        assertEquals(0, process.exitValue())
    }

    private fun initialize() = server.initialize(
        InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            capabilities = ClientCapabilities().apply {
                general = GeneralClientCapabilities().apply { positionEncodings = listOf("utf-16") }
            }
            workspaceFolders = listOf(WorkspaceFolder(projectDir.toUri().toString(), "project"))
        },
    ).get(30, TimeUnit.SECONDS).also { server.initialized(InitializedParams()) }

    private fun write(relative: String, text: String): Path =
        projectDir.resolve(relative).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, text)
        }

    private fun uri(file: Path) = file.toUri().toString()

    private fun open(file: Path, languageId: String, text: String) =
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri(file), languageId, 1, text)))

    private fun change(file: Path, version: Int, text: String) =
        server.textDocumentService.didChange(
            DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri(file), version), listOf(TextDocumentContentChangeEvent(text))),
        )

    private fun endOfLine(file: Path): String =
        server.endOfLine(LaravelFmtLanguageServer.EndOfLineParams(TextDocumentIdentifier(uri(file)))).get(30, TimeUnit.SECONDS)

    private fun format(file: Path): List<TextEdit> =
        server.textDocumentService
            .formatting(DocumentFormattingParams(TextDocumentIdentifier(uri(file)), FormattingOptions(4, true)))
            .get(30, TimeUnit.SECONDS)

    private fun cliFormat(file: Path, text: String): String {
        val cli = ProcessBuilder(binary, "--stdin-filepath", file.toString())
            .directory(projectDir.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        cli.outputStream.use { it.write(text.toByteArray()) }

        val output = cli.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(cli.waitFor(30, TimeUnit.SECONDS))

        return output
    }

    private fun apply(text: String, edits: List<TextEdit>): String = LspTextEdits.applyEdits(text, edits)

    /** Keeps the messages the server sends; the IDE shows `showMessage` as a balloon and only logs `logMessage`. */
    private class RecordingClient : LanguageClient {
        val shown = CopyOnWriteArrayList<MessageParams>()
        val logged = CopyOnWriteArrayList<MessageParams>()

        override fun telemetryEvent(`object`: Any?) = Unit
        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) = Unit
        override fun showMessage(messageParams: MessageParams) {
            shown += messageParams
        }
        override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)
        override fun logMessage(message: MessageParams) {
            logged += message
        }
    }
}
