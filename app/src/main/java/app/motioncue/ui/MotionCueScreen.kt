package app.motioncue.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.motioncue.core.engine.CueConfig
import app.motioncue.core.engine.Upcoming
import app.motioncue.core.model.Seat
import app.motioncue.session.SessionState
import app.motioncue.session.SourceMode
import kotlin.math.max as maxOfDouble

data class MotionCueActions(
    val onSelectMode: (SourceMode) -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onApplySettings: (CueConfig, Boolean) -> Unit = { _, _ -> },
    val onToggleRecovery: (Seat) -> Unit = {},
)

private val WideBreakpoint = 1000.dp

/** 이 높이 아래로 창이 줄면 화면 전체를 세로 스크롤한다 (2열 레이아웃은 유지). */
private val WideMinBodyHeight = 880.dp

@Composable
fun MotionCueScreen(state: SessionState, actions: MotionCueActions) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    MotionCueTheme {
        Surface(Modifier.fillMaxSize(), color = Bg, contentColor = TextMain) {
            BoxWithConstraints(Modifier.safeDrawingPadding().padding(20.dp)) {
                val wide = maxWidth >= WideBreakpoint
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TopBar(state, actions, onLog = { showLog = true }, onSettings = { showSettings = true })
                    if (wide) WideBody(state, actions, Modifier.weight(1f)) else NarrowBody(state, actions, Modifier.weight(1f))
                }
            }
        }
        if (showSettings) SettingsDialog(state, onDismiss = { showSettings = false }) { config, lights ->
            actions.onApplySettings(config, lights)
            showSettings = false
        }
        if (showLog) LogDialog(state, onDismiss = { showLog = false })
    }
}

@Composable
private fun WideBody(state: SessionState, actions: MotionCueActions, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        Row(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).height(max(maxHeight, WideMinBodyHeight)),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CabinPanel(state, actions, Modifier.weight(1f).fillMaxHeight(), fillHeight = true)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NextTurnCard(state, Modifier.weight(1f).fillMaxWidth())
                CautionCard(state.cue.nextCaution)
                SignalTiles(state)
                PlaybackBar(state, actions)
            }
        }
    }
}

@Composable
private fun NarrowBody(state: SessionState, actions: MotionCueActions, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaybackBar(state, actions)
        CabinPanel(state, actions, Modifier.fillMaxWidth(), fillHeight = false)
        NextTurnCard(state, Modifier.fillMaxWidth())
        CautionCard(state.cue.nextCaution)
        SignalTiles(state)
    }
}

@Composable
private fun TopBar(state: SessionState, actions: MotionCueActions, onLog: () -> Unit, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 830dp보다 좁은 창에서도 버튼이 잘리지 않도록 가로로 밀어 볼 수 있게 둔다.
        Row(
            Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.size(52.dp).semantics { contentDescription = "MotionCue 로고" }) { drawLogo() }
                Text("MotionCue", style = MaterialTheme.typography.headlineMedium)
            }
            Row(
                Modifier.padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Segmented(
                    options = SourceMode.entries,
                    selected = state.mode,
                    label = { if (it == SourceMode.MOCK) "시연 (Mock)" else "실차 (Live)" },
                    onSelect = actions.onSelectMode,
                    enabled = !state.busy,
                )
                CueButton("기록", onLog)
                CueButton("설정", onSettings, enabled = !state.busy && !state.settingsLocked)
            }
        }
    }
}

