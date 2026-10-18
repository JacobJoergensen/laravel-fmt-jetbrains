package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaravelFmtUsageTest {

    private fun usedIn(
        files: Map<String, String> = emptyMap(),
        subdirectories: Map<String, List<String>> = emptyMap(),
    ): Boolean = LaravelFmtUsage.isUsedIn(
        roots = listOf("/app"),
        isWindows = false,
        fileExists = { it in files },
        readFile = { files[it] },
        listSubdirectories = { subdirectories[it].orEmpty() },
    )

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
    fun `a monorepo app below the project root is found`() {
        val composer = """{ "require": { "jacobjoergensen/laravel-fmt": "*" } }"""

        assertTrue(usedIn(mapOf("/app/apps/web/composer.json" to composer), mapOf("/app" to listOf("apps"), "/app/apps" to listOf("web"))))
    }
}
