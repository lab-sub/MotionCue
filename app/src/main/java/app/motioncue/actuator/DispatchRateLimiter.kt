package app.motioncue.actuator

import app.motioncue.core.actuation.RateLimiter
import kotlinx.coroutines.delay

/** Reserve immediately before an SDK call, never before awaiting a preceding area's callback. */
internal class DispatchRateLimiter(
    private val nowMs: () -> Long,
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
    private val limiter: RateLimiter = RateLimiter(),
) {
    suspend fun acquire(key: Any, allowed: () -> Boolean = { true }): Boolean {
        while (allowed()) {
            if (limiter.tryAcquire(key, nowMs())) return true
            waitMs(50)
        }
        return false
    }
}