@Composable
private fun NextTurnCard(state: SessionState, modifier: Modifier) {
    val turn = state.cue.nextTurn
    val active = turn?.cueActive == true
    CueCard(modifier, borderColor = if (active) Coral else Line) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("다음 회전", style = MaterialTheme.typography.titleLarge)
                when {
                    turn == null -> StatusChip("안내 대기")
                    active -> StatusChip("예고 중", Coral)
                    else -> StatusChip("접근 중")
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val glyph = turnGlyphFor(turn?.label)
                val arrowColor = when {
                    turn == null -> Line
                    active -> Coral
                    else -> TextMain
                }
                Canvas(
                    Modifier.fillMaxWidth(0.34f).widthIn(max = 280.dp).aspectRatio(1f)
                        .semantics { contentDescription = turn?.let { "${it.label} 방향 화살표" } ?: "안내 없음" },
                ) { drawTurnGlyph(glyph, arrowColor) }
                Column(Modifier.weight(1f)) {
                    Text(turn?.label ?: "안내 대기", fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
                        color = if (turn == null) TextSub else TextMain)
                    if (turn == null) {
                        Text("경로 안내가 시작되면 표시합니다", color = TextSub, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(wholeText(turn.distanceM), fontSize = 88.sp, lineHeight = 96.sp, fontWeight = FontWeight.Bold,
                                color = if (active) Coral else TextMain, softWrap = false)
                            Text("m", fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
                                color = if (active) Coral else TextMain, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
                        }
                        Text(turn.etaSec?.let { "도착까지 약 ${wholeText(it)}초" } ?: "저속 구간", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            EtaTimeline(turn, state.config.leadTimeSec)
        }
    }
}

/** 오른쪽 끝이 도착. 점이 칠한 구간에 들어오면 예고가 켜진다. */
@Composable
private fun EtaTimeline(turn: Upcoming?, leadTimeSec: Double) {
    val active = turn?.cueActive == true
    val eta = turn?.etaSec
    val leadText = "예고 구간 ${oneDecimalText(leadTimeSec)}초"
    Column(Modifier.semantics {
        contentDescription = leadText + (eta?.let { ", 도착까지 약 ${wholeText(it)}초" } ?: "")
    }) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val barHeight = 12.dp.toPx()
            val radius = 18.dp.toPx() / 2
            val left = radius
            val span = size.width - radius * 2
            val barTop = (size.height - barHeight) / 2
            val corner = CornerRadius(barHeight / 2)
            drawRoundRect(Line, Offset(left, barTop), Size(span, barHeight), corner)
            val zoneStart = left + span * etaFraction(leadTimeSec)
            drawRoundRect(
                Coral.copy(alpha = if (active) 1f else 0.45f),
                Offset(zoneStart, barTop), Size(left + span - zoneStart, barHeight), corner,
            )
            if (eta != null) {
                val center = Offset(left + span * etaFraction(eta), size.height / 2)
                drawCircle(Bg, radius = radius + 3.dp.toPx(), center = center)
                drawCircle(if (active) Coral else TextMain, radius = radius, center = center)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${ETA_WINDOW_SEC.toInt()}초 전", color = TextSub, style = MaterialTheme.typography.bodySmall)
            Text(leadText, color = Coral, style = MaterialTheme.typography.bodySmall)
            Text("도착", color = TextSub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CautionCard(caution: Upcoming?) {
    val active = caution?.cueActive == true
    CueCard(Modifier.fillMaxWidth(), borderColor = if (active) Amber else Line) {
        // 최소 높이 = 칩 + 거리. "예고 중" 칩이 나타나도 카드가 커져 회전 카드를 밀지 않게 한다.
        Row(
            Modifier.heightIn(min = 88.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Canvas(Modifier.size(76.dp)) { drawWarning(if (caution == null) Line else Amber, if (caution == null) TextSub else Bg) }
            Column(Modifier.weight(1f)) {
                Text("다음 주의 지점", style = MaterialTheme.typography.titleLarge)
                Text(caution?.label ?: "안내 대기", color = if (caution == null) TextSub else TextMain,
                    style = MaterialTheme.typography.bodyLarge)
            }
            if (caution != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (active) StatusChip("예고 중", Amber)
                    Text("${wholeText(caution.distanceM)} m", fontSize = 40.sp, lineHeight = 48.sp,
                        fontWeight = FontWeight.Bold, color = Amber, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun SignalTiles(state: SessionState) {
    val cue = state.cue
    val speedUnknown = state.mode == SourceMode.LIVE && !state.source.speedKnown
    val (steerValue, steerSide) = steeringText(cue.steeringDeg)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SignalTile("속도", if (speedUnknown) "–" else wholeText(cue.speedKmh), if (speedUnknown) "확인 중" else "km/h", Modifier.weight(1f))
        SignalTile("핸들", steerValue, steerSide, Modifier.weight(1f))
        SignalTile(
            "브레이크",
            if (cue.brakePressed) "밟음" else "꺼짐",
            "감속 ${oneDecimalText(maxOfDouble(0.0, cue.decelMps2))} m/s²",
            Modifier.weight(1f),
            valueColor = if (cue.brakePressed) Amber else TextMain,
        )
    }
}

@Composable
private fun SignalTile(title: String, value: String, detail: String, modifier: Modifier, valueColor: Color = TextMain) {
    CueCard(modifier) {
        Text(title, color = TextSub, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, color = valueColor, softWrap = false)
        Text(detail, color = TextSub, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlaybackBar(state: SessionState, actions: MotionCueActions) {
    CueCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            if (maxWidth >= 640.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlaybackButtons(state, actions)
                    PlaybackInfo(state, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlaybackButtons(state, actions)
                    }
                    PlaybackInfo(state, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PlaybackButtons(state: SessionState, actions: MotionCueActions) {
    val playing = state.source.playing
    CueButton(actions.onPlayPause, Modifier.widthIn(min = 196.dp), Tone.Main, enabled = !state.busy, minHeight = MainTouch) {
        Canvas(Modifier.size(32.dp)) { if (playing) drawPause(Bg) else drawPlay(Bg) }
        Spacer(Modifier.size(12.dp))
        Text(if (playing) "일시정지" else "재생")
    }
    CueButton("처음부터", actions.onRestart, enabled = !state.busy)
}

@Composable
private fun PlaybackInfo(state: SessionState, modifier: Modifier) {
    val source = state.source
    val duration = source.durationMs
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (duration != null && duration > 0L) {
            val fraction = (source.elapsedMs.toFloat() / duration).coerceIn(0f, 1f)
            val track = RoundedCornerShape(6.dp)
            Box(Modifier.fillMaxWidth().height(12.dp).background(Line, track)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Coral, track))
            }
            val finished = !source.playing && source.elapsedMs >= duration
            Text("${clockText(source.elapsedMs)} / ${clockText(duration)}${if (finished) " · 재생 완료" else ""}",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(if (source.playing) "실시간 수신 중" else "재생을 누르면 실차 데이터를 받습니다", style = MaterialTheme.typography.bodyMedium)
        }
        Text("예고 ${oneDecimalText(state.config.leadTimeSec)}초 · 민감도 ${state.config.sensitivity.label}",
            color = TextSub, style = MaterialTheme.typography.bodySmall)
        if (state.settingsLocked) {
            Text("정차 확인 전 설정 잠금 · 시연 모드로 전환 가능", color = Amber, style = MaterialTheme.typography.bodySmall)
        }
        // 반복 애니메이션을 쓰지 않으려고 진행 표시를 문구로 대신한다.
        if (state.busy) Text("처리 중", color = TextSub, style = MaterialTheme.typography.bodySmall)
    }
}
