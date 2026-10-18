package io.github.jacobjoergensen.laravelfmt

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.lsWidget.LspServerWidgetItem

/**
 * Doesn't start the server when laravel-fmt is off, unused by the project or not found. Only the last one
 * gets a notification, instead of a server error. The IDE calls [fileOpened] for every opened file, so
 * the lookups are cached.
 */
class LaravelFmtLspServerSupportProvider : LspServerSupportProvider {

    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (project.service<LaravelFmtServerService>().formats(file)) {
            start(project, serverStarter::ensureServerStarted)
        }
    }

    override fun createLspServerWidgetItem(lspServer: LspServer, currentFile: VirtualFile?): LspServerWidgetItem =
        LspServerWidgetItem(lspServer, currentFile, AllIcons.Actions.ReformatCode, LaravelFmtConfigurable::class.java)

    companion object {
        /** False when laravel-fmt isn't found. */
        fun start(project: Project, ensureStarted: (LspServerDescriptor) -> Unit): Boolean {
            val resolution = project.service<LaravelFmtServerService>().resolve()
            val notifier = project.service<LaravelFmtNotifications>()

            resolution.explicitPathMissing?.let { notifier.explicitPathMissingOnce(it, resolution.path) }

            if (resolution.path == null) {
                notifier.notFoundOnce(resolution.blockedByTrust)
                return false
            }

            ensureStarted(LaravelFmtLspServerDescriptor(project))

            return true
        }
    }
}
