package app.motioncue.core.engine

import app.motioncue.core.model.TurnInfo
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vehicle SDK에 차량 속도 API가 없어서 속도를 다른 데이터로 추정한다.
 * 출처는 우선순위 순서로 나열한다 (앞일수록 신뢰).
 */
enum class SpeedSource(val label: String) {
    FUSED_LOCATION("FusedLocation 위치"),
    NAVI_LOCATION("내비 현재 위치"),
    ROUTE_DISTANCE("TBT 남은 거리 변화"),
}

/** 연속 위치(위경도)로 속도(m/s)를 추정한다. 위치 튐은 버리고 지수 평활한다. */
class PositionSpeedEstimator(
    private val smoothing: Double = 0.5,
    private val minIntervalMs: Long = 300,
    private val maxIntervalMs: Long = 10_000,
    private val maxPlausibleMps: Double = 70.0,
) {
    private var lastTimeMs = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasFix = false
    private var estimate: Double? = null

    /** 새로 수락한 속도만 반환한다. 기준점 설정·거부한 샘플은 null이며 신선도를 갱신하면 안 된다. */
    fun onPosition(timeMs: Long, latitude: Double, longitude: Double): Double? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return null
        if (!hasFix) {
            remember(timeMs, latitude, longitude)
            return null
        }
        val dtMs = timeMs - lastTimeMs
        if (dtMs < minIntervalMs) return null // 너무 촘촘하면 다음 샘플과 합쳐서 계산
        val distance = haversineM(lastLat, lastLon, latitude, longitude)
        if (dtMs > maxIntervalMs) {
            remember(timeMs, latitude, longitude)
            estimate = null // 오래 끊겼다가 다시 들어오면 새로 시작
            return null
        }
        val mps = distance / (dtMs / 1000.0)
        if (mps > maxPlausibleMps) return null
        remember(timeMs, latitude, longitude)
        estimate = estimate?.let { it + smoothing * (mps - it) } ?: mps
        return estimate
    }

    fun reset() {
        hasFix = false
        estimate = null
    }

    private fun remember(timeMs: Long, latitude: Double, longitude: Double) {
        lastTimeMs = timeMs
        lastLat = latitude
        lastLon = longitude
        hasFix = true
    }
}

/** 같은 기동까지 남은 거리가 줄어드는 속도 = 접근 속도(m/s). 기동이 바뀌면 새로 시작한다. */
class ClosingSpeedEstimator(
    private val smoothing: Double = 0.5,
    private val minIntervalMs: Long = 500,
    private val maxIntervalMs: Long = 5_000,
    private val maxPlausibleMps: Double = 70.0,
) {
    private var lastKey: String? = null
    private var lastTimeMs = 0L
    private var lastDistance = 0.0
    private var estimate: Double? = null

    /** 현재 경로의 첫 기동을 추적한다. 잘못된 head를 후속 기동의 짧은 거리로 대체하지 않는다. */
    fun onRoute(timeMs: Long, items: List<TurnInfo>): Double? {
        val next = nextRouteInstruction(items)
        if (next == null) {
            reset()
            return null
        }
        return onDistance(timeMs, "${next.turnType}|${next.description}", next.distanceM)
    }

    /** 새로 수락한 속도만 반환한다. 이전 추정치를 새 측정처럼 재발행하지 않는다. */
    fun onDistance(timeMs: Long, key: String, distanceM: Double): Double? {
        if (!distanceM.isFinite() || distanceM < 0.0) return null
        if (lastKey != null && timeMs <= lastTimeMs) return null
        if (key != lastKey || distanceM > lastDistance) {
            if (lastKey != null && timeMs - lastTimeMs > maxIntervalMs) estimate = null
            lastKey = key
            lastTimeMs = timeMs
            lastDistance = distanceM
            return null
        }
        val dtMs = timeMs - lastTimeMs
        if (dtMs < minIntervalMs) return null
        val mps = (lastDistance - distanceM) / (dtMs / 1000.0)
        lastTimeMs = timeMs
        lastDistance = distanceM
        if (dtMs > maxIntervalMs || mps > maxPlausibleMps) {
            estimate = null
            return null
        }
        estimate = estimate?.let { it + smoothing * (mps - it) } ?: mps
        return estimate
    }

    fun reset() {
        lastKey = null
        estimate = null
    }
}

/**
 * [timeMs]는 수신 시각(신선도), [measurementTimeMs]는 출처의 측정 시각(감속 계산)이다.
 * 측정 시계가 다른 출처로 바뀌면 호출 쪽에서 감속 계산 기준을 초기화해야 한다.
 */
data class SpeedSample(
    val source: SpeedSource,
    val timeMs: Long,
    val mps: Double,
    val measurementTimeMs: Long = timeMs,
)

/** 여러 출처의 속도 중 신선하고 우선순위가 높은 값을 고른다. */
class SpeedFusion(private val staleMs: Long = 3_000) {

    private val latest = mutableMapOf<SpeedSource, SpeedSample>()

    fun update(source: SpeedSource, timeMs: Long, mps: Double, measurementTimeMs: Long = timeMs) {
        if (!mps.isFinite() || mps < 0.0) return
        val previous = latest[source]
        if (previous != null && (timeMs < previous.timeMs || measurementTimeMs <= previous.measurementTimeMs)) return
        latest[source] = SpeedSample(source, timeMs, mps, measurementTimeMs)
    }

    fun current(nowMs: Long): Pair<SpeedSource, Double>? =
        currentSample(nowMs)?.let { it.source to it.mps }

    fun currentSample(nowMs: Long): SpeedSample? =
        SpeedSource.entries.firstNotNullOfOrNull { source ->
            latest[source]?.takeIf { nowMs - it.timeMs in 0..staleMs }
        }

    fun reset() = latest.clear()
}

/** 두 위경도 사이 거리(m) */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadiusM * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
