package app.motioncue.actuator

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import app.motioncue.core.model.Seat

/** Only explicitly identified passenger areas are eligible; an empty/unknown/driver area is unsafe. */
internal object SafeAreas {
    fun passengers(area: SeatArea): Set<Seat> = knownPassengers(area.values.map { value ->
        when (value) {
            SeatValue.ROW_1_RIGHT -> Seat.FRONT_PASSENGER
            SeatValue.ROW_2_LEFT -> Seat.REAR_LEFT
            SeatValue.ROW_2_RIGHT -> Seat.REAR_RIGHT
            else -> null
        }
    })

    fun passengers(area: WindowArea): Set<Seat> = knownPassengers(area.values.map { value ->
        when (value) {
            WindowValue.ROW_1_RIGHT -> Seat.FRONT_PASSENGER
            WindowValue.ROW_2_LEFT -> Seat.REAR_LEFT
            WindowValue.ROW_2_RIGHT -> Seat.REAR_RIGHT
            else -> null
        }
    })

    private fun knownPassengers(values: List<Seat?>): Set<Seat> =
        if (values.isEmpty() || values.any { it == null }) emptySet() else values.filterNotNull().toSet()

    /** Overlapping capability entries have ambiguous write ownership; omit every conflicting entry. */
    fun lightAreas(areas: List<SeatArea>): Map<SeatArea, Set<Seat>> {
        val safe = areas.distinct().associateWith(::passengers).filterValues { it.isNotEmpty() }
        return safe.filter { (area, seats) ->
            safe.none { (other, others) -> other != area && seats.any { it in others } }
        }
    }

    fun windowAreas(areas: List<WindowArea>): Map<WindowArea, Set<Seat>> {
        val safe = areas.distinct().associateWith(::passengers).filterValues { it.isNotEmpty() }
        return safe.filter { (area, seats) ->
            safe.none { (other, others) -> other != area && seats.any { it in others } }
        }
    }
}
