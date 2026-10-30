package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.RequestBuilder
import com.intellij.util.system.CpuArch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

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

    private fun downloadFailed(details: String?) = LaunchException(LaravelFmtBundle.message("launch.download.failed"), details)

    fun prepare(project: Project, app: String?): String {
        val resolved = project.service<LaravelFmtServerService>().resolveNow(app).path
            ?: throw LaunchException(LaravelFmtBundle.message("launch.not.found"))

        val os = currentOs()
        // No release is built for other CPUs, so there is nothing better to run.
        val arch = currentArch() ?: return resolved

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
            throw downloadFailed(e.message)
        }
    }

    private fun installedVersion(native: String): String {
        val installedPhp = runCatching { File(ComposerInstall.installedVersionsFile(native)).readText() }.getOrNull()

        return installedPhp?.let(ComposerInstall::installedVersion)
            ?: throw LaunchException(LaravelFmtBundle.message("launch.version.unknown"))
    }

    @Suppress("kotlin:S6310")
    // The platform owns this scope; blocking work goes to IO, and nothing would inject another dispatcher.
    private fun download(project: Project, release: LaravelFmtRelease): ByteArray {
        val download = project.service<LaravelFmtDownloads>().scope.async(Dispatchers.IO) {
            // A failure is returned, so the deferred only ends exceptionally when it's canceled.
            try {
                Result.success(
                    withBackgroundProgress(project, LaravelFmtBundle.message("launch.download.task")) {
                        coroutineToIndicator { indicator ->
                            indicator.text = LaravelFmtBundle.message("launch.download.progress")

                            try {
                                val archive = request(release.url(release.archiveName)).readBytes(indicator)
                                val checksums = request(release.url(release.checksumsName)).readString(indicator)

                                release.verify(archive, checksums)
                                release.extractBinary(archive)
                            } catch (e: IOException) {
                                throw downloadFailed(e.message)
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        try {
            // Decided in here: any cancellation that leaves runBlockingMaybeCancellable becomes a
            // ProcessCanceledException, which would hide a timeout or a canceled download.
            return runBlockingMaybeCancellable {
                // The requests enforce their own timeouts; this is only a backstop. A canceled server start
                // rethrows from join().
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_SECONDS.seconds) { download.join() }
                    ?: throw LaunchException(LaravelFmtBundle.message("launch.download.timeout", DOWNLOAD_TIMEOUT_SECONDS))

                if (download.isCancelled) throw LaunchException(LaravelFmtBundle.message("launch.download.cancelled"))

                download.await().getOrThrow()
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            throw e as? LaunchException ?: downloadFailed(e.message)
        } finally {
            download.cancel()
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

/** Holds downloads for the project's lifetime: its scope is canceled when the project closes or the plugin unloads. */
@Service(Service.Level.PROJECT)
internal class LaravelFmtDownloads(val scope: CoroutineScope)
