package io.github.jacobjoergensen.laravelfmt

import java.io.File

/**
 * Which folders of a project use laravel-fmt. These are its apps: each file is formatted by the app it
 * belongs to, and automatic mode leaves files outside every app to the IDE.
 */
object LaravelFmtUsage {

    const val CONFIG_FILE = "laravel-fmt.json"

    const val COMPOSER_FILE = "composer.json"

    /**
     * The package as a JSON key, so `require` and `require-dev` match without parsing the file. A package
     * named in another section, like `suggest`, matches too, which only turns the plugin on.
     */
    private val COMPOSER_REQUIREMENT = Regex("\"${Regex.escape(ComposerInstall.PACKAGE)}\"\\s*:")

    /** In [BinaryResolver.projectDirs] order, so the roots come first. */
    fun appDirs(
        roots: List<String>,
        isWindows: Boolean,
        fileExists: (String) -> Boolean = BinaryResolver::isFile,
        readFile: (String) -> String? = { runCatching { File(it).readText() }.getOrNull() },
        listSubdirectories: (String) -> List<String> = BinaryResolver::subdirectoryNames,
    ): List<String> = BinaryResolver.projectDirs(roots, listSubdirectories).filter { dir ->
        fileExists("$dir/$CONFIG_FILE") ||
            BinaryResolver.vendorBinIn(dir, isWindows, fileExists) != null ||
            readFile("$dir/$COMPOSER_FILE")?.let(COMPOSER_REQUIREMENT::containsMatchIn) == true
    }.distinct().toList()

    /** The app [path] belongs to: the innermost one, so an app nested in another formats its own files. */
    fun appFor(path: String, apps: List<String>, ignoreCase: Boolean = false): String? =
        enclosingApps(path, apps, ignoreCase).firstOrNull()

    /**
     * Innermost first. [ignoreCase] is for file systems that ignore it, where a project's folder and its
     * files can be spelled differently, like a drive letter.
     */
    fun enclosingApps(path: String, apps: List<String>, ignoreCase: Boolean = false): List<String> {
        val normalized = path.replace('\\', '/')

        return apps
            .filter { normalized.equals(it, ignoreCase) || normalized.startsWith("$it/", ignoreCase) }
            .sortedByDescending { it.length }
    }
}
