package app.motioncue.recovery

import kotlin.math.ceil
import kotlin.math.roundToInt

internal object RecoveryMath {
    fun fan(current: Int, min: Int, max: Int): Int? =
        if (min > max || current !in min..max) null else (current.toLong() + 2).coerceIn(min.toLong(), max.toLong()).toInt()

    fun temperature(current: Float, min: Float, max: Float, step: Float, gridOrigin: Float = min): Float? {
        if (!listOf(current, min, max, step, gridOrigin).all { it.isFinite() } || min > max || step <= 0 || current !in min..max) return null
        val desired = (current - 1.5f).coerceIn(min, max)
        // Round upward on the supported grid: never cool by more than the requested 1.5 Celsius.
        // Area bounds restrict the range; only the SDK grid minimum defines the step origin.
        val target = gridOrigin + ceil(((desired - gridOrigin) / step).toDouble() - 1e-6).toFloat() * step
        return target.takeIf { it.isFinite() && it in min..max && it <= current }
    }

    /** AAOS window position: zero closed, positive maximum open. Negative/ambiguous ranges are skipped. */
    fun window(current: Int, min: Int, max: Int): Int? {
        if (min != 0 || max <= min || current !in min..max) return null
        val vent = (max.toDouble() * 0.2).roundToInt().coerceIn(min, max)
        return maxOf(current, vent) // Never close an already more-open window as a recovery action.
    }
}
