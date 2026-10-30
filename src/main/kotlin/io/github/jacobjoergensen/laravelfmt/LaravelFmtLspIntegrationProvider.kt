package io.github.jacobjoergensen.laravelfmt

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem

/** Only a missing binary gets a notification, instead of a server error. */
class LaravelFmtLspIntegrationProvider : LspIntegrationProvider {

    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        val server = project.service<LaravelFmtServerService>()

        if (server.formats(file)) {
            startLaravelFmtServer(project, server.appFor(file), clientStarter::ensureClientStarted)
        }
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem =
        LspClientWidgetItem(lspClient, currentFile, AllIcons.Actions.ReformatCode, LaravelFmtConfigurable::class.java)
}

/** False when laravel-fmt isn't found, or [app] is gone since the last lookup. */
fun startLaravelFmtServer(project: Project, app: String?, ensureStarted: (LspClientDescriptor) -> Unit): Boolean {
    val resolution = project.service<LaravelFmtServerService>().resolve(app) ?: return false
    val notifier = project.service<LaravelFmtNotifications>()

    resolution.explicitPathMissing?.let { notifier.explicitPathMissingOnce(it, resolution.path) }

    if (resolution.path == null) {
        notifier.notFoundOnce(resolution.blockedByTrust)
        return false
    }

    ensureStarted(LaravelFmtLspClientDescriptor(project, app))

    return true
}
