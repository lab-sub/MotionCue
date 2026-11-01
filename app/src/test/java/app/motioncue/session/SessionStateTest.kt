package app.motioncue.session

import app.motioncue.core.engine.CueState
import app.motioncue.data.SourceStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    @Test fun `pausing moving Live does not unlock settings`() {
        val state = SessionState(mode = SourceMode.LIVE,
            source = SourceStatus(playing = false, speedKnown = true), cue = CueState(speedKmh = 30.0))
        assertTrue(state.settingsLocked)
    }

    @Test fun `unknown Live speed is not evidence of a stopped vehicle`() {
        assertTrue(SessionState(mode = SourceMode.LIVE, cue = CueState(speedKmh = 0.0)).settingsLocked)
    }

    @Test fun `fresh stationary Live and Mock allow settings`() {
        assertFalse(SessionState(mode = SourceMode.LIVE,
            source = SourceStatus(playing = true, speedKnown = true)).settingsLocked)
        assertFalse(SessionState(mode = SourceMode.MOCK, cue = CueState(speedKmh = 50.0)).settingsLocked)
    }
}
