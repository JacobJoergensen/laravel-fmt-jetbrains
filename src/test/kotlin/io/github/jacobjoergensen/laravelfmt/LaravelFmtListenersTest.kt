package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LaravelFmtListenersTest {

    @Test
    fun `setup listener reacts to the install and the project's own config files only`() {
        assertTrue(affectsLaravelFmt("/p/vendor/bin/laravel-fmt"))
        assertTrue(affectsLaravelFmt("C:\\p\\vendor\\bin\\laravel-fmt.bat"))
        assertTrue(affectsLaravelFmt("/p/vendor/jacobjoergensen/laravel-fmt"))
        assertTrue(affectsLaravelFmt("/p/composer.json"))
        assertTrue(affectsLaravelFmt("C:\\p\\apps\\web\\laravel-fmt.json"))

        assertFalse(affectsLaravelFmt("/p/vendor/jacobjoergensen/laravel-fmt/bin/laravel-fmt-Linux-x86_64"))
        assertFalse(affectsLaravelFmt("/p/vendor/bin/pint"))
        assertFalse(affectsLaravelFmt("/p/src/laravel-fmt.php"))
        assertFalse(affectsLaravelFmt("/p/vendor/laravel/framework/composer.json"))
        assertFalse(affectsLaravelFmt("/p/node_modules/x/composer.json"))
    }

    @Test
    fun `changes in another project are ignored`() {
        val roots = listOf("/repo", "C:\\work\\app")

        assertTrue(isInProject("/repo/apps/web/vendor/bin/laravel-fmt", roots))
        assertTrue(isInProject("C:/work/app/vendor/bin/laravel-fmt.bat", roots))

        assertFalse(isInProject("/other/vendor/bin/laravel-fmt", roots))
        assertFalse(isInProject("/repo-other/vendor/bin/laravel-fmt", roots))
    }
}
