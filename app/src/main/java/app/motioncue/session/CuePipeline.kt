package app.motioncue.session

import android.content.Context
import app.motioncue.actuator.PleosCabinActuator
import app.motioncue.core.engine.CueConfig
import app.motioncue.core.engine.CueEngine
import app.motioncue.core.engine.CueState
import app.motioncue.core.model.Seat
import app.motioncue.core.model.MotionEvent
import app.motioncue.data.LiveSdkSource
import app.motioncue.data.MockScenarioSource
import app.motioncue.data.MotionEventSource
import app.motioncue.data.SourceStatus
import app.motioncue.recovery.PleosVoiceGuide
import app.motioncue.recovery.RecoveryController
import app.motioncue.sdk.VehicleConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SourceMode { MOCK, LIVE }

data class SessionState(
    val mode: SourceMode = SourceMode.MOCK,
    val source: SourceStatus = SourceStatus(),
    val cue: CueState = CueState(),
    val config: CueConfig = CueConfig(),
    val lightingEnabled: Boolean = false,
    val actuatorStatus: String = "화면 전용",
    val recoverySeats: Set<Seat> = emptySet(),
    val logs: List<String> = emptyList(),
    val busy: Boolean = false,
    val foreground: Boolean = false,
) {
    // Pausing cannot prove that a moving vehicle stopped. Mock remains available as a safe fallback.
    val settingsLocked: Boolean get() = mode == SourceMode.LIVE && (!source.speedKnown || cue.speedKmh > 0.5)
}

