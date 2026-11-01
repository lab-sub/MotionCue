package app.motioncue.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import app.motioncue.MainActivity
import app.motioncue.core.engine.Upcoming
import app.motioncue.core.model.Seat
import app.motioncue.session.CuePipeline
import app.motioncue.session.SessionState
import app.motioncue.session.SourceMode
import app.motioncue.ui.MainViewModel
import app.motioncue.verification.VehicleSdkSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug instrumentation only: observe the production pipeline; never inject MotionEvents. */
class LiveSessionProbe : Instrumentation() {
    private var arguments = Bundle()
    private val activityCreated = CountDownLatch(1)
    @Volatile private var activity: MainActivity? = null
    private var pipeline: CuePipeline? = null
    private val outputLock = Any()
    private var writer: BufferedWriter? = null
    private var outputFile: File? = null
    private var sequence = 0L
    private var liveStartedAt: Long? = null
    private var mirrorLogcat = false
    private var sdkSnapshots = false
    private var previousLogs: List<String> = emptyList()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        super.callActivityOnCreate(activity, icicle)
        if (activity is MainActivity) {
            this.activity = activity
            activityCreated.countDown()
        }
    }

    override fun callActivityOnStart(activity: Activity) {
        super.callActivityOnStart(activity)
        if (activity is MainActivity) emit("lifecycle", JSONObject().put("callback", "onStart"))
    }

    override fun callActivityOnStop(activity: Activity) {
        super.callActivityOnStop(activity)
        if (activity is MainActivity) emit("lifecycle", JSONObject().put("callback", "onStop"))
    }

    override fun onStart() {
        val result = Bundle()
        var success = false
        try {
            val options = Options.parse(arguments)
            mirrorLogcat = options.logcat
            sdkSnapshots = options.sdkSnapshot
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "Probe requires a debuggable target APK"
            }
            if (options.lightingEnabled || options.recoverySeat != null) {
                check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
                    "Actuation options are restricted to ranchu/goldfish emulators; hardware=${Build.HARDWARE}"
                }
            }
            // Pleos multi-user external storage may be unreadable even to adb/run-as.
            // Internal app storage can be retrieved using run-as for the actual foreground user.
            val directory = File(targetContext.filesDir, "motioncue-probe")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create probe output directory" }
            val runId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            outputFile = File(directory, "${options.runName}-$runId.jsonl")
            check(outputFile!!.createNewFile()) { "Probe output already exists" }
            writer = outputFile!!.bufferedWriter(Charsets.UTF_8)
            recoverLegacyTraces(directory)
            emit("start", options.toJson().put("hardware", Build.HARDWARE)
                .put("model", Build.MODEL).put("sdkInt", Build.VERSION.SDK_INT)
                .put("entryPoint", "MainActivity / existing MainViewModel / production CuePipeline")
                .put("fakeMotionEvents", false).put("permissionDialogExercised", false)
                .put("permissions", permissionSnapshot()))
            sendStatus(1, Bundle().apply {
                putString("phase", "launching")
                putString("artifact_path", outputFile!!.absolutePath)
            })

            // startActivitySync/waitForIdleSync are deliberately avoided: Compose and SDK ticks stay live.
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            check(activityCreated.await(20, TimeUnit.SECONDS)) { "MainActivity did not start within 20 seconds" }
            runOnMainSync {
                pipeline = ViewModelProvider(requireNotNull(activity))[MainViewModel::class.java].pipeline
            }
            waitForState("foreground", 20_000L) { it.foreground && !it.busy }
            if (options.tts) KoreanTtsProbe(this, targetContext, runId, ::emit).run()
            if (sdkSnapshots) captureVehicle("before_live")

            // Configure the default Mock session before entering Live, as the normal UI permits.
            // No source playback, fabricated input, reflection, or private-field mutation is involved.
            runOnMainSync { requireNotNull(pipeline).configure(readState().config, options.lightingEnabled) }
            waitForState("configuration", 10_000L) { !it.busy && it.lightingEnabled == options.lightingEnabled }
            runOnMainSync { requireNotNull(pipeline).selectMode(SourceMode.LIVE) }
            waitForState("Live selected", 20_000L) { it.mode == SourceMode.LIVE && !it.busy }
            runOnMainSync { requireNotNull(pipeline).playPause() }
            waitForState("Live playing", 45_000L) { it.source.playing && !it.busy }
            liveStartedAt = SystemClock.elapsedRealtime()
            emit("live_started", JSONObject().put("durationMs", options.durationMs))
            sendStatus(1, Bundle().apply {
                putString("phase", "live_started")
                putString("artifact_path", outputFile!!.absolutePath)
                putLong("elapsed_realtime_ms", liveStartedAt!!)
            })

            val actions = options.actions().iterator()
            var next = if (actions.hasNext()) actions.next() else null
            while (SystemClock.elapsedRealtime() - liveStartedAt!! < options.durationMs) {
                val elapsed = SystemClock.elapsedRealtime() - liveStartedAt!!
                while (next != null && elapsed >= next.atMs) {
                    perform(next)
                    next = if (actions.hasNext()) actions.next() else null
                }
                observe("sample")
                SystemClock.sleep(options.sampleMs)
            }
            observe("end_of_observation")
            success = true
        } catch (error: Throwable) {
            emit("probe_error", JSONObject().put("type", error.javaClass.name).put("message", error.message))
            Log.e(TAG, "Probe failed", error)
            result.putString("error", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            try {
                if (pipeline != null) {
                    emit("cleanup_requested", JSONObject().put("method", "MainActivity.finish / onStop / onCleared"))
                    runOnMainSync { activity?.finish() }
                    waitForState("lifecycle cleanup", 45_000L) { !it.foreground && !it.source.playing && !it.busy }
                    observe("cleanup_observed")
                    if (sdkSnapshots) captureVehicle("after_cleanup")
                }
            } catch (error: Throwable) {
                success = false
                emit("cleanup_error", JSONObject().put("message", error.message))
                result.putString("cleanup_error", error.message)
            }
            emit("finish", JSONObject().put("status", if (success) "observation_complete" else "probe_failed")
                .put("hardwareRestorationVerified", false))
            result.putString("status", if (success) "observation_complete" else "probe_failed")
            result.putString("artifact_path", outputFile?.absolutePath)
            synchronized(outputLock) { writer?.close(); writer = null }
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }

    private fun perform(action: Action) {
        if (action.kind == "snapshot") {
            emit("action", JSONObject().put("kind", action.kind).put("scheduledAtMs", action.atMs)
                .put("outcome", "capture_started"))
            // This method runs on the instrumentation worker, outside runOnMainSync.
            captureVehicle("scheduled")
            return
        }
        var outcome = "requested"
        runOnMainSync {
            val current = readState()
            when (action.kind) {
                "pause" -> if (current.source.playing) requireNotNull(pipeline).playPause() else outcome = "already_paused"
                "resume" -> if (!current.source.playing && current.foreground) requireNotNull(pipeline).playPause()
                    else outcome = "skipped_not_paused_or_foreground"
                "background" -> outcome = if (activity?.moveTaskToBack(true) == true) "task_moved_back" else "task_move_rejected"
                "foreground" -> targetContext.startActivity(Intent(targetContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                "recovery_on" -> if (current.source.playing && current.foreground && action.seat !in current.recoverySeats)
                    requireNotNull(pipeline).toggleRecovery(requireNotNull(action.seat)) else outcome = "skipped_state"
                "recovery_off" -> if (action.seat in current.recoverySeats)
                    requireNotNull(pipeline).toggleRecovery(requireNotNull(action.seat)) else outcome = "not_active"
            }
        }
        emit("action", JSONObject().put("kind", action.kind).put("scheduledAtMs", action.atMs)
            .put("seat", action.seat?.name ?: JSONObject.NULL).put("outcome", outcome))
    }

    private fun captureVehicle(phase: String) {
        try {
            // Instrumentation worker thread only; SDK helper switches to Main without blocking it.
            val snapshot = runBlocking { VehicleSdkSnapshot.capture(targetContext) }
            emit("vehicle_snapshot", JSONObject().put("phase", phase).put("snapshot", snapshot))
        } catch (error: Exception) {
            emit("vehicle_snapshot", JSONObject().put("phase", phase).put("error", error.message))
        }
    }

    /** Preserve earlier external traces by copying only this runner's generated JSONL filenames. */
    private fun recoverLegacyTraces(destinationDirectory: File) {
        try {
            val external = targetContext.getExternalFilesDir("motioncue-probe") ?: return
            if (!external.isDirectory) return
            val parent = external.canonicalFile
            val generatedName = Regex("[A-Za-z0-9_-]*-[0-9]{13}-[0-9a-f]{8}\\.jsonl")
            external.listFiles().orEmpty().sortedBy { it.name }.forEach { source ->
                if (!source.isFile || !generatedName.matches(source.name) || source.canonicalFile.parentFile != parent) return@forEach
                val preferred = File(destinationDirectory, "imported-${source.name}")
                if (preferred.isFile && preferred.length() == source.length() && sameBytes(source, preferred)) {
                    emit("legacy_trace_import", JSONObject().put("status", "already_copied")
                        .put("source", source.absolutePath).put("path", preferred.absolutePath).put("bytes", preferred.length()))
                    return@forEach
                }
                // An incomplete prior copy is retained. Never overwrite it or the original trace.
                val destination = if (!preferred.exists()) preferred
                    else File(destinationDirectory, "imported-${UUID.randomUUID().toString().take(8)}-${source.name}")
                try {
                    check(destination.createNewFile()) { "Import destination already exists" }
                    source.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                    check(sameBytes(source, destination)) { "Copied trace differs from its source" }
                    emit("legacy_trace_import", JSONObject().put("status", "copied")
                        .put("source", source.absolutePath).put("path", destination.absolutePath).put("bytes", destination.length()))
                    sendStatus(1, Bundle().apply {
                        putString("phase", "legacy_trace_imported")
                        putString("artifact_path", destination.absolutePath)
                    })
                } catch (error: Exception) {
                    emit("legacy_trace_import", JSONObject().put("status", "copy_failed")
                        .put("source", source.absolutePath).put("path", destination.absolutePath).put("error", error.message))
                }
            }
        } catch (error: Exception) {
            emit("legacy_trace_import", JSONObject().put("status", "scan_failed").put("error", error.message))
        }
    }

    private fun sameBytes(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        first.inputStream().buffered().use { left ->
            second.inputStream().buffered().use { right ->
                while (true) {
                    val a = left.read()
                    if (a != right.read()) return false
                    if (a < 0) return true
                }
            }
        }
    }

    /** StateFlow's immutable snapshot can be read without waiting for the UI to become idle. */
    private fun readState(): SessionState = requireNotNull(pipeline).state.value

    private fun waitForState(label: String, timeoutMs: Long, predicate: (SessionState) -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            val current = readState()
            observe("waiting:$label", current)
            if (predicate(current)) return
            SystemClock.sleep(100L)
        }
        error("Timed out waiting for $label after ${timeoutMs}ms")
    }

    private fun observe(phase: String, current: SessionState = readState()) {
        val overlapping = (minOf(previousLogs.size, current.logs.size) downTo 0).first { size ->
            previousLogs.takeLast(size) == current.logs.take(size)
        }
        val newLogs = current.logs.drop(overlapping)
        previousLogs = current.logs
        val zones = JSONObject()
        current.cue.zones.forEach { (seat, cue) -> zones.put(seat.name, JSONObject()
            .put("kind", cue.kind.name).put("label", cue.label)
            .put("argb", String.format(java.util.Locale.ROOT, "#%08X", cue.color))) }
        emit("state", JSONObject().put("phase", phase).put("mode", current.mode.name)
            .put("foreground", current.foreground).put("busy", current.busy)
            .put("playing", current.source.playing).put("sourceElapsedMs", current.source.elapsedMs)
            .put("sourceDetail", current.source.detail).put("speedKnown", current.source.speedKnown)
            .put("speedKmh", current.cue.speedKmh).put("steeringDeg", current.cue.steeringDeg)
            .put("brakePressed", current.cue.brakePressed).put("decelMps2", current.cue.decelMps2)
            .put("activeKinds", JSONArray(current.cue.activeKinds.map { it.name }))
            .put("zones", zones).put("nextTurn", upcoming(current.cue.nextTurn))
            .put("nextCaution", upcoming(current.cue.nextCaution))
            .put("lightingEnabled", current.lightingEnabled).put("actuatorStatus", current.actuatorStatus)
            .put("recoverySeats", JSONArray(current.recoverySeats.map { it.name }))
            .put("settingsLocked", current.settingsLocked).put("newLogs", JSONArray(newLogs)))
    }

    private fun upcoming(value: Upcoming?): Any = value?.let {
        JSONObject().put("label", it.label).put("distanceM", it.distanceM)
            .put("etaSec", it.etaSec ?: JSONObject.NULL).put("cueActive", it.cueActive)
    } ?: JSONObject.NULL

    private fun permissionSnapshot(): JSONObject {
        val values = JSONObject()
        @Suppress("DEPRECATION")
        val permissions = targetContext.packageManager.getPackageInfo(targetContext.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        permissions.filter { it.startsWith("pleos.") }.forEach {
            values.put(it, targetContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
        }
        return values
    }

    private fun emit(type: String, data: JSONObject) {
        synchronized(outputLock) {
            val now = SystemClock.elapsedRealtime()
            val json = JSONObject().put("sequence", ++sequence).put("type", type)
                .put("elapsedRealtimeMs", now).put("wallTimeMs", System.currentTimeMillis())
                .put("probeElapsedMs", liveStartedAt?.let { now - it } ?: JSONObject.NULL)
                .put("data", data).toString()
            writer?.apply { write(json); newLine(); flush() }
            if (mirrorLogcat || type != "state") Log.i(TAG, json)
        }
    }

    private data class Action(val atMs: Long, val kind: String, val seat: Seat? = null)

    private data class Options(
        val durationMs: Long, val sampleMs: Long, val runName: String,
        val lightingEnabled: Boolean, val recoverySeat: Seat?, val recoveryAtMs: Long,
        val recoveryOffAtMs: Long, val pauseAtMs: Long, val resumeAtMs: Long,
        val backgroundAtMs: Long, val foregroundAtMs: Long, val tts: Boolean, val logcat: Boolean,
        val sdkSnapshot: Boolean, val snapshotAtMs: Long,
    ) {
        fun actions(): List<Action> = buildList {
            if (recoverySeat != null) {
                add(Action(recoveryAtMs, "recovery_on", recoverySeat))
                if (recoveryOffAtMs >= 0) add(Action(recoveryOffAtMs, "recovery_off", recoverySeat))
            }
            listOf(pauseAtMs to "pause", resumeAtMs to "resume", backgroundAtMs to "background", foregroundAtMs to "foreground")
                .filter { it.first >= 0 }.forEach { add(Action(it.first, it.second)) }
            if (snapshotAtMs >= 0L) add(Action(snapshotAtMs, "snapshot"))
        }.sortedBy { it.atMs }

        fun toJson() = JSONObject().put("durationMs", durationMs).put("sampleMs", sampleMs)
            .put("lightingEnabled", lightingEnabled).put("tts", tts).put("logcat", logcat).put("sdkSnapshot", sdkSnapshot)
            .put("scheduledActions", JSONArray(actions().map {
                JSONObject().put("atMs", it.atMs).put("kind", it.kind).put("seat", it.seat?.name ?: JSONObject.NULL)
            }))

        companion object {
            fun parse(bundle: Bundle): Options {
                fun long(name: String, default: Long) = bundle.getString(name)?.toLongOrNull()
                    ?: if (bundle.containsKey(name)) error("$name must be an integer") else default
                fun boolean(name: String) = when (val value = bundle.getString(name)) {
                    null, "false" -> false
                    "true" -> true
                    else -> error("$name must be true or false, got $value")
                }
                val duration = long("durationMs", 15_000L)
                val sample = long("sampleMs", 100L)
                require(duration in 1_000L..600_000L) { "durationMs must be 1000..600000" }
                require(sample in 50L..2_000L) { "sampleMs must be 50..2000" }
                val seat = bundle.getString("recoverySeat")?.let { Seat.valueOf(it) }
                require(seat?.isDriver != true) { "DRIVER recovery is never permitted" }
                val options = Options(duration, sample,
                    (bundle.getString("runName") ?: "live").replace(Regex("[^A-Za-z0-9_-]"), "_").take(50),
                    boolean("lightingEnabled"), seat, long("recoveryAtMs", -1L), long("recoveryOffAtMs", -1L),
                    long("pauseAtMs", -1L), long("resumeAtMs", -1L),
                    long("backgroundAtMs", -1L), long("foregroundAtMs", -1L), boolean("tts"), boolean("logcat"), boolean("sdkSnapshot"),
                    long("snapshotAtMs", -1L))
                if (seat != null) require(options.recoveryAtMs >= 0L) { "recoverySeat requires recoveryAtMs" }
                require(options.actions().all { it.atMs in 0 until duration }) { "Action times must be within durationMs" }
                require(options.recoveryOffAtMs < 0L || options.recoveryOffAtMs > options.recoveryAtMs) {
                    "recoveryOffAtMs must follow recoveryAtMs"
                }
                return options
            }
        }
    }

    companion object { private const val TAG = "MotionCueProbe" }
}
