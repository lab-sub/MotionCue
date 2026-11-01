package app.motioncue.data

import app.motioncue.core.model.MotionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SourceStatus(
    val playing: Boolean = false,
    val elapsedMs: Long = 0,
    val durationMs: Long? = null,
    val detail: String = "",
    val speedKnown: Boolean = false,
)

interface MotionEventSource {
    val events: Flow<MotionEvent>
    val status: StateFlow<SourceStatus>
    suspend fun play()
    suspend fun pause()
    suspend fun reset()
    suspend fun close()
}
