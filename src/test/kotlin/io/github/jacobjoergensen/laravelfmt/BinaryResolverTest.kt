package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BinaryResolverTest {

    private val noPath: (String) -> String? = { null }

    private fun existsOnly(vararg paths: String): (String) -> Boolean {
        val set = paths.toSet()

        return { it in set }
    }

    @Test
    fun `explicit path wins when it exists`() {
        val result = BinaryResolver.resolve(
            explicitPath = "/opt/tools/laravel-fmt",
            projectRoots = listOf("/project"),
            isWindows = false,
            allowProjectLocal = true,
            pathLookup = { "/usr/bin/laravel-fmt" },
            fileExists = existsOnly("/opt/tools/laravel-fmt", "/project/vendor/bin/laravel-fmt"),
        )

        assertEquals("/opt/tools/laravel-fmt", result)
    }

    @Test
    fun `blank explicit path is ignored`() {
        val result = BinaryResolver.resolve(
            explicitPath = "   ",
            projectRoots = listOf("/project"),
            isWindows = false,
            allowProjectLocal = true,
            pathLookup = noPath,
            fileExists = existsOnly("/project/vendor/bin/laravel-fmt"),
        )

        assertEquals("/project/vendor/bin/laravel-fmt", result)
    }

    @Test
    fun `non-existent explicit path falls through to vendor bin`() {
        val result = BinaryResolver.resolve(
            explicitPath = "/nope/laravel-fmt",
            projectRoots = listOf("/project"),
            isWindows = false,
            allowProjectLocal = true,
            pathLookup = { "/usr/bin/laravel-fmt" },
            fileExists = existsOnly("/project/vendor/bin/laravel-fmt"),
        )

        assertEquals("/project/vendor/bin/laravel-fmt", result)
    }

    @Test
    fun `windows vendor bin prefers the bat, cmd and exe shims over the bare name`() {
        val seen = mutableListOf<String>()

        BinaryResolver.findProjectLocal(
            roots = listOf("C:/project"),
            isWindows = true,
            fileExists = {
                seen += it
                false
            },
            listSubdirectories = { emptyList() },
        )

        assertEquals(
            listOf(
                "C:/project/vendor/bin/laravel-fmt.bat",
                "C:/project/vendor/bin/laravel-fmt.cmd",
                "C:/project/vendor/bin/laravel-fmt.exe",
                "C:/project/vendor/bin/laravel-fmt",
            ),
            seen,
        )
    }

    @Test
    fun `falls back to PATH when no vendor bin`() {
        val result = BinaryResolver.resolve(
            explicitPath = "",
            projectRoots = listOf("/project"),
            isWindows = false,
            allowProjectLocal = true,
            pathLookup = { name ->
                if (name == "laravel-fmt") {
                    "/usr/local/bin/laravel-fmt"
                } else {
                    null
                }
            },
            fileExists = { false },
        )

        assertEquals("/usr/local/bin/laravel-fmt", result)
    }

    @Test
    fun `untrusted project skips vendor bin and falls back to PATH`() {
        val result = BinaryResolver.resolve(
            explicitPath = "",
            projectRoots = listOf("/project"),
            isWindows = false,
            allowProjectLocal = false,
            pathLookup = { "/usr/local/bin/laravel-fmt" },
            fileExists = existsOnly("/project/vendor/bin/laravel-fmt"),
        )

        assertEquals("/usr/local/bin/laravel-fmt", result)
    }

    @Test
    fun `untrusted project still honours the explicit path`() {
        val result = BinaryResolver.resolve(
            explicitPath = "/opt/tools/laravel-fmt",
            projectRoots = listOf("/project"),
            isWindows = false,
            allowProjectLocal = false,
            pathLookup = noPath,
            fileExists = existsOnly("/opt/tools/laravel-fmt", "/project/vendor/bin/laravel-fmt"),
        )

        assertEquals("/opt/tools/laravel-fmt", result)
    }

    @Test
    fun `returns null when nothing is found`() {
        val result = BinaryResolver.resolve(
            explicitPath = "",
            projectRoots = listOf("/project"),
            isWindows = false,
            allowProjectLocal = true,
            pathLookup = noPath,
            fileExists = { false },
        )

        assertNull(result)
    }

    @Test
    fun `vendor bin is found in a monorepo app two levels down`(@TempDir repo: Path) {
        val binary = Files.createDirectories(repo.resolve("apps/web/vendor/bin")).resolve("laravel-fmt")
        Files.createFile(binary)
        Files.createDirectories(repo.resolve("docs"))

        val result = BinaryResolver.findProjectLocal(listOf(repo.toString()), isWindows = false)

        assertEquals(binary.toString().replace('\\', '/'), result)
    }

    @Test
    fun `vendor bin three levels down is not searched`(@TempDir repo: Path) {
        Files.createFile(Files.createDirectories(repo.resolve("a/b/c/vendor/bin")).resolve("laravel-fmt"))

        val result = BinaryResolver.findProjectLocal(listOf(repo.toString()), isWindows = false)

        assertNull(result)
    }

    @Test
    fun `project roots win over subdirectories, then subdirectories are sorted`() {
        val subdirs = mapOf("/repo" to listOf("zeta", "alpha"), "/other" to emptyList<String>())
        val fileExists = existsOnly(
            "/repo/alpha/vendor/bin/laravel-fmt",
            "/repo/zeta/vendor/bin/laravel-fmt",
            "/other/vendor/bin/laravel-fmt",
        )

        val result = BinaryResolver.findProjectLocal(listOf("/repo", "/other"), false, fileExists) { subdirs[it].orEmpty() }
        val withoutRoot = BinaryResolver.findProjectLocal(listOf("/repo"), false, fileExists) { subdirs[it].orEmpty() }

        assertEquals("/other/vendor/bin/laravel-fmt", result)
        assertEquals("/repo/alpha/vendor/bin/laravel-fmt", withoutRoot)
    }

    @Test
    fun `hidden and non-app subdirectories are not searched`() {
        val result = BinaryResolver.findProjectLocal(
            roots = listOf("/repo"),
            isWindows = false,
            fileExists = existsOnly(
                "/repo/vendor/x/vendor/bin/laravel-fmt",
                "/repo/.git/vendor/bin/laravel-fmt",
                "/repo/node_modules/vendor/bin/laravel-fmt",
            ),
            listSubdirectories = { listOf("vendor", ".git", "node_modules") },
        )

        assertNull(result)
    }

    @Test
    fun `a root listed twice is searched once`() {
        val seen = mutableListOf<String>()

        BinaryResolver.findProjectLocal(
            roots = listOf("C:\\repo\\", "C:/repo", ""),
            isWindows = false,
            fileExists = {
                seen += it
                false
            },
            listSubdirectories = { emptyList() },
        )

        assertEquals(listOf("C:/repo/vendor/bin/laravel-fmt"), seen)
    }

    @Test
    fun `PATH lookup on windows tries PATHEXT extensions in order`() {
        val result = BinaryResolver.findOnPath(
            name = "laravel-fmt",
            pathDirs = listOf("C:\\tools", "\"C:\\go\\bin\"\\"),
            isWindows = true,
            windowsExtensions = listOf(".COM", ".EXE", ".BAT"),
            isExecutableFile = { it == "C:\\go\\bin\\laravel-fmt.exe" || it == "C:\\go\\bin\\laravel-fmt.bat" },
        )

        assertEquals("C:\\go\\bin\\laravel-fmt.exe", result)
    }

    @Test
    fun `PATH lookup on unix uses the bare name and first match`() {
        val result = BinaryResolver.findOnPath(
            name = "laravel-fmt",
            pathDirs = listOf("", "/usr/bin/", "/usr/local/bin"),
            isWindows = false,
            isExecutableFile = { it == "/usr/local/bin/laravel-fmt" || it == "/usr/bin/laravel-fmt" },
        )

        assertEquals("/usr/bin/laravel-fmt", result)
    }

    @Test
    fun `PATH lookup skips relative entries`() {
        val unix = BinaryResolver.findOnPath(
            name = "laravel-fmt",
            pathDirs = listOf(".", "bin", "/usr/bin"),
            isWindows = false,
            isExecutableFile = { true },
        )

        val windows = BinaryResolver.findOnPath(
            name = "laravel-fmt",
            pathDirs = listOf(".", "tools\\bin", "C:\\"),
            isWindows = true,
            windowsExtensions = listOf(".EXE"),
            isExecutableFile = { true },
        )

        assertEquals("/usr/bin/laravel-fmt", unix)
        assertEquals("C:\\laravel-fmt.exe", windows)
    }

    @Test
    fun `explicit path missing is reported only when set and absent`() {
        assertTrue(BinaryResolver.isExplicitPathMissing("/nope", existsOnly()))
        assertFalse(BinaryResolver.isExplicitPathMissing("/yes", existsOnly("/yes")))
        assertFalse(BinaryResolver.isExplicitPathMissing("  ", existsOnly()))
        assertFalse(BinaryResolver.isExplicitPathMissing(null, existsOnly()))
    }
}
