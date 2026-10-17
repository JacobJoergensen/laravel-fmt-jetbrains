package io.github.jacobjoergensen.laravelfmt

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
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Runs in a PhpStorm test instance, for the wiring unit tests can't reach: plugin.xml, services, actions. */
class LaravelFmtPluginTest : BasePlatformTestCase() {

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

    fun `test server is not started when laravel-fmt is disabled for the project`() {
        val settings = project.service<LaravelFmtProjectSettings>()
        settings.enabled = false

        try {
            assertFalse(startsServerFor(file("Example.php", "<?php echo 1;\n")))
        } finally {
            settings.enabled = true
        }
    }

    fun `test laravel-fmt is enabled for a project by default`() {
        assertTrue(project.service<LaravelFmtServerService>().isEnabled)
    }

    fun `test restart action is hidden when laravel-fmt is disabled for the project`() {
        val action = LaravelFmtRestartAction()
        val settings = project.service<LaravelFmtProjectSettings>()

        assertTrue(updatedPresentationVisible(action))

        settings.enabled = false

        try {
            assertFalse(updatedPresentationVisible(action))
        } finally {
            settings.enabled = true
        }
    }

    fun `test settings page shows its name`() {
        val settings = Configurable.PROJECT_CONFIGURABLE.getExtensions(project)
            .single { it.id == "io.github.jacobjoergensen.laravelfmt.settings" }

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
