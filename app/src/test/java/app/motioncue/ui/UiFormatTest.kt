package app.motioncue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.motioncue.core.engine.TurnClassifier

class UiFormatTest {

    @Test
    fun `every classifier label picks the arrow on its own side`() {
        val cases = mapOf(
            "TURN_LEFT" to TurnGlyph.Turn(left = true),
            "TURN_RIGHT" to TurnGlyph.Turn(left = false),
            "TURN_SHARP_L" to TurnGlyph.Sharp(left = true),
            "TURN_SHARP_R" to TurnGlyph.Sharp(left = false),
            "TURN_KEEP_L" to TurnGlyph.Keep(left = true),
            "TURN_KEEP_R" to TurnGlyph.Keep(left = false),
            "TURN_UTURN" to TurnGlyph.UTurn,
            "TURN_ENTER_IC_L" to TurnGlyph.Keep(left = true),
            "TURN_EXIT_IC_R" to TurnGlyph.Keep(left = false),
            "TURN_AVOID_OVER_L" to TurnGlyph.Keep(left = true),
            "TURN_ENTER_UNDER_R" to TurnGlyph.Keep(left = false),
            "TURN_ENTER_ROUNDABOUT_03" to TurnGlyph.Roundabout(3),
            "TURN_ENTER_ROUNDABOUT_09" to TurnGlyph.Roundabout(9),
            "TURN_ENTER_ROUNDABOUT_12" to TurnGlyph.Roundabout(12),
        )
        for ((turnType, glyph) in cases) {
            val label = requireNotNull(TurnClassifier.classify(turnType)).label
            assertEquals(turnType, glyph, turnGlyphFor(label))
        }
    }

    @Test
    fun `no guidance or unknown label falls back to straight`() {
        assertEquals(TurnGlyph.Straight, turnGlyphFor(null))
        assertEquals(TurnGlyph.Straight, turnGlyphFor("안내 없음"))
    }

    @Test
    fun `eta dot enters the painted zone exactly at lead time`() {
        val leadZoneStart = etaFraction(2.5)
        assertEquals(0.75f, leadZoneStart, 1e-6f)
        assertTrue(etaFraction(2.6) < leadZoneStart)
        assertTrue(etaFraction(2.4) > leadZoneStart)
        assertEquals(0f, etaFraction(60.0), 0f)
        assertEquals(1f, etaFraction(0.0), 0f)
    }

    @Test
    fun `clock text rounds to the nearest second`() {
        assertEquals("0:00", clockText(0))
        assertEquals("0:21", clockText(20_600))
        assertEquals("1:35", clockText(95_000))
    }

    @Test
    fun `numbers never show negative zero`() {
        assertEquals("0", wholeText(-0.2))
        assertEquals("0", wholeText(Double.NaN))
        assertEquals("0.0", oneDecimalText(0.0))
        assertEquals("0°" to "중앙", steeringText(-0.2))
        assertEquals("120°" to "왼쪽", steeringText(-120.0))
        assertEquals("100°" to "오른쪽", steeringText(100.0))
    }
}
