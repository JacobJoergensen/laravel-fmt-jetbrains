package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaravelFmtListenersTest {

    @Test
    fun `vendor listener reacts to proxies and the package dir only`() {
        assertTrue(LaravelFmtVendorListener.isLaravelFmtInstallChange("/p/vendor/bin/laravel-fmt"))
        assertTrue(LaravelFmtVendorListener.isLaravelFmtInstallChange("C:\\p\\vendor\\bin\\laravel-fmt.bat"))
        assertTrue(LaravelFmtVendorListener.isLaravelFmtInstallChange("/p/vendor/jacobjoergensen/laravel-fmt"))

        assertFalse(LaravelFmtVendorListener.isLaravelFmtInstallChange("/p/vendor/jacobjoergensen/laravel-fmt/bin/laravel-fmt-Linux-x86_64"))
        assertFalse(LaravelFmtVendorListener.isLaravelFmtInstallChange("/p/vendor/bin/pint"))
        assertFalse(LaravelFmtVendorListener.isLaravelFmtInstallChange("/p/src/laravel-fmt.php"))
    }

    @Test
    fun `changes in another project are ignored`() {
        val roots = listOf("/repo", "C:\\work\\app")

        assertTrue(LaravelFmtVendorListener.isInProject("/repo/apps/web/vendor/bin/laravel-fmt", roots))
        assertTrue(LaravelFmtVendorListener.isInProject("C:/work/app/vendor/bin/laravel-fmt.bat", roots))

        assertFalse(LaravelFmtVendorListener.isInProject("/other/vendor/bin/laravel-fmt", roots))
        assertFalse(LaravelFmtVendorListener.isInProject("/repo-other/vendor/bin/laravel-fmt", roots))
    }
}
