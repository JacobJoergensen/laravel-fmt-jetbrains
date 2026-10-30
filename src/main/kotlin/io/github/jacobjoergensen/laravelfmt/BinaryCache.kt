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
 * The server never runs the binary under `vendor/` itself: Windows locks a running executable, so
 * `composer update` would fail while the IDE is open.
 */
class BinaryCache(private val root: Path, private val clock: Clock = Clock.systemUTC()) {

    fun materialize(source: Path): Path {
        val hash = sha256(source)
        val dir = root.resolve(hash.take(HASH_PREFIX_LENGTH))
        val target = dir.resolve(source.fileName.toString())
        val isIntact = { isRunnable(target) && sha256(target) == hash }

        if (!isIntact()) {
            install(target, isIntact) { Files.copy(source, it, StandardCopyOption.REPLACE_EXISTING) }
        }

        return markUsed(target)
    }

    /** Unlike [materialize], not hashed again on later use: the checksum was verified on download. */
    fun release(key: String, binaryName: String, download: () -> ByteArray): Path {
        val target = root.resolve(key).resolve(binaryName)
        val isIntact = { isRunnable(target) }

        if (!isIntact()) {
            val binary = download()

            install(target, isIntact) { Files.write(it, binary) }
        }

        return markUsed(target)
    }

    /** Writes under a temp name first, so nothing ever runs a half-written executable. */
    private fun install(target: Path, isIntact: () -> Boolean, write: (Path) -> Unit) {
        val dir = Files.createDirectories(target.parent)
        val temp = Files.createTempFile(dir, "partial-", ".tmp")

        try {
            write(temp)

            // Check the outcome, not the return value: Windows has no execute bit, and a noexec mount takes the
            // bit but won't run the file.
            val bitSet = temp.toFile().setExecutable(true, true)

            if (!Files.isExecutable(temp)) {
                val reason = if (bitSet) " Its folder doesn't allow running programs." else ""

                throw IOException("Could not make $target executable.$reason")
            }

            moveIntoPlace(temp, target, isIntact)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun moveIntoPlace(temp: Path, target: Path, isIntact: () -> Boolean) {
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // Another project put the same version in place first and may already be running it, which
            // Windows won't let us replace. Its copy is identical, so use that one.
            if (!isIntact()) {
                throw e
            }
        }
    }

    private fun markUsed(target: Path): Path {
        Files.setLastModifiedTime(target.parent, FileTime.from(clock.instant()))
        pruneUnused()

        return target
    }

    /**
     * The cache is shared by all open projects, so a version another project just prepared must survive.
     * Ones still running stay, since Windows keeps them locked.
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

        fun isRunnable(file: Path): Boolean = Files.isRegularFile(file) && Files.isExecutable(file)

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
