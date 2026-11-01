package app.motioncue.actuator

import app.motioncue.core.engine.CueState
import kotlinx.coroutines.flow.StateFlow

interface CabinActuator {
    val status: StateFlow<String>
    suspend fun start(enabled: Boolean)
    fun render(state: CueState)
    suspend fun stop()
}
