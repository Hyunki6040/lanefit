package app.lanefit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lanefit.Card
import app.lanefit.Report
import app.lanefit.Source
import app.lanefit.UiState
import app.lanefit.engine.MIN_CONFIDENCE
import app.lanefit.engine.Mode
import app.lanefit.engine.RiskLevel
import app.lanefit.engine.RiskResult
import app.lanefit.engine.Side
import app.lanefit.engine.spoken
import java.util.Locale
import kotlin.math.roundToInt

// Pleos component sizes (dp). Landscape app windows are 1256-1700 wide, portrait ones 628-840.
private val WIDE_MIN = 1000.dp
private val GAP = 24.dp
private val CardShape = RoundedCornerShape(48.dp)
private val TileShape = RoundedCornerShape(24.dp)
private val PillShape = RoundedCornerShape(50)

/**
 * Two screens, chosen by the car and not by the driver: while moving there is one sentence and nothing to touch;
 * every control lives on the parked screen.
 * ponytail: "parked" comes from DriveSession (gear P, or stopped 3 s when no gear is reported). Listen to
 * CarUxRestrictions as well if a market's policy differs from that.
 */
@Composable
fun LaneFitApp(
    ui: UiState,
    source: Source,
    signals: List<Pair<String, String>>,
    onPinMode: (Mode?) -> Unit,
    onCalmOnPassenger: (Boolean) -> Unit,
    onSource: (Source) -> Unit,
) {
    val c = if (isSystemInDarkTheme()) PleosDark else PleosLight
    CompositionLocalProvider(LocalPleos provides c) {
        BoxWithConstraints(Modifier.fillMaxSize().background(c.backgroundPrimary)) {
            val wide = maxWidth >= WIDE_MIN
            val pad = if (wide) 48.dp else 32.dp
            if (ui.parked) {
                Parked(ui, source, signals, wide, pad, onPinMode, onCalmOnPassenger, onSource)
            } else {
                Driving(ui, source, wide, pad)
            }
        }
    }
}

// ---------------------------------------------------------------- driving

@Composable
private fun Driving(ui: UiState, source: Source, wide: Boolean, pad: Dp) {
    val card = ui.card
    val side: @Composable (Modifier) -> Unit = { m ->
        if (card is Card.Risk) RiskPanel(card.result, m) else LanePanel(ui, m)
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(pad), spacedBy(GAP)) {
            Column(Modifier.weight(1f).fillMaxHeight(), spacedBy(GAP)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Status(ui)
                    Spacer(Modifier.weight(1f))
                    source.label?.let { Pill(it) }
                }
                MessageCard(ui, Modifier.weight(1f).fillMaxWidth())
            }
            side(Modifier.fillMaxWidth(0.3f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize().padding(pad), spacedBy(GAP)) {
            Status(ui)
            source.label?.let { Pill(it) }
            MessageCard(ui, Modifier.weight(1f).fillMaxWidth())
            side(Modifier.weight(0.8f).fillMaxWidth())
        }
    }
}

@Composable
private fun Status(ui: UiState) {
    val c = LocalPleos.current
    val confidence = ui.estimate.confidence
    val level = when {
        ui.estimate.lane == null || confidence < MIN_CONFIDENCE -> "낮음"
        confidence < 0.8 -> "보통"
        else -> "높음"
    }
    Row(horizontalArrangement = spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        T("모드", PleosType.BodyM, c.textSecondary)
        T(ui.mode.label, PleosType.BodyM.copy(fontWeight = PleosType.Bold))
        Spacer(Modifier.size(20.dp))
        T("차선 확신도", PleosType.BodyM, c.textSecondary)
        T(level, PleosType.BodyM.copy(fontWeight = PleosType.Bold))
    }
}

