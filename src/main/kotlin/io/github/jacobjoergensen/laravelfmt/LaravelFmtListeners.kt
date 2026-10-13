package io.github.jacobjoergensen.laravelfmt

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Restarts the server when `composer require`, `update` or `remove` changes the laravel-fmt install.
 *
 * Only the `vendor/bin/laravel-fmt*` proxies and the package directory are watched, not the package's
 * `bin/` folder: the wrapper downloads the binary there, and reacting to that would restart the server
 * it was just downloaded for.
 */
class LaravelFmtVendorListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) {
            return
        }

        val changes = events.map { it.path }.filter { isLaravelFmtInstallChange(it) }

        if (changes.isEmpty()) {
            return
        }

        val server = project.service<LaravelFmtServerService>()

        if (!server.isEnabled) {
            return
        }

        // File events arrive for every open project, so only react to this project's own install.
        val roots = server.projectRoots()

        if (changes.any { isInProject(it, roots) }) {
            server.scheduleRestart()
        }
    }

    companion object {
        fun isInProject(path: String, projectRoots: List<String>): Boolean {
            val normalized = path.replace('\\', '/')

            return projectRoots.any { root ->
                val prefix = root.replace('\\', '/').trimEnd('/')

                normalized == prefix || normalized.startsWith("$prefix/")
            }
        }

        fun isLaravelFmtInstallChange(path: String): Boolean {
            val normalized = path.replace('\\', '/')

            val isBinProxy = normalized.substringAfterLast('/').startsWith(BinaryResolver.BASE_NAME) &&
                normalized.substringBeforeLast('/').endsWith("/vendor/bin")

            return isBinProxy || normalized.endsWith("/vendor/${ComposerInstall.PACKAGE}")
        }
    }
}

/** Trust decides whether the project's own `vendor/bin` binary may run. */
class LaravelFmtTrustListener : TrustedProjectsListener {

    override fun onProjectTrusted(project: Project) = restart(project)

    override fun onProjectUntrusted(project: Project) = restart(project)

    private fun restart(project: Project) {
        if (!project.isDisposed) {
            project.service<LaravelFmtServerService>().restartServer()
        }
    }
}
