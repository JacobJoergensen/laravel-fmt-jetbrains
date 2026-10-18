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

/**
 * Shows each notification at most once until [reset], so repeated formatting attempts and server
 * restarts don't stack up balloons.
 */
@Service(Service.Level.PROJECT)
class LaravelFmtNotifications(private val project: Project) {

    private val shown = ConcurrentHashMap.newKeySet<String>()

    fun notFoundOnce(blockedByTrust: Boolean) {
        if (blockedByTrust) {
            once("untrusted") {
                notify(
                    LaravelFmtBundle.message("notification.untrusted.title"),
                    LaravelFmtBundle.message("notification.untrusted.content"),
                )
            }
        } else {
            once("not-found") {
                notify(
                    LaravelFmtBundle.message("notification.not.found.title"),
                    LaravelFmtBundle.message("notification.not.found.content"),
                    extraAction = disableForProjectAction(),
                )
            }
        }
    }

    fun explicitPathMissingOnce(configured: String, fallback: String?) {
        once("explicit-missing\u0000$configured") {
            val outcome = fallback
                ?.let { LaravelFmtBundle.message("notification.explicit.missing.fallback", StringUtil.escapeXmlEntities(it)) }
                ?: LaravelFmtBundle.message("notification.explicit.missing.no.fallback")

            notify(
                LaravelFmtBundle.message("notification.explicit.missing.title"),
                LaravelFmtBundle.message("notification.explicit.missing.content", StringUtil.escapeXmlEntities(configured), outcome),
            )
        }
    }

    fun startFailed(message: String, details: String?) {
        once("start-failed\u0000$message\u0000${details.orEmpty()}") {
            // Details are raw process output (e.g. a PHP exception), so escape them before rendering.
            val detailsHtml = details?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { "<br/><pre>${StringUtil.escapeXmlEntities(it.takeLast(MAX_DETAILS_LENGTH))}</pre>" }
                .orEmpty()

            notify(
                LaravelFmtBundle.message("notification.start.failed.title"),
                StringUtil.escapeXmlEntities(message) + detailsHtml + "<br/>" +
                    LaravelFmtBundle.message("notification.start.failed.hint"),
                NotificationType.ERROR,
            )
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

    /** For projects that don't use laravel-fmt at all. */
    private fun disableForProjectAction(): NotificationAction =
        NotificationAction.createSimpleExpiring(LaravelFmtBundle.message("notification.action.disable.project")) {
            project.service<LaravelFmtProjectSettings>().mode = LaravelFmtProjectSettings.Mode.OFF
            project.service<LaravelFmtServerService>().restartServer()
        }

    private fun notify(
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

    companion object {
        const val GROUP_ID = "laravel-fmt"

        private const val MAX_DETAILS_LENGTH = 1500
    }
}
