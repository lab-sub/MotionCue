package app.motioncue.sdk

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context

/** Shared Vehicle SDK lifetime. All owners use this from the main dispatcher. */
class VehicleConnection(context: Context, private val log: (String) -> Unit) {
    val vehicle = Vehicle(context.applicationContext)
    var initialized: Boolean = false
        private set

    fun initialize(): Boolean {
        if (initialized) return true
        return try {
            vehicle.initialize()
            initialized = true
            log("Vehicle SDK 초기화 요청 완료")
            true
        } catch (error: Exception) {
            log("Vehicle SDK 초기화 실패: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    fun release() {
        if (!initialized) return
        try {
            vehicle.release()
        } catch (error: Exception) {
            log("Vehicle SDK 해제 실패: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            initialized = false
        }
    }
}
