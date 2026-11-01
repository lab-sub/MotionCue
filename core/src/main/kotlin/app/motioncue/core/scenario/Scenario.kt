package app.motioncue.core.scenario

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mock 주행 시나리오 (JSON).
 * 속도·핸들 각도는 [시각(초), 값] 키프레임으로 주고 사이는 선형 보간한다.
 * 기동·주의 지점은 "지나는 시각"과 "처음 안내하는 남은 거리"로 주면,
 * 재생기가 속도를 적분해 남은 거리를 계산하고 1초마다 TBT처럼 내보낸다.
 */
@Serializable
data class Scenario(
    val name: String,
    val durationSec: Double,
    val tickMs: Long = 100,
    /** [시각(초), 속도(km/h)] */
    val speedKmh: List<List<Double>>,
    /** [시각(초), 핸들 각도(도)] — 안내 없는 커브 등. 왼쪽이 음수 */
    val steeringDeg: List<List<Double>> = emptyList(),
    /** [시작(초), 끝(초)] 브레이크를 밟는 구간 */
    val brakeIntervalsSec: List<List<Double>> = emptyList(),
    val maneuvers: List<ScenarioManeuver> = emptyList(),
    val cautions: List<ScenarioCaution> = emptyList(),
) {
    init {
        require(durationSec > 0.0) { "durationSec must be positive" }
        require(tickMs > 0) { "tickMs must be positive" }
        require(speedKmh.isNotEmpty()) { "speedKmh needs at least one keyframe" }
        (speedKmh + steeringDeg + brakeIntervalsSec).forEach {
            require(it.size == 2) { "keyframes and intervals are [time, value] pairs: $it" }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Scenario = json.decodeFromString(serializer(), text)

        /** core 모듈 리소스의 기본 시나리오 */
        fun loadDefault(): Scenario {
            val stream = Scenario::class.java.getResourceAsStream(DEFAULT_RESOURCE)
                ?: error("Missing scenario resource $DEFAULT_RESOURCE")
            return stream.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        }

        const val DEFAULT_RESOURCE = "/scenarios/default.json"
    }
}

@Serializable
data class ScenarioManeuver(
    /** SDK TurnType 이름 (예: "TURN_LEFT") */
    val turnType: String,
    /** 기동 지점을 지나는 시각(초) */
    val passAtSec: Double,
    /** 처음 안내할 때의 남은 거리(m) */
    val announceDistanceM: Double,
    val description: String = "",
    /** 기동 중 최대 핸들 각도(도). 왼쪽이 음수, 0이면 조향 없음 */
    val steerPeakDeg: Double = 0.0,
    /** 핸들을 돌렸다가 푸는 데 걸리는 시간(초). 통과 시각이 가운데 */
    val steerDurationSec: Double = 4.0,
)

@Serializable
data class ScenarioCaution(
    /** SDK CautionPointType 이름 (예: "SPEED_BUMP") */
    val type: String,
    val passAtSec: Double,
    val announceDistanceM: Double,
    val id: String = "",
)
