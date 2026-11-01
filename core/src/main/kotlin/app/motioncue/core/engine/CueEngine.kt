package app.motioncue.core.engine

import app.motioncue.core.model.CautionInfo
import app.motioncue.core.model.MotionEvent
import app.motioncue.core.model.Seat
import app.motioncue.core.model.Side
import app.motioncue.core.model.TurnInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class CueKind(val priority: Int, val label: String) {
    PREDICTIVE_TURN(3, "회전 예고"),
    PREDICTIVE_CAUTION(2, "감속 예고"),
    REACTIVE_STEER(1, "조향 반응"),
    REACTIVE_BRAKE(1, "감속 반응"),
}

/** 한 좌석 구역에 표시할 큐 */
data class ZoneCue(val kind: CueKind, val color: Int, val label: String)

/** 상태 카드용: 다가오는 기동·주의 지점 */
data class Upcoming(
    val label: String,
    val distanceM: Double,
    /** 저속(minSpeedKmh 미만)이면 null */
    val etaSec: Double?,
    val cueActive: Boolean,
)

data class CueState(
    /** 큐가 켜진 좌석만 들어 있다. 없는 좌석은 기본색. 운전석은 절대 들어가지 않는다 */
    val zones: Map<Seat, ZoneCue> = emptyMap(),
    val nextTurn: Upcoming? = null,
    val nextCaution: Upcoming? = null,
    val speedKmh: Double = 0.0,
    val steeringDeg: Double = 0.0,
    val brakePressed: Boolean = false,
    val decelMps2: Double = 0.0,
    val activeKinds: Set<CueKind> = emptySet(),
)

data class CueLog(val timeMs: Long, val message: String)

data class CueOutput(val state: CueState, val logs: List<CueLog>)

/**
 * 차량 움직임 이벤트를 받아 좌석 구역별 앰비언트 큐를 결정한다. 순수 Kotlin, 시간은 이벤트로만 받는다.
 *
 * - 예측 큐: 내비가 알려준 기동·주의 지점의 ETA(남은 거리 ÷ 속도)가 leadTime 이하이면 켠다.
 *   TBT가 1초 간격으로 와도 늦지 않도록 그 사이엔 속도로 남은 거리를 줄여 계산한다.
 * - 반응 큐: 안내에 없는 커브(핸들 각도)·급감속(브레이크)에 반응한다.
 * - 깜빡임 방지: 최소 유지 시간, 재점등 최소 간격, 히스테리시스.
 * - 운전석 구역은 어떤 경우에도 출력하지 않는다.
 */
class CueEngine(config: CueConfig = CueConfig()) {

    var config: CueConfig = config
        private set

    private var speedMps = 0.0
    // 기존 순수 엔진의 초기 정차 상태는 유지하되, 명시적 데이터 만료 후에는 새 측정이 필요하다.
    private var speedValid = true
    private var lastSpeedTimeMs: Long? = null
    private var decelMps2 = 0.0
    private var steeringDeg = 0.0
    private var brakePressed = false

    private val lastEndMs = mutableMapOf<CueKind, Long>()
    private var approachSeq = 0
    private var reactiveSteer: Reactive? = null
    private var reactiveBrake: Reactive? = null

    private val turns = PredictiveChannel<TurnClass>(
        kind = CueKind.PREDICTIVE_TURN,
        seatsFor = { seatsOn(it.side) },
        colorFor = { CuePalette.turn(it.strength) },
        cueLabel = { "${it.label} 예고" },
        isSettled = { cue, now ->
            val passedAt = cue.passedAtMs
            when {
                cue.cancelled -> true
                passedAt == null -> false
                cue.steerEngaged && abs(steeringDeg) < config.steerCenterDeg -> true
                else -> now - passedAt >= config.turnSettleMs
            }
        },
    )

    private val cautions = PredictiveChannel<CautionClass>(
        kind = CueKind.PREDICTIVE_CAUTION,
        seatsFor = { config.passengerTargets },
        colorFor = { CuePalette.decel(it.strength) },
        cueLabel = { "${it.label} 예고" },
        isSettled = { cue, now ->
            cue.cancelled || (cue.passedAtMs?.let { now - it >= config.cautionSettleMs } ?: false)
        },
    )

    fun updateConfig(newConfig: CueConfig) {
        config = newConfig
    }

    fun reset() {
        speedMps = 0.0
        speedValid = true
        lastSpeedTimeMs = null
        decelMps2 = 0.0
        steeringDeg = 0.0
        brakePressed = false
        lastEndMs.clear()
        reactiveSteer = null
        reactiveBrake = null
        turns.reset()
        cautions.reset()
    }

