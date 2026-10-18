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
 * Caches the binary lookup and whether the project uses laravel-fmt, since both touch the file system and
 * run for every opened file. The cache is dropped by [restartServer] whenever an input changes: the
 * settings, project trust, the laravel-fmt Composer install, `composer.json` or `laravel-fmt.json`.
 */
@Service(Service.Level.PROJECT)
class LaravelFmtServerService(private val project: Project) : Disposable {

    data class Resolution(
        val path: String?,
        /** A `vendor/bin` binary exists but was skipped because the project isn't trusted. */
        val blockedByTrust: Boolean,
        /** The configured explicit path, if it doesn't exist. */
        val explicitPathMissing: String?,
        val usesLaravelFmt: Boolean,
    )

    @Volatile
    private var cached: Resolution? = null

    /** Bumped by [invalidate], so a lookup that started before it doesn't put a stale result back. */
    private var generation = 0L

    @Volatile
    private var pendingRestart: ScheduledFuture<*>? = null

    private val settings get() = project.service<LaravelFmtProjectSettings>()

    val isOff: Boolean
        get() = settings.mode == LaravelFmtProjectSettings.Mode.OFF

    val isEnabled: Boolean
        get() = (settings.formatPhp || settings.formatBlade) && isOn

    fun formats(file: VirtualFile): Boolean = when (LaravelFmtLspServerDescriptor.fileKind(file)) {
        LaravelFmtLspServerDescriptor.FileKind.PHP -> settings.formatPhp
        LaravelFmtLspServerDescriptor.FileKind.BLADE -> settings.formatBlade
        null -> false
    } && isOn

    private val isOn: Boolean
        get() = when (settings.mode) {
            LaravelFmtProjectSettings.Mode.AUTOMATIC -> resolve().usesLaravelFmt
            LaravelFmtProjectSettings.Mode.ALWAYS -> true
            LaravelFmtProjectSettings.Mode.OFF -> false
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

    /**
     * Safe to call from any thread. The provider starts the server again for open files if it should run.
     *
     * Looks up again on a pooled thread first: in automatic mode [formats] needs the lookup, and it is
     * also called on the EDT, where walking a slow project folder would freeze the IDE.
     */
    fun restartServer() {
        invalidate()
        project.service<LaravelFmtNotifications>().reset()

        AppExecutorUtil.getAppExecutorService().execute {
            if (!project.isDisposed) {
                resolve()
            }

            ApplicationManager.getApplication().invokeLater({
                LspServerManager.getInstance(project).stopAndRestartIfNeeded(LaravelFmtLspServerSupportProvider::class.java)
            }, project.disposed)
        }
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
        val explicitPath = service<LaravelFmtSettings>().binaryPath
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

        return Resolution(path, blockedByTrust, explicitMissing, LaravelFmtUsage.isUsedIn(roots, SystemInfo.isWindows))
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
