package io.github.jacobjoergensen.laravelfmt

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Never store paths here: this file can be committed, and a shared setting must not be able to point the
 * IDE at an executable.
 */
@Service(Service.Level.PROJECT)
@State(name = "LaravelFmtProjectSettings", storages = [Storage("laravel-fmt.xml")])
class LaravelFmtProjectSettings : SimplePersistentStateComponent<LaravelFmtProjectSettings.State>(State()) {

    class State : BaseState() {
        var enabled by property(true)
        var formatPhp by property(true)
        var formatBlade by property(true)
        var formatOnSave by property(false)
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    var formatPhp: Boolean
        get() = state.formatPhp
        set(value) {
            state.formatPhp = value
        }

    var formatBlade: Boolean
        get() = state.formatBlade
        set(value) {
            state.formatBlade = value
        }

    var formatOnSave: Boolean
        get() = state.formatOnSave
        set(value) {
            state.formatOnSave = value
        }
}
