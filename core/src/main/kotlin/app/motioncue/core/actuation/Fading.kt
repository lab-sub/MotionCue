package app.motioncue.core.actuation

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

object ColorMath {

    /** ARGB 채널별 선형 보간. alpha는 항상 0xFF (SDK 요구사항) */
    fun lerp(from: Int, to: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * k).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun opaque(color: Int): Int = color or (0xFF shl 24)
}

/**
 * 차량 API 호출 빈도 제한.
 * 같은 구역은 [perKeyIntervalMs] 이상 간격, 전체는 [windowMs] 동안 [maxPerWindow]회까지.
 */
class RateLimiter(
    private val perKeyIntervalMs: Long = 100,
    private val maxPerWindow: Int = 20,
    private val windowMs: Long = 1_000,
) {
    private val lastByKey = HashMap<Any, Long>()
    private val recent = ArrayDeque<Long>()

    fun tryAcquire(key: Any, nowMs: Long): Boolean {
        while (recent.isNotEmpty() && nowMs - recent.first() >= windowMs) recent.removeFirst()
        val last = lastByKey[key]
        if (last != null && nowMs - last < perKeyIntervalMs) return false
        if (recent.size >= maxPerWindow) return false
        lastByKey[key] = nowMs
        recent.addLast(nowMs)
        return true
    }

    fun reset() {
        lastByKey.clear()
        recent.clear()
    }
}

/**
 * 구역별 색 페이드 계획. 점멸 없이 [fadeMs] 동안 [steps]단계로 바꾼다.
 * 페이드 도중 목표가 바뀌면 마지막으로 보낸 색에서 새 목표로 다시 페이드한다.
 * 호출 쪽은 [poll]을 주기적으로 불러 나온 (구역, 색)을 차량에 보낸다.
 */
class FadePlanner<K : Any>(
    private val fadeMs: Long = 500,
    private val steps: Int = 5,
) {
    init {
        require(fadeMs > 0) { "fadeMs must be positive" }
        require(steps > 0) { "steps must be positive" }
    }

    private class Track(var from: Int, var to: Int, var startMs: Long, var sent: Int?)

    private val tracks = LinkedHashMap<K, Track>()

    val keys: Set<K> get() = tracks.keys

    /** 현재 실제 색을 알려준다 (페이드 출발점) */
    fun setCurrent(key: K, color: Int, nowMs: Long) {
        val c = ColorMath.opaque(color)
        tracks[key] = Track(from = c, to = c, startMs = nowMs, sent = c)
    }

    fun setTarget(key: K, color: Int, nowMs: Long) {
        val c = ColorMath.opaque(color)
        val track = tracks[key]
        if (track == null) {
            // 출발 색을 모르면 페이드 없이 목표로 바로 간다
            tracks[key] = Track(from = c, to = c, startMs = nowMs, sent = null)
            return
        }
        if (track.to == c) return
        track.from = track.sent ?: c
        track.to = c
        track.startMs = nowMs
    }

    /** 지금 보내야 할 (구역, 색) 목록. [limiter]가 막은 구역은 다음 poll에서 다시 시도한다 */
    fun poll(nowMs: Long, limiter: RateLimiter? = null): List<Pair<K, Int>> {
        val out = mutableListOf<Pair<K, Int>>()
        for ((key, track) in tracks) {
            if (track.sent == track.to) continue
            val progress = ((nowMs - track.startMs).toDouble() / fadeMs).coerceIn(0.0, 1.0)
            val step = min(steps, floor(progress * steps).toInt())
            val color = if (step >= steps) track.to else ColorMath.lerp(track.from, track.to, step.toDouble() / steps)
            if (color == track.sent) continue
            if (limiter != null && !limiter.tryAcquire(key, nowMs)) continue
            track.sent = color
            out += key to color
        }
        return out
    }

    fun isIdle(): Boolean = tracks.values.all { it.sent == it.to }

    fun target(key: K): Int? = tracks[key]?.to

    fun clear() = tracks.clear()
}
