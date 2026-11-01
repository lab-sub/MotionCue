package app.motioncue.core.scenario

import app.motioncue.core.model.CautionInfo
import app.motioncue.core.model.MotionEvent
import app.motioncue.core.model.TurnInfo
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 시나리오를 틱 단위 이벤트로 펼친다. 결정적(같은 틱 → 같은 이벤트)이라 재생기와 테스트가 같이 쓴다.
 *
 * - 속도·핸들 각도: 매 틱
 * - 브레이크: 상태가 바뀔 때
 * - TBT·주의 지점: 1초마다 + 새로 안내되거나 지나가는 틱 (실제 내비처럼 남은 거리가 줄어든다)
 */
class ScenarioTimeline(val scenario: Scenario) {

    val tickMs: Long = scenario.tickMs
    val durationMs: Long = (scenario.durationSec * 1000).roundToLong()

    /** 마지막 틱 번호 (포함) */
    val lastTick: Int = (durationMs / tickMs).toInt()

    private class Plan<T>(val item: T, val passPositionM: Double, val announceTick: Int, val passTick: Int)

    /** 틱 번호 → 출발점부터 이동 거리(m) */
    private val positionsM = DoubleArray(lastTick + 1)

    private val maneuverPlans: List<Plan<ScenarioManeuver>>
    private val cautionPlans: List<Plan<ScenarioCaution>>

    init {
        for (i in 1..lastTick) {
            val v0 = speedKmhAt(secOf(i - 1)) / 3.6
            val v1 = speedKmhAt(secOf(i)) / 3.6
            positionsM[i] = positionsM[i - 1] + (v0 + v1) / 2.0 * tickMs / 1000.0
        }
        maneuverPlans = scenario.maneuvers.map { plan(it, it.passAtSec, it.announceDistanceM) }
        cautionPlans = scenario.cautions.map { plan(it, it.passAtSec, it.announceDistanceM) }
    }

    fun timeMsOf(tick: Int): Long = tick * tickMs

    fun eventsAt(tick: Int): List<MotionEvent> {
        require(tick in 0..lastTick) { "tick $tick out of 0..$lastTick" }
        val timeMs = timeMsOf(tick)
        val sec = secOf(tick)
        val events = mutableListOf<MotionEvent>()

        events += MotionEvent.Speed(timeMs, speedKmhAt(sec))
        events += MotionEvent.Steering(timeMs, steeringAt(sec))

        val brake = brakeAt(sec)
        if (tick == 0 || brake != brakeAt(secOf(tick - 1))) events += MotionEvent.Brake(timeMs, brake)

        if (isReportTick(tick, maneuverPlans)) {
            val items = maneuverPlans.filter { tick >= it.announceTick && tick < it.passTick }.map {
                TurnInfo(it.item.turnType, remainingM(it, tick), it.item.description)
            }
            events += MotionEvent.Tbt(timeMs, items)
        }
        if (isReportTick(tick, cautionPlans)) {
            val items = cautionPlans.withIndex()
                .filter { (_, p) -> tick >= p.announceTick && tick < p.passTick }
                .map { (index, p) ->
                    CautionInfo(p.item.id.ifBlank { "caution-$index" }, p.item.type, remainingM(p, tick))
                }
            events += MotionEvent.Caution(timeMs, items)
        }
        return events
    }

    fun speedKmhAt(sec: Double): Double = max(0.0, interpolate(scenario.speedKmh, sec))

    fun steeringAt(sec: Double): Double {
        var angle = interpolate(scenario.steeringDeg, sec)
        for (m in scenario.maneuvers) {
            if (m.steerPeakDeg == 0.0 || m.steerDurationSec <= 0.0) continue
            val x = (sec - (m.passAtSec - m.steerDurationSec / 2)) / m.steerDurationSec
            if (x in 0.0..1.0) angle += m.steerPeakDeg * sin(PI * x)
        }
        return angle
    }

    fun brakeAt(sec: Double): Boolean = scenario.brakeIntervalsSec.any { sec >= it[0] && sec < it[1] }

    private fun secOf(tick: Int): Double = tick * tickMs / 1000.0

    private fun <T> plan(item: T, passAtSec: Double, announceDistanceM: Double): Plan<T> {
        val passPosition = positionAtSec(passAtSec)
        val announcePosition = passPosition - announceDistanceM
        val announceTick = (0..lastTick).firstOrNull { positionsM[it] >= announcePosition } ?: lastTick
        val passTick = (0..lastTick).firstOrNull { positionsM[it] >= passPosition } ?: (lastTick + 1)
        return Plan(item, passPosition, announceTick, passTick)
    }

    private fun positionAtSec(sec: Double): Double {
        val exact = (sec * 1000.0 / tickMs).coerceIn(0.0, lastTick.toDouble())
        val i = floor(exact).toInt()
        if (i >= lastTick) return positionsM[lastTick]
        val frac = exact - i
        return positionsM[i] + (positionsM[i + 1] - positionsM[i]) * frac
    }

    private fun remainingM(plan: Plan<*>, tick: Int): Double =
        Math.round(plan.passPositionM - positionsM[tick]).toDouble()

    private fun isReportTick(tick: Int, plans: List<Plan<*>>): Boolean {
        if (plans.isEmpty()) return false
        if (tick == 0) return true
        val crossesSecond = timeMsOf(tick) / REPORT_INTERVAL_MS != timeMsOf(tick - 1) / REPORT_INTERVAL_MS
        return crossesSecond || plans.any { it.announceTick == tick || it.passTick == tick }
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 1_000L

        /** 키프레임 선형 보간. 범위 밖은 양 끝 값, 키프레임이 없으면 0 */
        fun interpolate(keyframes: List<List<Double>>, sec: Double): Double {
            if (keyframes.isEmpty()) return 0.0
            if (sec <= keyframes.first()[0]) return keyframes.first()[1]
            if (sec >= keyframes.last()[0]) return keyframes.last()[1]
            val upper = keyframes.indexOfFirst { it[0] >= sec }
            val (t0, v0) = keyframes[upper - 1]
            val (t1, v1) = keyframes[upper]
            if (t1 <= t0) return v1
            return v0 + (v1 - v0) * (sec - t0) / (t1 - t0)
        }
    }
}
