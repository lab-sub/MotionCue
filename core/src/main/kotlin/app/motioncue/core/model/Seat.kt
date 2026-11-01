package app.motioncue.core.model

enum class Side(val label: String) {
    LEFT("왼쪽"),
    RIGHT("오른쪽"),
}

/**
 * 좌석 구역. 국내 좌핸들 차량 기준으로 운전석은 1열 왼쪽이다.
 * (Vehicle SDK에 운전석 위치 API가 없어 고정값으로 둔다.)
 *
 * SDK SeatValue 대응: DRIVER=ROW_1_LEFT, FRONT_PASSENGER=ROW_1_RIGHT,
 * REAR_LEFT=ROW_2_LEFT, REAR_RIGHT=ROW_2_RIGHT
 */
enum class Seat(val side: Side, val isDriver: Boolean, val label: String) {
    DRIVER(Side.LEFT, true, "운전석"),
    FRONT_PASSENGER(Side.RIGHT, false, "조수석"),
    REAR_LEFT(Side.LEFT, false, "뒷좌석 왼쪽"),
    REAR_RIGHT(Side.RIGHT, false, "뒷좌석 오른쪽");

    companion object {
        /** 큐·회복 개입 대상이 될 수 있는 좌석. 운전석은 어떤 경우에도 포함하지 않는다. */
        val PASSENGERS: List<Seat> = entries.filterNot { it.isDriver }
    }
}
