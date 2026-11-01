package app.motioncue.core.engine

import app.motioncue.core.model.TurnInfo

/**
 * 내비가 보낸 경로 순서를 보존한다. 뒤쪽 기동의 거리에는 현재 위치부터의 누적 거리가 아닌
 * 값도 있으므로 최솟값을 찾거나 앞의 미지원/잘못된 항목을 건너뛰면 다른 기동을 예고하게 된다.
 */
fun nextRouteInstruction(items: List<TurnInfo>): TurnInfo? =
    items.firstOrNull()?.takeIf { it.distanceM.isFinite() && it.distanceM >= 0.0 }