    fun process(event: MotionEvent): CueOutput {
        val now = event.timeMs
        val logs = mutableListOf<CueLog>()
        // 이번 입력 직전까지의 이동은 이전에 알던 속도로 누적한다.
        // 새 속도를 과거 보고 시각까지 소급하면 감속 시 남은 거리가 다시 늘어난다.
        turns.advanceDistance(now)
        cautions.advanceDistance(now)
        when (event) {
            is MotionEvent.Speed -> onSpeed(event.measurementTimeMs, event.kmh, event.valid)
            is MotionEvent.Steering -> steeringDeg = event.angleDeg
            is MotionEvent.Brake -> brakePressed = event.pressed
            is MotionEvent.Tbt -> turns.onCandidate(nextTurnCandidate(event.items), now, logs)
            is MotionEvent.Caution -> cautions.onCandidate(nearestCaution(event.items), now, logs)
            is MotionEvent.Tick -> Unit
        }
        turns.update(now, logs)
        cautions.update(now, logs)
        updateReactiveSteer(now, logs)
        updateReactiveBrake(now, logs)

        val activeKinds = buildSet {
            if (turns.active != null) add(CueKind.PREDICTIVE_TURN)
            if (cautions.active != null) add(CueKind.PREDICTIVE_CAUTION)
            if (reactiveSteer != null) add(CueKind.REACTIVE_STEER)
            if (reactiveBrake != null) add(CueKind.REACTIVE_BRAKE)
        }
        val state = CueState(
            zones = composeZones(),
            nextTurn = turns.upcoming(now),
            nextCaution = cautions.upcoming(now),
            speedKmh = speedMps * 3.6,
            steeringDeg = steeringDeg,
            brakePressed = brakePressed,
            decelMps2 = decelMps2,
            activeKinds = activeKinds,
        )
        return CueOutput(state, logs)
    }

    // ---- 입력 처리 ----

    private fun onSpeed(measurementTimeMs: Long, kmh: Double, valid: Boolean) {
        if (!valid || !kmh.isFinite()) {
            speedValid = false
            speedMps = 0.0
            lastSpeedTimeMs = null
            decelMps2 = 0.0
            return
        }
        val prevTime = lastSpeedTimeMs
        if (prevTime != null && measurementTimeMs <= prevTime) {
            // 중복·역순 측정은 최신 속도를 덮어쓰거나 새 감속으로 해석하지 않는다.
            decelMps2 = 0.0
            return
        }
        speedValid = true
        val mps = max(0.0, kmh) / 3.6
        if (prevTime != null) {
            val dtSec = (measurementTimeMs - prevTime) / 1000.0
            if (dtSec > MAX_SPEED_GAP_SEC) {
                decelMps2 = 0.0
            } else if (dtSec > 0.0) {
                val instant = (speedMps - mps) / dtSec
                val alpha = min(1.0, dtSec / DECEL_SMOOTHING_SEC)
                decelMps2 += alpha * (instant - decelMps2)
            }
        }
        lastSpeedTimeMs = measurementTimeMs
        speedMps = mps
    }

    private fun nextTurnCandidate(items: List<TurnInfo>): Candidate<TurnClass>? {
        val item = nextRouteInstruction(items) ?: return null
        return TurnClassifier.classify(item.turnType)?.let { cls ->
            Candidate("${item.turnType}|${item.description}", cls, cls.label, item.distanceM)
        }
    }

    private fun nearestCaution(items: List<CautionInfo>): Candidate<CautionClass>? =
        items.mapNotNull { item ->
            CautionClassifier.classify(item.type)?.let { cls ->
                Candidate("${item.type}|${item.id}", cls, cls.label, item.remainDistanceM)
            }
        }
            .filter { it.distanceM >= 0.0 }
            .minByOrNull { it.distanceM }

    // ---- 반응 큐 ----

    private fun updateReactiveSteer(now: Long, logs: MutableList<CueLog>) {
        val onDeg = config.sensitivity.steerOnDeg
        val current = reactiveSteer
        // 늦게 도착한 예측 안내도 이미 켜진 반응 큐보다 우선한다.
        if (turns.active != null) {
            if (current != null) {
                reactiveSteer = null
                lastEndMs[CueKind.REACTIVE_STEER] = now
                logs += CueLog(now, "조향 반응 종료 → 회전 예고로 전환")
            }
            return
        }
        if (current == null) {
            if (abs(steeringDeg) >= onDeg && gapElapsed(CueKind.REACTIVE_STEER, now)) {
                val side = if (steeringDeg < 0) Side.LEFT else Side.RIGHT // 왼쪽이 음수
                reactiveSteer = Reactive(side, now)
                logs += CueLog(now, "조향 반응 시작: 핸들 ${fmt0(steeringDeg)}° → ${seatList(seatsOn(side))}")
            }
            return
        }
        val side = if (steeringDeg < 0) Side.LEFT else Side.RIGHT
        if (steeringDeg != 0.0 && side != current.side) {
            if (abs(steeringDeg) >= onDeg) {
                // 샘플 사이에 중앙을 지나쳤어도 현재 방향을 표시한다.
                // 채널은 계속 켜져 있으므로 최초 시작 시각과 최소 유지 시간은 유지한다.
                reactiveSteer = Reactive(side, current.startMs)
                logs += CueLog(now, "조향 반응 방향 변경: 핸들 ${fmt0(steeringDeg)}° → ${seatList(seatsOn(side))}")
            } else {
                // 반대쪽은 새 큐 임계값 미만이다. 잘못된 방향을 최소 유지 시간 때문에 남기지 않는다.
                reactiveSteer = null
                lastEndMs[CueKind.REACTIVE_STEER] = now
                logs += CueLog(now, "조향 반응 종료: 방향 반전 → 기본색 복귀")
            }
            return
        }
        val released = abs(steeringDeg) < onDeg * config.releaseRatio
        if (released && now - current.startMs >= config.minHoldMs) {
            reactiveSteer = null
            lastEndMs[CueKind.REACTIVE_STEER] = now
            logs += CueLog(now, "조향 반응 종료 → 기본색 복귀")
        }
    }

