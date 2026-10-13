package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Per machine, so the binary path never ends up in a project. */
@Service(Service.Level.APP)
@State(name = "LaravelFmtSettings", storages = [Storage("laravel-fmt.xml")])
class LaravelFmtSettings : SimplePersistentStateComponent<LaravelFmtSettings.State>(State()) {

    class State : BaseState() {
        var binaryPath by string("")
    }

    var binaryPath: String
        get() = state.binaryPath ?: ""
        set(value) {
            state.binaryPath = value
        }

    companion object {
        fun getInstance(): LaravelFmtSettings =
            ApplicationManager.getApplication().getService(LaravelFmtSettings::class.java)
    }
}
