package app.motioncue.actuator

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import app.motioncue.core.model.Seat
import org.junit.Assert.*
import org.junit.Test

class SafeAreasTest {
    @Test fun driverAndUnknownCompoundAreasAreNeverEligible() {
        assertTrue(SafeAreas.passengers(SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)).isEmpty())
        assertTrue(SafeAreas.passengers(SeatArea(SeatValue.ROW_2_LEFT, SeatValue.ROW_3_LEFT)).isEmpty())
        assertTrue(SafeAreas.passengers(SeatArea()).isEmpty())
        assertTrue(SafeAreas.passengers(WindowArea(WindowValue.ROW_1_RIGHT, WindowValue.ROOF_TOP_1)).isEmpty())
    }

    @Test fun overlappingCapabilityEntriesCannotFightOverTheSameSeat() {
        val left = SeatArea(SeatValue.ROW_2_LEFT)
        val rear = SeatArea(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
        val front = SeatArea(SeatValue.ROW_1_RIGHT)
        assertEquals(mapOf(front to setOf(Seat.FRONT_PASSENGER)), SafeAreas.lightAreas(listOf(left, rear, front)))
    }

    @Test fun sharedPassengerOnlyAreaRemainsEligible() {
        val rear = SeatArea(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
        assertEquals(setOf(Seat.REAR_LEFT, Seat.REAR_RIGHT), SafeAreas.lightAreas(listOf(rear)).getValue(rear))
    }
}