    private fun updateReactiveBrake(now: Long, logs: MutableList<CueLog>) {
        val onDecel = config.sensitivity.decelOnMps2
        val current = reactiveBrake
        if (current == null) {
            if (brakePressed && decelMps2 >= onDecel && gapElapsed(CueKind.REACTIVE_BRAKE, now)) {
                reactiveBrake = Reactive(null, now)
                logs += CueLog(now, "감속 반응 시작: 브레이크 · ${fmt1(decelMps2)} m/s² → ${seatList(config.passengerTargets)}")
            }
            return
        }
        val released = !brakePressed || decelMps2 < onDecel * config.releaseRatio
        if (released && now - current.startMs >= config.minHoldMs) {
            reactiveBrake = null
            lastEndMs[CueKind.REACTIVE_BRAKE] = now
            logs += CueLog(now, "감속 반응 종료 → 기본색 복귀")
        }
    }

    // ---- 구역 합성 ----

    private fun composeZones(): Map<Seat, ZoneCue> {
        val candidates = buildList {
            addAll(turns.zoneCues())
            addAll(cautions.zoneCues())
            reactiveSteer?.side?.let { side ->
                val cue = ZoneCue(CueKind.REACTIVE_STEER, CuePalette.turn(Strength.SOFT), "조향 반응")
                seatsOn(side).forEach { add(it to cue) }
            }
            if (reactiveBrake != null) {
                val cue = ZoneCue(CueKind.REACTIVE_BRAKE, CuePalette.decel(Strength.NORMAL), "감속 반응")
                config.passengerTargets.forEach { add(it to cue) }
            }
        }
        val zones = LinkedHashMap<Seat, ZoneCue>()
        for ((seat, cue) in candidates) {
            if (seat.isDriver || seat !in config.targetSeats) continue // 운전석은 절대 포함하지 않는다
            val existing = zones[seat]
            if (existing == null || cue.kind.priority > existing.kind.priority) zones[seat] = cue
        }
        return zones
    }

    private fun seatsOn(side: Side): List<Seat> = config.passengerTargets.filter { it.side == side }

    private fun gapElapsed(kind: CueKind, now: Long): Boolean =
        lastEndMs[kind]?.let { now - it >= config.minGapMs } ?: true

    private fun seatList(seats: List<Seat>): String =
        if (seats.isEmpty()) "대상 좌석 없음(화면 표시만)" else seats.joinToString(", ") { it.label }

    // ---- 예측 큐 채널 (회전 / 주의 지점) ----

    private data class Candidate<T>(val baseKey: String, val info: T, val label: String, val distanceM: Double)

    private class Approach<T>(
        val id: String,
        val baseKey: String,
        val info: T,
        val label: String,
        var distanceM: Double,
        var estimatedAtMs: Long,
    )

    private class ActiveCue(val approachId: String, val label: String, val startMs: Long) {
        var passedAtMs: Long? = null
        var cancelled = false
        var steerEngaged = false
    }

    private class Reactive(val side: Side?, val startMs: Long)

