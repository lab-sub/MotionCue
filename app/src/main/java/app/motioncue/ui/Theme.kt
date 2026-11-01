package app.motioncue.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.motioncue.core.engine.CuePalette
import app.motioncue.core.engine.Strength

internal val Bg = Color(0xFF0D0C0F)
internal val Card = Color(0xFF17151A)
internal val CardRaised = Color(0xFF211E25)
internal val Line = Color(0xFF3A3540)
internal val TextMain = Color(0xFFF7F3F0)
internal val TextSub = Color(0xFFB3ACB6)

// 강조색은 큐 색 두 가지뿐. 화면 색과 조명 요청 색이 같도록 core 팔레트에서 가져온다.
internal val Coral = Color(CuePalette.turn(Strength.NORMAL))
internal val Amber = Color(CuePalette.decel(Strength.NORMAL))

internal val MinTouch = 76.dp
internal val MainTouch = 84.dp
private val CardShape = RoundedCornerShape(24.dp)
internal val ControlShape = RoundedCornerShape(18.dp)

// AAOS 시야 거리 기준. 어떤 Material 슬롯을 쓰든 20sp 아래로 내려가지 않게 한다.
private val CarTypography = Typography(
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodySmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelSmall = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
)

private val CarColors = darkColorScheme(
    primary = Coral, onPrimary = Bg,
    primaryContainer = CardRaised, onPrimaryContainer = TextMain,
    secondary = Amber, onSecondary = Bg,
    secondaryContainer = CardRaised, onSecondaryContainer = TextMain,
    tertiary = Amber, onTertiary = Bg,
    tertiaryContainer = CardRaised, onTertiaryContainer = TextMain,
    background = Bg, onBackground = TextMain,
    surface = Card, onSurface = TextMain,
    surfaceVariant = Line, onSurfaceVariant = TextSub,
    surfaceTint = Color.Transparent,
    inverseSurface = TextMain, inverseOnSurface = Bg, inversePrimary = Coral,
    outline = Line, outlineVariant = Line,
    scrim = Color.Black,
    surfaceBright = Line, surfaceDim = Bg,
    surfaceContainerLowest = Bg, surfaceContainerLow = Card,
    surfaceContainer = Card, surfaceContainerHigh = CardRaised,
    surfaceContainerHighest = Line,
)

@Composable
internal fun MotionCueTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CarColors, typography = CarTypography, content = content)
}

internal enum class Tone(val container: Color, val content: Color) {
    Main(Coral, Bg),
    Recovery(Amber, Bg),
    Neutral(CardRaised, TextMain),
}

/** 앱 공통 버튼. 모양을 한 가지로 통일하고 터치 영역을 76dp 아래로 두지 않는다. */
@Composable
internal fun CueButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
    minHeight: Dp = MinTouch,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = tone.container, contentColor = tone.content,
            disabledContainerColor = Card, disabledContentColor = TextSub.copy(alpha = 0.6f),
        ),
        border = if (tone == Tone.Neutral) BorderStroke(2.dp, Line) else null,
        contentPadding = PaddingValues(horizontal = 20.dp),
        content = content,
    )
}

@Composable
internal fun CueButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
) = CueButton(onClick, modifier, tone, enabled) { Text(text) }

@Composable
internal fun CueCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Line,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = CardShape, color = Card, contentColor = TextMain, border = BorderStroke(2.dp, borderColor)) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

/** 상태 칩. [fill]이 있으면 강조, 없으면 차분한 외곽선. */
@Composable
internal fun StatusChip(text: String, fill: Color? = null) {
    val shape = RoundedCornerShape(50)
    val base = if (fill != null) Modifier.background(fill, shape) else Modifier.border(2.dp, Line, shape)
    Text(
        text,
        modifier = base.padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (fill != null) Bg else TextSub,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.border(2.dp, Line, ControlShape).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier.heightIn(min = MinTouch).widthIn(min = 96.dp)
                    .clip(ControlShape)
                    .background(if (on) Line else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = when {
                        !enabled -> TextSub.copy(alpha = 0.6f)
                        on -> TextMain
                        else -> TextSub
                    },
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
