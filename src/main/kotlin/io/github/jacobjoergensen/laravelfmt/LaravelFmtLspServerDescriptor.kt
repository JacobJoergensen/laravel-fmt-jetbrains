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

    enum class FileKind(val languageId: String) {
        PHP("php"),
        BLADE("blade"),
    }

    /** File types unchecked in the settings are never sent to the server, so the IDE keeps formatting them. */
    override fun isSupportedFile(file: VirtualFile): Boolean = project.service<LaravelFmtServerService>().formats(file)

    override fun getLanguageId(file: VirtualFile): String = (fileKind(file) ?: FileKind.PHP).languageId

    /**
     * The first run may download the binary (see [LaravelFmtLauncher]), which is fine here: the IDE calls
     * this on a pooled thread. A failure shows a notification and marks the server as failed.
     */
    override fun createCommandLine(): GeneralCommandLine {
        val executable = try {
            LaravelFmtLauncher.prepare(project)
        } catch (e: LaravelFmtLauncher.LaunchException) {
            project.service<LaravelFmtNotifications>().startFailed(e.userMessage, e.details)
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
        fun fileKind(file: VirtualFile): FileKind? = when {
            file.isDirectory -> null
            file.name.endsWith(BLADE_SUFFIX, ignoreCase = true) -> FileKind.BLADE
            file.fileType == PhpFileType.INSTANCE -> FileKind.PHP
            else -> null
        }
    }
}
