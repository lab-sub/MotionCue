package app.motioncue.core.actuation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FadingTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val amber = 0xFFFFB000.toInt()

    private fun red(c: Int) = (c shr 16) and 0xFF

    @Test
    fun `lerp keeps alpha opaque and hits both ends`() {
        assertEquals(black, ColorMath.lerp(black, white, 0.0))
        assertEquals(white, ColorMath.lerp(black, white, 1.0))
        assertEquals(0xFF808080.toInt(), ColorMath.lerp(black, white, 0.5))
        assertEquals(0xFF, (ColorMath.lerp(0x00000000, 0x00FFFFFF, 0.5) ushr 24) and 0xFF)
    }

    @Test
    fun `fade reaches the target in steps without going back`() {
        val planner = FadePlanner<String>(fadeMs = 500, steps = 5)
        planner.setCurrent("zone", black, 0)
        planner.setTarget("zone", white, 0)
        val sent = mutableListOf<Int>()
        for (t in 0L..1_000L step 50) sent += planner.poll(t).map { it.second }
        assertEquals(5, sent.size)
        assertEquals(white, sent.last())
        // 단조 증가 = 점멸 없음
        assertTrue(sent.zipWithNext().all { (a, b) -> red(b) > red(a) })
        assertTrue(planner.isIdle())
    }

    @Test
    fun `retarget mid-fade starts from the last sent color`() {
        val planner = FadePlanner<String>(fadeMs = 500, steps = 5)
        planner.setCurrent("zone", black, 0)
        planner.setTarget("zone", white, 0)
        val first = planner.poll(100).single().second // 20%
        planner.setTarget("zone", black, 150)
        assertTrue(planner.poll(150).isEmpty())
        val next = planner.poll(250).single().second
        assertTrue(red(next) < red(first))
        var last = next
        for (t in 300L..800L step 50) planner.poll(t).forEach { last = it.second }
        assertEquals(black, last)
    }

    @Test
    fun `fade takes the full configured duration`() {
        val planner = FadePlanner<String>(fadeMs = 500, steps = 5)
        planner.setCurrent("zone", black, 0)
        planner.setTarget("zone", white, 0)
        assertTrue(planner.poll(0).isEmpty())
        var last = black
        for (t in 50L..450L step 50) planner.poll(t).forEach { last = it.second }
        assertTrue(red(last) < red(white))
        assertFalse(planner.isIdle())
        assertEquals(listOf("zone" to white), planner.poll(500))
        assertTrue(planner.isIdle())
    }

    @Test
    fun `unknown starting color jumps straight to the target`() {
        val planner = FadePlanner<String>()
        planner.setTarget("zone", amber, 0)
        assertEquals(listOf("zone" to amber), planner.poll(0))
        assertTrue(planner.isIdle())
    }

    @Test
    fun `same target does not resend`() {
        val planner = FadePlanner<String>()
        planner.setCurrent("zone", amber, 0)
        planner.setTarget("zone", amber, 10)
        assertTrue(planner.poll(20).isEmpty())
    }

    @Test
    fun `rate limiter enforces per-key interval and global cap`() {
        val limiter = RateLimiter(perKeyIntervalMs = 100, maxPerWindow = 3, windowMs = 1_000)
        assertTrue(limiter.tryAcquire("a", 0))
        assertFalse(limiter.tryAcquire("a", 50))
        assertTrue(limiter.tryAcquire("a", 100))
        assertTrue(limiter.tryAcquire("b", 100))
        assertFalse(limiter.tryAcquire("c", 200)) // 1초에 3회 초과
        assertTrue(limiter.tryAcquire("c", 1_001))
    }

    @Test
    fun `limited fade still converges`() {
        val planner = FadePlanner<String>(fadeMs = 500, steps = 5)
        val limiter = RateLimiter(perKeyIntervalMs = 300, maxPerWindow = 20)
        planner.setCurrent("zone", black, 0)
        planner.setTarget("zone", white, 0)
        var last = black
        var calls = 0
        for (t in 0L..2_000L step 50) planner.poll(t, limiter).forEach { last = it.second; calls++ }
        assertEquals(white, last)
        assertTrue("calls=$calls", calls <= 3)
    }
}