/** All input, engine updates and commands run on the owning Main dispatcher. */
class CuePipeline(context: Context, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()
    private val commandMutex = Mutex()
    private val engine = CueEngine()
    private val connection = VehicleConnection(context, ::log)
    private val voice = PleosVoiceGuide(context, ::log)
    private val actuator = PleosCabinActuator(connection, scope, ::log)
    private val recovery = RecoveryController(connection, scope, voice, ::log)
    private val appContext = context.applicationContext
    private var source: MotionEventSource = MockScenarioSource(::log)
    private var eventsJob: Job? = null
    private var statusJob: Job? = null
    private var sourceGeneration = 0
    private var lastEventTimeMs = 0L
    private var closed = false
    private var activeCommand: Job? = null

    init {
        bindSource()
        scope.launch { actuator.status.collect { value -> mutableState.update { it.copy(actuatorStatus = value) } } }
        scope.launch { recovery.activeSeats.collect { value -> mutableState.update { it.copy(recoverySeats = value) } } }
        log("Mock 시나리오 준비 · 실제 차량 제어는 Live에서만 사용")
    }

    private fun bindSource() {
        val generation = ++sourceGeneration
        eventsJob?.cancel()
        statusJob?.cancel()
        val boundSource = source
        eventsJob = scope.launch {
            boundSource.events.collect { event ->
                if (generation != sourceGeneration || closed) return@collect
                lastEventTimeMs = event.timeMs
                val output = engine.process(event)
                mutableState.update { it.copy(cue = output.state) }
                actuator.render(output.state)
                output.logs.forEach { log("${formatTime(it.timeMs)} ${it.message}") }
            }
        }
        statusJob = scope.launch {
            var wasPlaying = false
            boundSource.status.collect { value ->
                if (generation != sourceGeneration || closed) return@collect
                mutableState.update { it.copy(source = value) }
                val ended = wasPlaying && !value.playing && value.durationMs?.let { value.elapsedMs >= it } == true
                wasPlaying = value.playing
                if (ended) command {
                    if (generation != sourceGeneration) return@command
                    restoreOutputs()
                    engine.reset()
                    mutableState.update { it.copy(cue = CueState()) }
                    log("시나리오 종료 · 처음부터 버튼으로 다시 재생할 수 있습니다")
                }
            }
        }
    }

    fun setForeground(foreground: Boolean) {
        mutableState.update { it.copy(foreground = foreground) }
        if (!foreground) {
            activeCommand?.cancel()
            command {
                source.pause()
                restoreOutputs()
                log("화면 비활성화 · 재생 중지 및 차량 원상복원 요청")
            }
        }
    }

    fun playPause() = command {
        if (state.value.source.playing) {
            source.pause()
            restoreOutputs()
            log("일시정지")
        } else {
            if (!state.value.foreground) return@command
            if (state.value.mode == SourceMode.LIVE) {
                engine.reset()
                mutableState.update { it.copy(cue = CueState()) }
                connection.initialize()
                actuator.start(state.value.lightingEnabled)
            }
            source.play()
        }
    }

    fun restart() = command {
        source.pause()
        restoreOutputs()
        source.reset()
        engine.reset()
        mutableState.update { it.copy(cue = CueState()) }
        log("처음부터 · 엔진 상태 초기화")
    }

    fun selectMode(mode: SourceMode) = command {
        if (mode == state.value.mode) return@command
        source.pause()
        restoreOutputs()
        source.close()
        engine.reset()
        source = if (mode == SourceMode.MOCK) MockScenarioSource(::log) else LiveSdkSource(appContext, connection, ::log)
        mutableState.update { it.copy(mode = mode, cue = CueState(), source = SourceStatus()) }
        bindSource()
        log("${mode.name} 모드 · 재생을 눌러 시작")
    }

    fun configure(config: CueConfig, lightingEnabled: Boolean) = command {
        if (state.value.settingsLocked) { log("Live 주행 중 설정 잠금"); return@command }
        engine.updateConfig(config)
        val refreshed = engine.process(MotionEvent.Tick(lastEventTimeMs))
        val wasEnabled = state.value.lightingEnabled
        mutableState.update { it.copy(config = config, lightingEnabled = lightingEnabled, cue = refreshed.state) }
        actuator.render(refreshed.state)
        refreshed.logs.forEach { log(it.message) }
        if (state.value.foreground && state.value.mode == SourceMode.LIVE && state.value.source.playing && wasEnabled != lightingEnabled) {
            actuator.stop()
            actuator.start(lightingEnabled)
            actuator.render(state.value.cue)
        }
        log("설정 적용 · 예고 ${config.leadTimeSec}초 / ${config.sensitivity.label}")
    }

    fun toggleRecovery(seat: Seat) = command {
        if (seat.isDriver || !state.value.foreground) return@command
        if (state.value.mode == SourceMode.LIVE && !state.value.source.playing) {
            log("회복 개입은 Live 재생 중에 사용할 수 있습니다")
            return@command
        }
        recovery.toggle(seat, simulate = state.value.mode == SourceMode.MOCK)
    }

    fun reportPermissionResult(granted: Boolean) {
        log(if (granted) "Live 런타임 권한 확인 완료" else "일부 Live 권한 미허용 · 허용된 입력만 사용합니다")
    }

    private suspend fun restoreOutputs() {
        try { actuator.stop() } finally { recovery.restoreAll() }
    }

    /** Stop and restore before releasing SDKs, even if the ViewModel job is being cancelled. */
    suspend fun close() = withContext(NonCancellable) {
        commandMutex.withLock {
            if (closed) return@withLock
            closed = true
            ++sourceGeneration
            eventsJob?.cancel()
            statusJob?.cancel()
            try { source.close() } catch (e: Exception) { log("소스 종료 실패: ${e.message}") }
            try { restoreOutputs() } catch (e: Exception) { log("원상복원 실패: ${e.message}") }
            voice.close()
            connection.release()
        }
    }

    private fun command(block: suspend () -> Unit) {
        scope.launch {
            commandMutex.withLock {
                if (closed) return@withLock
                activeCommand = currentCoroutineContext()[Job]
                mutableState.update { it.copy(busy = true) }
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { log("작업 실패: ${e.message ?: e.javaClass.simpleName}") }
                finally {
                    activeCommand = null
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun log(message: String) {
        mutableState.update { it.copy(logs = (it.logs + message).takeLast(120)) }
    }

    private fun formatTime(timeMs: Long): String = if (state.value.mode == SourceMode.MOCK)
        "%.1fs".format(java.util.Locale.ROOT, timeMs / 1000.0) else "Live"
}
