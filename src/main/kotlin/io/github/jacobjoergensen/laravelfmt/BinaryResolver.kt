package io.github.jacobjoergensen.laravelfmt

import java.io.File

/**
 * Finds the `laravel-fmt` executable, in this order:
 *  1. the explicit path from the settings, if it exists
 *  2. `vendor/bin/laravel-fmt` in a project root or one of its subfolders, only for trusted projects,
 *     since that binary ships with the project
 *  3. `PATH`
 */
object BinaryResolver {

    const val BASE_NAME = "laravel-fmt"

    private const val MAX_NESTING = 2

    private val SKIPPED_SUBDIRECTORIES = setOf("vendor", "node_modules", "storage", "bootstrap", "public")

    /** A drive path (`C:\`) or a UNC path (`\\server\share`). */
    private val WINDOWS_ABSOLUTE = Regex("""^([A-Za-z]:[\\/]|\\\\)""")

    private fun vendorBinCandidates(isWindows: Boolean): List<String> {
        if (isWindows) {
            return listOf("$BASE_NAME.bat", "$BASE_NAME.cmd", "$BASE_NAME.exe", BASE_NAME)
        }

        return listOf(BASE_NAME)
    }

    /**
     * Also searches up to [MAX_NESTING] levels below the roots, level by level, so a Laravel app in a
     * monorepo's `apps/web/` is found.
     */
    fun findProjectLocal(
        roots: List<String>,
        isWindows: Boolean,
        fileExists: (String) -> Boolean = ::isFile,
        listSubdirectories: (String) -> List<String> = ::subdirectoryNames,
    ): String? {
        var level = roots.filter { it.isNotBlank() }.map { it.replace('\\', '/').trimEnd('/') }.distinct()

        for (depth in 0..MAX_NESTING) {
            if (depth > 0) {
                level = level.flatMap { dir ->
                    listSubdirectories(dir)
                        .filter { !it.startsWith(".") && it !in SKIPPED_SUBDIRECTORIES }
                        .sorted()
                        .map { "$dir/$it" }
                }
            }

            val found = level.firstNotNullOfOrNull { dir ->
                vendorBinCandidates(isWindows).map { "$dir/vendor/bin/$it" }.firstOrNull(fileExists)
            }

            if (found != null) {
                return found
            }
        }

        return null
    }

    /**
     * Skips relative entries such as `.`: they resolve against the IDE's working directory, which may be
     * an untrusted project.
     */
    fun findOnPath(
        name: String,
        pathDirs: List<String>,
        isWindows: Boolean,
        windowsExtensions: List<String> = listOf(".exe", ".bat", ".cmd"),
        isExecutableFile: (String) -> Boolean = { File(it).let { f -> f.isFile && f.canExecute() } },
    ): String? {
        val names = if (isWindows && !name.contains('.')) {
            windowsExtensions.map { name + it.lowercase() }
        } else {
            listOf(name)
        }

        val separator = if (isWindows) {
            '\\'
        } else {
            '/'
        }

        return pathDirs.asSequence()
            .map { it.replace("\"", "").trim() }
            .filter { isAbsolute(it, isWindows) }
            .map { it.trimEnd('/', '\\') }
            .flatMap { dir -> names.asSequence().map { "$dir$separator$it" } }
            .firstOrNull(isExecutableFile)
    }

    fun isExplicitPathMissing(
        explicitPath: String?,
        fileExists: (String) -> Boolean = ::isFile,
    ): Boolean = explicitPath?.trim()?.let { it.isNotEmpty() && !fileExists(it) } ?: false

    fun resolve(
        explicitPath: String?,
        projectRoots: List<String>,
        isWindows: Boolean,
        allowProjectLocal: Boolean,
        pathLookup: (String) -> String?,
        fileExists: (String) -> Boolean = ::isFile,
        listSubdirectories: (String) -> List<String> = ::subdirectoryNames,
    ): String? {
        // A stale explicit path falls through to auto-detection instead of breaking a working project.
        explicitPath?.trim()
            ?.takeIf { it.isNotEmpty() && fileExists(it) }
            ?.let { return it }

        if (allowProjectLocal) {
            findProjectLocal(projectRoots, isWindows, fileExists, listSubdirectories)?.let { return it }
        }

        return pathLookup(BASE_NAME)
    }

    private fun isAbsolute(dir: String, isWindows: Boolean): Boolean {
        if (isWindows) {
            return WINDOWS_ABSOLUTE.containsMatchIn(dir)
        }

        return dir.startsWith('/')
    }

    private fun isFile(path: String): Boolean = File(path).isFile

    private fun subdirectoryNames(dir: String): List<String> =
        File(dir).listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
}
