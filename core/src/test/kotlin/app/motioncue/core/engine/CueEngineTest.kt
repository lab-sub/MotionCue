package app.motioncue.core.engine

import app.motioncue.core.model.CautionInfo
import app.motioncue.core.model.MotionEvent
import app.motioncue.core.model.Seat
import app.motioncue.core.model.TurnInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class CueEngineTest {

    /** 한 틱의 결과 */
    private data class Frame(val timeMs: Long, val state: CueState, val logs: List<CueLog>)

    /**
     * 등속으로 기동에 접근한다. 속도·조향은 100ms마다, TBT는 1초마다 (남은 거리는 1m 단위 반올림).
     * 남은 거리가 0 이하가 되면 TBT 목록에서 빠진다 (실제 내비처럼).
     */
    private fun approach(
        engine: CueEngine,
        turnType: String,
        startDistanceM: Double,
        speedKmh: Double,
        untilMs: Long,
        steering: (Long) -> Double = { 0.0 },
        tbtOverride: ((Long, Double) -> List<TurnInfo>?)? = null,
    ): List<Frame> {
        val mps = speedKmh / 3.6
        val frames = mutableListOf<Frame>()
        var t = 0L
        while (t <= untilMs) {
            val logs = mutableListOf<CueLog>()
            val remaining = startDistanceM - mps * t / 1000.0
            if (t % 1000 == 0L) {
                val items = tbtOverride?.invoke(t, remaining)
                    ?: if (remaining > 0) listOf(TurnInfo(turnType, remaining.roundToInt().toDouble())) else emptyList()
                logs += engine.process(MotionEvent.Tbt(t, items)).logs
            }
            logs += engine.process(MotionEvent.Speed(t, speedKmh)).logs
            val out = engine.process(MotionEvent.Steering(t, steering(t)))
            logs += out.logs
            frames += Frame(t, out.state, logs)
            t += 100
        }
        return frames
    }

    private fun firstOn(frames: List<Frame>, kind: CueKind): Long? =
        frames.firstOrNull { kind in it.state.activeKinds }?.timeMs

    private fun firstOffAfter(frames: List<Frame>, kind: CueKind, afterMs: Long): Long? =
        frames.firstOrNull { it.timeMs > afterMs && kind !in it.state.activeKinds }?.timeMs

    /** 큐가 켜졌다 꺼진 횟수 (깜빡임 검사용) */
    private fun onCount(frames: List<Frame>, kind: CueKind): Int {
        var count = 0
        var prev = false
        for (f in frames) {
            val on = kind in f.state.activeKinds
            if (on && !prev) count++
            prev = on
        }
        return count
    }

    // ---- 예측 회전 큐 ----

    @Test
    fun `turn cue starts leadTime before the maneuver`() {
        // 36 km/h = 10 m/s, 100m 앞 → 통과 10.0초. leadTime 2.5초 → 7.5초에 시작
        val frames = approach(CueEngine(), "TURN_LEFT", 100.0, 36.0, untilMs = 12_000)
        val start = firstOn(frames, CueKind.PREDICTIVE_TURN)
        assertNotNull(start)
        assertTrue("started at $start", start!! in 7_400..7_600)
    }

    @Test
    fun `lead time setting changes trigger time`() {
        val frames = approach(CueEngine(CueConfig(leadTimeSec = 4.0)), "TURN_LEFT", 100.0, 36.0, untilMs = 12_000)
        val start = firstOn(frames, CueKind.PREDICTIVE_TURN)
        assertTrue("started at $start", start!! in 5_900..6_100)
    }

    @Test
    fun `no cue while the maneuver is far away`() {
        // 50 km/h로 300m 앞: 21.6초 남음
        val frames = approach(CueEngine(), "TURN_LEFT", 300.0, 50.0, untilMs = 15_000)
        assertNull(firstOn(frames, CueKind.PREDICTIVE_TURN))
        assertTrue(frames.all { it.state.zones.isEmpty() })
    }

    @Test
    fun `left turn lights only the rear left seat`() {
        val frames = approach(CueEngine(), "TURN_LEFT", 100.0, 36.0, untilMs = 9_000)
        val zones = frames.last().state.zones
        assertEquals(setOf(Seat.REAR_LEFT), zones.keys)
        assertEquals(CueKind.PREDICTIVE_TURN, zones.getValue(Seat.REAR_LEFT).kind)
        assertEquals(CuePalette.turn(Strength.NORMAL), zones.getValue(Seat.REAR_LEFT).color)
    }

    @Test
    fun `right turn lights front passenger and rear right`() {
        val frames = approach(CueEngine(), "TURN_SHARP_R", 100.0, 36.0, untilMs = 9_000)
        val zones = frames.last().state.zones
        assertEquals(setOf(Seat.FRONT_PASSENGER, Seat.REAR_RIGHT), zones.keys)
        assertEquals(CuePalette.turn(Strength.STRONG), zones.getValue(Seat.FRONT_PASSENGER).color)
    }

    @Test
    fun `straight or unknown maneuvers never cue`() {
        val frames = approach(CueEngine(), "TURN_STRAIGHT", 50.0, 36.0, untilMs = 8_000)
        assertTrue(frames.all { it.state.zones.isEmpty() })
        assertNull(frames.last().state.nextTurn)
    }

    @Test
    fun `actual Pleos route order wins over smaller distances on later legs`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        val out = engine.process(
            MotionEvent.Tbt(
                0,
                listOf(
                    TurnInfo("TURN_LEFT", 80.0),
                    TurnInfo("TURN_RIGHT", 380.0),
                    TurnInfo("TURN_AVOID_UNDER_R", 730.0),
                    TurnInfo("TURN_RIGHT", 820.0),
                    TurnInfo("TURN_RIGHT", 200.0),
                    TurnInfo("TURN_RIGHT", 50.0),
                    TurnInfo("TURN_ARRIVE", 10.0),
                ),
            ),
        )
        assertEquals("좌회전", out.state.nextTurn?.label)
        assertEquals(80.0, out.state.nextTurn!!.distanceM, 0.01)
        assertEquals(8.0, out.state.nextTurn!!.etaSec!!, 0.01)
    }

    @Test
    fun `unsupported route head does not expose a later leg as the next turn`() {
        for (type in listOf("TURN_STRAIGHT", "UNKNOWN", "TURN_ARRIVE")) {
            val engine = CueEngine()
            engine.process(MotionEvent.Speed(0, 36.0))
            val out = engine.process(MotionEvent.Tbt(0, listOf(
                TurnInfo(type, 80.0), TurnInfo("TURN_RIGHT", 10.0),
            )))
            assertNull(out.state.nextTurn)
            assertTrue(out.state.zones.isEmpty())
        }
    }

    @Test
    fun `invalid first route distance is not replaced by a later valid instruction`() {
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val engine = CueEngine()
            engine.process(MotionEvent.Speed(0, 36.0))
            val out = engine.process(MotionEvent.Tbt(0, listOf(
                TurnInfo("TURN_LEFT", distance), TurnInfo("TURN_RIGHT", 10.0),
            )))
            assertNull(out.state.nextTurn)
            assertTrue(out.state.zones.isEmpty())
        }
    }

    @Test
    fun `distance is dead-reckoned between TBT updates`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 100.0))))
        val out = engine.process(MotionEvent.Tick(500))
        assertEquals(95.0, out.state.nextTurn!!.distanceM, 0.01)
    }

    @Test
    fun `changed speed affects future travel without rewriting past distance`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 100.0))))
        engine.process(MotionEvent.Caution(0, listOf(CautionInfo("bump", "SPEED_BUMP", 100.0))))
        assertEquals(95.0, engine.process(MotionEvent.Tick(500)).state.nextTurn!!.distanceM, 1e-9)

        // 첫 1초는 10m/s, 이후에는 20m/s. ETA만 새 속도로 계산한다.
        val accelerated = engine.process(MotionEvent.Speed(1_000, 72.0)).state
        assertEquals(90.0, accelerated.nextTurn!!.distanceM, 1e-9)
        assertEquals(90.0, accelerated.nextCaution!!.distanceM, 1e-9)
        assertEquals(4.5, accelerated.nextTurn!!.etaSec!!, 1e-9)
        val later = engine.process(MotionEvent.Tick(1_500)).state
        assertEquals(80.0, later.nextTurn!!.distanceM, 1e-9)
        assertEquals(80.0, later.nextCaution!!.distanceM, 1e-9)
    }

    @Test
    fun `stopping preserves distance already traveled`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 100.0))))
        val stopped = engine.process(MotionEvent.Speed(1_000, 0.0)).state
        assertEquals(90.0, stopped.nextTurn!!.distanceM, 1e-9)
        assertNull(stopped.nextTurn!!.etaSec)
        assertEquals(90.0, engine.process(MotionEvent.Tick(3_000)).state.nextTurn!!.distanceM, 1e-9)
    }

    @Test
    fun `speed loss freezes remaining distance until a fresh measurement arrives`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 100.0))))
        val lost = engine.process(MotionEvent.Speed(1_000, 0.0, valid = false)).state
        assertEquals(90.0, lost.nextTurn!!.distanceM, 1e-9)
        assertEquals(90.0, engine.process(MotionEvent.Tick(2_000)).state.nextTurn!!.distanceM, 1e-9)
        val recovered = engine.process(MotionEvent.Speed(3_000, 72.0)).state
        assertEquals(90.0, recovered.nextTurn!!.distanceM, 1e-9)
        assertEquals(80.0, engine.process(MotionEvent.Tick(3_500)).state.nextTurn!!.distanceM, 1e-9)
    }

    @Test
    fun `new navigation report replaces estimated remaining distance`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 100.0))))
        engine.process(MotionEvent.Caution(0, listOf(CautionInfo("bump", "SPEED_BUMP", 100.0))))
        engine.process(MotionEvent.Speed(1_000, 72.0))
        engine.process(MotionEvent.Tbt(1_500, listOf(TurnInfo("TURN_LEFT", 75.0))))
        val reported = engine.process(MotionEvent.Caution(1_500, listOf(CautionInfo("bump", "SPEED_BUMP", 75.0)))).state
        assertEquals(75.0, reported.nextTurn!!.distanceM, 1e-9)
        assertEquals(75.0, reported.nextCaution!!.distanceM, 1e-9)
        val later = engine.process(MotionEvent.Tick(2_000)).state
        assertEquals(65.0, later.nextTurn!!.distanceM, 1e-9)
        assertEquals(65.0, later.nextCaution!!.distanceM, 1e-9)
    }

    @Test
    fun `low speed uses the near distance instead of ETA`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 3.0))
        val far = engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 20.0))))
        assertTrue(far.state.zones.isEmpty())
        assertNull(far.state.nextTurn!!.etaSec)
        val near = engine.process(MotionEvent.Tbt(1_000, listOf(TurnInfo("TURN_LEFT", 14.0))))
        assertEquals(setOf(Seat.REAR_LEFT), near.state.zones.keys)
    }

    @Test
    fun `stopped far from the maneuver never triggers`() {
        val frames = approach(CueEngine(), "TURN_LEFT", 100.0, 0.0, untilMs = 10_000)
        assertNull(firstOn(frames, CueKind.PREDICTIVE_TURN))
    }

    @Test
    fun `near distance does not override lead time when speed supports ETA`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 10.0))
        // 10 km/h로 14m 앞이면 ETA 5.04초: 저속 대체 거리(15m)만으로 일찍 켜면 안 된다.
        val farEta = engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 14.0))))
        assertTrue(farEta.state.zones.isEmpty())
        assertEquals(5.04, farEta.state.nextTurn!!.etaSec!!, 1e-9)
        val due = engine.process(MotionEvent.Tick(2_600))
        assertEquals(setOf(Seat.REAR_LEFT), due.state.zones.keys)
    }

    @Test
    fun `unknown speed cannot start near cues but a valid stopped sample can`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Speed(100, 0.0, valid = false))
        engine.process(MotionEvent.Tbt(200, listOf(TurnInfo("TURN_LEFT", 14.0))))
        val unknown = engine.process(MotionEvent.Caution(200, listOf(CautionInfo("bump", "SPEED_BUMP", 14.0))))
        assertTrue(unknown.state.zones.isEmpty())
        assertTrue(unknown.state.activeKinds.isEmpty())
        assertTrue(engine.process(MotionEvent.Tick(300)).state.zones.isEmpty())
        val stopped = engine.process(MotionEvent.Speed(400, 0.0))
        assertEquals(setOf(CueKind.PREDICTIVE_TURN, CueKind.PREDICTIVE_CAUTION), stopped.state.activeKinds)
        assertEquals(Seat.PASSENGERS.toSet(), stopped.state.zones.keys)
    }

    @Test
    fun `ETA jitter around the threshold does not flicker`() {
        // 보고 거리가 ±8m 흔들려 ETA가 임계값을 오르내려도 한 번 켜지면 통과까지 유지
        val frames = approach(
            CueEngine(), "TURN_LEFT", 100.0, 36.0, untilMs = 16_000,
            tbtOverride = { t, remaining ->
                val noisy = remaining + if ((t / 1000) % 2 == 0L) 8.0 else -8.0
                if (remaining > 0) listOf(TurnInfo("TURN_LEFT", noisy.roundToInt().toDouble())) else emptyList()
            },
        )
        assertEquals(1, onCount(frames, CueKind.PREDICTIVE_TURN))
    }

    @Test
    fun `turn cue ends when the steering returns to center`() {
        // 10초에 통과, 핸들은 8~12초 동안 -250°까지 돌렸다가 푼다
        val steering = { t: Long ->
            val x = (t - 8_000) / 4_000.0
            if (x in 0.0..1.0) -250.0 * sin(PI * x) else 0.0
        }
        val frames = approach(CueEngine(), "TURN_LEFT", 100.0, 36.0, untilMs = 16_000, steering = steering)
        val start = firstOn(frames, CueKind.PREDICTIVE_TURN)!!
        val end = firstOffAfter(frames, CueKind.PREDICTIVE_TURN, start)!!
        // |핸들| < 40°가 되는 시각 ≈ 11.8초
        assertTrue("ended at $end", end in 11_700..12_000)
        assertTrue(frames.last().state.zones.isEmpty())
    }

    @Test
    fun `turn cue ends after the settle timeout without steering data`() {
        val frames = approach(CueEngine(), "TURN_LEFT", 100.0, 36.0, untilMs = 16_000)
        val start = firstOn(frames, CueKind.PREDICTIVE_TURN)!!
        val end = firstOffAfter(frames, CueKind.PREDICTIVE_TURN, start)!!
        // 통과(10초) + 4초
        assertTrue("ended at $end", end in 13_900..14_200)
    }

    @Test
    fun `cue stays on at least the minimum hold time`() {
        // 아주 가까운 기동을 0.5초 만에 지나고 핸들도 바로 풀어도 1.2초는 유지
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 5.0))))
        engine.process(MotionEvent.Steering(200, -100.0))
        engine.process(MotionEvent.Tbt(500, emptyList()))
        val early = engine.process(MotionEvent.Steering(600, 0.0))
        assertTrue(CueKind.PREDICTIVE_TURN in early.state.activeKinds)
        val later = engine.process(MotionEvent.Tick(1_200))
        assertFalse(CueKind.PREDICTIVE_TURN in later.state.activeKinds)
    }

    @Test
    fun `reroute cancels an active cue`() {
        val engine = CueEngine(CueConfig(leadTimeSec = 6.0))
        // 10 m/s, 100m → 4초에 60m 남아 큐 시작. 5초에 다른 기동으로 바뀜
        val frames = approach(
            engine, "TURN_LEFT", 100.0, 36.0, untilMs = 8_000,
            tbtOverride = { t, remaining ->
                if (t < 5_000) listOf(TurnInfo("TURN_LEFT", remaining.roundToInt().toDouble()))
                else listOf(TurnInfo("TURN_RIGHT", 300.0))
            },
        )
        val start = firstOn(frames, CueKind.PREDICTIVE_TURN)!!
        val end = firstOffAfter(frames, CueKind.PREDICTIVE_TURN, start)!!
        assertTrue("ended at $end", end in 5_000..5_300)
        assertTrue(frames.any { f -> f.logs.any { "경로 변경" in it.message } })
        assertTrue(frames.last().state.zones.isEmpty())
    }

    @Test
    fun `finished maneuver does not retrigger while still listed`() {
        val engine = CueEngine()
        val frames = approach(
            engine, "TURN_LEFT", 100.0, 36.0, untilMs = 20_000,
            // 통과 후에도 내비가 2m 남았다고 계속 보고하는 경우
            tbtOverride = { _, remaining ->
                listOf(TurnInfo("TURN_LEFT", maxOf(2.0, remaining.roundToInt().toDouble())))
            },
        )
        assertEquals(1, onCount(frames, CueKind.PREDICTIVE_TURN))
        assertTrue(frames.last().state.zones.isEmpty())
    }

    // ---- 주의 지점 ----

    @Test
    fun `speed bump lights all passenger seats amber`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        val out = engine.process(MotionEvent.Caution(0, listOf(CautionInfo("b1", "SPEED_BUMP", 20.0))))
        assertEquals(Seat.PASSENGERS.toSet(), out.state.zones.keys)
        assertTrue(out.state.zones.values.all { it.color == CuePalette.decel(Strength.STRONG) })
        assertEquals("과속방지턱", out.state.nextCaution?.label)
    }

    @Test
    fun `unused caution types are ignored`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        val out = engine.process(MotionEvent.Caution(0, listOf(CautionInfo("s1", "SCHOOL_ZONE", 10.0))))
        assertTrue(out.state.zones.isEmpty())
        assertNull(out.state.nextCaution)
    }

    @Test
    fun `turn has priority on its side while caution covers the rest`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Caution(0, listOf(CautionInfo("b1", "SPEED_BUMP", 20.0))))
        val out = engine.process(MotionEvent.Tbt(100, listOf(TurnInfo("TURN_LEFT", 20.0))))
        val zones = out.state.zones
        assertEquals(CueKind.PREDICTIVE_TURN, zones.getValue(Seat.REAR_LEFT).kind)
        assertEquals(CueKind.PREDICTIVE_CAUTION, zones.getValue(Seat.FRONT_PASSENGER).kind)
        assertEquals(CueKind.PREDICTIVE_CAUTION, zones.getValue(Seat.REAR_RIGHT).kind)
    }

    // ---- 반응 큐 ----

    @Test
    fun `unannounced curve triggers a reactive cue on the steering side`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 40.0))
        val out = engine.process(MotionEvent.Steering(100, -100.0))
        assertEquals(setOf(Seat.REAR_LEFT), out.state.zones.keys)
        assertEquals(CueKind.REACTIVE_STEER, out.state.zones.getValue(Seat.REAR_LEFT).kind)

        val right = CueEngine().process(MotionEvent.Steering(100, 100.0))
        assertEquals(setOf(Seat.FRONT_PASSENGER, Seat.REAR_RIGHT), right.state.zones.keys)
    }

    @Test
    fun `steering hysteresis keeps the cue on between thresholds`() {
        val engine = CueEngine() // 보통: 80°에서 켜지고 48° 아래에서 꺼짐
        var t = 0L
        var count = 0
        var prev = false
        repeat(40) { i ->
            val angle = if (i == 0) -85.0 else if (i % 2 == 0) -75.0 else -55.0
            val on = CueKind.REACTIVE_STEER in engine.process(MotionEvent.Steering(t, angle)).state.activeKinds
            if (on && !prev) count++
            prev = on
            t += 100
        }
        assertEquals(1, count)
        assertTrue(prev)
    }

    @Test
    fun `reactive steering is suppressed during a predictive turn`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Tbt(0, listOf(TurnInfo("TURN_RIGHT", 20.0))))
        val out = engine.process(MotionEvent.Steering(100, -150.0))
        assertFalse(CueKind.REACTIVE_STEER in out.state.activeKinds)
        assertEquals(setOf(Seat.FRONT_PASSENGER, Seat.REAR_RIGHT), out.state.zones.keys)
    }

    @Test
    fun `steering reversal retargets the active cue without a stale side`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Steering(0, -100.0))
        // 중앙 근처 샘플이 누락돼도 반대 방향의 현재 조향을 표시한다.
        val reversed = engine.process(MotionEvent.Steering(100, 100.0))
        assertEquals(setOf(Seat.FRONT_PASSENGER, Seat.REAR_RIGHT), reversed.state.zones.keys)
        assertEquals(setOf(CueKind.REACTIVE_STEER), reversed.state.activeKinds)
        val stillHeld = engine.process(MotionEvent.Steering(200, 0.0))
        assertTrue(CueKind.REACTIVE_STEER in stillHeld.state.activeKinds)
        // 방향 변경은 새 점등이 아니므로 최초 시작 시각부터 최소 유지 시간을 센다.
        assertTrue(engine.process(MotionEvent.Tick(1_200)).state.zones.isEmpty())
    }

    @Test
    fun `weak opposite steering clears the incorrect direction before minimum hold`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Steering(0, -100.0))
        val out = engine.process(MotionEvent.Steering(100, 60.0))
        assertTrue(out.state.zones.isEmpty())
        assertFalse(CueKind.REACTIVE_STEER in out.state.activeKinds)
    }

    @Test
    fun `late predictive turn replaces an existing opposite reactive cue`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Steering(0, -100.0))
        val out = engine.process(MotionEvent.Tbt(100, listOf(TurnInfo("TURN_RIGHT", 10.0))))
        assertEquals(setOf(Seat.FRONT_PASSENGER, Seat.REAR_RIGHT), out.state.zones.keys)
        assertEquals(setOf(CueKind.PREDICTIVE_TURN), out.state.activeKinds)
        assertTrue(out.state.zones.values.all { it.kind == CueKind.PREDICTIVE_TURN })
    }

    @Test
    fun `sensitivity changes the steering threshold`() {
        val angle = -60.0
        assertTrue(CueEngine(CueConfig(sensitivity = Sensitivity.NORMAL)).process(MotionEvent.Steering(0, angle)).state.zones.isEmpty())
        assertFalse(CueEngine(CueConfig(sensitivity = Sensitivity.HIGH)).process(MotionEvent.Steering(0, angle)).state.zones.isEmpty())
    }

    @Test
    fun `reactive brake cue needs both brake and deceleration`() {
        fun decelerate(engine: CueEngine, brake: Boolean): CueState {
            engine.process(MotionEvent.Brake(0, brake))
            var state = CueState()
            var kmh = 40.0
            for (t in 0L..2_000L step 100) {
                state = engine.process(MotionEvent.Speed(t, kmh)).state
                kmh -= 0.6 // 0.1초에 0.6 km/h = 1.67 m/s²
            }
            return state
        }
        assertTrue(decelerate(CueEngine(), brake = false).zones.isEmpty())
        val braking = decelerate(CueEngine(), brake = true)
        assertEquals(Seat.PASSENGERS.toSet(), braking.zones.keys)
        assertTrue(braking.zones.values.all { it.kind == CueKind.REACTIVE_BRAKE })
    }

    @Test
    fun `speed expiry and recovery do not create artificial braking`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Brake(0, true))
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Speed(100, 36.0))
        val expired = engine.process(MotionEvent.Speed(200, 0.0, valid = false))
        assertEquals(0.0, expired.state.decelMps2, 0.0)
        assertFalse(CueKind.REACTIVE_BRAKE in expired.state.activeKinds)
        // 데이터가 끊긴 사이 속도가 달라졌어도 새 측정은 새 기준점이다.
        val recovered = engine.process(MotionEvent.Speed(300, 3.6))
        assertEquals(0.0, recovered.state.decelMps2, 0.0)
        assertFalse(CueKind.REACTIVE_BRAKE in recovered.state.activeKinds)
        val steady = engine.process(MotionEvent.Speed(400, 3.6))
        assertEquals(0.0, steady.state.decelMps2, 0.0)
        assertTrue(steady.state.zones.isEmpty())
    }

    @Test
    fun `deceleration uses measurement interval even when delivery is uneven`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Brake(900, true))
        engine.process(MotionEvent.Speed(900, 36.0, measurementTimeMs = 0))
        engine.process(MotionEvent.Tick(1_000))
        // 1초에 0.6m/s 감소. 두 이벤트의 전달 간격 0.2초로 나누면 거짓 급감속이 된다.
        val mild = engine.process(MotionEvent.Speed(1_100, 33.84, measurementTimeMs = 1_000)).state
        assertEquals(0.6, mild.decelMps2, 1e-9)
        assertFalse(CueKind.REACTIVE_BRAKE in mild.activeKinds)
        // 실제로 1초 동안 2m/s 감소하면 정상적으로 큐를 켠다.
        val hard = engine.process(MotionEvent.Speed(2_100, 26.64, measurementTimeMs = 2_000)).state
        assertEquals(2.0, hard.decelMps2, 1e-9)
        assertTrue(CueKind.REACTIVE_BRAKE in hard.activeKinds)
    }

    @Test
    fun `duplicate and older speed measurements cannot overwrite the latest speed`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Brake(0, true))
        engine.process(MotionEvent.Speed(0, 36.0, measurementTimeMs = 0))
        engine.process(MotionEvent.Speed(1_000, 28.8, measurementTimeMs = 1_000))
        val duplicate = engine.process(MotionEvent.Speed(1_100, 0.0, measurementTimeMs = 1_000)).state
        assertEquals(28.8, duplicate.speedKmh, 1e-9)
        assertEquals(0.0, duplicate.decelMps2, 1e-9)
        val older = engine.process(MotionEvent.Speed(1_200, 0.0, measurementTimeMs = 500)).state
        assertEquals(28.8, older.speedKmh, 1e-9)
        assertEquals(0.0, older.decelMps2, 1e-9)
        val next = engine.process(MotionEvent.Speed(2_000, 27.0, measurementTimeMs = 2_000)).state
        assertEquals(0.5, next.decelMps2, 1e-9)
    }

    @Test
    fun `cue does not relight before the minimum gap`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Steering(0, -100.0))
        engine.process(MotionEvent.Steering(1_300, 0.0)) // 1.2초 유지 후 해제
        val tooSoon = engine.process(MotionEvent.Steering(1_500, -100.0))
        assertTrue(tooSoon.state.zones.isEmpty())
        val later = engine.process(MotionEvent.Steering(2_200, -100.0))
        assertEquals(setOf(Seat.REAR_LEFT), later.state.zones.keys)
    }

    // ---- 좌석 설정·운전석 보호 ----

    @Test
    fun `target seat setting limits the cue`() {
        val config = CueConfig(targetSeats = setOf(Seat.FRONT_PASSENGER))
        val left = approach(CueEngine(config), "TURN_LEFT", 100.0, 36.0, untilMs = 9_000)
        // 왼쪽엔 운전석·뒷좌석 왼쪽뿐 → 표시할 좌석 없음 (큐 자체는 켜져 로그에 남음)
        assertTrue(left.all { it.state.zones.isEmpty() })
        assertNotNull(firstOn(left, CueKind.PREDICTIVE_TURN))
        assertTrue(left.any { f -> f.logs.any { "대상 좌석 없음" in it.message } })

        val right = approach(CueEngine(config), "TURN_RIGHT", 100.0, 36.0, untilMs = 9_000)
        assertEquals(setOf(Seat.FRONT_PASSENGER), right.last().state.zones.keys)
    }

    @Test
    fun `driver seat is never lit even when configured`() {
        val engine = CueEngine(CueConfig(targetSeats = Seat.entries.toSet()))
        engine.process(MotionEvent.Speed(0, 36.0))
        engine.process(MotionEvent.Brake(0, true))
        val events = listOf(
            MotionEvent.Tbt(0, listOf(TurnInfo("TURN_LEFT", 10.0), TurnInfo("TURN_UTURN", 5.0))),
            MotionEvent.Caution(100, listOf(CautionInfo("b", "SPEED_BUMP", 5.0))),
            MotionEvent.Steering(200, -300.0),
            MotionEvent.Speed(300, 20.0),
            MotionEvent.Steering(400, 300.0),
        )
        for (e in events) {
            val zones = engine.process(e).state.zones
            assertFalse("driver lit by $e", Seat.DRIVER in zones)
        }
    }

    @Test
    fun `reset clears every cue`() {
        val engine = CueEngine()
        engine.process(MotionEvent.Steering(0, -100.0))
        engine.reset()
        val out = engine.process(MotionEvent.Tick(100))
        assertTrue(out.state.zones.isEmpty())
        assertTrue(out.state.activeKinds.isEmpty())
    }
}
