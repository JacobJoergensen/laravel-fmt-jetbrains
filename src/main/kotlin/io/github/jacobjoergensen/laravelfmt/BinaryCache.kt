package io.github.jacobjoergensen.laravelfmt

import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat

/**
 * Copies of the native laravel-fmt binary, keyed by SHA-256, so the server never runs the file under
 * `vendor/`. Windows locks a running executable, which would make `composer update` fail while the
 * IDE is open.
 */
class BinaryCache(private val root: Path, private val clock: Clock = Clock.systemUTC()) {

    fun materialize(source: Path): Path {
        val hash = sha256(source)
        val dir = root.resolve(hash.take(HASH_PREFIX_LENGTH))
        val target = dir.resolve(source.fileName.toString())

        if (!Files.isRegularFile(target) || sha256(target) != hash) {
            Files.createDirectories(dir)

            // Copy under a temp name first, so nothing ever runs a half-written executable.
            val temp = Files.createTempFile(dir, "partial-", ".tmp")

            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
                temp.toFile().setExecutable(true, true)
                moveIntoPlace(temp, target, hash)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        Files.setLastModifiedTime(dir, FileTime.from(clock.instant()))
        pruneUnused()

        return target
    }

    private fun moveIntoPlace(temp: Path, target: Path, hash: String) {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // Another project put the same version in place first and may already be running it, which
            // Windows won't let us replace. Its copy is identical, so use that one.
            if (!Files.isRegularFile(target) || sha256(target) != hash) {
                throw e
            }
        }
    }

    /**
     * Removes versions no project has used for a while. The cache is shared by all open projects, so a
     * version another project just prepared must survive. Ones still running stay, since Windows keeps
     * them locked.
     */
    private fun pruneUnused() {
        val cutoff = clock.instant().minus(UNUSED_FOR)

        Files.list(root).use { entries ->
            entries.filter { Files.isDirectory(it) }.forEach { dir ->
                runCatching {
                    if (Files.getLastModifiedTime(dir).toInstant() < cutoff) {
                        Files.walk(dir).use { walk ->
                            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val HASH_PREFIX_LENGTH = 16

        val UNUSED_FOR: Duration = Duration.ofDays(7)

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
