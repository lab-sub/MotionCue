package app.motioncue.core.engine

import app.motioncue.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifierTest {

    /** NaviHelper SDK 3.0.5(navi-data 3.0.4) TurnType 전체 이름 */
    private val sdkTurnTypes = listOf(
        "TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_RIGHT", "TURN_LEFT", "TURN_KEEP_R", "TURN_KEEP_L",
        "TURN_SHARP_R", "TURN_SHARP_L", "TURN_UTURN",
        "TURN_ENTER_IC_R", "TURN_ENTER_IC_L", "TURN_ENTER_IC_C",
        "TURN_EXIT_IC_R", "TURN_EXIT_IC_L", "TURN_EXIT_IC_C",
        "TURN_ENTER_UNDER_R", "TURN_ENTER_UNDER_L", "TURN_ENTER_UNDER_C",
        "TURN_AVOID_UNDER_R", "TURN_AVOID_UNDER_L", "TURN_AVOID_UNDER_C",
        "TURN_ENTER_OVER_R", "TURN_ENTER_OVER_L", "TURN_ENTER_OVER_C",
        "TURN_AVOID_OVER_R", "TURN_AVOID_OVER_L", "TURN_AVOID_OVER_C",
        "TURN_ENTER_ROUNDABOUT_02", "TURN_ENTER_ROUNDABOUT_03", "TURN_ENTER_ROUNDABOUT_04",
        "TURN_ENTER_ROUNDABOUT_06", "TURN_ENTER_ROUNDABOUT_08", "TURN_ENTER_ROUNDABOUT_09",
        "TURN_ENTER_ROUNDABOUT_10", "TURN_ENTER_ROUNDABOUT_12", "TURN_EXIT_ROUNDABOUT",
        "TURN_WAY", "TURN_ARRIVE", "TURN_TUNNEL", "TURN_DEVIDE_MERGE", "TURN_TOLL",
        "TURN_ENTER_SERVICEAREA", "TURN_RESTAREA",
    )

    private val expectedLeft = setOf(
        "TURN_LEFT", "TURN_KEEP_L", "TURN_SHARP_L", "TURN_UTURN",
        "TURN_ENTER_IC_L", "TURN_EXIT_IC_L", "TURN_ENTER_UNDER_L", "TURN_AVOID_UNDER_L",
        "TURN_ENTER_OVER_L", "TURN_AVOID_OVER_L",
        "TURN_ENTER_ROUNDABOUT_08", "TURN_ENTER_ROUNDABOUT_09", "TURN_ENTER_ROUNDABOUT_10",
        "TURN_ENTER_ROUNDABOUT_12",
    )

    private val expectedRight = setOf(
        "TURN_RIGHT", "TURN_KEEP_R", "TURN_SHARP_R",
        "TURN_ENTER_IC_R", "TURN_EXIT_IC_R", "TURN_ENTER_UNDER_R", "TURN_AVOID_UNDER_R",
        "TURN_ENTER_OVER_R", "TURN_AVOID_OVER_R",
        "TURN_ENTER_ROUNDABOUT_02", "TURN_ENTER_ROUNDABOUT_03", "TURN_ENTER_ROUNDABOUT_04",
    )

    @Test
    fun `every SDK turn type has the expected direction`() {
        for (type in sdkTurnTypes) {
            val expected = when (type) {
                in expectedLeft -> Side.LEFT
                in expectedRight -> Side.RIGHT
                else -> null
            }
            assertEquals(type, expected, TurnClassifier.classify(type)?.side)
        }
    }

    @Test
    fun `sharp turns and u-turn are strong, keep is soft`() {
        assertEquals(Strength.STRONG, TurnClassifier.classify("TURN_SHARP_L")?.strength)
        assertEquals(Strength.STRONG, TurnClassifier.classify("TURN_SHARP_R")?.strength)
        assertEquals(Strength.STRONG, TurnClassifier.classify("TURN_UTURN")?.strength)
        assertEquals(Strength.NORMAL, TurnClassifier.classify("TURN_LEFT")?.strength)
        assertEquals(Strength.SOFT, TurnClassifier.classify("TURN_KEEP_R")?.strength)
    }

    @Test
    fun `unknown names are ignored`() {
        assertNull(TurnClassifier.classify("TURN_SOMETHING_NEW"))
        assertNull(TurnClassifier.classify(""))
    }

    @Test
    fun `caution types used by MotionCue`() {
        assertEquals(Strength.STRONG, CautionClassifier.classify("SPEED_BUMP")?.strength)
        assertEquals(Strength.NORMAL, CautionClassifier.classify("DOWNHILL_CAUTION_AREA")?.strength)
        assertEquals(Strength.SOFT, CautionClassifier.classify("NARROW_ROAD")?.strength)
        assertNull(CautionClassifier.classify("SCHOOL_ZONE"))
        assertNull(CautionClassifier.classify("FOG_AREA"))
    }
}
