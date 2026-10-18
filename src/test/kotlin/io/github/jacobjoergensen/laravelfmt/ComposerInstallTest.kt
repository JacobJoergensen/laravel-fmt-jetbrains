package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposerInstallTest {

    @Test
    fun `native binary is found next to the vendor bin wrapper`() {
        val result = ComposerInstall.nativeBinary(
            resolvedPath = "C:\\project\\vendor\\bin\\laravel-fmt.bat",
            os = ComposerInstall.Os.WINDOWS,
            arch = ComposerInstall.Arch.X86_64,
            dirExists = { it == "C:/project/vendor/jacobjoergensen/laravel-fmt" },
        )

        assertEquals("C:/project/vendor/jacobjoergensen/laravel-fmt/bin/laravel-fmt-Windows-x86_64.exe", result)
    }

    @Test
    fun `native binary is found when pointed at the package bin directly`() {
        val result = ComposerInstall.nativeBinary(
            resolvedPath = "/p/vendor/jacobjoergensen/laravel-fmt/bin/laravel-fmt",
            os = ComposerInstall.Os.MAC,
            arch = ComposerInstall.Arch.ARM64,
            dirExists = { false },
        )

        assertEquals("/p/vendor/jacobjoergensen/laravel-fmt/bin/laravel-fmt-Darwin-arm64", result)
    }

    @Test
    fun `a plain native binary is not treated as composer`() {
        val result = ComposerInstall.nativeBinary(
            resolvedPath = "/usr/local/bin/laravel-fmt",
            os = ComposerInstall.Os.LINUX,
            arch = ComposerInstall.Arch.X86_64,
            dirExists = { false },
        )

        assertNull(result)
    }

    @Test
    fun `the version list is found in the vendor folder, also on a WSL share`() {
        val native = "//wsl$/Ubuntu/home/app/vendor/jacobjoergensen/laravel-fmt/bin/laravel-fmt-Windows-x86_64.exe"

        assertEquals("//wsl$/Ubuntu/home/app/vendor/composer/installed.php", ComposerInstall.installedVersionsFile(native))
    }

    @Test
    fun `the installed version is read from Composer's version list`() {
        val installedPhp = """
            <?php return array(
                'versions' => array(
                    'jacobjoergensen/laravel-fmt' => array(
                        'pretty_version' => '0.3.0',
                        'version' => '0.3.0.0',
                        'aliases' => array(),
                    ),
                    'laravel/pint' => array(
                        'pretty_version' => 'v1.25.1',
                    ),
                ),
            );
        """.trimIndent()

        assertEquals("0.3.0", ComposerInstall.installedVersion(installedPhp))
    }

    @Test
    fun `a dev install has no release to download`() {
        val installedPhp = "'jacobjoergensen/laravel-fmt' => array(\n    'pretty_version' => 'dev-main',"

        assertNull(ComposerInstall.installedVersion(installedPhp))
    }

    @Test
    fun `a version that isn't a release number is never used`() {
        val installedPhp = "'jacobjoergensen/laravel-fmt' => array(\n    'pretty_version' => '../../outside',"

        assertNull(ComposerInstall.installedVersion(installedPhp))
    }

    @Test
    fun `an unsupported CPU has no native binary`() {
        val result = ComposerInstall.nativeBinary(
            resolvedPath = "/p/vendor/bin/laravel-fmt",
            os = ComposerInstall.Os.LINUX,
            arch = null,
            dirExists = { true },
        )

        assertNull(result)
    }
}
