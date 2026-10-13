package io.github.jacobjoergensen.laravelfmt

import java.io.File

/** Where a Composer install of laravel-fmt keeps the native binary its `vendor/bin` wrapper downloads. */
object ComposerInstall {

    const val PACKAGE = "jacobjoergensen/laravel-fmt"

    /**
     * File name of the downloaded native binary (mirrors `BinaryDownloader::cachedPath()`), or `null` on an
     * unsupported platform.
     */
    private fun nativeName(os: Os, arch: Arch?): String? {
        arch ?: return null

        val suffix = if (os == Os.WINDOWS) {
            ".exe"
        } else {
            ""
        }

        return "${BinaryResolver.BASE_NAME}-${os.composerName}-${arch.composerName}$suffix"
    }

    /** The native binary next to the wrapper at [resolvedPath], or `null` if it isn't a Composer install. */
    fun nativeBinary(
        resolvedPath: String,
        os: Os,
        arch: Arch?,
        dirExists: (String) -> Boolean = { File(it).isDirectory },
    ): String? {
        val name = nativeName(os, arch) ?: return null
        val binDir = resolvedPath.replace('\\', '/').substringBeforeLast('/', missingDelimiterValue = "")

        if (binDir.isEmpty()) {
            return null
        }

        // <vendor>/bin/laravel-fmt -> <vendor>/jacobjoergensen/laravel-fmt/bin/<native>
        val vendorDir = binDir.substringBeforeLast('/', missingDelimiterValue = "")
        val packageDir = "$vendorDir/$PACKAGE"

        if (binDir.endsWith("/bin") && vendorDir.isNotEmpty() && dirExists(packageDir)) {
            return "$packageDir/bin/$name"
        }

        // <vendor>/jacobjoergensen/laravel-fmt/bin/laravel-fmt -> <native> next to it
        if (binDir.endsWith("/$PACKAGE/bin")) {
            return "$binDir/$name"
        }

        return null
    }

    enum class Os(val composerName: String) {
        WINDOWS("Windows"), MAC("Darwin"), LINUX("Linux")
    }

    enum class Arch(val composerName: String) {
        X86_64("x86_64"), ARM64("arm64")
    }
}
