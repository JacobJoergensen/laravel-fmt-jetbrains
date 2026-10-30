package io.github.jacobjoergensen.laravelfmt

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.formatting.service.FormattingService
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType
import io.github.jacobjoergensen.laravelfmt.LaravelFmtProjectSettings.Mode

class LaravelFmtPluginTest : BasePlatformTestCase() {

    private val projectSettings get() = project.service<LaravelFmtProjectSettings>()

    /** The test project has no `composer.json`, so automatic mode would keep laravel-fmt off. */
    override fun setUp() {
        super.setUp()
        projectSettings.mode = Mode.ALWAYS
    }

    override fun tearDown() {
        try {
            projectSettings.loadState(LaravelFmtProjectSettings.State())
            FormatOnSaveOptions.getInstance(project).isRunOnSaveEnabled = false
        } finally {
            super.tearDown()
        }
    }

    fun `test laravel-fmt is available as a language server`() {
        val providers = LspIntegrationProvider.EP_NAME.extensionList

        assertTrue(providers.any { it is LaravelFmtLspIntegrationProvider })
    }

    fun `test PHP and Blade files are supported with their language ids`() {
        val descriptor = LaravelFmtLspClientDescriptor(project)
        val php = file("Example.php", "<?php echo 1;\n")
        val blade = file("welcome.blade.php", $$"<div>{{ $name }}</div>\n")

        assertTrue(descriptor.isSupportedFile(php))
        assertTrue(descriptor.isSupportedFile(blade))
        assertFalse(descriptor.isSupportedFile(file("notes.txt", "hello\n")))
        assertEquals("php", descriptor.getLanguageId(php))
        assertEquals("blade", descriptor.getLanguageId(blade))
    }

    fun `test typing in the editor never waits for a new binary lookup`() {
        val server = project.service<LaravelFmtServerService>()

        server.refresh()
        val apps = server.apps()
        val resolution = server.resolve(app = null)

        server.invalidate()

        assertSame(apps, server.apps())
        assertSame(resolution, server.resolve(app = null))
    }

    fun `test the project-wide server and a root app's server are told apart`() {
        val root = project.basePath!!.replace('\\', '/').trimEnd('/')

        val outside = LaravelFmtLspClientDescriptor(project, app = null)
        val rootApp = LaravelFmtLspClientDescriptor(project, app = root)

        assertEquals("laravel-fmt", rootApp.presentableName)
        assertFalse(outside.presentableName == rootApp.presentableName)
    }

    fun `test an app's server only gets the files of that app`() {
        val php = file("Example.php", "<?php echo 1;\n")

        assertFalse(LaravelFmtLspClientDescriptor(project, app = "/elsewhere/apps/shop").isSupportedFile(php))
    }

    fun `test laravel-fmt formats its files ahead of PhpStorm's own formatter`() {
        val services = FormattingService.EP_NAME.extensionList

        val laravelFmt = services.indexOfFirst { it is LaravelFmtFormattingService }
        val builtIn = services.indexOfFirst { it.javaClass.simpleName == "CoreFormattingService" }

        assertTrue("laravel-fmt at $laravelFmt, built-in at $builtIn", laravelFmt in 0 until builtIn)
    }

    fun `test PhpStorm's LSP formatter doesn't format laravel-fmt's files a second time`() {
        val formatting = LaravelFmtLspClientDescriptor(project).lspCustomization.formattingCustomizer as LspFormattingSupport
        val php = file("Example.php", "<?php echo 1;\n")

        assertFalse(formatting.shouldFormatThisFileExclusivelyByServer(php, ideCanFormatThisFileItself = true, serverExplicitlyWantsToFormatThisFile = true))
    }

    fun `test a project that doesn't use laravel-fmt keeps the IDE formatter by default`() {
        projectSettings.loadState(LaravelFmtProjectSettings.State())

        assertFalse(LaravelFmtLspClientDescriptor(project).isSupportedFile(file("Example.php", "<?php echo 1;\n")))
    }

