package app.motioncue.data

import app.motioncue.core.model.MotionEvent
import app.motioncue.core.scenario.Scenario
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

class MockScenarioSourceTest {
    private fun scenario() = Scenario(
        name = "Playback contract", durationSec = 0.3, tickMs = 100,
        speedKmh = listOf(listOf(0.0, 36.0), listOf(0.3, 0.0)),
    )

    @Test fun `plays each tick once and finishes at scenario duration`() = runBlocking {
        val source = MockScenarioSource({}, coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, scenario())
        val ticks = mutableListOf<Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            source.events.collect { if (it is MotionEvent.Tick) ticks += it.timeMs }
        }
        try {
            source.play()
            withTimeout(2_000) { source.status.first { !it.playing && it.elapsedMs == 300L } }
            assertEquals(listOf(0L, 100L, 200L, 300L), ticks)
            assertEquals(300L, source.status.value.durationMs)
        } finally {
            source.close()
            collector.cancelAndJoin()
        }
    }

    @Test fun `pause preserves next tick and resume has no duplicates`() = runBlocking {
        val source = MockScenarioSource({}, coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, scenario())
        val ticks = mutableListOf<Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            source.events.collect { if (it is MotionEvent.Tick) ticks += it.timeMs }
        }
        try {
            source.play()
            withTimeout(2_000) { source.status.first { it.elapsedMs == 100L } }
            source.pause()
            val paused = ticks.toList()
            delay(150)
            assertFalse(source.status.value.playing)
            assertEquals(paused, ticks)
            source.play()
            withTimeout(2_000) { source.status.first { !it.playing && it.elapsedMs == 300L } }
            assertEquals(listOf(0L, 100L, 200L, 300L), ticks)
        } finally {
            source.close()
            collector.cancelAndJoin()
        }
    }

    @Test fun `reset restarts time zero and close prevents playback`() = runBlocking {
        val source = MockScenarioSource({}, coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, scenario())
        val ticks = mutableListOf<Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            source.events.collect { if (it is MotionEvent.Tick) ticks += it.timeMs }
        }
        try {
            source.play()
            withTimeout(2_000) { source.status.first { it.elapsedMs == 100L } }
            source.reset()
            assertEquals(0L, source.status.value.elapsedMs)
            ticks.clear()
            source.play()
            withTimeout(2_000) { source.status.first { !it.playing && it.elapsedMs == 300L } }
            assertEquals(listOf(0L, 100L, 200L, 300L), ticks)
            source.close()
            source.play()
            assertFalse(source.status.value.playing)
        } finally {
            source.close()
            collector.cancelAndJoin()
        }
    }
}
