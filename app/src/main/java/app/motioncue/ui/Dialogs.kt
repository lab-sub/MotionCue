package app.motioncue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.motioncue.core.engine.CueConfig
import app.motioncue.core.engine.Sensitivity
import app.motioncue.core.model.Seat
import app.motioncue.session.SessionState
import app.motioncue.session.SourceMode

@Composable
private fun CarDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(20.dp).widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight(0.94f),
            shape = RoundedCornerShape(24.dp),
            color = Card,
            contentColor = TextMain,
            content = content,
        )
    }
}

@Composable
internal fun SettingsDialog(state: SessionState, onDismiss: () -> Unit, apply: (CueConfig, Boolean) -> Unit) {
    var lead by remember { mutableFloatStateOf(state.config.leadTimeSec.toFloat()) }
    var sensitivity by remember { mutableStateOf(state.config.sensitivity) }
    var seats by remember { mutableStateOf(state.config.targetSeats) }
    var lights by remember { mutableStateOf(state.lightingEnabled) }
    val locked = state.settingsLocked
    CarDialog(onDismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("설정", style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (locked) Text("실차 주행 중에는 설정을 바꿀 수 없습니다", color = Amber)
                Text("예고 시간  ${oneDecimalText(lead.toDouble())}초", style = MaterialTheme.typography.titleLarge)
                Slider(value = lead, onValueChange = { lead = it }, valueRange = 1f..4f, steps = 5,
                    enabled = !locked, modifier = Modifier.heightIn(min = MinTouch))
                Text("큐 대상 좌석", style = MaterialTheme.typography.titleLarge)
                Seat.PASSENGERS.forEach { seat ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(
                            value = seat in seats, enabled = !locked, role = Role.Checkbox,
                            onValueChange = { selected -> seats = if (selected) seats + seat else seats - seat },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = seat in seats, onCheckedChange = null, enabled = !locked)
                        Text(seat.label)
                    }
                }
                Text("민감도", style = MaterialTheme.typography.titleLarge)
                Segmented(
                    options = Sensitivity.entries,
                    selected = sensitivity,
                    label = { it.label },
                    onSelect = { sensitivity = it },
                    enabled = !locked,
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(
                        value = lights, enabled = !locked, role = Role.Switch, onValueChange = { lights = it },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Live 실제 조명 제어", style = MaterialTheme.typography.titleLarge)
                        Text("지원되는 동승자 전용 구역만 사용", color = TextSub, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = lights,
                        onCheckedChange = null,
                        enabled = !locked,
                        // 기본 꺼짐 손잡이는 outline 색이라 이 팔레트에서 트랙과 구분되지 않는다.
                        colors = SwitchDefaults.colors(uncheckedThumbColor = TextSub, uncheckedBorderColor = TextSub),
                    )
                }
                Text("공조·창문 개입은 좌석의 ‘멀미 나요’ 버튼으로 시작하며 다시 누르면 복원을 요청합니다.",
                    color = TextSub, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CueButton("닫기", onDismiss, Modifier.weight(1f))
                CueButton(
                    "적용",
                    onClick = { apply(state.config.copy(leadTimeSec = lead.toDouble(), targetSeats = seats, sensitivity = sensitivity), lights) },
                    modifier = Modifier.weight(1f),
                    tone = Tone.Main,
                    enabled = !locked,
                )
            }
        }
    }
}

/** 메인 화면에서 뺀 개발용 로그·SDK 용어·면책 문구는 모두 여기서 볼 수 있다. */
@Composable
internal fun LogDialog(state: SessionState, onDismiss: () -> Unit) {
    CarDialog(onDismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("기록", style = MaterialTheme.typography.headlineMedium)
            Column(
                Modifier.fillMaxWidth().background(CardRaised, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val small = MaterialTheme.typography.bodySmall
                Text("입력: ${state.source.detail.ifBlank { "재생 대기" }}", style = MaterialTheme.typography.bodyMedium)
                Text("조명: ${state.actuatorStatus}", style = MaterialTheme.typography.bodyMedium)
                if (state.mode == SourceMode.MOCK) {
                    Text("Mock: 화면·안내 음성으로 시연합니다. 차량 장치는 제어하지 않습니다.", color = TextSub, style = small)
                    Text("95초 합성 시나리오 · 멀미 개선 효과를 검증한 결과가 아닙니다.", color = TextSub, style = small)
                } else {
                    Text("화면은 큐 목표색을 표시합니다. 차량의 실제 조명 상태는 별도 확인이 필요합니다.", color = TextSub, style = small)
                    Text("속도는 SDK 수신값 또는 내비 기반 추정값입니다.", color = TextSub, style = small)
                }
                Text("운전석은 조명·공조·창문 제어에서 제외합니다.", color = TextSub, style = small)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (state.logs.isEmpty()) item { Text("아직 기록이 없습니다", color = TextSub) }
                items(state.logs.asReversed()) { message ->
                    Text(message, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color = Line)
                }
            }
            CueButton("닫기", onDismiss, Modifier.fillMaxWidth())
        }
    }
}
