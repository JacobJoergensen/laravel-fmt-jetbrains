package io.github.jacobjoergensen.laravelfmt

import java.io.File

object ComposerInstall {

    const val PACKAGE = "jacobjoergensen/laravel-fmt"

    /** Mirrors `BinaryDownloader::cachedPath()`. */
    private fun nativeName(os: Os, arch: Arch?): String? {
        arch ?: return null

        val suffix = if (os == Os.WINDOWS) {
            ".exe"
        } else {
            ""
        }

        return "${BinaryResolver.BASE_NAME}-${os.composerName}-${arch.composerName}$suffix"
    }

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

        val vendorDir = binDir.substringBeforeLast('/', missingDelimiterValue = "")
        val packageDir = "$vendorDir/$PACKAGE"

        if (binDir.endsWith("/bin") && vendorDir.isNotEmpty() && dirExists(packageDir)) {
            return "$packageDir/bin/$name"
        }

        if (binDir.endsWith("/$PACKAGE/bin")) {
            return "$binDir/$name"
        }

        return null
    }

    fun installedVersionsFile(nativeBinary: String): String =
        nativeBinary.replace('\\', '/').substringBefore("/$PACKAGE/") + "/composer/installed.php"

    /**
     * Read where the wrapper's `InstalledVersions::getPrettyVersion()` reads it. The project's own file names
     * it, and it ends up in a download URL and a cache folder name, so only a release number is accepted.
     */
    fun installedVersion(installedPhp: String): String? =
        INSTALLED_VERSION.find(installedPhp)?.groupValues?.get(1)?.takeIf { RELEASE.matches(it) && "dev" !in it }

    private val INSTALLED_VERSION = Regex("""'${Regex.escape(PACKAGE)}'\s*=>\s*array\(\s*'pretty_version'\s*=>\s*'([^']+)'""")

    private val RELEASE = Regex("""v?\d+(\.\d+)*([-+][0-9A-Za-z.-]+)?""")

    enum class Os(val composerName: String) {
        WINDOWS("Windows"), MAC("Darwin"), LINUX("Linux")
    }

    enum class Arch(val composerName: String) {
        X86_64("x86_64"), ARM64("arm64")
    }
}
