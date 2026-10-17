package io.github.jacobjoergensen.laravelfmt

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Caches the binary lookup, since it touches the file system and runs for every opened file. The cache is
 * dropped by [restartServer] whenever an input changes: the settings, project trust, or the laravel-fmt
 * Composer install.
 */
@Service(Service.Level.PROJECT)
class LaravelFmtServerService(private val project: Project) : Disposable {

    data class Resolution(
        val path: String?,
        /** A `vendor/bin` binary exists but was skipped because the project isn't trusted. */
        val blockedByTrust: Boolean,
        /** The configured explicit path, if it doesn't exist. */
        val explicitPathMissing: String?,
    )

    @Volatile
    private var cached: Resolution? = null

    /** Bumped by [invalidate], so a lookup that started before it doesn't put a stale result back. */
    private var generation = 0L

    @Volatile
    private var pendingRestart: ScheduledFuture<*>? = null

    private val settings get() = project.service<LaravelFmtProjectSettings>()

    val isEnabled: Boolean
        get() = settings.enabled && (settings.formatPhp || settings.formatBlade)

    fun formats(file: VirtualFile): Boolean = settings.enabled && when (LaravelFmtLspServerDescriptor.fileKind(file)) {
        LaravelFmtLspServerDescriptor.FileKind.PHP -> settings.formatPhp
        LaravelFmtLspServerDescriptor.FileKind.BLADE -> settings.formatBlade
        null -> false
    }

    fun resolve(refresh: Boolean = false): Resolution {
        val current = cached

        if (!refresh && current != null) {
            return current
        }

        val startedAt = synchronized(this) { generation }
        val result = compute()

        synchronized(this) {
            if (generation == startedAt) {
                cached = result
            }
        }

        return result
    }

    fun invalidate() {
        synchronized(this) {
            generation++
            cached = null
        }
    }

    /** Safe to call from any thread. The provider starts the server again for open files if it should run. */
    fun restartServer() {
        invalidate()
        project.service<LaravelFmtBinaryNotifier>().reset()

        ApplicationManager.getApplication().invokeLater({
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(LaravelFmtLspServerSupportProvider::class.java)
        }, project.disposed)
    }

    /** Debounced, for bursts of file events such as a `composer update`. */
    fun scheduleRestart() {
        synchronized(this) {
            pendingRestart?.cancel(false)

            pendingRestart = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (!project.isDisposed) {
                    restartServer()
                }
            }, RESTART_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun dispose() {
        pendingRestart?.cancel(false)
    }

    private fun compute(): Resolution {
        val explicitPath = LaravelFmtSettings.getInstance().binaryPath
        val trusted = TrustedProjects.isProjectTrusted(project)
        val roots = projectRoots()

        val path = BinaryResolver.resolve(
            explicitPath = explicitPath,
            projectRoots = roots,
            isWindows = SystemInfo.isWindows,
            allowProjectLocal = trusted,
            pathLookup = ::findOnPath,
        )

        val blockedByTrust = !trusted && BinaryResolver.findProjectLocal(roots, SystemInfo.isWindows) != null
        val explicitMissing = explicitPath.trim().takeIf { BinaryResolver.isExplicitPathMissing(it) }

        return Resolution(path, blockedByTrust, explicitMissing)
    }

    /** Includes every content root, for monorepos and attached folders. */
    fun projectRoots(): List<String> {
        val contentRoots = ApplicationManager.getApplication().runReadAction(Computable {
            if (project.isDisposed) {
                emptyList()
            } else {
                ProjectRootManager.getInstance(project).contentRoots.filter { it.isInLocalFileSystem }.map { it.path }
            }
        })

        return listOfNotNull(project.basePath) + contentRoots
    }

    private fun findOnPath(name: String): String? {
        val pathValue = PathEnvironmentVariableUtil.getPathVariableValue() ?: return null

        return BinaryResolver.findOnPath(
            name = name,
            pathDirs = PathEnvironmentVariableUtil.getPathDirs(pathValue),
            isWindows = SystemInfo.isWindows,
            windowsExtensions = PathEnvironmentVariableUtil.getWindowsExecutableFileExtensions(),
        )
    }

    companion object {
        private const val RESTART_DELAY_MS = 1500L

        fun restartAll() {
            ProjectManager.getInstance().openProjects
                .filterNot { it.isDisposed }
                .forEach { it.service<LaravelFmtServerService>().restartServer() }
        }
    }
}
