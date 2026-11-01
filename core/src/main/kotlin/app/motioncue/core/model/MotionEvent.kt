package app.motioncue.core.model

/** 내비 TBT 한 항목. [turnType]은 SDK TurnType 이름 그대로 (예: "TURN_LEFT"). */
data class TurnInfo(
    val turnType: String,
    val distanceM: Double,
    val description: String = "",
)

/** 내비 주의 지점 한 항목. [type]은 SDK CautionPointType 이름 그대로 (예: "SPEED_BUMP"). */
data class CautionInfo(
    val id: String,
    val type: String,
    val remainDistanceM: Double,
)

/**
 * CueEngine 입력 이벤트.
 * [timeMs]는 단조 증가하는 시각(ms) — Mock은 시나리오 시각, Live는 elapsedRealtime.
 */
sealed interface MotionEvent {
    val timeMs: Long

    /** 내비의 경로 순서 그대로인 기동 목록. 후속 항목의 거리로 재정렬하지 않는다. */
    data class Tbt(override val timeMs: Long, val items: List<TurnInfo>) : MotionEvent

    /** 내비가 알려준 주의 지점 목록 */
    data class Caution(override val timeMs: Long, val items: List<CautionInfo>) : MotionEvent

    /**
     * valid=false는 정차 측정이 아니라 데이터 만료다. 감속 계산 기준을 함께 초기화한다.
     * [measurementTimeMs]는 실제 측정 시각이며, 큐를 처리하는 [timeMs]와 별도로 감속 계산에 쓴다.
     */
    data class Speed(
        override val timeMs: Long,
        val kmh: Double,
        val valid: Boolean = true,
        val measurementTimeMs: Long = timeMs,
    ) : MotionEvent

    /** 핸들 조향각(도). 왼쪽이 음수 */
    data class Steering(override val timeMs: Long, val angleDeg: Double) : MotionEvent

    data class Brake(override val timeMs: Long, val pressed: Boolean) : MotionEvent

    /** 새 데이터 없이 시간만 흐를 때 (ETA 재계산용) */
    data class Tick(override val timeMs: Long) : MotionEvent
}
