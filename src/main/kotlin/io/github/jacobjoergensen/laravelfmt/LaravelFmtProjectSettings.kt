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

    enum class Mode {
        AUTOMATIC,
        ALWAYS,
        OFF,
    }

    class State : BaseState() {
        var mode by enum(Mode.AUTOMATIC)

        /** The on/off checkbox [mode] replaced, only read so a project switched off with it stays off. */
        var enabled by property(true)

        var formatPhp by property(true)
        var formatBlade by property(true)
        var formatOnSave by property(false)
    }

    var mode: Mode
        get() = state.mode
        set(value) {
            state.mode = value
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

    override fun loadState(state: State) {
        if (!state.enabled) {
            state.mode = Mode.OFF
            state.enabled = true
        }

        super.loadState(state)
    }
}
