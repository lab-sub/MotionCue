package app.motioncue.recovery

import org.junit.Assert.*
import org.junit.Test

class RecoveryMathTest {
    @Test fun fanHonorsCapabilitiesAndRejectsUnknownInitialValues() {
        assertEquals(7, RecoveryMath.fan(6, 0, 7))
        assertEquals(5, RecoveryMath.fan(3, 0, 7))
        assertNull(RecoveryMath.fan(-1, 0, 7))
    }

    @Test fun temperatureUsesSupportedGridWithoutExcessCooling() {
        assertEquals(22.5f, RecoveryMath.temperature(24f, 16f, 30f, .5f)!!, .001f)
        assertEquals(23f, RecoveryMath.temperature(24f, 16f, 30f, 1f)!!, .001f)
        assertEquals(16f, RecoveryMath.temperature(16.5f, 16f, 30f, .5f)!!, .001f)
        assertNull(RecoveryMath.temperature(Float.NaN, 16f, 30f, .5f))
        assertNull(RecoveryMath.temperature(24f, 16f, 30f, 0f))
    }

    @Test fun windowUsesActualRangeAndNeverClosesAnAlreadyOpenWindow() {
        assertEquals(2, RecoveryMath.window(0, 0, 10))
        assertEquals(20, RecoveryMath.window(0, 0, 100))
        assertEquals(5, RecoveryMath.window(5, 0, 10))
        assertNull(RecoveryMath.window(0, -10, 10))
        assertNull(RecoveryMath.window(11, 0, 10))
    }

    @Test fun narrowerAreaBoundsDoNotShiftTheSdkTemperatureGrid() {
        assertEquals(22.5f, RecoveryMath.temperature(24f, 16.2f, 29.8f, .5f, 16f)!!, .001f)
        assertEquals(16.5f, RecoveryMath.temperature(17f, 16.2f, 29.8f, .5f, 16f)!!, .001f)
        assertNull(RecoveryMath.temperature(16.3f, 16.2f, 29.8f, .5f, 16f))
        assertNull(RecoveryMath.temperature(24f, 16f, 30f, .5f, Float.NaN))
    }
}
