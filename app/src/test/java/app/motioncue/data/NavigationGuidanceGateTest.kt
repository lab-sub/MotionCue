package app.motioncue.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGuidanceGateTest {
    @Test fun `startup cannot accept retained TBT until guidance state is confirmed`() {
        val gate = NavigationGuidanceGate()
        assertFalse(gate.allowsRouteData(0))
        gate.onState(false, 1_000)
        assertFalse(gate.allowsRouteData(1_100))
        gate.onState(true, 2_000)
        assertTrue(gate.allowsRouteData(2_100))
    }

    @Test fun `end latch rejects arbitrarily late active responses and retained TBT`() {
        val gate = NavigationGuidanceGate()
        gate.onState(true, 0)
        gate.onEnded()
        repeat(20) { gate.onState(true, it * 1_000L) }
        assertTrue(gate.awaitingStartEvent)
        assertFalse(gate.allowsRouteData(19_001))
        gate.onState(false, 20_000)
        gate.onState(true, 21_000)
        assertFalse(gate.allowsRouteData(21_001))
    }

    @Test fun `new start requires a subsequent state before accepting route data`() {
        val gate = NavigationGuidanceGate()
        gate.onState(true, 0)
        gate.onEnded()
        gate.onState(true, 1_000)
        gate.onStarted()
        assertFalse(gate.allowsRouteData(1_001))
        gate.onState(true, 2_000)
        assertTrue(gate.allowsRouteData(2_001))
    }

    @Test fun `provider start followed by route ended rechecks mode without permanent closure`() {
        val gate = NavigationGuidanceGate()
        gate.onEnded() // Previous cancellation or FREE_DRIVE.
        gate.onStarted()
        gate.invalidateState() // Navi42 emits RouteEnded immediately after RouteStarted.
        assertFalse(gate.allowsRouteData(1_000))
        gate.onState(true, 1_001)
        assertTrue(gate.allowsRouteData(1_002))
        gate.onEnded() // Genuine cancellation/free-drive must still win over RouteEnded.
        gate.invalidateState()
        gate.onState(true, 1_003)
        assertFalse(gate.allowsRouteData(1_004))
    }

    @Test fun `state expiry blocks TBT even if TBT continues arriving`() {
        val gate = NavigationGuidanceGate()
        gate.onState(true, 1_000)
        assertTrue(gate.allowsRouteData(5_000))
        assertFalse(gate.allowsRouteData(5_001))
        assertTrue(gate.expire(5_001))
        assertFalse(gate.expire(5_100))
        gate.onState(true, 6_000)
        assertTrue(gate.allowsRouteData(6_001))
    }

    @Test fun `cached closing speed cannot return after end start or inactive transitions`() {
        val gate = NavigationGuidanceGate()
        gate.onState(true, 0)
        gate.recordRouteSpeed(1_000)
        assertTrue(gate.allowsRouteSpeed(1_100, 1_000))
        gate.onEnded()
        assertFalse(gate.allowsRouteSpeed(1_101, 1_000))
        gate.onStarted()
        gate.onState(true, 1_200)
        assertFalse(gate.allowsRouteSpeed(1_201, 1_000))
        gate.recordRouteSpeed(1_300)
        assertTrue(gate.allowsRouteSpeed(1_301, 1_300))
        gate.onState(false, 1_400)
        gate.onState(true, 1_500)
        assertFalse(gate.allowsRouteSpeed(1_501, 1_300))
    }

    @Test fun `pause replay reset permits fresh state without requiring a missed start event`() {
        val gate = NavigationGuidanceGate()
        gate.onEnded()
        gate.reset()
        assertFalse(gate.allowsRouteData(0))
        gate.onState(true, 100)
        assertTrue(gate.allowsRouteData(101))
    }

    @Test fun `future state time and rejected speed never become fresh route evidence`() {
        val gate = NavigationGuidanceGate()
        gate.recordRouteSpeed(10)
        gate.onState(true, 100)
        assertFalse(gate.allowsRouteData(99))
        assertFalse(gate.allowsRouteSpeed(101, 10))
        gate.recordRouteSpeed(101)
        gate.clearRouteSpeed()
        assertFalse(gate.allowsRouteSpeed(102, 101))
    }
}
