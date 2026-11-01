package app.motioncue.core.engine

import app.motioncue.core.model.Seat

/**
 * 반응 큐 민감도.
 * [steerOnDeg]는 핸들 각도(도) 기준, [decelOnMps2]는 브레이크를 밟은 상태의 감속도 기준.
 */
enum class Sensitivity(val label: String, val steerOnDeg: Double, val decelOnMps2: Double) {
    LOW("낮음", 120.0, 1.6),
    NORMAL("보통", 80.0, 1.0),
    HIGH("높음", 45.0, 0.6),
}

data class CueConfig(
    /** 기동 몇 초 전에 예측 큐를 켤지 (Hainich et al. 2021은 2초 전) */
    val leadTimeSec: Double = 2.5,
    /** 큐를 보여줄 좌석. 운전석이 들어와도 엔진이 무시한다 */
    val targetSeats: Set<Seat> = Seat.PASSENGERS.toSet(),
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    /** 이 속도 미만이면 ETA를 계산하지 않는다 (무한대로 본다) */
    val minSpeedKmh: Double = 5.0,
    /** ETA를 계산하지 않는 저속에서만 이 거리 안이면 예측 큐를 켠다 */
    val nearDistanceM: Double = 15.0,
    /** 큐 최소 유지 시간 — 깜빡임 방지 */
    val minHoldMs: Long = 1_200,
    /** 같은 종류의 큐가 꺼진 뒤 다시 켜지기까지 최소 간격 — 깜빡임 방지 */
    val minGapMs: Long = 800,
    /** 회전 지점 통과 후 핸들 복귀를 기다리는 최대 시간 */
    val turnSettleMs: Long = 4_000,
    /** 주의 지점 통과 후 큐 유지 시간 */
    val cautionSettleMs: Long = 1_500,
    /** 핸들이 이 각도 아래로 돌아오면 회전이 끝난 것으로 본다 */
    val steerCenterDeg: Double = 40.0,
    /** 반응 큐는 켜짐 임계값 × 이 비율 아래로 내려와야 꺼진다 (히스테리시스) */
    val releaseRatio: Double = 0.6,
) {
    init {
        require(leadTimeSec > 0.0) { "leadTimeSec must be positive" }
        require(releaseRatio in 0.0..1.0) { "releaseRatio must be within 0..1" }
    }

    /** 실제 큐 대상: 설정된 좌석 중 운전석을 제외한 것 */
    val passengerTargets: List<Seat>
        get() = Seat.PASSENGERS.filter { it in targetSeats }
}
