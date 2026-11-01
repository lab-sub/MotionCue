package app.motioncue.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.motioncue.core.model.TurnInfo

class SpeedEstimationTest {

    /** 위도 1도 ≈ 111,195m */
    private val metersPerDegreeLat = 111_195.0

    @Test
    fun `haversine matches one degree of latitude`() {
        assertEquals(metersPerDegreeLat, haversineM(37.0, 127.0, 38.0, 127.0), 100.0)
        assertEquals(0.0, haversineM(37.5, 127.0, 37.5, 127.0), 1e-6)
    }

    @Test
    fun `position estimator follows steady motion`() {
        val estimator = PositionSpeedEstimator()
        var estimate: Double? = null
        for (i in 0..10) {
            // 북쪽으로 초당 10m
            estimate = estimator.onPosition(i * 1_000L, 37.5 + i * 10.0 / metersPerDegreeLat, 127.0)
        }
        assertEquals(10.0, estimate!!, 0.2)
    }

    @Test
    fun `position jumps are discarded`() {
        val estimator = PositionSpeedEstimator()
        estimator.onPosition(0, 37.5, 127.0)
        val steady = estimator.onPosition(1_000, 37.5 + 10.0 / metersPerDegreeLat, 127.0)
        // 1초에 1km 튐 → 무시
        val afterJump = estimator.onPosition(2_000, 37.5 + 1_010.0 / metersPerDegreeLat, 127.0)
        assertNull(afterJump)
        // 거부한 위치를 새 기준점으로 삼지 않아 다음 정상 샘플에서 바로 회복한다.
        val recovered = estimator.onPosition(3_000, 37.5 + 30.0 / metersPerDegreeLat, 127.0)
        assertEquals(steady!!, recovered!!, 1e-6)
    }

    @Test
    fun `long gap restarts the estimate`() {
        val estimator = PositionSpeedEstimator()
        estimator.onPosition(0, 37.5, 127.0)
        estimator.onPosition(1_000, 37.5 + 10.0 / metersPerDegreeLat, 127.0)
        assertNull(estimator.onPosition(20_000, 37.6, 127.0))
    }

    @Test
    fun `closing speed comes from shrinking route distance`() {
        val estimator = ClosingSpeedEstimator()
        estimator.onDistance(0, "left", 100.0)
        estimator.onDistance(1_000, "left", 90.0)
        val v = estimator.onDistance(2_000, "left", 80.0)
        assertEquals(10.0, v!!, 1e-9)
    }

    @Test
    fun `route speed follows the first instruction rather than a short later leg`() {
        val estimator = ClosingSpeedEstimator()
        fun route(head: Double) = listOf(
            TurnInfo("TURN_LEFT", head), TurnInfo("TURN_RIGHT", 380.0),
            TurnInfo("TURN_RIGHT", 50.0), TurnInfo("TURN_ARRIVE", 10.0),
        )
        assertNull(estimator.onRoute(0, route(80.0)))
        assertEquals(10.0, estimator.onRoute(1_000, route(70.0))!!, 1e-9)
        assertEquals(10.0, estimator.onRoute(2_000, route(60.0))!!, 1e-9)
    }

    @Test
    fun `straight route head supports speed but invalid or missing head resets its baseline`() {
        val estimator = ClosingSpeedEstimator()
        fun route(distance: Double) = listOf(
            TurnInfo("TURN_STRAIGHT", distance), TurnInfo("TURN_RIGHT", 10.0),
        )
        assertNull(estimator.onRoute(0, route(100.0)))
        assertEquals(10.0, estimator.onRoute(1_000, route(90.0))!!, 1e-9)
        assertNull(estimator.onRoute(2_000, route(Double.NaN)))
        assertNull(estimator.onRoute(3_000, route(50.0)))
        assertEquals(5.0, estimator.onRoute(4_000, route(45.0))!!, 1e-9)
        assertNull(estimator.onRoute(5_000, emptyList()))
        assertNull(estimator.onRoute(6_000, route(25.0)))
    }

    @Test
    fun `new maneuver restarts closing speed baseline`() {
        val estimator = ClosingSpeedEstimator()
        estimator.onDistance(0, "left", 20.0)
        val first = estimator.onDistance(1_000, "left", 10.0)
        // 다음 기동(거리 증가)에서는 이전 기준과 비교하지 않는다
        val afterSwitch = estimator.onDistance(2_000, "right", 300.0)
        assertEquals(10.0, first!!, 1e-9)
        assertNull(afterSwitch)
        val next = estimator.onDistance(3_000, "right", 286.0)
        assertEquals(10.0 + 0.5 * (14.0 - 10.0), next!!, 1e-9)
    }

    @Test
    fun `samples without a fresh estimate do not keep fusion alive`() {
        val position = PositionSpeedEstimator()
        val closing = ClosingSpeedEstimator()
        val fusion = SpeedFusion(staleMs = 3_000)
        fun updatePosition(t: Long, meters: Double) {
            position.onPosition(t, 37.5 + meters / metersPerDegreeLat, 127.0)?.let {
                fusion.update(SpeedSource.NAVI_LOCATION, t, it)
            }
        }
        fun updateDistance(t: Long, key: String, distance: Double) {
            closing.onDistance(t, key, distance)?.let {
                fusion.update(SpeedSource.ROUTE_DISTANCE, t, it)
            }
        }
        updatePosition(0, 0.0)
        updatePosition(1_000, 10.0)
        updateDistance(0, "turn", 100.0)
        updateDistance(1_000, "turn", 90.0)
        assertEquals(SpeedSource.NAVI_LOCATION, fusion.current(1_000)?.first)
        for (t in 2_000L..5_000L step 1_000) {
            updatePosition(t, 10_000.0) // 지속적인 위치 오류
            updateDistance(t, "turn-$t", 100.0) // 매번 새 기준점뿐인 안내
        }
        assertNull(fusion.current(5_000))
    }

