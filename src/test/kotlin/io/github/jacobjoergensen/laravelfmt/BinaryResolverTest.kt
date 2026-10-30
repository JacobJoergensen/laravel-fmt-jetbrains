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
            appDirs = listOf("/project"),
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
            appDirs = listOf("/project"),
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
            appDirs = listOf("/project"),
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

        BinaryResolver.vendorBinIn("C:/project", isWindows = true) {
            seen += it
            false
        }

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
            appDirs = listOf("/project"),
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
            appDirs = listOf("/project"),
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
            appDirs = listOf("/project"),
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
            appDirs = listOf("/project"),
            isWindows = false,
            allowProjectLocal = true,
            pathLookup = noPath,
            fileExists = { false },
        )

        assertNull(result)
    }

    @Test
    fun `the first app folder with an install wins, and other folders are not searched`() {
        val result = BinaryResolver.resolve(
            explicitPath = "",
            appDirs = listOf("/repo/apps/shop", "/repo"),
            isWindows = false,
            allowProjectLocal = true,
            pathLookup = noPath,
            fileExists = existsOnly("/repo/vendor/bin/laravel-fmt", "/repo/apps/admin/vendor/bin/laravel-fmt"),
        )

        assertEquals("/repo/vendor/bin/laravel-fmt", result)
    }

    @Test
    fun `project dirs reach two levels down but not three`(@TempDir repo: Path) {
        Files.createDirectories(repo.resolve("apps/web/src"))
        val root = repo.toString().replace('\\', '/')

        val dirs = BinaryResolver.projectDirs(listOf(root)).toList()

        assertEquals(listOf(root, "$root/apps", "$root/apps/web"), dirs)
    }

    @Test
    fun `project roots come before subdirectories, then subdirectories are sorted`() {
        val subdirs = mapOf("/repo" to listOf("zeta", "alpha"), "/other" to emptyList())

        val dirs = BinaryResolver.projectDirs(listOf("/repo", "/other")) { subdirs[it].orEmpty() }.toList()

        assertEquals(listOf("/repo", "/other", "/repo/alpha", "/repo/zeta"), dirs)
    }

    @Test
    fun `hidden and non-app subdirectories are not searched`() {
        val subdirs = mapOf("/repo" to listOf("vendor", ".git", "node_modules", "app"))

        val dirs = BinaryResolver.projectDirs(listOf("/repo")) { subdirs[it].orEmpty() }.toList()

        assertEquals(listOf("/repo", "/repo/app"), dirs)
    }

    @Test
    fun `a root listed twice is searched once`() {
        val dirs = BinaryResolver.projectDirs(listOf("C:\\repo\\", "C:/repo", "")) { emptyList() }.toList()

        assertEquals(listOf("C:/repo"), dirs)
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
            pathDirs = listOf("/usr/bin/", "/usr/local/bin"),
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
