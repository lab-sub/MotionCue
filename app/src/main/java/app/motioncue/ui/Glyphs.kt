package app.motioncue.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// material-icons 의존성을 늘리지 않으려고 아이콘을 직접 그린다. 좌표는 100×100 기준.

private class Pen(val scope: DrawScope, val color: Color) {
    val unit = scope.size.minDimension / 100f
    val stroke = Stroke(width = 11f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun at(x: Float, y: Float) = Offset(x * unit, y * unit)

    fun line(build: Path.() -> Unit) = scope.drawPath(Path().apply(build), color, style = stroke)

    fun Path.move(x: Float, y: Float) = moveTo(x * unit, y * unit)
    fun Path.to(x: Float, y: Float) = lineTo(x * unit, y * unit)
    fun Path.curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        cubicTo(x1 * unit, y1 * unit, x2 * unit, y2 * unit, x3 * unit, y3 * unit)

    /** 선 끝([ex],[ey])에서 [px],[py]→끝 방향으로 나가는 화살촉 */
    fun head(px: Float, py: Float, ex: Float, ey: Float) {
        val length = hypot(ex - px, ey - py)
        val dx = (ex - px) / length
        val dy = (ey - py) / length
        val path = Path().apply {
            moveTo((ex + dx * 24f) * unit, (ey + dy * 24f) * unit)
            lineTo((ex - dy * 19f) * unit, (ey + dx * 19f) * unit)
            lineTo((ex + dy * 19f) * unit, (ey - dx * 19f) * unit)
            close()
        }
        scope.drawPath(path, color)
        scope.drawPath(path, color, style = Stroke(width = 4f * unit, join = StrokeJoin.Round))
    }
}

internal fun DrawScope.drawTurnGlyph(glyph: TurnGlyph, color: Color) {
    val mirrored = when (glyph) {
        is TurnGlyph.Turn -> !glyph.left
        is TurnGlyph.Sharp -> !glyph.left
        is TurnGlyph.Keep -> !glyph.left
        else -> false
    }
    // 왼쪽 모양만 그리고 오른쪽은 좌우 반전한다.
    scale(if (mirrored) -1f else 1f, 1f) {
        with(Pen(this, color)) {
            when (glyph) {
                TurnGlyph.Straight -> {
                    line { move(50f, 94f); to(50f, 36f) }
                    head(50f, 94f, 50f, 36f)
                }
                is TurnGlyph.Turn -> {
                    line { move(68f, 94f); to(68f, 58f); curve(68f, 46f, 60f, 40f, 50f, 40f); to(36f, 40f) }
                    head(50f, 40f, 36f, 40f)
                }
                is TurnGlyph.Sharp -> {
                    line { move(72f, 94f); to(72f, 26f); to(38f, 56f) }
                    head(72f, 26f, 38f, 56f)
                }
                is TurnGlyph.Keep -> {
                    line { move(64f, 94f); to(64f, 68f); curve(64f, 52f, 44f, 50f, 38f, 34f) }
                    head(44f, 50f, 38f, 34f)
                }
                TurnGlyph.UTurn -> {
                    line { move(72f, 94f); to(72f, 40f); curve(72f, 8f, 30f, 8f, 30f, 40f); to(30f, 56f) }
                    head(30f, 40f, 30f, 56f)
                }
                is TurnGlyph.Roundabout -> {
                    val center = at(50f, 50f)
                    scope.drawCircle(color, radius = 17f * unit, center = center, style = stroke)
                    line { move(50f, 96f); to(50f, 67f) }
                    val angle = Math.toRadians(glyph.clock * 30.0)
                    val sx = sin(angle).toFloat()
                    val cy = cos(angle).toFloat()
                    line { move(50f + sx * 17f, 50f - cy * 17f); to(50f + sx * 22f, 50f - cy * 22f) }
                    head(50f + sx * 17f, 50f - cy * 17f, 50f + sx * 22f, 50f - cy * 22f)
                }
            }
        }
    }
}

internal fun DrawScope.drawPlay(color: Color) {
    val u = size.minDimension / 100f
    val path = Path().apply {
        moveTo(24f * u, 12f * u)
        lineTo(88f * u, 50f * u)
        lineTo(24f * u, 88f * u)
        close()
    }
    drawPath(path, color)
}

internal fun DrawScope.drawPause(color: Color) {
    val u = size.minDimension / 100f
    listOf(20f, 60f).forEach { x ->
        drawRoundRect(color, Offset(x * u, 12f * u), Size(20f * u, 76f * u), CornerRadius(4f * u))
    }
}

internal fun DrawScope.drawSteeringWheel(color: Color) {
    val u = size.minDimension / 100f
    val center = Offset(size.width / 2, size.height / 2)
    val style = Stroke(width = 8f * u, cap = StrokeCap.Round)
    drawCircle(color, radius = 42f * u, center = center, style = style)
    drawCircle(color, radius = 11f * u, center = center, style = style)
    listOf(-90f, 150f, 30f).forEach { degrees ->
        val a = Math.toRadians((degrees + 180f).toDouble())
        val dx = cos(a).toFloat() * u
        val dy = sin(a).toFloat() * u
        drawLine(color, center + Offset(dx * 11f, dy * 11f), center + Offset(dx * 42f, dy * 42f), strokeWidth = 8f * u, cap = StrokeCap.Round)
    }
}

/** 삼각형 경고 표시. [mark]는 안쪽 느낌표 색 */
internal fun DrawScope.drawWarning(color: Color, mark: Color) {
    val u = size.minDimension / 100f
    val path = Path().apply {
        moveTo(50f * u, 10f * u)
        lineTo(92f * u, 86f * u)
        lineTo(8f * u, 86f * u)
        close()
    }
    drawPath(path, color)
    drawPath(path, color, style = Stroke(width = 10f * u, join = StrokeJoin.Round))
    drawLine(mark, Offset(50f * u, 38f * u), Offset(50f * u, 60f * u), strokeWidth = 9f * u, cap = StrokeCap.Round)
    drawCircle(mark, radius = 5.5f * u, center = Offset(50f * u, 74f * u))
}

/** 로고: 위에서 본 차와 좌우 앰비언트 라인. 런처 아이콘과 같은 구성 */
internal fun DrawScope.drawLogo() {
    val u = size.minDimension / 100f
    drawRoundRect(Line, Offset(26f * u, 6f * u), Size(48f * u, 88f * u), CornerRadius(14f * u))
    drawRoundRect(TextSub, Offset(34f * u, 18f * u), Size(32f * u, 16f * u), CornerRadius(4f * u))
    drawRoundRect(Coral, Offset(8f * u, 20f * u), Size(9f * u, 60f * u), CornerRadius(4f * u))
    drawRoundRect(Amber, Offset(83f * u, 20f * u), Size(9f * u, 60f * u), CornerRadius(4f * u))
}
