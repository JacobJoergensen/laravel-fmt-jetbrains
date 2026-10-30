package io.github.jacobjoergensen.laravelfmt

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.jetbrains.php.lang.PhpFileType
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.charset.StandardCharsets

/**
 * One server per app (see [LaravelFmtUsage]), so each app's files run the laravel-fmt version that app
 * installed, from its own folder. [app] is null for the server of files outside every app.
 *
 * Only formatting is configured: the server advertises nothing else, so other LSP features stay off.
 */
class LaravelFmtLspClientDescriptor(project: Project, val app: String? = null) :
    LspClientDescriptor(project, presentableName(project, app), *roots(project, app)) {

    enum class FileKind(val languageId: String) {
        PHP("php"),
        BLADE("blade"),
    }

    /** File types unchecked in the settings are never sent to the server, so the IDE keeps formatting them. */
    override fun isSupportedFile(file: VirtualFile): Boolean {
        val server = project.service<LaravelFmtServerService>()

        return server.formats(file) && server.appFor(file) == app
    }

    override fun getLanguageId(file: VirtualFile): String = (fileKind(file) ?: FileKind.PHP).languageId

    /**
     * The first run may download the binary (see [LaravelFmtLauncher]), which is fine here: the IDE calls
     * this on a pooled thread. A failure shows a notification and marks the server as failed.
     */
    override fun createCommandLine(): GeneralCommandLine {
        val executable = try {
            LaravelFmtLauncher.prepare(project, app)
        } catch (e: LaravelFmtLauncher.LaunchException) {
            project.service<LaravelFmtNotifications>().startFailed(e.userMessage, e.details)
            throw ExecutionException(e.userMessage)
        }

        val commandLine = GeneralCommandLine(executable, "lsp")
            .withCharset(StandardCharsets.UTF_8)

        (app ?: project.basePath)?.let { commandLine.withWorkDirectory(it) }

        return commandLine
    }

    override val lsp4jServerClass: Class<out LanguageServer> = LaravelFmtLanguageServer::class.java

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val formattingCustomizer: LspFormattingCustomizer = LaravelFmtFormattingSupport
    }

    /** [LaravelFmtFormattingService] formats these files instead. */
    private object LaravelFmtFormattingSupport : LspFormattingSupport() {
        override fun shouldFormatThisFileExclusivelyByServer(
            file: VirtualFile,
            ideCanFormatThisFileItself: Boolean,
            serverExplicitlyWantsToFormatThisFile: Boolean,
        ): Boolean = false
    }

    companion object {
        private const val PRESENTABLE_NAME = "laravel-fmt"

        private const val BLADE_SUFFIX = ".blade.php"

        /**
         * The platform tells servers apart by name and roots, and the server of files outside every app shares
         * its roots with an app at the project root.
         */
        private fun presentableName(project: Project, app: String?): String {
            val base = project.basePath?.replace('\\', '/')?.trimEnd('/')
            val name = when {
                app == null -> LaravelFmtBundle.message("server.outside.apps")
                app == base -> return PRESENTABLE_NAME
                base != null && app.startsWith("$base/") -> app.removePrefix("$base/")
                else -> app.substringAfterLast('/')
            }

            return "$PRESENTABLE_NAME ($name)"
        }

        /**
         * Files outside every app can be in any of the project's folders, attached ones included. So can an
         * app's, while the IDE doesn't know its folder yet, as just after `composer create-project`.
         */
        private fun roots(project: Project, app: String?): Array<VirtualFile> {
            val folder = app?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                ?: return project.getBaseDirectories().toTypedArray()

            return arrayOf(folder)
        }

        /** Blade is matched by name, so `.blade.php` files are formatted as Blade even without the Blade plugin. */
        fun fileKind(file: VirtualFile): FileKind? = when {
            file.isDirectory -> null
            file.name.endsWith(BLADE_SUFFIX, ignoreCase = true) -> FileKind.BLADE
            file.fileType == PhpFileType.INSTANCE -> FileKind.PHP
            else -> null
        }
    }
}
