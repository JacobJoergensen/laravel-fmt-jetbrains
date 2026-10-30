package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaravelFmtUsageTest {

    private val requiresLaravelFmt = """{ "require": { "jacobjoergensen/laravel-fmt": "*" } }"""

    private fun appDirs(
        files: Map<String, String> = emptyMap(),
        subdirectories: Map<String, List<String>> = emptyMap(),
    ): List<String> = LaravelFmtUsage.appDirs(
        roots = listOf("/app"),
        isWindows = false,
        fileExists = { it in files },
        readFile = { files[it] },
        listSubdirectories = { subdirectories[it].orEmpty() },
    )

    private fun usedIn(files: Map<String, String>): Boolean = appDirs(files).isNotEmpty()

    @Test
    fun `a project requiring laravel-fmt in composer json uses it before it is installed`() {
        val composer = """{ "require-dev": { "jacobjoergensen/laravel-fmt": "^0.3" } }"""

        assertTrue(usedIn(mapOf("/app/composer.json" to composer)))
    }

    @Test
    fun `a project with only other packages in composer json does not use it`() {
        val composer = """{ "require-dev": { "laravel/pint": "^1.0" } }"""

        assertFalse(usedIn(mapOf("/app/composer.json" to composer)))
    }

    @Test
    fun `a laravel-fmt json config alone is enough`() {
        assertTrue(usedIn(mapOf("/app/laravel-fmt.json" to "{}")))
    }

    @Test
    fun `an installed vendor bin binary is enough`() {
        assertTrue(usedIn(mapOf("/app/vendor/bin/laravel-fmt" to "")))
    }

    @Test
    fun `every monorepo app using laravel-fmt is found, but not a sister app that doesn't`() {
        val apps = appDirs(
            mapOf(
                "/app/apps/admin/vendor/bin/laravel-fmt" to "",
                "/app/apps/shop/composer.json" to requiresLaravelFmt,
                "/app/apps/blog/composer.json" to """{ "require": { "laravel/framework": "^12" } }""",
            ),
            mapOf("/app" to listOf("apps"), "/app/apps" to listOf("shop", "blog", "admin")),
        )

        assertEquals(listOf("/app/apps/admin", "/app/apps/shop"), apps)
    }

    @Test
    fun `a file belongs to the innermost app around it`() {
        val apps = listOf("/repo", "/repo/apps/shop")

        assertEquals("/repo/apps/shop", LaravelFmtUsage.appFor("/repo/apps/shop/app/Models/User.php", apps))
        assertEquals("/repo", LaravelFmtUsage.appFor("/repo/apps/blog/routes/web.php", apps))
    }

    @Test
    fun `a file outside every app belongs to none`() {
        val apps = listOf("/repo/apps/admin", "/repo/apps/shop")

        assertNull(LaravelFmtUsage.appFor("/repo/apps/blog/routes/web.php", apps))
        assertNull(LaravelFmtUsage.appFor("/repo/apps/shop-legacy/index.php", apps))
    }

    @Test
    fun `a file spelled in another case belongs to its app where case is ignored`() {
        val apps = listOf("C:/repo/apps/shop")

        assertEquals("C:/repo/apps/shop", LaravelFmtUsage.appFor("c:/Repo/apps/shop/index.php", apps, ignoreCase = true))
        assertNull(LaravelFmtUsage.appFor("c:/Repo/apps/shop/index.php", apps))
    }

    @Test
    fun `an app's own install comes before the one of the app around it`() {
        val apps = listOf("/repo", "/repo/apps/admin", "/repo/apps/shop")

        assertEquals(listOf("/repo/apps/shop", "/repo"), LaravelFmtUsage.enclosingApps("/repo/apps/shop", apps))
    }
}
