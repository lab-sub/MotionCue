package app.motioncue.data

/** Public TBT/state callbacks carry no route or request ID. End events therefore latch closed. */
internal class NavigationGuidanceGate(private val stateMaxAgeMs: Long = 4_000L) {
    private var stateAt: Long? = null
    private var activeState = false
    private var routeSpeedAt: Long? = null
    var awaitingStartEvent = false
        private set

    fun onState(active: Boolean, now: Long) {
        if (awaitingStartEvent) return
        stateAt = now
        activeState = active
        if (!active) routeSpeedAt = null
    }

    fun onStarted() = reset()

    /** This provider also emits RouteEnded immediately after RouteStarted. Recheck its mode. */
    fun invalidateState() {
        stateAt = null
        activeState = false
        routeSpeedAt = null
    }

    fun onEnded() {
        reset()
        awaitingStartEvent = true
    }

    fun allowsRouteData(now: Long): Boolean = !awaitingStartEvent && activeState &&
        stateAt?.let { now >= it && now - it <= stateMaxAgeMs } == true

    fun expire(now: Long): Boolean {
        val received = stateAt ?: return false
        if (now >= received && now - received <= stateMaxAgeMs) return false
        invalidateState()
        return true
    }

    fun recordRouteSpeed(now: Long) {
        if (allowsRouteData(now)) routeSpeedAt = now
    }

    fun allowsRouteSpeed(now: Long, sampleAt: Long): Boolean =
        allowsRouteData(now) && routeSpeedAt == sampleAt

    fun clearRouteSpeed() { routeSpeedAt = null }

    fun reset() {
        invalidateState()
        awaitingStartEvent = false
    }
}
