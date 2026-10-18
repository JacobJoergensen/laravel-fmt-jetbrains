package io.github.jacobjoergensen.laravelfmt

import com.intellij.codeStyle.AbstractConvertLineSeparatorsAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.LineSeparator

/** Edits can't set the line separator: PhpStorm keeps `\n` in the editor, so the server never sees a `\r`. */
@Service(Service.Level.PROJECT)
class LaravelFmtLineEndings(private val project: Project) {

    /** Called off the EDT, since [askServer] waits for the server. */
    fun afterFormat(file: VirtualFile, askServer: () -> String?) {
        val separator = when (runCatching(askServer).getOrNull()) {
            "lf" -> LineSeparator.LF.separatorString
            "crlf" -> LineSeparator.CRLF.separatorString
            else -> return
        }

        ApplicationManager.getApplication().invokeLater({
            val current = LoadTextUtil.detectLineSeparator(file, true)

            // A file without line breaks has no separator to change.
            if (file.isValid && current != null && current != separator) {
                // What the status bar does: saves the file and converts it as one undo step.
                AbstractConvertLineSeparatorsAction.changeLineSeparators(project, file, separator)
            }
        }, project.disposed)
    }
}
