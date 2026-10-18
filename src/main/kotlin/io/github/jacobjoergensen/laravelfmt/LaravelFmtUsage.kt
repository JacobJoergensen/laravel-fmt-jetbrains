package io.github.jacobjoergensen.laravelfmt

import java.io.File

/** Whether a project uses laravel-fmt, which is what switches the plugin on in automatic mode. */
object LaravelFmtUsage {

    const val CONFIG_FILE = "laravel-fmt.json"

    const val COMPOSER_FILE = "composer.json"

    /**
     * The package as a JSON key, so `require` and `require-dev` match without parsing the file. A package
     * named in another section, like `suggest`, matches too, which only turns the plugin on.
     */
    private val COMPOSER_REQUIREMENT = Regex("\"${Regex.escape(ComposerInstall.PACKAGE)}\"\\s*:")

    fun isUsedIn(
        roots: List<String>,
        isWindows: Boolean,
        fileExists: (String) -> Boolean = BinaryResolver::isFile,
        readFile: (String) -> String? = { runCatching { File(it).readText() }.getOrNull() },
        listSubdirectories: (String) -> List<String> = BinaryResolver::subdirectoryNames,
    ): Boolean = BinaryResolver.projectDirs(roots, listSubdirectories).any { dir ->
        fileExists("$dir/$CONFIG_FILE") ||
            BinaryResolver.vendorBinIn(dir, isWindows, fileExists) != null ||
            readFile("$dir/$COMPOSER_FILE")?.let(COMPOSER_REQUIREMENT::containsMatchIn) == true
    }
}