    @Test
    fun `too close and invalid samples are not fresh or new baselines`() {
        val position = PositionSpeedEstimator()
        position.onPosition(0, 37.5, 127.0)
        position.onPosition(1_000, 37.5 + 10.0 / metersPerDegreeLat, 127.0)
        assertNull(position.onPosition(1_100, 37.5 + 11.0 / metersPerDegreeLat, 127.0))
        assertNull(position.onPosition(2_000, Double.NaN, 127.0))
        assertNull(position.onPosition(2_000, 91.0, 127.0))
        assertEquals(10.0, position.onPosition(3_000, 37.5 + 30.0 / metersPerDegreeLat, 127.0)!!, 0.2)

        val closing = ClosingSpeedEstimator()
        closing.onDistance(0, "turn", 100.0)
        closing.onDistance(1_000, "turn", 90.0)
        assertNull(closing.onDistance(1_100, "turn", 89.0))
        assertNull(closing.onDistance(500, "old-turn", 1_000.0))
        assertNull(closing.onDistance(2_000, "turn", Double.POSITIVE_INFINITY))
        assertNull(closing.onDistance(2_000, "turn", -10.0))
        assertEquals(10.0, closing.onDistance(3_000, "turn", 70.0)!!, 1e-9)
    }

    @Test
    fun `closing speed long gaps and implausible jumps reset the estimate`() {
        val estimator = ClosingSpeedEstimator()
        estimator.onDistance(0, "turn", 1_000.0)
        estimator.onDistance(1_000, "turn", 990.0)
        assertNull(estimator.onDistance(10_000, "turn", 900.0))
        assertEquals(20.0, estimator.onDistance(11_000, "turn", 880.0)!!, 1e-9)
        assertNull(estimator.onDistance(12_000, "turn", 100.0))
        assertEquals(5.0, estimator.onDistance(13_000, "turn", 95.0)!!, 1e-9)
    }

    @Test
    fun `fusion rejects invalid and out of order updates`() {
        val fusion = SpeedFusion()
        fusion.update(SpeedSource.NAVI_LOCATION, 1_000, 10.0)
        fusion.update(SpeedSource.NAVI_LOCATION, 2_000, Double.NaN)
        fusion.update(SpeedSource.NAVI_LOCATION, 2_000, -1.0)
        fusion.update(SpeedSource.NAVI_LOCATION, 500, 30.0)
        assertEquals(SpeedSource.NAVI_LOCATION to 10.0, fusion.current(2_000))
        assertNull(fusion.current(500))
        assertNull(fusion.current(4_001))
    }

    @Test
    fun `fusion prefers fresh high-priority sources`() {
        val fusion = SpeedFusion(staleMs = 3_000)
        fusion.update(SpeedSource.ROUTE_DISTANCE, 0, 9.0)
        assertEquals(SpeedSource.ROUTE_DISTANCE, fusion.current(1_000)?.first)
        fusion.update(SpeedSource.NAVI_LOCATION, 1_000, 10.0)
        assertEquals(SpeedSource.NAVI_LOCATION to 10.0, fusion.current(1_500))
        // 내비 위치가 끊기면(3초 초과) 다음 출처로
        fusion.update(SpeedSource.ROUTE_DISTANCE, 4_500, 8.0)
        assertEquals(SpeedSource.ROUTE_DISTANCE to 8.0, fusion.current(4_600))
        assertNull(fusion.current(10_000))
    }

    @Test
    fun `fusion preserves measurement identity across repeated reads and fallback`() {
        val fusion = SpeedFusion()
        fusion.update(SpeedSource.NAVI_LOCATION, 1_000, 10.0)
        fusion.update(SpeedSource.ROUTE_DISTANCE, 2_000, 9.0)
        val navi = SpeedSample(SpeedSource.NAVI_LOCATION, 1_000, 10.0)
        assertEquals(navi, fusion.currentSample(2_000))
        assertEquals(navi, fusion.currentSample(2_100))
        assertEquals(SpeedSample(SpeedSource.ROUTE_DISTANCE, 2_000, 9.0), fusion.currentSample(4_001))
        assertNull(fusion.currentSample(5_001))
    }

    @Test
    fun `fusion freshness uses receipt time while preserving the source measurement clock`() {
        val fusion = SpeedFusion()
        val sourceTime = 1_700_000_000_000L
        fusion.update(SpeedSource.FUSED_LOCATION, 1_000, 10.0, measurementTimeMs = sourceTime)
        assertEquals(SpeedSample(SpeedSource.FUSED_LOCATION, 1_000, 10.0, sourceTime), fusion.currentSample(2_000))
        // 늦게 도착한 과거 측정값이 신선도를 연장하거나 현재 속도를 덮어쓰면 안 된다.
        fusion.update(SpeedSource.FUSED_LOCATION, 2_000, 20.0, measurementTimeMs = sourceTime - 1_000)
        fusion.update(SpeedSource.FUSED_LOCATION, 3_000, 20.0, measurementTimeMs = sourceTime)
        assertEquals(10.0, fusion.currentSample(2_000)!!.mps, 1e-9)
        assertNull(fusion.currentSample(4_001))
    }
}
