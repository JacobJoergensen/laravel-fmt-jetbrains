package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

class LaravelFmtLineEndingsTest : BasePlatformTestCase() {

    fun `test lf turns a CRLF file into LF`() {
        val file = file("<?php\r\necho 1;\r\n")

        afterFormat(file) { "lf" }

        assertEquals("<?php\necho 1;\n", text(file))
    }

    fun `test crlf turns an LF file into CRLF`() {
        val file = file("<?php\necho 1;\n")

        afterFormat(file) { "crlf" }

        assertEquals("<?php\r\necho 1;\r\n", text(file))
    }

    fun `test auto leaves the file untouched`() {
        val file = file("<?php\r\necho 1;\r\n")

        afterFormat(file) { "auto" }

        assertEquals("<?php\r\necho 1;\r\n", text(file))
    }

    fun `test a server without the request leaves the file untouched`() {
        val file = file("<?php\r\necho 1;\r\n")

        afterFormat(file) {
            throw ResponseErrorException(ResponseError(ResponseErrorCode.MethodNotFound, "method not supported: laravel-fmt/endOfLine", null))
        }

        assertEquals("<?php\r\necho 1;\r\n", text(file))
    }

    fun `test a file that already matches is not written`() {
        val file = file("<?php\necho 1;\n")
        val stamp = file.modificationStamp

        afterFormat(file) { "lf" }

        assertEquals(stamp, file.modificationStamp)
    }

    private fun file(text: String): VirtualFile {
        val file = myFixture.tempDirFixture.createFile("Example.php")

        WriteAction.runAndWait<Throwable> { file.setBinaryContent(text.toByteArray()) }

        return file
    }

    private fun afterFormat(file: VirtualFile, askServer: () -> String?) {
        project.service<LaravelFmtLineEndings>().afterFormat(file, askServer)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun text(file: VirtualFile): String = String(file.contentsToByteArray())
}
