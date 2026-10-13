package io.github.jacobjoergensen.laravelfmt

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.util.concurrent.ConcurrentHashMap

object LaravelFmtNotifications {

    const val GROUP_ID = "laravel-fmt"

    private const val MAX_DETAILS_LENGTH = 1500

    fun binaryNotFound(project: Project) {
        notify(
            project,
            LaravelFmtBundle.message("notification.not.found.title"),
            LaravelFmtBundle.message("notification.not.found.content"),
            extraAction = disableForProjectAction(project),
        )
    }

    fun projectNotTrusted(project: Project) {
        notify(
            project,
            LaravelFmtBundle.message("notification.untrusted.title"),
            LaravelFmtBundle.message("notification.untrusted.content"),
        )
    }

    fun explicitPathMissing(project: Project, configured: String, fallback: String?) {
        val outcome = fallback
            ?.let { LaravelFmtBundle.message("notification.explicit.missing.fallback", StringUtil.escapeXmlEntities(it)) }
            ?: LaravelFmtBundle.message("notification.explicit.missing.no.fallback")

        notify(
            project,
            LaravelFmtBundle.message("notification.explicit.missing.title"),
            LaravelFmtBundle.message("notification.explicit.missing.content", StringUtil.escapeXmlEntities(configured), outcome),
        )
    }

    fun startFailed(project: Project, message: String, details: String?) {
        // Details are raw process output (e.g. a PHP exception), so escape them before rendering.
        val detailsHtml = details?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "<br/><pre>${StringUtil.escapeXmlEntities(it.takeLast(MAX_DETAILS_LENGTH))}</pre>" }
            .orEmpty()

        notify(
            project,
            LaravelFmtBundle.message("notification.start.failed.title"),
            StringUtil.escapeXmlEntities(message) + detailsHtml + "<br/>" +
                LaravelFmtBundle.message("notification.start.failed.hint"),
            NotificationType.ERROR,
        )
    }

    /** For projects that don't use laravel-fmt at all. */
    private fun disableForProjectAction(project: Project): NotificationAction =
        NotificationAction.createSimpleExpiring(LaravelFmtBundle.message("notification.action.disable.project")) {
            project.service<LaravelFmtProjectSettings>().enabled = false
            project.service<LaravelFmtServerService>().restartServer()
        }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.WARNING,
        extraAction: NotificationAction? = null,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .addAction(NotificationAction.createSimpleExpiring(LaravelFmtBundle.message("notification.action.open.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, LaravelFmtConfigurable::class.java)
            })

        extraAction?.let(notification::addAction)

        notification.notify(project)
    }
}

/**
 * Shows each notification at most once until [reset], so repeated formatting attempts and server
 * restarts don't stack up balloons.
 */
@Service(Service.Level.PROJECT)
class LaravelFmtBinaryNotifier(private val project: Project) {

    private val shown = ConcurrentHashMap.newKeySet<String>()

    fun notFoundOnce(blockedByTrust: Boolean) {
        if (blockedByTrust) {
            once("untrusted") { LaravelFmtNotifications.projectNotTrusted(project) }
        } else {
            once("not-found") { LaravelFmtNotifications.binaryNotFound(project) }
        }
    }

    fun explicitPathMissingOnce(configured: String, fallback: String?) {
        once("explicit-missing\u0000$configured") { LaravelFmtNotifications.explicitPathMissing(project, configured, fallback) }
    }

    fun startFailed(message: String, details: String?) {
        once("start-failed\u0000$message\u0000${details.orEmpty()}") {
            LaravelFmtNotifications.startFailed(project, message, details)
        }
    }

    fun reset() {
        shown.clear()
    }

    private fun once(key: String, show: () -> Unit) {
        if (shown.add(key)) {
            show()
        }
    }
}
