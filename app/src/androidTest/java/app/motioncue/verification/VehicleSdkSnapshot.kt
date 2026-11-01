package app.motioncue.verification

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.os.SystemClock
import app.motioncue.sdk.awaitSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Instrumentation-only, read-only capability and value snapshot. Includes driver/shared areas
 * for comparison, without using any setter. Call from a coroutine; do not runBlocking on Main.
 * The four property groups have at most one outstanding read each. awaitSdk bounds every
 * callback wait to 3 seconds, and a shared 20-second budget preserves partial results.
 */
object VehicleSdkSnapshot {
    private const val CALLBACK_BUDGET_MS = 20_000L

    suspend fun capture(context: Context): JSONObject = withContext(Dispatchers.Main.immediate) {
        val started = SystemClock.elapsedRealtime()
        val snapshot = JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("startedAtElapsedMs", started)
            .put("readOnly", true)
            .put("driverConvention", "ROW_1_LEFT")
            .put("callbackBudgetMs", CALLBACK_BUDGET_MS)
            .put("maximumConcurrentReads", 4)
        val sections = listOf("ambientColor", "fanSpeed", "temperatureSetpoint", "windowPosition")
            .associateWith { JSONObject().put("status", "not_started").put("areas", JSONArray()) }
        sections.forEach { (name, section) -> snapshot.put(name, section) }
        var vehicle: Vehicle? = null
        try {
            val connected = Vehicle(context.applicationContext)
            vehicle = connected
            connected.initialize()
            snapshot.put("initialize", "requested")
            val completed = withTimeoutOrNull(CALLBACK_BUDGET_MS) {
                supervisorScope {
                    listOf(
                        launch { captureGroup(sections.getValue("ambientColor")) { light(connected, it) } },
                        launch { captureGroup(sections.getValue("fanSpeed")) { fan(connected, it) } },
                        launch { captureGroup(sections.getValue("temperatureSetpoint")) { temperature(connected, it) } },
                        launch { captureGroup(sections.getValue("windowPosition")) { window(connected, it) } },
                    ).joinAll()
                }
                true
            } ?: false
            snapshot.put("callbackBudgetExceeded", !completed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            snapshot.put("captureError", errorJson(error))
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                try {
                    vehicle?.release()
                    snapshot.put("release", "requested")
                } catch (error: Exception) {
                    snapshot.put("releaseError", errorJson(error))
                }
            }
            snapshot.put("completedAtElapsedMs", SystemClock.elapsedRealtime())
                .put("durationMs", SystemClock.elapsedRealtime() - started)
        }
        snapshot
    }

    private suspend fun captureGroup(section: JSONObject, block: suspend (JSONObject) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        section.put("status", "running").put("startedAtElapsedMs", started)
        try {
            block(section)
            // Individual read errors remain in their area; completion does not imply all succeeded.
            section.put("status", "complete")
        } catch (error: CancellationException) {
            section.put("status", "cancelled_or_budget_exhausted")
            throw error
        } catch (error: Exception) {
            section.put("status", "error").put("error", errorJson(error))
        } finally {
            section.put("durationMs", SystemClock.elapsedRealtime() - started)
        }
    }

    private suspend fun light(vehicle: Vehicle, section: JSONObject) {
        val light = vehicle.getLight()
        val (config, areas) = awaitSdk<Pair<CapabilityConfig, List<SeatArea>>> { done, fail ->
            light.checkAmbientLightBrightnessColorCapabilities({ capability, values -> done(capability to values) }, fail)
        }
        section.put("access", config.accessType.name)
        val rows = areas.map { area -> area to seatArea(area) }
        rows.forEach { section.getJSONArray("areas").put(it.second) }
        for ((area, row) in rows) {
            read(row, config, color = true) {
                awaitSdk<Int?> { done, fail -> light.getAmbientLightBrightnessColor(area, done, fail) }
            }
        }
    }

    private suspend fun fan(vehicle: Vehicle, section: JSONObject) {
        val hvac = vehicle.getHVAC()
        val (config, areas) = awaitSdk<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { done, fail ->
            hvac.checkHvacFanSpeedCapabilities({ capability, values -> done(capability to values) }, fail)
        }
        section.put("access", config.accessType.name)
        val rows = areas.map { (area, bounds) -> area to seatArea(area).withBounds(bounds.minValue, bounds.maxValue) }
        rows.forEach { section.getJSONArray("areas").put(it.second) }
        for ((area, row) in rows) {
            read(row, config) { awaitSdk<Int?> { done, fail -> hvac.getHvacFanSpeed(area, done, fail) } }
        }
    }

