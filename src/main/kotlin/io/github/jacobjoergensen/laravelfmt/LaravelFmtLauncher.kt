package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import com.intellij.util.system.CpuArch
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * When the binary resolves to the Composer wrapper (`vendor/bin/laravel-fmt`, a PHP script), runs the
 * native binary for this system instead, from the [BinaryCache]. Running the wrapper as the server would
 * need `php` on the IDE's PATH, add a PHP process, and lock an `.exe` under `vendor/` on Windows.
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

        val os = currentOs()
        // No release is built for other CPUs, so there is nothing better to run.
        val arch = currentArch() ?: return resolved

        // Not a Composer install (explicit path or PATH), so it is the native binary already.
        val native = ComposerInstall.nativeBinary(resolved, os, arch) ?: return resolved

        if (File(native).isFile) {
            return try {
                BinaryCache(cacheRoot()).materialize(Path.of(native)).toString()
            } catch (e: Exception) {
                // Running the vendor copy directly still works; it only risks locking it on Windows.
                LOG.warn("Could not copy $native into the laravel-fmt binary cache; running it in place", e)
                native
            }
        }

        // Composer ran on another system, such as Sail or WSL, or the wrapper never ran here.
        val release = LaravelFmtRelease(installedVersion(native), os, arch)

        return try {
            BinaryCache(cacheRoot()).release(release.cacheKey, release.binaryName) { download(project, release) }.toString()
        } catch (e: IOException) {
            throw LaunchException(LaravelFmtBundle.message("launch.download.failed"), e.message)
        }
    }

    private fun installedVersion(native: String): String {
        val installedPhp = runCatching { File(ComposerInstall.installedVersionsFile(native)).readText() }.getOrNull()

        return installedPhp?.let(ComposerInstall::installedVersion)
            ?: throw LaunchException(LaravelFmtBundle.message("launch.version.unknown"))
    }

    private fun download(project: Project, release: LaravelFmtRelease): ByteArray {
        val result = CompletableFuture<ByteArray>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            LaravelFmtBundle.message("launch.download.task"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = LaravelFmtBundle.message("launch.download.progress")

                try {
                    val archive = request(release.url(release.archiveName)).readBytes(indicator)
                    val checksums = request(release.url(release.checksumsName)).readString(indicator)

                    release.verify(archive, checksums)
                    result.complete(release.extractBinary(archive))
                } catch (e: IOException) {
                    result.completeExceptionally(LaunchException(LaravelFmtBundle.message("launch.download.failed"), e.message))
                }
            }

            override fun onCancel() {
                result.completeExceptionally(LaunchException(LaravelFmtBundle.message("launch.download.cancelled")))
            }

            override fun onThrowable(error: Throwable) {
                result.completeExceptionally(error)
            }
        })

        try {
            // The requests enforce their own timeouts; this is only a backstop.
            return result.get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause as? LaunchException ?: LaunchException(LaravelFmtBundle.message("launch.download.failed"), e.cause?.message)
        } catch (_: TimeoutException) {
            throw LaunchException(LaravelFmtBundle.message("launch.download.timeout", DOWNLOAD_TIMEOUT_SECONDS))
        }
    }

    /** The IDE's HTTP client, so its proxy settings apply. */
    private fun request(url: String): RequestBuilder =
        HttpRequests.request(url)
            .productNameAsUserAgent()
            .connectTimeout(TimeUnit.SECONDS.toMillis(30).toInt())
            .readTimeout(TimeUnit.SECONDS.toMillis(60).toInt())

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
