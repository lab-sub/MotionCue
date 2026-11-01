package app.motioncue.recovery

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import app.motioncue.actuator.SafeAreas
import app.motioncue.core.model.Seat
import app.motioncue.sdk.VehicleConnection
import app.motioncue.sdk.PendingSdkWrites
import app.motioncue.sdk.awaitSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One saved value per physical property/area, shared by all passenger requests using that area. */
class RecoveryController(
    private val connection: VehicleConnection,
    private val scope: CoroutineScope,
    private val voice: VoiceGuide,
    private val log: (String) -> Unit,
) {
    private data class Key(val property: String, val area: Any)
    private class Entry(val owners: MutableSet<Seat>, val restore: suspend () -> Unit, var uncertain: Boolean = false)
    private val entries = linkedMapOf<Key, Entry>()
    private val simulated = mutableSetOf<Seat>()
    private val mutex = Mutex()
    private val writes = PendingSdkWrites { settle ->
        scope.launch(Dispatchers.Main.immediate) { mutex.withLock { settle() } }
    }
    private val mutableActive = MutableStateFlow<Set<Seat>>(emptySet())
    val activeSeats = mutableActive.asStateFlow()

    suspend fun toggle(seat: Seat, simulate: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        if (seat.isDriver) return@withContext
        mutex.withLock {
            if (seat in mutableActive.value) {
                withContext(NonCancellable) { releaseSeat(seat) }
                return@withLock
            }
            if (simulate) {
                simulated += seat
                publish()
                log("${seat.label} 회복 시연: 풍량 +2 · 온도 −1.5℃ · 창문 20% (화면·음성만)")
                voice.speak("편안하게 호흡하고 먼 곳을 바라보세요.")
                return@withLock
            }
            if (writes.hasPending) {
                log("이전 회복 요청 응답 대기 중 · 결과가 확인될 때까지 새 차량 제어를 시작하지 않음")
                return@withLock
            }
            if (entries.values.any { it.uncertain }) {
                log("이전 회복 개입의 복원이 끝나지 않음 · 활성 좌석을 다시 눌러 복원 재시도")
                return@withLock
            }
            if (!connection.initialize()) {
                log("차량 연결 불가: ${seat.label} 회복 개입 생략")
                return@withLock
            }
            try {
                attempt("풍량") { applyFan(seat) }
                attempt("온도") { applyTemperature(seat) }
                attempt("창문") { applyWindow(seat) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { releaseSeat(seat) }
                throw e
            }
            publish()
            if (seat in mutableActive.value) {
                voice.speak("편안하게 호흡하고 먼 곳을 바라보세요.")
                log("${seat.label} 회복 요청 완료 · 다시 누르면 저장한 값으로 복원 (실제 상태 확인은 별도)")
            } else log("${seat.label}: 독립 구역·읽기·쓰기 조건을 만족하는 회복 개입 없음")
        }
    }

    suspend fun restoreAll() = withContext(Dispatchers.Main.immediate + NonCancellable) {
        mutex.withLock {
            simulated.clear()
            for (key in entries.keys.toList()) restoreEntry(key)
            publish()
        }
    }

    private suspend fun applyFan(seat: Seat) {
        val hvac = connection.vehicle.getHVAC()
        val (config, areas) = awaitSdk<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { done, fail ->
            hvac.checkHvacFanSpeedCapabilities({ capability, bounds -> done(capability to bounds) }, fail)
        }
        if (config.accessType != AccessType.READ_WRITE) return
        val safe = SafeAreas.lightAreas(areas.keys.toList())
        for ((area, bounds) in areas) {
            if (seat !in safe[area].orEmpty()) continue
            val key = Key("풍량", area)
            if (shareExisting(key, seat)) continue
            attempt("${seat.label} 풍량 구역") {
                val initial = awaitSdk<Int?> { done, fail -> hvac.getHvacFanSpeed(area, done, fail) } ?: return@attempt
                val target = RecoveryMath.fan(initial, bounds.minValue, bounds.maxValue) ?: return@attempt
                if (target == initial) return@attempt
                suspend fun set(value: Int, settled: (suspend () -> Unit)? = null) = writes.await<Unit>(settled) { done, fail ->
                    hvac.setHvacFanSpeed(area, value, { _, _ -> done(Unit) }, fail)
                }
                apply(key, seat, { set(initial) }) { settled -> set(target, settled) }
            }
        }
    }

    private suspend fun applyTemperature(seat: Seat) {
        val hvac = connection.vehicle.getHVAC()
        val result = awaitSdk<Triple<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>> { done, fail ->
            hvac.checkHvacTemperatureCapabilities({ config, bounds, grid -> done(Triple(config, bounds, grid)) }, fail)
        }
        val (config, areas, reportedGrid) = result
        val grid = reportedGrid ?: run {
            log("온도 단계·섭씨 범위 정보 없음 · 온도 개입 생략")
            return
        }
        if (config.accessType != AccessType.READ_WRITE) return
        val safe = SafeAreas.lightAreas(areas.keys.toList())
        for ((area, bounds) in areas) {
            if (seat !in safe[area].orEmpty()) continue
            val key = Key("온도", area)
            if (shareExisting(key, seat)) continue
            attempt("${seat.label} 온도 구역") {
                val initial = awaitSdk<Float?> { done, fail -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, done, fail) } ?: return@attempt
                val target = RecoveryMath.temperature(initial, maxOf(bounds.minValue, grid.minCelsius), minOf(bounds.maxValue, grid.maxCelsius), grid.incrementCelsius, grid.minCelsius) ?: return@attempt
                if (target == initial) return@attempt
                suspend fun set(value: Float, settled: (suspend () -> Unit)? = null) = writes.await<Unit>(settled) { done, fail ->
                    hvac.setHvacTemperature(area, value, TemperatureUnit.CELSIUS, { _, _ -> done(Unit) }, fail)
                }
                apply(key, seat, { set(initial) }) { settled -> set(target, settled) }
            }
        }
    }

    private suspend fun applyWindow(seat: Seat) {
        val window = connection.vehicle.getWindow()
        val (config, areas) = awaitSdk<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { done, fail ->
            window.checkWindowPositionCapabilities({ capability, bounds -> done(capability to bounds) }, fail)
        }
        if (config.accessType != AccessType.READ_WRITE) return
        val safe = SafeAreas.windowAreas(areas.keys.toList())
        for ((area, bounds) in areas) {
            if (seat !in safe[area].orEmpty()) continue
            val key = Key("창문", area)
            if (shareExisting(key, seat)) continue
            attempt("${seat.label} 창문 구역") {
                val initial = awaitSdk<Int?> { done, fail -> window.getCurrentWindowPosition(area, done, fail) } ?: return@attempt
                val target = RecoveryMath.window(initial, bounds.minValue, bounds.maxValue) ?: return@attempt
                if (target == initial) return@attempt
                suspend fun set(value: Int, settled: (suspend () -> Unit)? = null) = writes.await<Unit>(settled) { done, fail ->
                    window.setWindowPosition(area, value, { _, _ -> done(Unit) }, fail)
                }
                apply(key, seat, { set(initial) }) { settled -> set(target, settled) }
            }
        }
    }

    private fun shareExisting(key: Key, seat: Seat): Boolean {
        val existing = entries[key] ?: return false
        existing.owners += seat
        return true
    }

    private suspend fun apply(key: Key, seat: Seat, restore: suspend () -> Unit, write: suspend (suspend () -> Unit) -> Unit) {
        // Capture BEFORE submission: failure/timeout does not prove that a write had no effect.
        val entry = Entry(mutableSetOf(seat), restore)
        entries[key] = entry
        val settled: suspend () -> Unit = {
            if (entry.uncertain) {
                // No newer request may take ownership while this result was outstanding.
                // Repeat rollback after a late completion, even if an earlier rollback succeeded.
                entries.putIfAbsent(key, entry)
                restoreEntry(key)
                publish()
            }
        }
        withContext(NonCancellable) {
            try { write(settled) }
            catch (e: Exception) {
                entry.uncertain = true
                log("${seat.label} ${key.property} 요청 실패: ${e.message}; 원래 값 복원 시도")
                restoreEntry(key)
            }
        }
    }

    private suspend fun releaseSeat(seat: Seat) {
        simulated -= seat
        for ((key, entry) in entries.toMap()) {
            if (seat !in entry.owners) continue
            if (entry.owners.size > 1) entry.owners -= seat else restoreEntry(key)
        }
        publish()
        log(if (seat in mutableActive.value) "${seat.label} 일부 복원 실패 · 다시 눌러 재시도" else "${seat.label} 회복 종료")
    }

    private suspend fun restoreEntry(key: Key) {
        val entry = entries[key] ?: return
        try {
            entry.restore()
            entries.remove(key)
            log("${key.property} 원래 값 복원 요청 완료 (실제 상태 확인은 별도)")
        } catch (e: Exception) {
            entry.uncertain = true
            log("${key.property} 원래 값 복원 실패: ${e.message} · 재시도용 원래 값 유지")
        }
    }

    private suspend fun attempt(label: String, block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("$label 지원 확인·읽기 실패: ${e.message}") }
    }

    private fun publish() { mutableActive.value = simulated + entries.values.flatMap { it.owners } }
}
