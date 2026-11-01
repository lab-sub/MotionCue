package app.motioncue.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.motioncue.core.engine.CueKind
import app.motioncue.core.engine.CuePalette
import app.motioncue.core.engine.CueState
import app.motioncue.core.engine.Strength
import app.motioncue.core.engine.Upcoming
import app.motioncue.core.engine.ZoneCue
import app.motioncue.core.model.Seat
import app.motioncue.data.SourceStatus
import app.motioncue.session.SessionState
import app.motioncue.session.SourceMode

private const val BG = 0xFF0D0C0F

private val mockSource = SourceStatus(durationMs = 95_000, detail = "Mock · 도심 주행 기본 시나리오", speedKnown = true)

private val idle = SessionState(source = mockSource, foreground = true)

private val leftTurnCue = idle.copy(
    source = mockSource.copy(playing = true, elapsedMs = 29_600),
    cue = CueState(
        zones = mapOf(Seat.REAR_LEFT to ZoneCue(CueKind.PREDICTIVE_TURN, CuePalette.turn(Strength.NORMAL), "좌회전 예고")),
        nextTurn = Upcoming("좌회전", 13.0, 2.3, cueActive = true),
        nextCaution = Upcoming("과속방지턱", 133.0, 23.9, cueActive = false),
        speedKmh = 20.0,
        steeringDeg = -40.0,
        activeKinds = setOf(CueKind.PREDICTIVE_TURN),
    ),
)

private val hardBraking = idle.copy(
    source = mockSource.copy(playing = true, elapsedMs = 87_000),
    cue = CueState(
        zones = Seat.PASSENGERS.associateWith { ZoneCue(CueKind.REACTIVE_BRAKE, CuePalette.decel(Strength.NORMAL), "감속 반응") },
        speedKmh = 27.0,
        brakePressed = true,
        decelMps2 = 1.2,
        activeKinds = setOf(CueKind.REACTIVE_BRAKE),
    ),
)

private val recovering = idle.copy(
    source = mockSource.copy(playing = true, elapsedMs = 15_000),
    cue = CueState(nextTurn = Upcoming("급우회전", 142.0, 10.2, cueActive = false), speedKmh = 50.0),
    recoverySeats = setOf(Seat.FRONT_PASSENGER),
)

private val liveLocked = SessionState(
    mode = SourceMode.LIVE,
    source = SourceStatus(playing = true, detail = "Live · 내비 연결 · FusedLocation 위치", speedKnown = true),
    cue = CueState(nextTurn = Upcoming("회전교차로 3시 방향", 74.0, 7.9, cueActive = false), speedKmh = 33.0, steeringDeg = 8.0),
    foreground = true,
)

@Preview(name = "대기", widthDp = 1680, heightDp = 1210, showBackground = true, backgroundColor = BG)
@Composable
fun PreviewIdle() = MotionCueScreen(idle, MotionCueActions())

@Preview(name = "좌회전 예고 중", widthDp = 1680, heightDp = 1210, showBackground = true, backgroundColor = BG)
@Composable
fun PreviewLeftTurnCue() = MotionCueScreen(leftTurnCue, MotionCueActions())

@Preview(name = "급감속 반응", widthDp = 1680, heightDp = 1210, showBackground = true, backgroundColor = BG)
@Composable
fun PreviewHardBraking() = MotionCueScreen(hardBraking, MotionCueActions())

@Preview(name = "회복 중 좌석 있음", widthDp = 1680, heightDp = 1210, showBackground = true, backgroundColor = BG)
@Composable
fun PreviewRecovering() = MotionCueScreen(recovering, MotionCueActions())

@Preview(name = "Live 설정 잠금", widthDp = 1680, heightDp = 1210, showBackground = true, backgroundColor = BG)
@Composable
fun PreviewLiveLocked() = MotionCueScreen(liveLocked, MotionCueActions())

@Preview(name = "좁은 화면", widthDp = 830, heightDp = 1210, showBackground = true, backgroundColor = BG)
@Composable
fun PreviewNarrow() {
    // 폭을 직접 고정해 Studio 밖(PreviewActivity)에서 띄워도 830dp로 렌더된다.
    Box(Modifier.width(830.dp)) { MotionCueScreen(leftTurnCue, MotionCueActions()) }
}
