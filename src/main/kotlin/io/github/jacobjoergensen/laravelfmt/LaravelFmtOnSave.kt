package io.github.jacobjoergensen.laravelfmt

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.ide.actionsOnSave.ActionOnSaveBackedByOwnConfigurable
import com.intellij.ide.actionsOnSave.ActionOnSaveComment
import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

/**
 * Formats only the saved files laravel-fmt handles, unlike the IDE's "Reformat code" save action, which
 * reformats every language. Goes through [ReformatCodeProcessor] like that action, so the edits come from
 * the server and each file is one undo step.
 */
class LaravelFmtOnSaveAction : ActionsOnSaveFileDocumentManagerListener.ActionOnSave() {

    override fun isEnabledForProject(project: Project): Boolean =
        project.service<LaravelFmtProjectSettings>().formatOnSave && project.service<LaravelFmtServerService>().isEnabled

    override fun processDocuments(project: Project, documents: Array<Document>) {
        // The same guard as the IDE's own Reformat code save action.
        if (CommandProcessor.getInstance().currentCommandName == ReformatCodeProcessor.getCommandName()) {
            return
        }

        // Looking PSI up can be a slow operation on the EDT; the cached one never parses, and a file saved from
        // an editor already has it.
        val psiDocuments = PsiDocumentManager.getInstance(project)
        val files = documents.mapNotNull { document ->
            FileDocumentManager.getInstance().getFile(document)
                ?.takeIf { formatsOnSave(project, it) }
                ?.let { psiDocuments.getCachedPsiFile(document) }
        }

        if (files.isEmpty()) {
            return
        }

        ReformatCodeProcessor(project, files.toTypedArray(), null, false).apply {
            setProcessAllFilesAsSingleUndoStep(false)
        }.run()
    }

    companion object {
        /**
         * Files outside the project, such as library sources, are left alone, and so are files the IDE's own
         * Reformat code save action already sends to laravel-fmt.
         */
        fun formatsOnSave(project: Project, file: VirtualFile): Boolean {
            val ideOptions = FormatOnSaveOptions.getInstance(project)
            val ideFormatsOnSave = ideOptions.isRunOnSaveEnabled && ideOptions.isFileTypeSelected(file.fileType)

            return !ideFormatsOnSave &&
                project.service<LaravelFmtServerService>().formats(file) &&
                ProjectFileIndex.getInstance(project).isInContent(file)
        }
    }
}

class LaravelFmtOnSaveInfoProvider : ActionOnSaveInfoProvider() {

    override fun getActionOnSaveInfos(context: ActionOnSaveContext): List<ActionOnSaveInfo> =
        listOf(LaravelFmtOnSaveInfo(context))

    override fun getSearchableOptions(): Collection<String> = listOf(LaravelFmtBundle.message("save.action.name"))
}

/** Mirrors the Run on save checkbox in the laravel-fmt settings, so either page can change it. */
private class LaravelFmtOnSaveInfo(context: ActionOnSaveContext) :
    ActionOnSaveBackedByOwnConfigurable<LaravelFmtConfigurable>(context, LaravelFmtConfigurable.ID, LaravelFmtConfigurable::class.java) {

    private val settings get() = project.service<LaravelFmtProjectSettings>()

    override fun getActionOnSaveName(): String = LaravelFmtBundle.message("save.action.name")

    override fun isApplicableAccordingToStoredState(): Boolean = settings.mode != LaravelFmtProjectSettings.Mode.OFF

    override fun isApplicableAccordingToUiState(configurable: LaravelFmtConfigurable): Boolean =
        !configurable.offRadioButton.isSelected

    override fun isActionOnSaveEnabledAccordingToStoredState(): Boolean = settings.formatOnSave

    override fun isActionOnSaveEnabledAccordingToUiState(configurable: LaravelFmtConfigurable): Boolean =
        configurable.formatOnSaveCheckBox.isSelected

    override fun setActionOnSaveEnabled(configurable: LaravelFmtConfigurable, enabled: Boolean) {
        configurable.formatOnSaveCheckBox.isSelected = enabled
    }

    override fun getCommentAccordingToStoredState(): ActionOnSaveComment? = disabledComment(isApplicableAccordingToStoredState())

    override fun getCommentAccordingToUiState(configurable: LaravelFmtConfigurable): ActionOnSaveComment? =
        disabledComment(isApplicableAccordingToUiState(configurable))

    override fun getActionLinks() = listOf(createGoToPageInSettingsLink(LaravelFmtConfigurable.ID))

    private fun disabledComment(enabled: Boolean): ActionOnSaveComment? =
        if (enabled) null else ActionOnSaveComment.info(LaravelFmtBundle.message("save.action.disabled"))
}
