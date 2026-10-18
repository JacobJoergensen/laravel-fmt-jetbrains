package io.github.jacobjoergensen.laravelfmt

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType
import io.github.jacobjoergensen.laravelfmt.LaravelFmtProjectSettings.Mode

/** Runs in a PhpStorm test instance, for the wiring unit tests can't reach: plugin.xml, services, actions. */
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
        val providers = ExtensionPointName<LspServerSupportProvider>("com.intellij.platform.lsp.serverSupportProvider").extensionList

        assertTrue(providers.any { it is LaravelFmtLspServerSupportProvider })
    }

    fun `test PHP and Blade files are supported with their language ids`() {
        val descriptor = LaravelFmtLspServerDescriptor(project)
        val php = file("Example.php", "<?php echo 1;\n")
        val blade = file("welcome.blade.php", "<div>{{ \$name }}</div>\n")

        assertTrue(descriptor.isSupportedFile(php))
        assertTrue(descriptor.isSupportedFile(blade))
        assertFalse(descriptor.isSupportedFile(file("notes.txt", "hello\n")))
        assertEquals("php", descriptor.getLanguageId(php))
        assertEquals("blade", descriptor.getLanguageId(blade))
    }

    fun `test the server takes over formatting from the built-in PHP formatter`() {
        val formatting = LaravelFmtLspServerDescriptor(project).lspCustomization.formattingCustomizer as LspFormattingSupport
        val php = file("Example.php", "<?php echo 1;\n")

        assertTrue(formatting.shouldFormatThisFileExclusivelyByServer(php, true, false))
    }

    fun `test a project that doesn't use laravel-fmt keeps the IDE formatter by default`() {
        projectSettings.loadState(LaravelFmtProjectSettings.State())

        assertFalse(LaravelFmtLspServerDescriptor(project).isSupportedFile(file("Example.php", "<?php echo 1;\n")))
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
        val descriptor = LaravelFmtLspServerDescriptor(project)

        assertFalse(descriptor.isSupportedFile(file("Example.php", "<?php echo 1;\n")))
        assertTrue(descriptor.isSupportedFile(file("welcome.blade.php", "<div></div>\n")))
    }

    fun `test unchecking Blade leaves Blade files to the IDE even though they are PHP files`() {
        projectSettings.formatBlade = false
        val descriptor = LaravelFmtLspServerDescriptor(project)

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
        val providers = ExtensionPointName<ActionOnSaveInfoProvider>("com.intellij.actionOnSaveInfoProvider").extensionList

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

        assertTrue(LaravelFmtOnSaveAction.formatsOnSave(project, file("Example.php", "<?php echo 1;\n")))
        assertFalse(LaravelFmtOnSaveAction.formatsOnSave(project, outside))
    }

    fun `test files the IDE's Reformat code save action covers are not formatted twice on save`() {
        val php = file("Example.php", "<?php echo 1;\n")

        FormatOnSaveOptions.getInstance(project).isRunOnSaveEnabled = true

        assertFalse(LaravelFmtOnSaveAction.formatsOnSave(project, php))
    }

    fun `test settings page shows its name`() {
        val settings = Configurable.PROJECT_CONFIGURABLE.getExtensions(project)
            .single { it.id == LaravelFmtConfigurable.ID }

        assertEquals("laravel-fmt", settings.getDisplayName())
    }

    fun `test notification group is registered as balloons`() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(LaravelFmtNotifications.GROUP_ID)

        assertNotNull("notification group", group)
        assertEquals(NotificationDisplayType.BALLOON, group.displayType)
    }

    fun `test restart action shows its menu text`() {
        val action = ActionManager.getInstance().getAction("LaravelFmt.RestartServer")

        assertNotNull(action)
        assertEquals("Restart laravel-fmt Server", action!!.templatePresentation.text)
    }

    private fun file(name: String, text: String): VirtualFile = myFixture.configureByText(name, text).virtualFile

    private fun startsServerFor(file: VirtualFile): Boolean {
        var started = false

        val starter = object : LspServerSupportProvider.LspServerStarter {
            override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                started = true
            }
        }

        LaravelFmtLspServerSupportProvider().fileOpened(project, file, starter)

        return started
    }

    private fun updatedPresentationVisible(action: LaravelFmtRestartAction): Boolean {
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)

        return event.presentation.isEnabledAndVisible
    }
}