    private inner class PredictiveChannel<T>(
        private val kind: CueKind,
        private val seatsFor: (T) -> List<Seat>,
        private val colorFor: (T) -> Int,
        private val cueLabel: (T) -> String,
        private val isSettled: (ActiveCue, Long) -> Boolean,
    ) {
        private var approach: Approach<T>? = null
        var active: ActiveCue? = null
            private set
        private var activeInfo: T? = null
        private val finished = ArrayDeque<String>()

        fun reset() {
            approach = null
            active = null
            activeInfo = null
            finished.clear()
        }

        /** 마지막 적분 시각부터 아직 반영하지 않은 이동 거리를 뺀다. */
        private fun distanceAt(a: Approach<T>, now: Long): Double {
            val elapsedSec = (now - a.estimatedAtMs).coerceAtLeast(0L) / 1000.0
            return a.distanceM - speedMps * elapsedSec
        }

        fun advanceDistance(now: Long) {
            val a = approach ?: return
            a.distanceM = distanceAt(a, now)
            a.estimatedAtMs = now
        }

        private fun etaFor(distanceM: Double): Double? =
            if (speedMps * 3.6 >= config.minSpeedKmh) max(0.0, distanceM) / speedMps else null

        fun onCandidate(candidate: Candidate<T>?, now: Long, logs: MutableList<CueLog>) {
            val current = approach
            if (candidate == null) {
                if (current != null) close(current, now, logs)
                approach = null
                return
            }
            val sameApproach = current != null &&
                current.baseKey == candidate.baseKey &&
                candidate.distanceM <= distanceAt(current, now) + NEW_APPROACH_JUMP_M
            if (sameApproach) {
                current!!.distanceM = candidate.distanceM
                current.estimatedAtMs = now
                return
            }
            if (current != null) close(current, now, logs)
            approach = Approach(
                id = "${candidate.baseKey}#${++approachSeq}",
                baseKey = candidate.baseKey,
                info = candidate.info,
                label = candidate.label,
                distanceM = candidate.distanceM,
                estimatedAtMs = now,
            )
            logs += CueLog(now, "안내 수신: ${candidate.label} ${fmt0(candidate.distanceM)}m 앞")
        }

        /** 추적하던 지점이 목록에서 빠지거나 다른 지점으로 바뀌었을 때 */
        private fun close(old: Approach<T>, now: Long, logs: MutableList<CueLog>) {
            val cue = active ?: return
            if (cue.approachId != old.id || cue.passedAtMs != null) return
            if (distanceAt(old, now) <= PASS_TOLERANCE_M) {
                cue.passedAtMs = now
                logs += CueLog(now, "${old.label} 지점 통과")
            } else {
                cue.cancelled = true
                logs += CueLog(now, "${old.label} 안내가 사라짐(경로 변경) → 예고 취소")
            }
        }

        fun update(now: Long, logs: MutableList<CueLog>) {
            val cue = active
            val a = approach
            if (cue == null) {
                if (!speedValid || a == null || a.id in finished || !gapElapsed(kind, now)) return
                val distance = distanceAt(a, now)
                val eta = etaFor(distance)
                val due = if (eta == null) distance <= config.nearDistanceM else eta <= config.leadTimeSec
                if (!due) return
                active = ActiveCue(a.id, a.label, now)
                activeInfo = a.info
                val etaText = eta?.let { "ETA ${fmt1(it)}초" } ?: "저속 근접"
                logs += CueLog(
                    now,
                    "${kind.label} 시작: ${a.label} · ${fmt0(max(0.0, distance))}m · $etaText → ${seatList(seatsFor(a.info))}",
                )
                return
            }
            if (cue.passedAtMs == null && a != null && a.id == cue.approachId && distanceAt(a, now) <= 0.0) {
                cue.passedAtMs = now
                logs += CueLog(now, "${cue.label} 지점 통과")
            }
            if (abs(steeringDeg) >= config.steerCenterDeg) cue.steerEngaged = true
            if (isSettled(cue, now) && now - cue.startMs >= config.minHoldMs) {
                finished.addLast(cue.approachId)
                if (finished.size > MAX_FINISHED) finished.removeFirst()
                active = null
                activeInfo = null
                lastEndMs[kind] = now
                logs += CueLog(now, "${kind.label} 종료: ${cue.label} → 기본색 복귀")
            }
        }

        fun zoneCues(): List<Pair<Seat, ZoneCue>> {
            val info = activeInfo ?: return emptyList()
            val cue = ZoneCue(kind, colorFor(info), cueLabel(info))
            return seatsFor(info).map { it to cue }
        }

        fun upcoming(now: Long): Upcoming? {
            val a = approach ?: return null
            val distance = max(0.0, distanceAt(a, now))
            return Upcoming(a.label, distance, etaFor(distance), cueActive = active?.approachId == a.id)
        }
    }

    private companion object {
        /** 같은 기동인데 남은 거리가 이만큼 늘면 새 기동으로 본다 */
        const val NEW_APPROACH_JUMP_M = 50.0
        /** 안내가 사라질 때 이 거리 안이었다면 통과로 본다 (밖이면 경로 변경) */
        const val PASS_TOLERANCE_M = 30.0
        const val DECEL_SMOOTHING_SEC = 0.5
        const val MAX_SPEED_GAP_SEC = 5.0
        const val MAX_FINISHED = 32

        fun fmt0(v: Double): String = String.format(Locale.ROOT, "%.0f", v)
        fun fmt1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    }
}