@Composable
private fun MessageCard(ui: UiState, modifier: Modifier) {
    val c = LocalPleos.current
    val lane = ui.estimate.lane.takeIf { ui.estimate.confidence >= MIN_CONFIDENCE }
    val card = ui.card
    PCard(modifier, if (card is Card.Stay) c.surfaceHigh else c.surfaceBasic, pad = 48.dp) {
        when (card) {
            Card.Idle ->
                if (lane != null) {
                    Message(null, "내 차선", "${lane}차선", "깜빡이를 켜면 알려드려요")
                } else {
                    Message({ Pill("추천 쉬는 중") }, null, "차선을 다시 확인하고 있어요", "확인되면 다시 알려드려요")
                }
            is Card.Stay -> {
                // "옮겨도 40초 차이예요, 유지하세요": the verdict is the last clause and gets the largest type.
                val parts = card.text.split(", ", limit = 2)
                Message(
                    { Pill("유지", Icons.Filled.KeyboardArrowUp, filled = true) },
                    parts.takeIf { it.size == 2 }?.let { it[0] + "," }, parts.last(), null, PleosType.HeadlineL,
                )
            }
            is Card.Change ->
                // Speed information only. Whether the gap is safe stays the driver's call.
                Message({ Pill("속도 정보", Icons.Filled.Info) }, null, card.text, "옆 차 간격은 직접 확인하세요")
            is Card.Risk ->
                Message({ Pill(if (card.result.needsCoaching) "다음 변경 때" else "방금 변경") }, null, card.result.coaching, null)
            Card.PassengerCalm -> Message(
                { Pill("동승자 탑승", Icons.Filled.Person) }, null, "Calm 모드로 바꿨어요",
                "${Mode.CALM.thresholdS / 60}분 이상 빨라질 때만 알려드려요",
            )
        }
    }
}

@Composable
private fun ColumnScope.Message(
    pill: (@Composable () -> Unit)?,
    lead: String?,
    main: String,
    sub: String?,
    mainStyle: TextStyle = PleosType.HeadlineS,
) {
    val c = LocalPleos.current
    pill?.invoke()
    Spacer(Modifier.weight(1f))
    lead?.let { T(it, PleosType.TitleL.copy(fontWeight = FontWeight.W400), c.textSecondary) }
    T(main, mainStyle, maxLines = 2)
    sub?.let {
        Spacer(Modifier.height(GAP))
        T(it, PleosType.BodyM, c.textSecondary)
    }
    Spacer(Modifier.weight(1f))
}

