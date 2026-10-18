package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LaravelFmtReleaseTest {

    private val linux = LaravelFmtRelease("0.3.0", ComposerInstall.Os.LINUX, ComposerInstall.Arch.X86_64)

    private val windows = LaravelFmtRelease("0.3.0", ComposerInstall.Os.WINDOWS, ComposerInstall.Arch.ARM64)

    private val binary = "native binary".toByteArray()

    @Test
    fun `release files are named like the Composer wrapper downloads them`() {
        assertEquals("laravel-fmt_0.3.0_Linux_x86_64.tar.gz", linux.archiveName)
        assertEquals("laravel-fmt", linux.binaryName)
        assertEquals("laravel-fmt_0.3.0_Windows_arm64.zip", windows.archiveName)
        assertEquals("laravel-fmt.exe", windows.binaryName)
        assertEquals("laravel-fmt_0.3.0_checksums.txt", windows.checksumsName)
        assertEquals(
            "https://github.com/JacobJoergensen/laravel-fmt/releases/download/0.3.0/laravel-fmt_0.3.0_Linux_x86_64.tar.gz",
            linux.url(linux.archiveName),
        )
    }

    @Test
    fun `a v-prefixed tag keeps its v in the URL but not in the file names`() {
        val release = LaravelFmtRelease("v1.0.0", ComposerInstall.Os.MAC, ComposerInstall.Arch.ARM64)

        assertEquals(
            "https://github.com/JacobJoergensen/laravel-fmt/releases/download/v1.0.0/laravel-fmt_1.0.0_Darwin_arm64.tar.gz",
            release.url(release.archiveName),
        )
    }

    @Test
    fun `an archive matching its listed checksum is accepted`() {
        val archive = tarGz("laravel-fmt" to binary)

        assertDoesNotThrow { linux.verify(archive, checksums(linux.archiveName to archive)) }
    }

    @Test
    fun `an archive that differs from its listed checksum is refused`() {
        val archive = tarGz("laravel-fmt" to binary)
        val listed = checksums(linux.archiveName to "something else".toByteArray())

        assertThrows<IOException> { linux.verify(archive, listed) }
    }

    @Test
    fun `an archive listed only under a longer name, like its SBOM, is refused`() {
        val archive = tarGz("laravel-fmt" to binary)

        assertThrows<IOException> { linux.verify(archive, checksums("${linux.archiveName}.sbom.json" to archive)) }
    }

    @Test
    fun `the binary is taken from a tar gz archive among other files`() {
        val archive = tarGz("README.md" to "readme with an odd length".toByteArray(), "laravel-fmt" to binary)

        assertArrayEquals(binary, linux.extractBinary(archive))
    }

    @Test
    fun `the binary is taken from a zip archive among other files`() {
        val archive = zip("LICENSE.md" to "license".toByteArray(), "laravel-fmt.exe" to binary)

        assertArrayEquals(binary, windows.extractBinary(archive))
    }

    @Test
    fun `an archive without the binary is refused`() {
        assertThrows<IOException> { linux.extractBinary(tarGz("README.md" to "readme".toByteArray())) }
    }

    private fun checksums(vararg files: Pair<String, ByteArray>): String =
        files.joinToString("\n") { (name, bytes) ->
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "  " + name
        }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()

        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        return out.toByteArray()
    }

    private fun tarGz(vararg files: Pair<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()

        files.forEach { (name, bytes) ->
            val header = ByteArray(512)

            fun put(offset: Int, value: String) = value.toByteArray(StandardCharsets.US_ASCII).copyInto(header, offset)

            put(0, name)
            put(100, "0000755")
            put(124, bytes.size.toString(8).padStart(11, '0'))
            put(156, "0")
            put(257, "ustar")

            tar.write(header)
            tar.write(bytes)
            tar.write(ByteArray((512 - bytes.size % 512) % 512))
        }

        tar.write(ByteArray(1024))

        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tar.toByteArray()) }

        return out.toByteArray()
    }
}
