package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

/** Unlike the status bar widget's restart, this also looks the binary up again. */
class LaravelFmtRestartAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && project.service<LaravelFmtServerService>().isEnabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<LaravelFmtServerService>()?.restartServer()
    }
}
