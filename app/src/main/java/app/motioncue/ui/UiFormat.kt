package app.motioncue.ui

import kotlin.math.abs
import java.util.Locale

/** 화살표 모양. 판정에는 쓰지 않고 Upcoming.label 문구로 표시만 고른다. */
sealed interface TurnGlyph {
    data object Straight : TurnGlyph
    data class Turn(val left: Boolean) : TurnGlyph
    data class Sharp(val left: Boolean) : TurnGlyph
    data class Keep(val left: Boolean) : TurnGlyph
    data object UTurn : TurnGlyph
    /** [clock]은 출구의 시계 방향 (1~12) */
    data class Roundabout(val clock: Int) : TurnGlyph
}

fun turnGlyphFor(label: String?): TurnGlyph {
    if (label == null) return TurnGlyph.Straight
    Regex("회전교차로 (\\d+)시").find(label)?.let { return TurnGlyph.Roundabout(it.groupValues[1].toInt()) }
    return when {
        "유턴" in label -> TurnGlyph.UTurn
        "급좌" in label -> TurnGlyph.Sharp(left = true)
        "급우" in label -> TurnGlyph.Sharp(left = false)
        "좌회전" in label -> TurnGlyph.Turn(left = true)
        "우회전" in label -> TurnGlyph.Turn(left = false)
        "왼쪽" in label -> TurnGlyph.Keep(left = true)
        "오른쪽" in label -> TurnGlyph.Keep(left = false)
        else -> TurnGlyph.Straight
    }
}

/** ETA 타임라인이 보여주는 시간 폭. 설정 가능한 leadTime 최대(4초)보다 넉넉하게 둔다. */
const val ETA_WINDOW_SEC = 10.0

/** 타임라인 위치: 왼쪽 끝 0 = [ETA_WINDOW_SEC]초 이상 남음, 오른쪽 끝 1 = 도착. */
fun etaFraction(etaSec: Double): Float = (1.0 - (etaSec / ETA_WINDOW_SEC).coerceIn(0.0, 1.0)).toFloat()

/** 21000ms → "0:21" */
fun clockText(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0) + 500) / 1000
    return String.format(Locale.ROOT, "%d:%02d", totalSec / 60, totalSec % 60)
}

/** Math.round라 -0을 만들지 않고 NaN에서도 죽지 않는다. */
fun wholeText(value: Double): String = Math.round(value).toString()

fun oneDecimalText(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** 핸들 각도 값과 방향. 왼쪽이 음수인 SDK 부호를 사용자 말로 바꾼다. */
fun steeringText(deg: Double): Pair<String, String> {
    val rounded = Math.round(deg)
    return "${abs(rounded)}°" to when {
        rounded < 0 -> "왼쪽"
        rounded > 0 -> "오른쪽"
        else -> "중앙"
    }
}