    private suspend fun temperature(vehicle: Vehicle, section: JSONObject) {
        val hvac = vehicle.getHVAC()
        val (config, areas, grid) = awaitSdk<Triple<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>, HvacTemperatureConfigArray?>> { done, fail ->
            hvac.checkHvacTemperatureCapabilities({ capability, values, configArray ->
                done(Triple(capability, values, configArray))
            }, fail)
        }
        section.put("access", config.accessType.name)
            .put("unit", "CELSIUS")
            .put("propertyMeaning", "HVAC_TEMPERATURE_SET, not measured cabin temperature")
            .put("temperatureGrid", grid?.let {
                JSONObject().put("minCelsius", jsonValue(it.minCelsius))
                    .put("maxCelsius", jsonValue(it.maxCelsius))
                    .put("incrementCelsius", jsonValue(it.incrementCelsius))
                    .put("minFahrenheit", jsonValue(it.minFahrenheit))
                    .put("maxFahrenheit", jsonValue(it.maxFahrenheit))
                    .put("incrementFahrenheit", jsonValue(it.incrementFahrenheit))
                    .put("raw", JSONArray(it.raw))
            } ?: JSONObject.NULL)
        val rows = areas.map { (area, bounds) -> area to seatArea(area).withBounds(bounds.minValue, bounds.maxValue) }
        rows.forEach { section.getJSONArray("areas").put(it.second) }
        for ((area, row) in rows) {
            read(row, config) {
                awaitSdk<Float?> { done, fail -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, done, fail) }
            }
        }
    }

    private suspend fun window(vehicle: Vehicle, section: JSONObject) {
        val window = vehicle.getWindow()
        val (config, areas) = awaitSdk<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { done, fail ->
            window.checkWindowPositionCapabilities({ capability, values -> done(capability to values) }, fail)
        }
        section.put("access", config.accessType.name)
        val rows = areas.map { (area, bounds) -> area to windowArea(area).withBounds(bounds.minValue, bounds.maxValue) }
        rows.forEach { section.getJSONArray("areas").put(it.second) }
        for ((area, row) in rows) {
            read(row, config) { awaitSdk<Int?> { done, fail -> window.getCurrentWindowPosition(area, done, fail) } }
        }
    }

    private suspend fun read(
        area: JSONObject,
        config: CapabilityConfig,
        color: Boolean = false,
        getter: suspend () -> Any?,
    ) {
        val result = area.getJSONObject("read")
        if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) {
            result.put("status", "skipped_no_read_access")
            return
        }
        val started = SystemClock.elapsedRealtime()
        result.put("status", "pending").put("requestedAtElapsedMs", started)
        try {
            val value = getter()
            val nonFinite = value is Float && !value.isFinite() || value is Double && !value.isFinite()
            result.put("status", when { value == null -> "null"; nonFinite -> "non_finite"; else -> "value" })
                .put("value", jsonValue(value))
            if (color && value is Int) result.put("argbHex", String.format(Locale.ROOT, "#%08X", value))
        } catch (error: CancellationException) {
            result.put("status", "cancelled_or_budget_exhausted")
            throw error
        } catch (error: Exception) {
            result.put("status", "error").put("error", errorJson(error))
        } finally {
            result.put("completedAtElapsedMs", SystemClock.elapsedRealtime())
                .put("durationMs", SystemClock.elapsedRealtime() - started)
        }
    }

    private fun seatArea(area: SeatArea) = JSONObject()
        .put("type", "SeatArea")
        .put("values", JSONArray(area.values.map { it.name }))
        .put("ids", JSONArray(area.values.map { it.id }))
        .put("containsDriverLeft", area.values.any { it.name == "ROW_1_LEFT" })
        .put("read", JSONObject().put("status", "not_read"))

    private fun windowArea(area: WindowArea) = JSONObject()
        .put("type", "WindowArea")
        .put("values", JSONArray(area.values.map { it.name }))
        .put("ids", JSONArray(area.values.map { it.id }))
        .put("containsDriverLeft", area.values.any { it.name == "ROW_1_LEFT" })
        .put("read", JSONObject().put("status", "not_read"))

    private fun JSONObject.withBounds(min: Any?, max: Any?) =
        put("min", jsonValue(min)).put("max", jsonValue(max))

    private fun errorJson(error: Exception) = JSONObject()
        .put("type", error.javaClass.name).put("message", error.message ?: JSONObject.NULL)

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Float -> if (value.isFinite()) value else value.toString()
        is Double -> if (value.isFinite()) value else value.toString()
        else -> value
    }
}
