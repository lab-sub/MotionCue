package app.motioncue.data

import app.motioncue.core.engine.SpeedSample
import app.motioncue.core.model.MotionEvent

/** Polling the cache advances the clock, but does not create a new speed measurement. */
internal class LiveSpeedEvents {
    private var lastSample: SpeedSample? = null
    private var initialized = false

    fun update(nowMs: Long, sample: SpeedSample?): List<MotionEvent.Speed> {
        val previous = lastSample
        if (initialized && sample == previous) return emptyList()
        initialized = true
        lastSample = sample
        if (sample == null) return listOf(MotionEvent.Speed(nowMs, 0.0, valid = false))

        return buildList {
            // Different estimators can disagree. Their offset is not physical deceleration.
            if (previous != null && previous.source != sample.source) {
                add(MotionEvent.Speed(nowMs, 0.0, valid = false))
            }
            add(MotionEvent.Speed(nowMs, sample.mps * 3.6, measurementTimeMs = sample.measurementTimeMs))
        }
    }

    fun reset() {
        lastSample = null
        initialized = false
    }
}