    fun `test a project switched off with the old checkbox stays off`() {
        projectSettings.loadState(LaravelFmtProjectSettings.State().apply { enabled = false })

        assertEquals(Mode.OFF, projectSettings.mode)
    }

    fun `test server is not started when laravel-fmt is disabled for the project`() {
        projectSettings.mode = Mode.OFF

        assertFalse(startsServerFor(file("Example.php", "<?php echo 1;\n")))
    }

    fun `test unchecking PHP leaves PHP files to the IDE but still formats Blade files`() {
        projectSettings.formatPhp = false
        val descriptor = LaravelFmtLspClientDescriptor(project)

        assertFalse(descriptor.isSupportedFile(file("Example.php", "<?php echo 1;\n")))
        assertTrue(descriptor.isSupportedFile(file("welcome.blade.php", "<div></div>\n")))
    }

    fun `test unchecking Blade leaves Blade files to the IDE even though they are PHP files`() {
        projectSettings.formatBlade = false
        val descriptor = LaravelFmtLspClientDescriptor(project)

        assertFalse(descriptor.isSupportedFile(file("welcome.blade.php", "<div></div>\n")))
        assertTrue(descriptor.isSupportedFile(file("Example.php", "<?php echo 1;\n")))
    }

    fun `test restart action is hidden when laravel-fmt is disabled for the project`() {
        val action = LaravelFmtRestartAction()

        assertTrue(updatedPresentationVisible(action))

        projectSettings.mode = Mode.OFF

        assertFalse(updatedPresentationVisible(action))
    }

    fun `test restart action is hidden when no file type is checked`() {
        projectSettings.formatPhp = false
        projectSettings.formatBlade = false

        assertFalse(updatedPresentationVisible(LaravelFmtRestartAction()))
    }

    fun `test Run laravel-fmt is listed on the Actions on Save page`() {
        val providers = ActionOnSaveInfoProvider.EP_NAME.extensionList

        assertTrue(providers.any { it is LaravelFmtOnSaveInfoProvider })
    }

    fun `test formatting on save needs both laravel-fmt and Run on save turned on`() {
        val action = LaravelFmtOnSaveAction()

        assertFalse(action.isEnabledForProject(project))

        projectSettings.formatOnSave = true

        assertTrue(action.isEnabledForProject(project))

        projectSettings.mode = Mode.OFF

        assertFalse(action.isEnabledForProject(project))
    }

    fun `test saving a file outside the project does not format it`() {
        val outside = LightVirtualFile("Outside.php", PhpFileType.INSTANCE, "<?php echo 1;\n")

        assertTrue(formatsOnSave(project, file("Example.php", "<?php echo 1;\n")))
        assertFalse(formatsOnSave(project, outside))
    }

    fun `test files the IDE's Reformat code save action covers are not formatted twice on save`() {
        val php = file("Example.php", "<?php echo 1;\n")

        FormatOnSaveOptions.getInstance(project).isRunOnSaveEnabled = true

        assertFalse(formatsOnSave(project, php))
    }

    fun `test settings page shows its name`() {
        val settings = Configurable.PROJECT_CONFIGURABLE.getExtensions(project)
            .single { it.id == LaravelFmtConfigurable.ID }

        assertEquals("laravel-fmt", settings.getDisplayName())
    }

    fun `test laravel-fmt messages appear as balloons`() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(LaravelFmtNotifications.GROUP_ID)

        assertNotNull("notification group", group)
        assertEquals(NotificationDisplayType.BALLOON, group.displayType)
    }

    private fun file(name: String, text: String): VirtualFile = myFixture.configureByText(name, text).virtualFile

    private fun startsServerFor(file: VirtualFile): Boolean {
        var started = false

        val starter = object : LspIntegrationProvider.LspClientStarter {
            override fun ensureClientStarted(descriptor: LspClientDescriptor) {
                started = true
            }
        }

        LaravelFmtLspIntegrationProvider().fileOpened(project, file, starter)

        return started
    }

    private fun updatedPresentationVisible(action: LaravelFmtRestartAction): Boolean {
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)

        return event.presentation.isEnabledAndVisible
    }
}
