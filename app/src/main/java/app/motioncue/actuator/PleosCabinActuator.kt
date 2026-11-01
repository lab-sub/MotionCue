package app.motioncue.actuator

import android.os.SystemClock
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import app.motioncue.core.actuation.FadePlanner
import app.motioncue.core.engine.CueState
import app.motioncue.core.model.Seat
import app.motioncue.sdk.VehicleConnection
import app.motioncue.sdk.PendingSdkWrites
import app.motioncue.sdk.awaitSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Called on Main. SDK callbacks only resume suspensions; mutable state stays on this dispatcher. */
class PleosCabinActuator(
    private val connection: VehicleConnection,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) : CabinActuator {
    private val mutableStatus = MutableStateFlow("화면 전용")
    override val status = mutableStatus.asStateFlow()
    private val lifecycle = Mutex()
    private val writes = PendingSdkWrites { settle ->
        scope.launch(Dispatchers.Main.immediate) {
            lifecycle.withLock {
                settle()
                if (!running) updateStoppedStatus()
            }
        }
    }
    private var running = false
    private var pump: Job? = null
    private var latest = CueState()
    private var generation = 0L
    private val original = linkedMapOf<SeatArea, Int>()
    private val areas = linkedMapOf<SeatArea, Set<Seat>>()
    private val failed = mutableSetOf<SeatArea>()
    private val planner = FadePlanner<SeatArea>()
    private val limiter = DispatchRateLimiter(SystemClock::elapsedRealtime)

    override suspend fun start(enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        lifecycle.withLock {
            stopInternal()
            if (!enabled) return@withLock
            if (writes.hasPending) {
                mutableStatus.value = "이전 조명 요청 응답 대기 · 제어 중지"
                log("시간 초과한 조명 요청의 결과가 아직 없어 새 세션을 시작하지 않음")
                return@withLock
            }
            if (original.isNotEmpty()) {
                mutableStatus.value = "이전 조명 복원 실패 · 제어 중지"
                log("이전 조명 복원이 끝나지 않아 새 조명 제어를 시작하지 않음")
                return@withLock
            }
            if (!connection.initialize()) {
                mutableStatus.value = "차량 연결 불가 · 화면 전용"
                return@withLock
            }
            val light = connection.vehicle.getLight()
            try {
                val (config, supported) = awaitSdk<Pair<CapabilityConfig, List<SeatArea>>> { done, fail ->
                    light.checkAmbientLightBrightnessColorCapabilities({ capability, list -> done(capability to list) }, fail)
                }
                if (config.accessType != AccessType.READ_WRITE) {
                    mutableStatus.value = "조명 읽기·쓰기 미지원 · 화면 전용"
                    log("조명 access=${config.accessType}: 원래 색을 읽고 복원할 수 없어 제외")
                    return@withLock
                }
                val safe = SafeAreas.lightAreas(supported)
                if (safe.size != supported.size) log("운전석 공유·알 수 없는 구역·중복 조명 구역 제외")
                for ((area, seats) in safe) {
                    try {
                        val color = awaitSdk<Int?> { done, fail -> light.getAmbientLightBrightnessColor(area, done, fail) }
                        if (color == null) {
                            log("${seats.label()}: 현재 조명 색이 없어 제어 제외")
                            continue
                        }
                        original[area] = color
                        areas[area] = seats
                        planner.setCurrent(area, color, SystemClock.elapsedRealtime())
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { log("${seats.label()} 조명 읽기 실패: ${e.message}") }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableStatus.value = "조명 지원 확인 실패 · 화면 전용"
                log("조명 지원 확인 실패: ${e.message}")
                return@withLock
            }
            if (areas.isEmpty()) {
                mutableStatus.value = "동승자 독립 조명 없음 · 화면 전용"
                return@withLock
            }
            running = true
            generation++
            val epoch = generation
            mutableStatus.value = "조명 제어 ${areas.size}구역 · 요청 상태"
            pump = scope.launch(Dispatchers.Main.immediate) {
                while (running) {
                    val now = SystemClock.elapsedRealtime()
                    for ((area, seats) in areas) {
                        if (area in failed) continue
                        // A shared passenger area is changed only when EVERY member requests the same cue.
                        // This avoids lighting a left seat during a right-only cue (or a deselected seat).
                        val cues = seats.map { latest.zones[it] }
                        val colors = cues.mapNotNull { it?.color }.distinct()
                        val target = if (cues.all { it != null } && colors.size == 1) colors.single() else original.getValue(area)
                        planner.setTarget(area, target, now)
                    }
                    for ((area, color) in planner.poll(now)) {
                        if (!running || area in failed) continue
                        try {
                            // Reserve at actual dispatch, after earlier areas' asynchronous calls.
                            if (!limiter.acquire(area) { running }) break
                            // Let an already submitted write complete before stop sends a restoration.
                            withContext(NonCancellable) { setColor(area, color, epoch) }
                        } catch (e: Exception) {
                            failed += area
                            mutableStatus.value = "조명 일부 실패 · 로그 확인"
                            log("${areas[area]?.label()} 조명 요청 실패: ${e.message}; 해당 구역 제어 중지")
                            withContext(NonCancellable) { restore(area) }
                        }
                    }
                    delay(50)
                }
            }
        }
    }

    override fun render(state: CueState) { latest = state }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate + NonCancellable) {
        lifecycle.withLock { stopInternal() }
    }

    private suspend fun stopInternal() {
        running = false
        pump?.join()
        pump = null
        for (area in original.keys.toList()) restore(area)
        areas.clear()
        failed.clear()
        planner.clear()
        latest = CueState()
        updateStoppedStatus()
    }

    private fun updateStoppedStatus() {
        mutableStatus.value = when {
            writes.hasPending -> "이전 조명 요청 응답 대기 · 제어 중지"
            original.isNotEmpty() -> "조명 복원 실패 · 로그 확인"
            else -> "화면 전용"
        }
    }

    private suspend fun setColor(area: SeatArea, color: Int, epoch: Long) {
        val rollbackColor = original[area]
        writes.await<Unit>(onSettled = {
            if ((!running || area in failed) && generation == epoch && rollbackColor != null) {
                original.putIfAbsent(area, rollbackColor)
                restore(area)
            }
        }) { done, fail ->
            connection.vehicle.getLight().setAmbientLightBrightnessColor(area, color, { _, _ -> done(Unit) }, fail)
        }
    }

    private suspend fun restore(area: SeatArea): Boolean {
        val color = original[area] ?: return true
        return try {
            // Restoration and late-completion compensation obey the same vehicle call limit.
            limiter.acquire(area)
            writes.await<Unit> { done, fail ->
                connection.vehicle.getLight().setAmbientLightBrightnessColor(area, color, { _, _ -> done(Unit) }, fail)
            }
            original.remove(area)
            log("${areas[area]?.label() ?: "조명 구역"} 원래 색 복원 요청 완료 (실제 상태 확인은 별도)")
            true
        } catch (e: Exception) {
            log("조명 원래 색 복원 실패: ${e.message} · 다음 종료 때 재시도")
            false
        }
    }

    private fun Set<Seat>.label() = joinToString { it.label }
}
