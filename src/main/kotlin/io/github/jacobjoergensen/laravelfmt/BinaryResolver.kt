package io.github.jacobjoergensen.laravelfmt

import java.io.File

object BinaryResolver {

    const val BASE_NAME = "laravel-fmt"

    private const val MAX_NESTING = 2

    private val SKIPPED_SUBDIRECTORIES = setOf("vendor", "node_modules", "storage", "bootstrap", "public")

    private val WINDOWS_ABSOLUTE = Regex("""^([A-Za-z]:[\\/]|\\\\)""")

    private fun vendorBinCandidates(isWindows: Boolean): List<String> {
        if (isWindows) {
            return listOf("$BASE_NAME.bat", "$BASE_NAME.cmd", "$BASE_NAME.exe", BASE_NAME)
        }

        return listOf(BASE_NAME)
    }

    fun vendorBinIn(dir: String, isWindows: Boolean, fileExists: (String) -> Boolean = ::isFile): String? =
        vendorBinCandidates(isWindows).map { "$dir/vendor/bin/$it" }.firstOrNull(fileExists)

    /** The roots, then up to [MAX_NESTING] levels below them, level by level. Callers rely on this order. */
    fun projectDirs(
        roots: List<String>,
        listSubdirectories: (String) -> List<String> = ::subdirectoryNames,
    ): Sequence<String> = sequence {
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

            yieldAll(level)
        }
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
        appDirs: List<String>,
        isWindows: Boolean,
        allowProjectLocal: Boolean,
        pathLookup: (String) -> String?,
        fileExists: (String) -> Boolean = ::isFile,
    ): String? {
        // A stale explicit path falls through to auto-detection instead of breaking a working project.
        explicitPath?.trim()
            ?.takeIf { it.isNotEmpty() && fileExists(it) }
            ?.let { return it }

        if (allowProjectLocal) {
            appDirs.firstNotNullOfOrNull { vendorBinIn(it, isWindows, fileExists) }?.let { return it }
        }

        return pathLookup(BASE_NAME)
    }

    private fun isAbsolute(dir: String, isWindows: Boolean): Boolean {
        if (isWindows) {
            return WINDOWS_ABSOLUTE.containsMatchIn(dir)
        }

        return dir.startsWith('/')
    }

    internal fun isFile(path: String): Boolean = File(path).isFile

    internal fun subdirectoryNames(dir: String): List<String> =
        File(dir).listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
}
