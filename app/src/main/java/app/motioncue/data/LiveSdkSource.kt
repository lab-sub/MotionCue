package app.motioncue.data

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.os.SystemClock
import app.motioncue.core.engine.ClosingSpeedEstimator
import app.motioncue.core.engine.PositionSpeedEstimator
import app.motioncue.core.engine.SpeedFusion
import app.motioncue.core.engine.SpeedSample
import app.motioncue.core.engine.SpeedSource
import app.motioncue.core.model.CautionInfo
import app.motioncue.core.model.MotionEvent
import app.motioncue.core.model.TurnInfo
import app.motioncue.sdk.VehicleConnection
import app.motioncue.sdk.awaitSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

/** All SDK callbacks are marshalled to Main; CueEngine never sees concurrent input. */
class LiveSdkSource(
    context: Context,
    private val connection: VehicleConnection,
    private val log: (String) -> Unit,
) : MotionEventSource {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableEvents = MutableSharedFlow<MotionEvent>(extraBufferCapacity = 64)
    override val events = mutableEvents.asSharedFlow()
    private val mutableStatus = MutableStateFlow(SourceStatus(detail = "Live · 대기"))
    override val status = mutableStatus.asStateFlow()
    private val fusedEstimator = PositionSpeedEstimator()
    private val naviEstimator = PositionSpeedEstimator()
    private val closingEstimator = ClosingSpeedEstimator()
    private val fusion = SpeedFusion()
    private val speedEvents = LiveSpeedEvents()
    private val routeGate = NavigationGuidanceGate()
    private val reportedErrors = mutableSetOf<String>()
    private var generation = 0L
    private var closed = false
    private var startedAt = 0L
    private var lastTbtAt: Long? = null
    private var lastCautionAt: Long? = null
    private var lastFusedTimestamp: Long? = null
    private var steeringRevision = 0L
    private var brakeRevision = 0L
    private var observedBrakePressed = false
    private var emittedBrakePressed = false
    private var naviReady = false
    private var startup: Job? = null
    private var callbackScope: CoroutineScope? = null
    private var ticks: Job? = null
    private var polling: Job? = null
    private var steeringHeartbeat: Job? = null
    private var brakeHeartbeat: Job? = null
    private var navi: NaviHelper? = null
    private var fused: FusedLocation? = null
    private var naviListener: NaviHelperEventListener? = null
    private var fusedListener: FusedLocationListener? = null
    private var steeringListener: SteeringWheelAngleListener? = null
    private var brakeListener: BrakePedalStatusListener? = null

    override suspend fun play() = withContext(Dispatchers.Main.immediate) {
        if (closed || mutableStatus.value.playing) return@withContext
        val token = ++generation
        callbackScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        resetEstimators()
        reportedErrors.clear()
        startedAt = SystemClock.elapsedRealtime()
        mutableStatus.value = SourceStatus(playing = true, detail = "Live · 연결 중 / 속도 대기")
        log("Live 시작 · 속도는 위치 또는 내비 거리로 추정")
        ticks = scope.launch {
            while (isActive && active(token)) {
                val now = SystemClock.elapsedRealtime()
                if (routeGate.expire(now)) {
                    clearRouteData(now)
                    reportOnce("내비 상태 수신 지연", "4초 이상 상태 미확인 · 경로 예고 차단")
                }
                val speed = currentSpeedSample(now)
                mutableStatus.value = mutableStatus.value.copy(
                    elapsedMs = now - startedAt,
                    speedKnown = speed != null,
                    detail = "Live · ${if (naviReady) "내비 연결" else "내비 미연결"} · ${speed?.source?.label ?: "속도 데이터 없음"}",
                )
                // Expiry is unknown speed, not physical braking. Neutralize before synthetic zero.
                publishBrake(now, speed != null)
                speedEvents.update(now, speed).forEach { mutableEvents.emit(it) }
                // TBT is polled once a second. A lost service must not keep extrapolating old turns.
                if (lastTbtAt?.let { now - it > 4_000L } == true) {
                    lastTbtAt = null
                    closingEstimator.reset()
                    routeGate.clearRouteSpeed()
                    mutableEvents.emit(MotionEvent.Tbt(now, emptyList()))
                    reportOnce("TBT 수신 지연", "4초 이상 응답 없음 · 예고 해제")
                }
                // Cautions have no public polling API; expire unrefreshed event data conservatively.
                if (lastCautionAt?.let { now - it > 10_000L } == true) {
                    lastCautionAt = null
                    mutableEvents.emit(MotionEvent.Caution(now, emptyList()))
                    reportOnce("주의 지점 수신 지연", "10초 이상 갱신 없음 · 예고 해제")
                }
                mutableEvents.emit(MotionEvent.Tick(now))
                delay(100L)
            }
        }
        startup = scope.launch {
            // Independent services remain useful when another SDK is unavailable.
            launch { startNavigation(token) }
            launch { startVehicleSignals(token) }
            launch { startFusedLocation(token) }
        }
    }

    private suspend fun startNavigation(token: Long) = attempt("NaviHelper 연결") {
        val helper = NaviHelper(appContext)
        navi = helper
        val listener = object : NaviHelperEventListener {
            override fun onTBTInfo(tbtInfo: List<TBTInfo>) = dispatch(token) { now ->
                if (!routeGate.allowsRouteData(now)) return@dispatch
                // 경로 head가 잘못된 경우에도 뒤쪽 leg가 앞으로 당겨지지 않도록 순서를 보존한다.
                val items = tbtInfo.map { TurnInfo(it.type.name, it.distance, it.description) }
                lastTbtAt = now
                closingEstimator.onRoute(now, items)
                    ?.let {
                        fusion.update(SpeedSource.ROUTE_DISTANCE, now, it)
                        routeGate.recordRouteSpeed(now)
                    }
                mutableEvents.emit(MotionEvent.Tbt(now, items))
            }

            override fun onCautionPointInfo(cautionPointInfo: List<CautionPoint>) = dispatch(token) { now ->
                if (!routeGate.allowsRouteData(now)) return@dispatch
                lastCautionAt = now
                mutableEvents.emit(MotionEvent.Caution(now, cautionPointInfo.filter { it.remainDistance >= 0 }.map {
                    CautionInfo(it.id, it.type.name, it.remainDistance.toDouble())
                }))
            }

            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = dispatch(token) { now ->
                naviEstimator.onPosition(now, currentLocationInfo.latitude, currentLocationInfo.longitude)
                    ?.let { fusion.update(SpeedSource.NAVI_LOCATION, now, it) }
            }

            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = dispatch(token) { now ->
                val mode = routeStateInfo.naviMode
                routeGate.onState(mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING, now)
                if (!routeGate.allowsRouteData(now)) clearRouteData(now)
                reportOnce("내비 안내 상태", mode.name)
            }

            override fun onRouteStarted(routeStartInfo: RouteStartInfo) = dispatch(token) { now ->
                routeGate.onStarted()
                clearRouteData(now)
                log("내비 새 경로 시작 · 안내 상태 재확인 대기")
            }

            // Navi42 also emits RouteEnded immediately after a normal RouteStarted.
            // Use explicit cancellation/free-drive status for the persistent end latch.
            override fun onRouteEnded() = dispatch(token) { now ->
                routeGate.invalidateState()
                clearRouteData(now)
                log("내비 경로 이벤트 · 안내 상태 재확인 대기")
            }
            override fun onRouteCancelled() = endRoute(token)
            override fun onNavigationStatus(status: NavigationStatus) {
                when (status) {
                    NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> endRoute(token)
                    NavigationStatus.INITIALIZING -> dispatch(token) { now ->
                        routeGate.invalidateState()
                        clearRouteData(now)
                    }
                    // An unsolicited or delayed positive status cannot reopen an ended route.
                    NavigationStatus.ROUTE_DRIVE -> Unit
                }
            }
            override fun onError(errorCode: NaviErrorCode) = dispatch(token) {
                reportOnce("NaviHelper", errorCode.name)
            }
        }
        naviListener = listener
        awaitSdk<Unit> { success, failure ->
            helper.initialize({ success(Unit) }, { failure(it as? Exception ?: IllegalStateException(it)) })
        }
        if (!active(token)) return@attempt
        helper.addListener(listener)
        naviReady = true
        log("NaviHelper 연결 완료 · 안내 상태/TBT/위치 1초 조회")
        polling = scope.launch {
            while (isActive && active(token)) {
                attempt("내비 상태 조회") { helper.getRouteStateInfo() }
                attempt("TBT 조회") { helper.getTBTInfo() }
                attempt("내비 위치 조회") { helper.getCurrentLocationInfo() }
                delay(1_000L)
            }
        }
    }

    private suspend fun startVehicleSignals(token: Long) {
        if (!connection.initialize() || !active(token)) return
        attempt("조향각 구독") {
            val steering = connection.vehicle.getSteeringWheel()
            val readable = retryTelemetryRead(
                isActive = { active(token) },
                onRetry = { next, error -> log("조향각 읽기 지원 조회 재시도 $next/3: ${error.message}") },
            ) {
                awaitSdk<Boolean> { success, failure ->
                    steering.checkSteeringWheelAngleCapabilities({ config, _ ->
                        success(config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE)
                    }, failure)
                }
            }
            if (!readable) log("조향각 읽기 미지원 · 조향 반응 생략")
            if (!readable || !active(token)) return@attempt
            val listener = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = dispatch(token) { now ->
                    steeringRevision++
                    mutableEvents.emit(MotionEvent.Steering(now, angle?.takeIf { it.isFinite() }?.toDouble() ?: 0.0))
                }
                override fun onFailed(e: Exception) = dispatch(token) { now ->
                    steeringRevision++
                    reportOnce("조향각", e.message ?: e.javaClass.simpleName)
                    mutableEvents.emit(MotionEvent.Steering(now, 0.0))
                }
            }
            steeringListener = listener
            steering.registerSteeringWheelAngle(listener)
            steeringHeartbeat = scope.launch {
                while (isActive && active(token)) {
                    val revision = steeringRevision
                    try {
                        val value = awaitSdk<Float?> { success, failure ->
                            steering.getCurrentSteeringWheelAngle(success, failure)
                        }
                        if (revision == steeringRevision) listener.onSteeringWheelAngleUpdated(value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (revision == steeringRevision) listener.onFailed(error)
                    }
                    delay(1_000L)
                }
            }
            log("조향각 구독 등록")
        }
        attempt("브레이크 구독") {
            val brake = connection.vehicle.getBrake()
            val readable = retryTelemetryRead(
                isActive = { active(token) },
                onRetry = { next, error -> log("브레이크 읽기 지원 조회 재시도 $next/3: ${error.message}") },
            ) {
                awaitSdk<Boolean> { success, failure ->
                    brake.checkBrakePedalStatusCapabilities({ config ->
                        success(config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE)
                    }, failure)
                }
            }
            if (!readable) log("브레이크 읽기 미지원 · 브레이크 반응 생략")
            if (!readable || !active(token)) return@attempt
            val listener = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(pressed: Boolean?) = dispatch(token) { now ->
                    brakeRevision++
                    observedBrakePressed = pressed ?: false
                    publishBrake(now, currentSpeedSample(now) != null)
                }
                override fun onFailed(e: Exception) = dispatch(token) { now ->
                    brakeRevision++
                    reportOnce("브레이크", e.message ?: e.javaClass.simpleName)
                    observedBrakePressed = false
                    publishBrake(now, false)
                }
            }
            brakeListener = listener
            brake.registerBrakePedalStatus(listener)
            brakeHeartbeat = scope.launch {
                while (isActive && active(token)) {
                    val revision = brakeRevision
                    try {
                        val value = awaitSdk<Boolean?> { success, failure ->
                            brake.getCurrentBrakePedalStatus(success, failure)
                        }
                        if (revision == brakeRevision) listener.onBrakePedalStatusUpdated(value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (revision == brakeRevision) listener.onFailed(error)
                    }
                    delay(1_000L)
                }
            }
            log("브레이크 구독 등록")
        }
    }

    private suspend fun startFusedLocation(token: Long) = attempt("FusedLocation 연결") {
        val location = FusedLocation(appContext)
        fused = location
        if (!location.isSupported()) {
            log("FusedLocation 미지원 · 내비 위치/거리 추정 사용")
            return@attempt
        }
        location.initialize()
        if (!active(token)) return@attempt
        val listener = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) = dispatch(token) { now ->
                if (data != null && (lastFusedTimestamp == null || data.timestampMillis > lastFusedTimestamp!!)) {
                    lastFusedTimestamp = data.timestampMillis
                    // Callback delivery can be delayed or batched. Physical speed uses fix time;
                    // cache freshness still uses our monotonic receive clock.
                    fusedEstimator.onPosition(data.timestampMillis, data.latitude, data.longitude)
                        ?.let { fusion.update(SpeedSource.FUSED_LOCATION, now, it, data.timestampMillis) }
                }
            }
            override fun onError(e: Exception) = dispatch(token) {
                reportOnce("FusedLocation", e.message ?: e.javaClass.simpleName)
            }
        }
        fusedListener = listener
        location.registerFusedLocationCallback(listener)
        log("FusedLocation 구독 등록")
    }

    private fun endRoute(token: Long) = dispatch(token) { now ->
        routeGate.onEnded()
        clearRouteData(now)
        log("내비 경로 종료 · 예고 차단. 새 경로 시작 이벤트 미수신 시 Live 일시정지/재생으로 재확인")
    }

    private suspend fun clearRouteData(now: Long) {
        lastTbtAt = null
        lastCautionAt = null
        closingEstimator.reset()
        routeGate.clearRouteSpeed()
        mutableEvents.emit(MotionEvent.Tbt(now, emptyList()))
        mutableEvents.emit(MotionEvent.Caution(now, emptyList()))
    }

    private fun currentSpeedSample(now: Long): SpeedSample? = fusion.currentSample(now)?.takeUnless {
        it.source == SpeedSource.ROUTE_DISTANCE && !routeGate.allowsRouteSpeed(now, it.timeMs)
    }

    private fun dispatch(token: Long, action: suspend (Long) -> Unit) {
        callbackScope?.launch {
            if (active(token)) action(SystemClock.elapsedRealtime())
        }
    }

    private fun active(token: Long) = !closed && mutableStatus.value.playing && token == generation

    private suspend fun publishBrake(now: Long, speedKnown: Boolean) {
        val pressed = observedBrakePressed && speedKnown
        if (pressed != emittedBrakePressed) {
            emittedBrakePressed = pressed
            mutableEvents.emit(MotionEvent.Brake(now, pressed))
        }
    }

    private fun reportOnce(label: String, detail: String) {
        if (reportedErrors.add("$label:$detail")) log("$label: $detail")
    }

    private suspend fun attempt(label: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportOnce(label, error.message ?: error.javaClass.simpleName)
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        ++generation
        mutableStatus.value = mutableStatus.value.copy(playing = false, detail = "Live · 일시정지", speedKnown = false)
        callbackScope?.cancel()
        callbackScope = null
        startup?.cancelAndJoin()
        steeringHeartbeat?.cancelAndJoin()
        brakeHeartbeat?.cancelAndJoin()
        polling?.cancelAndJoin()
        ticks?.cancelAndJoin()
        startup = null
        steeringHeartbeat = null
        brakeHeartbeat = null
        polling = null
        ticks = null
        steeringListener?.let { listener -> attempt("조향각 해제") {
            connection.vehicle.getSteeringWheel().unregisterSteeringWheelAngle(listener)
        } }
        brakeListener?.let { listener -> attempt("브레이크 해제") {
            connection.vehicle.getBrake().unregisterBrakePedalStatus(listener)
        } }
        naviListener?.let { listener -> attempt("내비 리스너 해제") { navi?.removeListener(listener) } }
        fusedListener?.let { listener -> attempt("위치 구독 해제") { fused?.unregisterFusedLocationCallback(listener) } }
        attempt("NaviHelper 해제") { navi?.release() }
        attempt("FusedLocation 해제") { fused?.release() }
        steeringListener = null
        brakeListener = null
        naviListener = null
        fusedListener = null
        navi = null
        fused = null
        naviReady = false
        resetEstimators()
    }

    override suspend fun reset() = withContext(Dispatchers.Main.immediate) {
        pause()
        mutableStatus.value = SourceStatus(detail = "Live · 대기")
    }

    override suspend fun close() = withContext(Dispatchers.Main.immediate) {
        pause()
        closed = true
        scope.cancel()
    }

    private fun resetEstimators() {
        fusedEstimator.reset()
        naviEstimator.reset()
        closingEstimator.reset()
        fusion.reset()
        speedEvents.reset()
        routeGate.reset()
        lastTbtAt = null
        lastCautionAt = null
        lastFusedTimestamp = null
        observedBrakePressed = false
        emittedBrakePressed = false
    }
}
