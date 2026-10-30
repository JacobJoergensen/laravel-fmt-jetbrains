package io.github.jacobjoergensen.laravelfmt

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Caches the apps (see [LaravelFmtUsage]) and each one's binary, since finding them touches the file system
 * and runs for every opened file. The EDT only reads the cache, even when it's out of date or not filled yet,
 * while a lookup runs in the background: a project folder can be slow, as under WSL or on a network share.
 */
@Service(Service.Level.PROJECT)
class LaravelFmtServerService(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    data class Resolution(
        val path: String?,
        /** A `vendor/bin` binary exists but was skipped because the project isn't trusted. */
        val blockedByTrust: Boolean,
        val explicitPathMissing: String?,
    )

    /** Resolutions are keyed by app, and by [NO_APP] for files outside every app. */
    private class Snapshot(val generation: Long, val apps: List<String>, val resolutions: Map<String, Resolution>)

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var generation = 0L

    private val refreshing = AtomicBoolean(false)

    private val lookUpLock = Any()

    @Volatile
    private var pendingRestart: Job? = null

    private val settings get() = project.service<LaravelFmtProjectSettings>()

    private val pathsIgnoreCase = !SystemInfo.isFileSystemCaseSensitive

    val isOff: Boolean
        get() = settings.mode == LaravelFmtProjectSettings.Mode.OFF

    /** From the last lookup, even an out-of-date one: menu updates and the save action ask often, and must be fast. */
    val isEnabled: Boolean
        get() = (settings.formatPhp || settings.formatBlade) && isOn { current(waitForNew = false).apps.isNotEmpty() }

    fun formats(file: VirtualFile): Boolean = when (LaravelFmtLspClientDescriptor.fileKind(file)) {
        LaravelFmtLspClientDescriptor.FileKind.PHP -> settings.formatPhp
        LaravelFmtLspClientDescriptor.FileKind.BLADE -> settings.formatBlade
        null -> false
    } && isOn { appFor(file) != null }

    /** Takes a lambda so only Automatic mode looks anything up. */
    private fun isOn(usesLaravelFmt: () -> Boolean): Boolean = when (settings.mode) {
        LaravelFmtProjectSettings.Mode.AUTOMATIC -> usesLaravelFmt()
        LaravelFmtProjectSettings.Mode.ALWAYS -> true
        LaravelFmtProjectSettings.Mode.OFF -> false
    }

    /** Null for a file outside every app, which only Always mode formats. */
    fun appFor(file: VirtualFile): String? = LaravelFmtUsage.appFor(file.path, apps(), ignoreCase = pathsIgnoreCase)

    fun apps(): List<String> = current().apps

    /** [app] is null for files outside every app. Null for an app gone since the last lookup. */
    fun resolve(app: String?): Resolution? = current().resolutions[app ?: NO_APP]

    /**
     * Looks the binary up afresh, for starting the server. Not on the EDT. The snapshot [resolve] reads only
     * decides whether to start, without touching the disk; this checks the disk right before launching, so
     * the server runs the binary that is there now, even for an app gone since the last lookup.
     */
    fun resolveNow(app: String?): Resolution = compute(apps(), app, ::findOnPath)

    /** Looks everything up on the calling thread, which must not be the EDT. */
    fun refresh() {
        lookUp()
    }

    fun invalidate() {
        synchronized(this) {
            generation++
        }
    }

    /**
     * Off the EDT, a missing lookup or one older than the last [invalidate] is done first. The EDT, and a
     * caller that doesn't [waitForNew], gets what there is, [NOTHING_YET] before the first lookup, and a new
     * one starts in the background.
     */
    private fun current(waitForNew: Boolean = !ApplicationManager.getApplication().isDispatchThread): Snapshot {
        val known = snapshot

        if (known != null && known.generation == generation) {
            return known
        }

        if (!waitForNew) {
            lookUpInBackground()

            return known ?: NOTHING_YET
        }

        return lookUp()
    }

    @Suppress("kotlin:S6310")
    // The platform owns this scope; blocking work goes to IO, and nothing would inject another dispatcher.
    private fun lookUpInBackground() {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                lookUp()
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * Every binary at once, so the EDT never has to look one up. `PATH` is searched once for all of them.
     *
     * The roots are read before taking [lookUpLock]: a read action under it could wait for a write
     * action on the EDT, which may itself be waiting for the lock.
     */
    private fun lookUp(): Snapshot {
        val startedAt = generation
        val roots = projectRoots()

        synchronized(lookUpLock) {
            // The lookup this one waited for may already be the one needed.
            snapshot?.takeIf { it.generation >= startedAt }?.let { return it }

            val apps = LaravelFmtUsage.appDirs(roots, SystemInfo.isWindows)
            val onPath by lazy { findOnPath(BinaryResolver.BASE_NAME) }

            val resolutions = (apps + NO_APP).associateWith { key ->
                compute(apps, key.takeUnless { it == NO_APP }) { onPath }
            }

            val result = Snapshot(startedAt, apps, resolutions)
            val first: Boolean

            synchronized(this) {
                first = snapshot == null

                // Kept even if an invalidate came since it started, until a newer lookup lands: the EDT
                // needs one to read. It is out of date then, so the next caller looks up again.
                if (generation == startedAt || first) {
                    snapshot = result
                }
            }

            if (first) {
                startHeldBackServers()
            }

            return result
        }
    }

    /** Files opened before the first lookup got no server: the EDT couldn't tell yet whether to start one. */
    @Suppress("kotlin:S6310")
    private fun startHeldBackServers() {
        scope.launch(Dispatchers.EDT) {
            LspClientManager.getInstance(project).startClientsIfNeeded(LaravelFmtLspIntegrationProvider::class.java)
        }
    }

    /** Safe to call from any thread. Looks up again first, so the servers restart with the new lookup. */
    @Suppress("kotlin:S6310")
    fun restartServer() {
        invalidate()
        project.service<LaravelFmtNotifications>().reset()

        scope.launch {
            withContext(Dispatchers.IO) { refresh() }

            withContext(Dispatchers.EDT) {
                LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(LaravelFmtLspIntegrationProvider::class.java)
            }
        }
    }

    /** Debounced, for bursts of file events such as a `composer update`. */
    fun scheduleRestart() {
        synchronized(this) {
            pendingRestart?.cancel()

            pendingRestart = scope.launch {
                delay(RESTART_DELAY_MS.milliseconds)
                restartServer()
            }
        }
    }

    /**
     * An app runs its own install, or else the one of an app around it, like a monorepo root's `vendor/bin`.
     * Files outside every app run the first install in the project.
     */
    private fun compute(apps: List<String>, app: String?, pathLookup: (String) -> String?): Resolution {
        val explicitPath = service<LaravelFmtSettings>().binaryPath
        val trusted = TrustedProjects.isProjectTrusted(project)
        // An app gone since the lookup, whose server is still running, keeps its own installation.
        val installDirs = app
            ?.let { LaravelFmtUsage.enclosingApps(it, apps, ignoreCase = pathsIgnoreCase).ifEmpty { listOf(it) } }
            ?: apps

        val path = BinaryResolver.resolve(
            explicitPath = explicitPath,
            appDirs = installDirs,
            isWindows = SystemInfo.isWindows,
            allowProjectLocal = trusted,
            pathLookup = pathLookup,
        )

        val blockedByTrust = !trusted && installDirs.any { BinaryResolver.vendorBinIn(it, SystemInfo.isWindows) != null }
        val explicitMissing = explicitPath.trim().takeIf { BinaryResolver.isExplicitPathMissing(it) }

        return Resolution(path, blockedByTrust, explicitMissing)
    }

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

        private const val NO_APP = ""

        private val NOTHING_YET = Snapshot(-1, emptyList(), emptyMap())

        fun restartAll() {
            ProjectManager.getInstance().openProjects
                .filterNot { it.isDisposed }
                .forEach { it.service<LaravelFmtServerService>().restartServer() }
        }
    }
}
