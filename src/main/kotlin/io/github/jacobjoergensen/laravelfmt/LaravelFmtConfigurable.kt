package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.layout.not
import java.io.File

/** No formatting options on purpose: those come from the project config the server reads. */
class LaravelFmtConfigurable(private val project: Project) : BoundConfigurable(LaravelFmtBundle.message("settings.display.name")) {

    private val projectSettings get() = project.service<LaravelFmtProjectSettings>()
    private val appSettings get() = service<LaravelFmtSettings>()

    /** Read and set by the laravel-fmt row on the Actions on Save page while both pages are open. */
    internal lateinit var offRadioButton: JBRadioButton
    internal lateinit var formatOnSaveCheckBox: JBCheckBox

    override fun createPanel(): DialogPanel {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle(LaravelFmtBundle.message("settings.binary.chooser.title"))
            .withDescription(LaravelFmtBundle.message("settings.binary.chooser.description"))

        val pathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))
        }

        return panel {
            group(LaravelFmtBundle.message("settings.group.project")) {
                lateinit var off: Cell<JBRadioButton>

                buttonsGroup(LaravelFmtBundle.message("settings.mode")) {
                    row {
                        radioButton(LaravelFmtBundle.message("settings.mode.automatic"), LaravelFmtProjectSettings.Mode.AUTOMATIC)
                            .comment(LaravelFmtBundle.message("settings.mode.automatic.comment"))
                    }
                    row {
                        radioButton(LaravelFmtBundle.message("settings.mode.always"), LaravelFmtProjectSettings.Mode.ALWAYS)
                            .comment(LaravelFmtBundle.message("settings.mode.always.comment"))
                    }
                    row {
                        off = radioButton(LaravelFmtBundle.message("settings.mode.off"), LaravelFmtProjectSettings.Mode.OFF)
                    }
                }.bind(projectSettings::mode)

                offRadioButton = off.component

                rowsRange {
                    row {
                        checkBox(LaravelFmtBundle.message("settings.format.php"))
                            .bindSelected(projectSettings::formatPhp)
                            .comment(LaravelFmtBundle.message("settings.format.php.comment"))
                    }
                    row {
                        checkBox(LaravelFmtBundle.message("settings.format.blade"))
                            .bindSelected(projectSettings::formatBlade)
                    }
                    row {
                        formatOnSaveCheckBox = checkBox(LaravelFmtBundle.message("settings.format.on.save"))
                            .bindSelected(projectSettings::formatOnSave)
                            .comment(LaravelFmtBundle.message("settings.format.on.save.comment"))
                            .component
                    }
                }.enabledIf(off.selected.not())
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
        val oldServerInputs = serverInputs()

        super.apply()

        when {
            appSettings.binaryPath != oldPath -> LaravelFmtServerService.restartAll()
            serverInputs() != oldServerInputs -> project.service<LaravelFmtServerService>().restartServer()
        }
    }

    /** Run on save is left out: the server doesn't depend on it, so toggling it needs no restart. */
    private fun serverInputs() = listOf(projectSettings.mode, projectSettings.formatPhp, projectSettings.formatBlade)

    companion object {
        const val ID = "io.github.jacobjoergensen.laravelfmt.settings"
    }
}
