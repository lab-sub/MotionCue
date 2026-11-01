package app.motioncue.actuator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DispatchRateLimiterTest {
    @Test fun slowPreviousCallbackDoesNotBackdateTheNextAreasReservation() = runBlocking {
        var now = 100L
        val limiter = DispatchRateLimiter(nowMs = { now }, waitMs = { now += it })
        assertTrue(limiter.acquire("left"))
        now += 500 // The left area's callback delays the next actual dispatch.
        assertTrue(limiter.acquire("right"))
        val firstRightDispatch = now
        now += 50
        assertTrue(limiter.acquire("right"))
        assertEquals(100L, now - firstRightDispatch)
    }

    @Test fun stopWhileWaitingDoesNotConsumeOrDispatchTheQueuedWrite() = runBlocking {
        var now = 0L
        var running = true
        val limiter = DispatchRateLimiter(nowMs = { now }, waitMs = { now += it; running = false })
        assertTrue(limiter.acquire("right"))
        assertFalse(limiter.acquire("right") { running })
        assertEquals(50L, now)
    }

    @Test fun restoreAndCueWritesShareTheGlobalWindow() = runBlocking {
        var now = 0L
        val limiter = DispatchRateLimiter(nowMs = { now }, waitMs = { now += it })
        repeat(20) { assertTrue(limiter.acquire("cue-$it")) }
        assertTrue(limiter.acquire("restore"))
        assertEquals(1_000L, now)
    }
}
