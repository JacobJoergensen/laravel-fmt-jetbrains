package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaravelFmtListenersTest {

    @Test
    fun `setup listener reacts to the install and the project's own config files only`() {
        assertTrue(LaravelFmtSetupListener.affectsLaravelFmt("/p/vendor/bin/laravel-fmt"))
        assertTrue(LaravelFmtSetupListener.affectsLaravelFmt("C:\\p\\vendor\\bin\\laravel-fmt.bat"))
        assertTrue(LaravelFmtSetupListener.affectsLaravelFmt("/p/vendor/jacobjoergensen/laravel-fmt"))
        assertTrue(LaravelFmtSetupListener.affectsLaravelFmt("/p/composer.json"))
        assertTrue(LaravelFmtSetupListener.affectsLaravelFmt("C:\\p\\apps\\web\\laravel-fmt.json"))

        assertFalse(LaravelFmtSetupListener.affectsLaravelFmt("/p/vendor/jacobjoergensen/laravel-fmt/bin/laravel-fmt-Linux-x86_64"))
        assertFalse(LaravelFmtSetupListener.affectsLaravelFmt("/p/vendor/bin/pint"))
        assertFalse(LaravelFmtSetupListener.affectsLaravelFmt("/p/src/laravel-fmt.php"))
        assertFalse(LaravelFmtSetupListener.affectsLaravelFmt("/p/vendor/laravel/framework/composer.json"))
        assertFalse(LaravelFmtSetupListener.affectsLaravelFmt("/p/node_modules/x/composer.json"))
    }

    @Test
    fun `changes in another project are ignored`() {
        val roots = listOf("/repo", "C:\\work\\app")

        assertTrue(LaravelFmtSetupListener.isInProject("/repo/apps/web/vendor/bin/laravel-fmt", roots))
        assertTrue(LaravelFmtSetupListener.isInProject("C:/work/app/vendor/bin/laravel-fmt.bat", roots))

        assertFalse(LaravelFmtSetupListener.isInProject("/other/vendor/bin/laravel-fmt", roots))
        assertFalse(LaravelFmtSetupListener.isInProject("/repo-other/vendor/bin/laravel-fmt", roots))
    }
}
