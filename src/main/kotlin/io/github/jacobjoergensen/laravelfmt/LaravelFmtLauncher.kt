package io.github.jacobjoergensen.laravelfmt

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * When the binary resolves to the Composer wrapper (`vendor/bin/laravel-fmt`, a PHP script), runs the
 * native binary it downloads instead, from a [BinaryCache] copy. Running the wrapper as the server
 * would need `php` on the IDE's PATH, add a PHP process, and lock an `.exe` under `vendor/` on Windows.
 *
 * Called on a pooled thread, so blocking here is fine.
 */
object LaravelFmtLauncher {

    private val LOG = logger<LaravelFmtLauncher>()

    private const val DOWNLOAD_TIMEOUT_SECONDS = 300L

    class LaunchException(val userMessage: String, val details: String? = null) : Exception(userMessage)

    /** Returns the executable to run as `<executable> lsp`. */
    fun prepare(project: Project): String {
        val resolved = project.service<LaravelFmtServerService>().resolve(refresh = true).path
            ?: throw LaunchException(LaravelFmtBundle.message("launch.not.found"))

        // Not a Composer install (explicit path or PATH), so it is the native binary already.
        val native = ComposerInstall.nativeBinary(resolved, currentOs(), currentArch())
            ?: return resolved

        if (!File(native).isFile) {
            downloadViaWrapper(project, resolved)

            if (!File(native).isFile) {
                // E.g. the IDE's JVM and PHP report different architectures, so the wrapper cached the
                // binary under another name. The wrapper itself still works as a server.
                LOG.warn("laravel-fmt wrapper did not produce $native; running the wrapper directly")
                return resolved
            }
        }

        return try {
            BinaryCache(cacheRoot()).materialize(Path.of(native)).toString()
        } catch (e: Exception) {
            // Running the vendor copy directly still works; it only risks locking it on Windows.
            LOG.warn("Could not copy $native into the laravel-fmt binary cache; running it in place", e)
            native
        }
    }

    /** Runs the wrapper once with `--version`, which makes it download the native binary. */
    private fun downloadViaWrapper(project: Project, wrapper: String) {
        val commandLine = GeneralCommandLine(wrapper, "--version")
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        project.basePath?.let { commandLine.withWorkDirectory(it) }

        val result = CompletableFuture<Unit>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            LaravelFmtBundle.message("launch.download.task"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = LaravelFmtBundle.message("launch.download.progress")
                indicator.isIndeterminate = true

                try {
                    val output = CapturingProcessHandler(commandLine)
                        .runProcessWithProgressIndicator(indicator, TimeUnit.SECONDS.toMillis(DOWNLOAD_TIMEOUT_SECONDS).toInt())

                    when {
                        output.isCancelled ->
                            result.completeExceptionally(LaunchException(LaravelFmtBundle.message("launch.download.cancelled")))
                        output.isTimeout ->
                            result.completeExceptionally(
                                LaunchException(LaravelFmtBundle.message("launch.download.timeout", DOWNLOAD_TIMEOUT_SECONDS)),
                            )
                        output.exitCode != 0 ->
                            result.completeExceptionally(
                                LaunchException(
                                    LaravelFmtBundle.message("launch.download.failed.exit", output.exitCode.toString()),
                                    output.stderr.ifBlank { output.stdout },
                                ),
                            )
                        else -> result.complete(Unit)
                    }
                } catch (e: Exception) {
                    result.completeExceptionally(
                        LaunchException(LaravelFmtBundle.message("launch.wrapper.failed"), e.message),
                    )
                }
            }

            override fun onThrowable(error: Throwable) {
                result.completeExceptionally(error)
            }
        })

        try {
            // The task enforces its own timeout; this is only a backstop.
            result.get(DOWNLOAD_TIMEOUT_SECONDS + 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause as? LaunchException ?: LaunchException(LaravelFmtBundle.message("launch.download.failed"), e.cause?.message)
        } catch (_: TimeoutException) {
            throw LaunchException(LaravelFmtBundle.message("launch.download.timeout", DOWNLOAD_TIMEOUT_SECONDS))
        }
    }

    private fun cacheRoot(): Path = PathManager.getSystemDir().resolve("laravel-fmt").resolve("bin")

    private fun currentOs(): ComposerInstall.Os = when {
        SystemInfo.isWindows -> ComposerInstall.Os.WINDOWS
        SystemInfo.isMac -> ComposerInstall.Os.MAC
        else -> ComposerInstall.Os.LINUX
    }

    private fun currentArch(): ComposerInstall.Arch? = when (CpuArch.CURRENT) {
        CpuArch.X86_64 -> ComposerInstall.Arch.X86_64
        CpuArch.ARM64 -> ComposerInstall.Arch.ARM64
        else -> null
    }
}
