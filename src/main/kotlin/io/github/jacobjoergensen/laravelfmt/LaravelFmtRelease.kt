package io.github.jacobjoergensen.laravelfmt

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * One platform's binary from a laravel-fmt GitHub release: the files the Composer wrapper's
 * `BinaryDownloader.php` fetches, so the plugin gets the binary without PHP on the host.
 */
class LaravelFmtRelease(private val version: String, os: ComposerInstall.Os, arch: ComposerInstall.Arch) {

    private val isWindows = os == ComposerInstall.Os.WINDOWS

    /** Release files drop a leading `v` from the tag, as GoReleaser names them. */
    private val fileVersion = version.replace(Regex("^v(?=\\d)"), "")

    private val platform = "${os.composerName}_${arch.composerName}"

    val archiveName = "${BinaryResolver.BASE_NAME}_${fileVersion}_$platform." + if (isWindows) "zip" else "tar.gz"

    val checksumsName = "${BinaryResolver.BASE_NAME}_${fileVersion}_checksums.txt"

    val binaryName = BinaryResolver.BASE_NAME + if (isWindows) ".exe" else ""

    val cacheKey = "release-${fileVersion}_$platform"

    fun url(fileName: String): String = "https://github.com/$REPO/releases/download/$version/$fileName"

    fun verify(archive: ByteArray, checksums: String) {
        val expected = checksums.lineSequence()
            .map { it.trim().split(WHITESPACE) }
            .firstOrNull { it.size >= 2 && it[1] == archiveName }
            ?.first()
            ?.lowercase()
            ?: throw IOException("No checksum listed for $archiveName.")

        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive))

        if (!MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
            throw IOException("Checksum mismatch for $archiveName.")
        }
    }

    fun extractBinary(archive: ByteArray): ByteArray {
        val binary = if (isWindows) {
            fromZip(archive)
        } else {
            fromTarGz(archive)
        }

        return binary ?: throw IOException("$archiveName does not contain $binaryName.")
    }

    private fun fromZip(archive: ByteArray): ByteArray? =
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == binaryName }
                ?.let { zip.readBytes() }
        }

    private fun fromTarGz(archive: ByteArray): ByteArray? {
        val tar = GZIPInputStream(ByteArrayInputStream(archive)).use { it.readBytes() }
        var offset = 0

        while (offset + TAR_BLOCK <= tar.size) {
            val name = tarField(tar, offset, 0, 100)

            // Two zeroed blocks end the archive.
            if (name.isEmpty()) {
                return null
            }

            val size = tarField(tar, offset, 124, 12).trim().ifEmpty { "0" }.toInt(8)
            val type = tar[offset + 156]
            val dataStart = offset + TAR_BLOCK
            val isRegularFile = type == '0'.code.toByte() || type == 0.toByte()

            if (isRegularFile && name.substringAfterLast('/') == binaryName) {
                return tar.copyOfRange(dataStart, dataStart + size)
            }

            offset = dataStart + (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK
        }

        return null
    }

    private fun tarField(tar: ByteArray, header: Int, start: Int, length: Int): String =
        String(tar, header + start, length, StandardCharsets.US_ASCII).substringBefore('\u0000')

    private companion object {
        const val REPO = "JacobJoergensen/laravel-fmt"

        const val TAR_BLOCK = 512

        val WHITESPACE = Regex("\\s+")
    }
}
