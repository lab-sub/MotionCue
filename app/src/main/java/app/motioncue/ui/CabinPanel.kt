package app.motioncue.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.motioncue.core.model.Seat
import app.motioncue.session.SessionState
import app.motioncue.session.SourceMode

private const val CAR_ASPECT = 0.72f
private val SeatShape = RoundedCornerShape(20.dp)

/** 왼쪽 히어로: 위에서 본 차량 실내도. [fillHeight]면 카드 높이를 채우고, 아니면(세로 스크롤) 폭에 맞춘다. */
@Composable
internal fun CabinPanel(
    state: SessionState,
    actions: MotionCueActions,
    modifier: Modifier = Modifier,
    fillHeight: Boolean,
) {
    CueCard(modifier) {
        Text(
            "▲ 전방",
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = TextSub,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        BoxWithConstraints(
            (if (fillHeight) Modifier.weight(1f) else Modifier.aspectRatio(0.85f)).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val carHeight = min(maxHeight, maxWidth / CAR_ASPECT)
            val carWidth = carHeight * CAR_ASPECT
            Box(Modifier.size(carWidth, carHeight)) {
                Canvas(Modifier.matchParentSize().semantics { contentDescription = "위에서 본 차량 실내도" }) { drawCar() }
                // 여백 비율은 drawCar의 앞유리·뒷유리·허리선 안쪽에 좌석이 들어오도록 맞춘 값
                Column(
                    Modifier.matchParentSize().padding(
                        start = carWidth * 0.11f, end = carWidth * 0.11f,
                        top = carHeight * 0.165f, bottom = carHeight * 0.115f,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DriverSeat(Modifier.weight(1f))
                        PassengerSeat(Seat.FRONT_PASSENGER, state, actions, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PassengerSeat(Seat.REAR_LEFT, state, actions, Modifier.weight(1f))
                        PassengerSeat(Seat.REAR_RIGHT, state, actions, Modifier.weight(1f))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        ) {
            LegendItem(Coral, "회전 코럴")
            LegendItem(Amber, "감속 앰버")
        }
        Text(
            "화면 색은 조명 목표색 · 운전석 제외",
            Modifier.fillMaxWidth().padding(top = 4.dp),
            color = TextSub,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(18.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DriverSeat(modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().background(Bg, SeatShape).border(3.dp, Line, SeatShape).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.size(72.dp)) { drawSteeringWheel(TextSub) }
        Text(Seat.DRIVER.label, color = TextSub, style = MaterialTheme.typography.titleMedium)
        Text("제어 제외", color = TextSub, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PassengerSeat(seat: Seat, state: SessionState, actions: MotionCueActions, modifier: Modifier) {
    val cue = state.cue.zones[seat]
    val recovering = seat in state.recoverySeats
    // 화면 움직임은 이 500ms 페이드뿐이다. 점멸·반복 애니메이션을 쓰지 않는다.
    val fill by animateColorAsState(
        cue?.let { Color(it.color).copy(alpha = 1f) } ?: Card,
        tween(500),
        label = "${seat.name} fill",
    )
    val edge by animateColorAsState(cue?.let { Color(it.color) } ?: Line, tween(500), label = "${seat.name} edge")
    // 페이드 도중에도 실제 배경에 대해 더 큰 명암 대비를 내는 검정/흰색을 고른다.
    val luminance = fill.luminance()
    val blackContrast = (luminance + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (luminance + 0.05f)
    val contentColor = if (blackContrast >= whiteContrast) Color.Black else Color.White
    Column(
        modifier.fillMaxHeight()
            .background(fill, SeatShape)
            .drawBehind { drawSeatShape(contentColor) }
            .border(3.dp, edge, SeatShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(seat.label, color = contentColor, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    recovering && cue != null -> "회복 중 · ${cue.label}"
                    recovering -> "회복 중"
                    else -> cue?.label ?: "대기"
                },
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (cue != null || recovering) FontWeight.Bold else FontWeight.Normal,
            )
        }
        // 실차 모드의 회복 개입은 재생 중에만 동작한다. 로그가 화면에서 빠졌으니 버튼 상태로 알려 준다.
        val usable = state.mode == SourceMode.MOCK || state.source.playing || recovering
        CueButton(
            if (recovering) "원래대로" else "멀미 나요",
            onClick = { actions.onToggleRecovery(seat) },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "${seat.label} ${if (recovering) "원래대로" else "멀미 나요"}"
            },
            tone = if (recovering) Tone.Recovery else Tone.Neutral,
            enabled = !state.busy && usable,
        )
    }
}

/** 좌석 윤곽 (쿠션·등받이·머리받침). 앞이 위쪽이라 머리받침이 아래에 온다. */
private fun DrawScope.drawSeatShape(contentColor: Color) {
    val w = size.width
    val h = size.height
    val tint = contentColor.copy(alpha = 0.08f)
    val radius = CornerRadius(w * 0.08f)
    drawRoundRect(tint, Offset(w * 0.2f, h * 0.25f), Size(w * 0.6f, h * 0.22f), radius)
    drawRoundRect(tint, Offset(w * 0.24f, h * 0.49f), Size(w * 0.52f, h * 0.12f), radius)
    drawRoundRect(tint, Offset(w * 0.34f, h * 0.63f), Size(w * 0.32f, h * 0.05f), radius)
}

/** 차체 실루엣. 앞이 위쪽. */
private fun DrawScope.drawCar() {
    val w = size.width
    val h = size.height
    fun Path.m(x: Float, y: Float) = moveTo(x * w, y * h)
    fun Path.l(x: Float, y: Float) = lineTo(x * w, y * h)
    fun Path.q(x1: Float, y1: Float, x2: Float, y2: Float) = quadraticBezierTo(x1 * w, y1 * h, x2 * w, y2 * h)
    fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        cubicTo(x1 * w, y1 * h, x2 * w, y2 * h, x3 * w, y3 * h)
    val edge = Stroke(width = 3.dp.toPx())

    // 사이드미러를 먼저 그려 뿌리가 차체 밑으로 들어가게 한다.
    listOf(0f, 0.925f).forEach { left ->
        val topLeft = Offset(left * w, 0.152f * h)
        val mirror = Size(0.075f * w, 0.034f * h)
        val corner = CornerRadius(0.022f * w)
        drawRoundRect(CardRaised, topLeft, mirror, corner)
        drawRoundRect(Line, topLeft, mirror, corner, style = edge)
    }

    val body = Path().apply {
        m(0.26f, 0.008f)
        l(0.74f, 0.008f)
        c(0.86f, 0.008f, 0.94f, 0.05f, 0.94f, 0.13f)
        c(0.95f, 0.3f, 0.925f, 0.4f, 0.925f, 0.5f)
        c(0.925f, 0.6f, 0.95f, 0.72f, 0.94f, 0.88f)
        c(0.94f, 0.96f, 0.86f, 0.992f, 0.74f, 0.992f)
        l(0.26f, 0.992f)
        c(0.14f, 0.992f, 0.06f, 0.96f, 0.06f, 0.88f)
        c(0.05f, 0.72f, 0.075f, 0.6f, 0.075f, 0.5f)
        c(0.075f, 0.4f, 0.05f, 0.3f, 0.06f, 0.13f)
        c(0.06f, 0.05f, 0.14f, 0.008f, 0.26f, 0.008f)
        close()
    }
    drawPath(body, CardRaised)
    drawPath(body, Line, style = edge)

    val windshield = Path().apply {
        m(0.2f, 0.078f)
        q(0.5f, 0.05f, 0.8f, 0.078f)
        l(0.75f, 0.148f)
        q(0.5f, 0.132f, 0.25f, 0.148f)
        close()
    }
    val rearGlass = Path().apply {
        m(0.25f, 0.905f)
        q(0.5f, 0.92f, 0.75f, 0.905f)
        l(0.79f, 0.955f)
        q(0.5f, 0.978f, 0.21f, 0.955f)
        close()
    }
    listOf(windshield, rearGlass).forEach {
        drawPath(it, Bg)
        drawPath(it, Line, style = edge)
    }
}
