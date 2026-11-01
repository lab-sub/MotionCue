package app.motioncue.data

import app.motioncue.core.model.MotionEvent
import app.motioncue.core.scenario.Scenario
import app.motioncue.core.scenario.ScenarioTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MockScenarioSource(
    private val log: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    scenario: Scenario = Scenario.loadDefault(),
) : MotionEventSource {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val timeline = ScenarioTimeline(scenario)
    private val mutableEvents = MutableSharedFlow<MotionEvent>()
    override val events = mutableEvents.asSharedFlow()
    private val mutableStatus = MutableStateFlow(
        SourceStatus(durationMs = timeline.durationMs, detail = "Mock · ${timeline.scenario.name}", speedKnown = true),
    )
    override val status = mutableStatus.asStateFlow()
    private var nextTick = 0
    private var playback: Job? = null
    private var closed = false

    override suspend fun play() = withContext(dispatcher) {
        if (closed || playback?.isActive == true) return@withContext
        if (nextTick > timeline.lastTick) {
            log("Mock 재생 완료 · 처음부터 버튼으로 다시 시작하세요")
            return@withContext
        }
        mutableStatus.value = mutableStatus.value.copy(playing = true)
        log("Mock 재생")
        playback = scope.launch {
            while (isActive && nextTick <= timeline.lastTick) {
                val tick = nextTick
                timeline.eventsAt(tick).forEach { mutableEvents.emit(it) }
                mutableEvents.emit(MotionEvent.Tick(timeline.timeMsOf(tick)))
                nextTick++
                mutableStatus.value = mutableStatus.value.copy(elapsedMs = timeline.timeMsOf(tick))
                if (nextTick <= timeline.lastTick) delay(timeline.tickMs)
            }
            mutableStatus.value = mutableStatus.value.copy(playing = false, detail = "Mock · 재생 완료")
            log("Mock 시나리오 완료")
        }
    }

    override suspend fun pause() = withContext(dispatcher) {
        playback?.cancelAndJoin()
        playback = null
        mutableStatus.value = mutableStatus.value.copy(playing = false)
    }

    /** The pipeline resets CueEngine before resubscribing/playing from time zero. */
    override suspend fun reset() = withContext(dispatcher) {
        pause()
        nextTick = 0
        mutableStatus.value = SourceStatus(durationMs = timeline.durationMs, detail = "Mock · ${timeline.scenario.name}", speedKnown = true)
    }

    override suspend fun close() = withContext(dispatcher) {
        pause()
        closed = true
        scope.cancel()
    }
}
