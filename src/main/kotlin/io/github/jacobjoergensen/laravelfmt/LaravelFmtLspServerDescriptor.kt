package io.github.jacobjoergensen.laravelfmt

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.jetbrains.php.lang.PhpFileType
import java.nio.charset.StandardCharsets

/** Only formatting is configured: the server advertises nothing else, so other LSP features stay off. */
class LaravelFmtLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, PRESENTABLE_NAME) {

    override fun isSupportedFile(file: VirtualFile): Boolean = isFormattable(file)

    override fun getLanguageId(file: VirtualFile): String {
        if (isBlade(file)) {
            return "blade"
        }

        return "php"
    }

    /**
     * The first run may download the binary (see [LaravelFmtLauncher]), which is fine here: the IDE calls
     * this on a pooled thread. A failure shows a notification and marks the server as failed.
     */
    override fun createCommandLine(): GeneralCommandLine {
        val executable = try {
            LaravelFmtLauncher.prepare(project)
        } catch (e: LaravelFmtLauncher.LaunchException) {
            project.service<LaravelFmtBinaryNotifier>().startFailed(e.userMessage, e.details)
            throw ExecutionException(e.userMessage)
        }

        val commandLine = GeneralCommandLine(executable, "lsp")
            .withCharset(StandardCharsets.UTF_8)

        project.basePath?.let { commandLine.withWorkDirectory(it) }

        return commandLine
    }

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val formattingCustomizer: LspFormattingCustomizer = LaravelFmtFormattingSupport
    }

    /**
     * The IDE keeps formatting PHP and Blade itself by default, because it has its own formatter for them.
     * Always returning `true` routes Reformat Code and format on save to laravel-fmt instead.
     */
    private object LaravelFmtFormattingSupport : LspFormattingSupport() {
        override fun shouldFormatThisFileExclusivelyByServer(
            file: VirtualFile,
            ideCanFormatThisFileItself: Boolean,
            serverExplicitlyWantsToFormatThisFile: Boolean,
        ): Boolean = true
    }

    companion object {
        private const val PRESENTABLE_NAME = "laravel-fmt"

        private const val BLADE_SUFFIX = ".blade.php"

        /** Blade is matched by name, so `.blade.php` files are formatted as Blade even without the Blade plugin. */
        fun isFormattable(file: VirtualFile): Boolean =
            !file.isDirectory && (isBlade(file) || file.fileType == PhpFileType.INSTANCE)

        private fun isBlade(file: VirtualFile): Boolean = file.name.endsWith(BLADE_SUFFIX, ignoreCase = true)
    }
}
