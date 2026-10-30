package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * So the first format or save, which can come before any PHP file is opened, doesn't search the project's
 * folders on the EDT.
 */
class LaravelFmtStartupActivity : ProjectActivity {

    @Suppress("kotlin:S6310")
    // The platform owns this scope; blocking work goes to IO, and nothing would inject another dispatcher.
    override suspend fun execute(project: Project) {
        val server = project.service<LaravelFmtServerService>()

        if (!server.isOff) {
            withContext(Dispatchers.IO) { server.refresh() }
        }
    }
}
