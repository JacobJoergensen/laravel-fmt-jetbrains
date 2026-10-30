package io.github.jacobjoergensen.laravelfmt

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Only the `vendor/bin/laravel-fmt*` proxies and the package directory are watched, not the package's
 * `bin/` folder: the wrapper downloads the binary there, and reacting to that would restart the server
 * it was just downloaded for.
 */
class LaravelFmtSetupListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) {
            return
        }

        val changes = events.map { it.path }.filter { affectsLaravelFmt(it) }

        if (changes.isEmpty()) {
            return
        }

        val server = project.service<LaravelFmtServerService>()

        if (server.isOff) {
            return
        }

        // File events arrive for every open project, so only react to this project's own installation.
        val roots = server.projectRoots()

        if (changes.any { isInProject(it, roots) }) {
            server.scheduleRestart()
        }
    }
}

fun isInProject(path: String, projectRoots: List<String>): Boolean {
    val normalized = path.replace('\\', '/')

    return projectRoots.any { root ->
        val prefix = root.replace('\\', '/').trimEnd('/')

        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

fun affectsLaravelFmt(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    val name = normalized.substringAfterLast('/')

    val isBinProxy = name.startsWith(BinaryResolver.BASE_NAME) && normalized.substringBeforeLast('/').endsWith("/vendor/bin")

    // Every installed package has its own composer.json, which says nothing about this project.
    val isProjectFile = (name == LaravelFmtUsage.COMPOSER_FILE || name == LaravelFmtUsage.CONFIG_FILE) &&
        "/vendor/" !in normalized && "/node_modules/" !in normalized

    return isBinProxy || isProjectFile || normalized.endsWith("/vendor/${ComposerInstall.PACKAGE}")
}

/**
 * Trust decides whether the project's own `vendor/bin` binary may run. Trusting a project from its
 * safe-mode banner doesn't reopen it, so only this picks the binary up.
 */
// Experimental only in 2026.1; 2026.2 made it stable without changing it.
@Suppress("UnstableApiUsage")
class LaravelFmtTrustListener : TrustedProjectsListener {

    override fun onProjectTrusted(project: Project) = restart(project)

    override fun onProjectUntrusted(project: Project) = restart(project)

    private fun restart(project: Project) {
        if (!project.isDisposed) {
            project.service<LaravelFmtServerService>().restartServer()
        }
    }
}
