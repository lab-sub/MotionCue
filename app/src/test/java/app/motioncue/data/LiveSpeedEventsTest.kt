package app.motioncue.data

import app.motioncue.core.engine.CueEngine
import app.motioncue.core.engine.ClosingSpeedEstimator
import app.motioncue.core.engine.CueKind
import app.motioncue.core.engine.SpeedFusion
import app.motioncue.core.engine.SpeedSample
import app.motioncue.core.engine.SpeedSource
import app.motioncue.core.model.MotionEvent
import app.motioncue.core.model.TurnInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSpeedEventsTest {
    @Test fun `route speed and upcoming turn use the same first instruction through the Live pipeline`() {
        val closing = ClosingSpeedEstimator()
        val fusion = SpeedFusion()
        val events = LiveSpeedEvents()
        val engine = CueEngine()
        fun route(headDistance: Double) = listOf(
            TurnInfo("TURN_LEFT", headDistance), TurnInfo("TURN_RIGHT", 380.0),
            TurnInfo("TURN_RIGHT", 50.0), TurnInfo("TURN_ARRIVE", 10.0),
        )
        events.update(0, null).forEach(engine::process)
        closing.onRoute(0, route(80.0))
        engine.process(MotionEvent.Tbt(0, route(80.0)))
        closing.onRoute(1_000, route(70.0))?.let {
            fusion.update(SpeedSource.ROUTE_DISTANCE, 1_000, it)
        }
        events.update(1_000, fusion.currentSample(1_000)).forEach(engine::process)
        val state = engine.process(MotionEvent.Tbt(1_000, route(70.0))).state
        assertEquals(36.0, state.speedKmh, 1e-9)
        assertEquals("좌회전", state.nextTurn!!.label)
        assertEquals(70.0, state.nextTurn!!.distanceM, 1e-9)
        assertEquals(7.0, state.nextTurn!!.etaSec!!, 1e-9)
    }

    @Test fun `cache polling does not replay the same speed as a new measurement`() {
        val events = LiveSpeedEvents()
        val sample = SpeedSample(SpeedSource.NAVI_LOCATION, 1_000L, 10.0)
        assertEquals(1, events.update(1_020L, sample).size)
        assertTrue(events.update(1_100L, sample).isEmpty())
        assertTrue(events.update(1_200L, sample).isEmpty())
        val next = events.update(2_020L, sample.copy(timeMs = 2_000L, measurementTimeMs = 2_000L)).single()
        assertEquals(2_020L, next.timeMs)
        assertEquals(2_000L, next.measurementTimeMs)
    }

    @Test fun `unknown speed is emitted once at startup and once on expiry`() {
        val events = LiveSpeedEvents()
        assertFalse(events.update(0L, null).single().valid)
        assertTrue(events.update(100L, null).isEmpty())
        assertTrue(events.update(1_000L, SpeedSample(SpeedSource.NAVI_LOCATION, 1_000L, 0.0)).single().valid)
        assertFalse(events.update(4_100L, null).single().valid)
        assertTrue(events.update(4_200L, null).isEmpty())
    }

    @Test fun `switching estimators resets derivative before applying the new value`() {
        val events = LiveSpeedEvents()
        val engine = CueEngine()
        events.update(1_000L, SpeedSample(SpeedSource.FUSED_LOCATION, 1_000L, 20.0)).forEach(engine::process)
        engine.process(MotionEvent.Brake(1_100L, true))
        val changed = events.update(2_000L, SpeedSample(SpeedSource.NAVI_LOCATION, 1_900L, 5.0))
        assertEquals(2, changed.size)
        assertFalse(changed.first().valid)
        changed.forEach(engine::process)
        val result = engine.process(MotionEvent.Tick(2_000L)).state
        assertEquals(18.0, result.speedKmh, 0.00001)
        assertEquals(0.0, result.decelMps2, 0.00001)
        assertFalse(CueKind.REACTIVE_BRAKE in result.activeKinds)
    }

    @Test fun `one second gentle deceleration is not compressed into the last cache poll`() {
        val events = LiveSpeedEvents()
        val engine = CueEngine()
        var sample = SpeedSample(SpeedSource.NAVI_LOCATION, 1_000L, 10.0)
        events.update(1_000L, sample).forEach(engine::process)
        engine.process(MotionEvent.Brake(1_000L, true))
        for (now in 1_100L..2_000L step 100L) {
            if (now == 2_000L) sample = sample.copy(timeMs = now, mps = 9.4, measurementTimeMs = now)
            events.update(now, sample).forEach(engine::process)
            engine.process(MotionEvent.Tick(now))
        }
        val result = engine.process(MotionEvent.Tick(2_000L)).state
        assertEquals(0.6, result.decelMps2, 0.00001)
        assertFalse(CueKind.REACTIVE_BRAKE in result.activeKinds)
    }

    @Test fun `restart invalidates speed until a new sample arrives`() {
        val events = LiveSpeedEvents()
        events.update(1_000L, SpeedSample(SpeedSource.NAVI_LOCATION, 1_000L, 10.0))
        events.reset()
        assertFalse(events.update(2_000L, null).single().valid)
        assertTrue(events.update(2_100L, null).isEmpty())
    }

    @Test fun `delayed SDK measurements preserve sampling interval independently of receipt time`() {
        val events = LiveSpeedEvents()
        val engine = CueEngine()
        events.update(1_000L, SpeedSample(SpeedSource.FUSED_LOCATION, 1_000L, 10.0, 80_000L))
            .forEach(engine::process)
        engine.process(MotionEvent.Brake(1_000L, true))
        val next = events.update(1_100L, SpeedSample(SpeedSource.FUSED_LOCATION, 1_100L, 9.4, 81_000L)).single()
        assertEquals(1_100L, next.timeMs)
        assertEquals(81_000L, next.measurementTimeMs)
        val result = engine.process(next).state
        assertEquals(0.6, result.decelMps2, 0.00001)
        assertFalse(CueKind.REACTIVE_BRAKE in result.activeKinds)
    }
}
