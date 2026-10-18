package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import java.io.File

/** No formatting options on purpose: those come from the project config the server reads. */
class LaravelFmtConfigurable(private val project: Project) : BoundConfigurable(LaravelFmtBundle.message("settings.display.name")) {

    private val projectSettings get() = project.service<LaravelFmtProjectSettings>()
    private val appSettings get() = service<LaravelFmtSettings>()

    override fun createPanel(): DialogPanel {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle(LaravelFmtBundle.message("settings.binary.chooser.title"))
            .withDescription(LaravelFmtBundle.message("settings.binary.chooser.description"))

        val pathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))
        }

        return panel {
            group(LaravelFmtBundle.message("settings.group.project")) {
                lateinit var enabled: Cell<JBCheckBox>

                row {
                    enabled = checkBox(LaravelFmtBundle.message("settings.enabled"))
                        .bindSelected(projectSettings::enabled)
                }

                indent {
                    row {
                        checkBox(LaravelFmtBundle.message("settings.format.php"))
                            .bindSelected(projectSettings::formatPhp)
                            .comment(LaravelFmtBundle.message("settings.format.php.comment"))
                    }
                    row {
                        checkBox(LaravelFmtBundle.message("settings.format.blade"))
                            .bindSelected(projectSettings::formatBlade)
                    }
                }.enabledIf(enabled.selected)
            }

            group(LaravelFmtBundle.message("settings.group.all.projects")) {
                row(LaravelFmtBundle.message("settings.binary.path")) {
                    cell(pathField)
                        .align(AlignX.FILL)
                        .bindText({ appSettings.binaryPath }, { appSettings.binaryPath = it.trim() })
                        .validationOnInput { field ->
                            val path = field.text.trim()

                            if (path.isNotEmpty() && !File(path).isFile) {
                                warning(LaravelFmtBundle.message("settings.binary.path.not.found"))
                            } else {
                                null
                            }
                        }
                        .comment(LaravelFmtBundle.message("settings.binary.path.comment"))
                }
            }
        }
    }

    override fun apply() {
        val oldPath = appSettings.binaryPath
        val oldProjectState = projectSettings.state.modificationCount

        super.apply()

        when {
            appSettings.binaryPath != oldPath -> LaravelFmtServerService.restartAll()
            projectSettings.state.modificationCount != oldProjectState -> project.service<LaravelFmtServerService>().restartServer()
        }
    }
}
