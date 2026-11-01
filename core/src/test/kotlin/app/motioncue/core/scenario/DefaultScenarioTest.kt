package app.motioncue.core.scenario

import app.motioncue.core.engine.CueConfig
import app.motioncue.core.engine.CueEngine
import app.motioncue.core.engine.CueKind
import app.motioncue.core.engine.CueState
import app.motioncue.core.engine.Upcoming
import app.motioncue.core.model.MotionEvent
import app.motioncue.core.model.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기본 Mock 시나리오를 처음부터 끝까지 CueEngine에 넣어 시연 흐름이 의도대로 나오는지 확인한다. */
class DefaultScenarioTest {

    private val scenario = Scenario.loadDefault()
    private val timeline = ScenarioTimeline(scenario)

    private data class Interval(val kind: CueKind, val startMs: Long, val endMs: Long)

    private fun run(config: CueConfig = CueConfig()): Pair<List<Pair<Long, CueState>>, List<Interval>> {
        val engine = CueEngine(config)
        val states = mutableListOf<Pair<Long, CueState>>()
        for (tick in 0..timeline.lastTick) {
            // 같은 틱의 Speed에서 큐가 켜진 뒤 TBT의 1m 반올림 값으로 ETA가 바뀔 수 있다.
            // 실제 시작을 판단한 이벤트를 잃지 않도록 모든 출력 상태를 보존한다.
            for (event in timeline.eventsAt(tick)) states += event.timeMs to engine.process(event).state
        }
        val intervals = mutableListOf<Interval>()
        for (kind in CueKind.entries) {
            var start: Long? = null
            for ((t, s) in states) {
                val on = kind in s.activeKinds
                if (on && start == null) start = t
                if (!on && start != null) {
                    intervals += Interval(kind, start, t)
                    start = null
                }
            }
            if (start != null) intervals += Interval(kind, start, states.last().first)
        }
        return states to intervals.sortedBy { it.startMs }
    }

    @Test
    fun `default scenario parses`() {
        assertEquals(95.0, scenario.durationSec, 0.0)
        assertEquals(950, timeline.lastTick)
        assertEquals(2, scenario.maneuvers.size)
    }

    @Test
    fun `left turn is announced about 300m ahead and distances shrink`() {
        val tbts = (0..timeline.lastTick).flatMap { timeline.eventsAt(it) }
            .filterIsInstance<MotionEvent.Tbt>()
            .filter { e -> e.items.any { it.turnType == "TURN_LEFT" } }
        val first = tbts.first().items.single { it.turnType == "TURN_LEFT" }
        assertEquals(300.0, first.distanceM, 2.0)
        val distances = tbts.map { e -> e.items.single { it.turnType == "TURN_LEFT" }.distanceM }
        assertTrue(distances.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun `each maneuver is cued at the current speed ETA threshold before passing`() {
        val (states, intervals) = run()
        val turns = intervals.filter { it.kind == CueKind.PREDICTIVE_TURN }
        assertEquals(2, turns.size)
        scenario.maneuvers.zip(turns).forEach { (m, cue) ->
            assertEtaThreshold(states, cue, m.passAtSec) { it.nextTurn }
        }
    }

    @Test
    fun `speed bump is cued before it is reached`() {
        val (states, intervals) = run()
        val bumps = intervals.filter { it.kind == CueKind.PREDICTIVE_CAUTION }
        assertEquals(1, bumps.size)
        assertEtaThreshold(states, bumps.single(), scenario.cautions.single().passAtSec) { it.nextCaution }
    }

    /**
     * 엔진은 미래의 시나리오 속도를 알 수 없고 현재 속도로 ETA를 추정한다.
     * 예를 들어 좌회전은 29초에 약 17.36m / 25km/h = 2.5초지만 이후 감속해 실제로는 32초에 통과한다.
     * 따라서 실제 통과 시각 - leadTime을 강제하지 않고, 출력 ETA의 임계값 통과와 사전 예고를 확인한다.
     */
    private fun assertEtaThreshold(
        states: List<Pair<Long, CueState>>,
        cue: Interval,
        passAtSec: Double,
        upcoming: (CueState) -> Upcoming?,
    ) {
        val config = CueConfig()
        val index = states.indexOfFirst { it.first == cue.startMs && cue.kind in it.second.activeKinds }
        assertTrue("missing frame before $cue", index > 0)
        val before = states[index - 1].second
        val current = states[index].second
        val etaBefore = upcoming(before)!!.etaSec!!
        val etaAtStart = upcoming(current)!!.etaSec!!
        assertFalse(cue.kind in before.activeKinds)
        assertTrue("$cue previous ETA=$etaBefore", etaBefore > config.leadTimeSec)
        // 100ms 틱 + 내비 거리의 1m 양자화로 생기는 오차만 허용한다.
        val toleranceSec = timeline.tickMs / 1000.0 + 1.0 / (current.speedKmh / 3.6)
        assertTrue(
            "$cue first ETA=$etaAtStart, lead=${config.leadTimeSec}, tolerance=$toleranceSec",
            etaAtStart in (config.leadTimeSec - toleranceSec)..config.leadTimeSec,
        )
        assertTrue("$cue was not predictive", cue.startMs < passAtSec * 1000)
    }

    @Test
    fun `unannounced curve and final stop use reactive cues`() {
        val (states, intervals) = run()
        val steer = intervals.filter { it.kind == CueKind.REACTIVE_STEER }
        assertEquals(1, steer.size)
        assertTrue("steer cue at ${steer.single().startMs}", steer.single().startMs in 76_500..78_000)
        val steerState = states.first {
            it.first == steer.single().startMs && CueKind.REACTIVE_STEER in it.second.activeKinds
        }.second
        assertEquals(setOf(Seat.REAR_LEFT), steerState.zones.keys)

        val brake = intervals.filter { it.kind == CueKind.REACTIVE_BRAKE }
        assertEquals(1, brake.size)
        assertTrue("brake cue at ${brake.single().startMs}", brake.single().startMs in 84_000..86_000)
    }

    @Test
    fun `driver seat is never lit and cues never flicker`() {
        val config = CueConfig()
        val (states, intervals) = run(config)
        assertFalse(states.any { (_, s) -> Seat.DRIVER in s.zones })
        intervals.forEach {
            assertTrue("$it shorter than min hold", it.endMs - it.startMs >= config.minHoldMs)
        }
        // 시나리오가 끝나면 모든 구역이 기본색으로 돌아와 있다
        assertTrue(states.last().second.zones.isEmpty())
    }
}