/** Own lane and the lane that was asked about. Other cars are never drawn: the app does not know the gaps. */
@Composable
private fun LanePanel(ui: UiState, modifier: Modifier) {
    val c = LocalPleos.current
    val est = ui.estimate
    val mine = est.lane.takeIf { est.confidence >= MIN_CONFIDENCE }
    val card = ui.card
    val askedSide = when (card) {
        is Card.Stay -> card.side
        is Card.Change -> card.side
        else -> null
    }
    val asked = if (mine == null || askedSide == null) null else mine + if (askedSide == Side.LEFT) -1 else 1
    PCard(modifier, pad = 24.dp) {
        if (mine == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { T("차선 확인 중", PleosType.TitleS, c.textSecondary) }
        } else {
            Row(Modifier.fillMaxSize(), spacedBy(8.dp)) {
                for (n in 1..est.laneCount) {
                    val isMine = n == mine
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(TileShape)
                            .background(if (isMine) c.surfaceAccent else c.surfaceLow)
                            .then(if (n == asked) Modifier.border(3.dp, c.textTertiary, TileShape) else Modifier)
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        T(
                            "$n",
                            PleosType.TitleM.copy(fontWeight = if (isMine) PleosType.ExtraBold else FontWeight.W400),
                            if (isMine) c.textPrimary else c.textSecondary,
                        )
                        Spacer(Modifier.weight(1f))
                        if (n == asked && card is Card.Change) {
                            T(
                                "${spoken(card.gainS)}\n빠름", PleosType.BodyS.copy(fontWeight = PleosType.Bold),
                                align = TextAlign.Center,
                            )
                        }
                        if (isMine) {
                            Box(Modifier.fillMaxWidth(0.5f).aspectRatio(0.53f).background(c.textPrimary, RoundedCornerShape(16.dp))) {
                                Box(
                                    Modifier.fillMaxWidth(0.7f).fillMaxHeight(0.2f).align(BiasAlignment(0f, -0.5f))
                                        .background(c.surfaceAccent, RoundedCornerShape(6.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskPanel(r: RiskResult, modifier: Modifier) {
    val c = LocalPleos.current
    // Level is told three ways: word, icon shape, and which of the three segments is filled.
    val (icon, color) = when (r.level) {
        RiskLevel.LOW -> Icons.Filled.CheckCircle to c.informativeActive
        RiskLevel.MID -> Icons.Filled.Info to c.textPrimary
        RiskLevel.HIGH -> Icons.Filled.Warning to c.informativeNegative
    }
    PCard(modifier, pad = 40.dp) {
        Spacer(Modifier.weight(1f))
        T("방금 변경의 위험 지수", PleosType.BodyM, c.textSecondary)
        T("${r.score}", PleosType.HeadlineL)
        Row(horizontalArrangement = spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(48.dp), tint = color)
            T(r.level.ko, PleosType.TitleM)
        }
        Spacer(Modifier.height(GAP))
        Row(horizontalArrangement = spacedBy(8.dp)) {
            for (l in RiskLevel.entries) {
                Box(
                    Modifier.weight(1f).height(16.dp)
                        .background(if (l == r.level) color else c.surfaceAccent, RoundedCornerShape(8.dp)),
                )
            }
        }
        Spacer(Modifier.height(GAP))
        T(
            r.leadDistanceM?.let { "신호 선행 ${it.roundToInt()}m · 기준 100m" } ?: "방향지시등 없이 바꿨어요",
            PleosType.BodyS, c.textSecondary,
        )
        T("참고용 추정치예요", PleosType.BodyS, c.textTertiary)
        Spacer(Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- parked

@Composable
private fun Parked(
    ui: UiState,
    source: Source,
    signals: List<Pair<String, String>>,
    wide: Boolean,
    pad: Dp,
    onPinMode: (Mode?) -> Unit,
    onCalmOnPassenger: (Boolean) -> Unit,
    onSource: (Source) -> Unit,
) {
    val cardPad = if (wide) 40.dp else 32.dp
    val report = @Composable { ReportCard(ui.report, source, if (wide) 3 else 2, cardPad) }
    val mode = @Composable { ModeCard(ui, cardPad, onPinMode, onCalmOnPassenger) }
    val data = @Composable { SourceCard(source, cardPad, onSource) }
    val check = @Composable { SignalCard(signals, cardPad) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), spacedBy(GAP)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("레인핏", PleosType.TitleL)
            Spacer(Modifier.weight(1f))
            source.label?.let { Pill(it) }
        }
        if (wide) {
            Row(horizontalArrangement = spacedBy(GAP)) {
                Column(Modifier.weight(1.2f), spacedBy(GAP)) { report(); check() }
                Column(Modifier.weight(1f), spacedBy(GAP)) { mode(); data() }
            }
        } else {
            report(); mode(); data(); check()
        }
    }
}

@Composable
private fun ReportCard(r: Report, source: Source, columns: Int, pad: Dp) {
    val c = LocalPleos.current
    val tiles = listOf(
        Triple("주행 거리", String.format(Locale.KOREA, "%.1f km", r.distanceKm), null),
        Triple("차선 변경", "${r.laneChanges}회", null),
        Triple("Stay 후 차선 유지", "${r.stayKept}/${r.stayShown}회", null),
        Triple("예상 절약 시간", spoken(r.savedS), "예측값 합계"),
        Triple("부드러운 운전 점수", r.smoothness?.toString() ?: "–", "위험 지수 평균"),
    )
    PCard(Modifier.fillMaxWidth(), pad = pad) {
        T("이번 주행", PleosType.TitleM)
        source.label?.let { T("$it 기준이며 실제 측정값이 아니에요", PleosType.LabelM, c.textSecondary) }
        Spacer(Modifier.height(GAP))
        Column(verticalArrangement = spacedBy(16.dp)) {
            for (row in tiles.chunked(columns)) {
                Row(horizontalArrangement = spacedBy(16.dp)) {
                    for ((label, value, note) in row) {
                        Column(Modifier.weight(1f).background(c.surfaceLow, TileShape).padding(24.dp)) {
                            T(label, PleosType.LabelM, c.textSecondary, maxLines = 1)
                            T(value, PleosType.TitleL, maxLines = 1)
                            note?.let { T(it, PleosType.LabelM, c.textTertiary) }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(ui: UiState, pad: Dp, onPinMode: (Mode?) -> Unit, onCalmOnPassenger: (Boolean) -> Unit) {
    val c = LocalPleos.current
    PCard(Modifier.fillMaxWidth(), pad = pad) {
        T("추천 모드", PleosType.TitleM)
        Spacer(Modifier.height(GAP))
        Segmented(listOf<Pair<Mode?, String>>(null to "자동") + Mode.entries.map { it to it.label }, ui.pinnedMode, onPinMode)
        Spacer(Modifier.height(16.dp))
        T(
            "지금 ${ui.mode.label} · ${ui.mode.thresholdS / 60}분 이상 빨라질 때만 알려드려요",
            PleosType.BodyS, c.textSecondary,
        )
        T("10km당 최대 ${ui.mode.maxChangesPer10Km}회 · 자동은 차량 주행 모드를 따라가요", PleosType.LabelM, c.textTertiary)
        Spacer(Modifier.height(GAP))
        SwitchRow("동승자가 타면 자동으로 Calm", ui.calmOnPassenger, onCalmOnPassenger)
    }
}

@Composable
private fun SourceCard(source: Source, pad: Dp, onSource: (Source) -> Unit) {
    val c = LocalPleos.current
    PCard(Modifier.fillMaxWidth(), pad = pad) {
        T("데이터 출처", PleosType.TitleM)
        Spacer(Modifier.height(GAP))
        Segmented(
            listOf(Source.CAR to "차량 신호", Source.SAMPLE_ROAD to "예시 도로", Source.SIMULATION to "시뮬레이션"),
            source, onSource,
        )
        Spacer(Modifier.height(16.dp))
        T(
            when (source) {
                Source.CAR -> "차량에서 받은 신호만 써요. 차로별 속도 데이터가 아직 연결되지 않아 추천은 나오지 않아요."
                Source.SAMPLE_ROAD -> "차량 신호에 예시로 넣은 4차로 속도를 더해요. 실제 도로 데이터가 아니에요."
                Source.SIMULATION -> "미리 만든 66초 주행을 재생해요. 측정값이 아니에요."
            },
            PleosType.BodyS, c.textSecondary,
        )
    }
}

@Composable
private fun SignalCard(signals: List<Pair<String, String>>, pad: Dp) {
    val c = LocalPleos.current
    PCard(Modifier.fillMaxWidth(), pad = pad) {
        T("차량 신호 점검", PleosType.TitleM)
        T("차량에서 실제로 받은 값만 표시해요", PleosType.LabelM, c.textSecondary)
        Spacer(Modifier.height(16.dp))
        for ((name, status) in signals) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), spacedBy(16.dp)) {
                T(name, PleosType.BodyS, c.textSecondary, Modifier.weight(1f), maxLines = 1)
                T(status, PleosType.BodyS.copy(fontWeight = PleosType.Bold), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- Pleos components

@Composable
private fun T(
    text: String,
    style: TextStyle,
    color: Color = LocalPleos.current.textPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) = Text(text, modifier, color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align)

/** Card: radius 48, 1dp alpha_dark_100 border, no shadow. */
@Composable
private fun PCard(
    modifier: Modifier = Modifier,
    color: Color = LocalPleos.current.surfaceBasic,
    pad: Dp,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier.clip(CardShape).background(color).border(1.dp, LocalPleos.current.border, CardShape).padding(pad),
    content = content,
)

/** A status label, not a button. Filled is used once per screen (Stay), with informative_active = "keep". */
@Composable
private fun Pill(text: String, icon: ImageVector? = null, filled: Boolean = false) {
    val c = LocalPleos.current
    val ink = if (filled) StaticDark else c.textPrimary
    Row(
        Modifier.height(64.dp)
            .then(if (filled) Modifier.background(c.informativeActive, PillShape) else Modifier.border(2.dp, c.border, PillShape))
            .padding(horizontal = 24.dp),
        spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(36.dp), tint = ink) }
        T(text, PleosType.BodyS.copy(fontWeight = PleosType.Bold), ink, maxLines = 1)
    }
}

/** Segmented menu, Box type, fitted: container 100 high with 8 padding, selected chip 84 high. */
@Composable
private fun <V> Segmented(options: List<Pair<V, String>>, selected: V, onSelect: (V) -> Unit) {
    val c = LocalPleos.current
    Row(
        Modifier.fillMaxWidth().height(100.dp).background(c.controllerTabBackground, PillShape).padding(8.dp)
            .selectableGroup(),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(PillShape)
                    .background(if (on) c.buttonSwitchEnabled else Color.Transparent)
                    .selectable(on, role = Role.Tab) { onSelect(value) },
                Alignment.Center,
            ) {
                T(label, PleosType.BodyS.copy(fontWeight = if (on) PleosType.Bold else FontWeight.W400), maxLines = 1)
            }
        }
    }
}

/** Switch: track 80x48 radius 16, knob 32 radius 8 inset 8. The whole row is the touch target. */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalPleos.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(TileShape)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        spacedBy(GAP), Alignment.CenterVertically,
    ) {
        T(title, PleosType.BodyM, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(80.dp, 48.dp).background(if (checked) c.switchOn else c.switchOff, RoundedCornerShape(16.dp))
                .padding(8.dp),
        ) {
            Box(
                Modifier.size(32.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(Color.White, RoundedCornerShape(8.dp)),
            )
        }
    }
}

private val Mode.label get() = name.lowercase().replaceFirstChar { it.uppercase() }
