package app.lanefit.engine

import kotlin.math.roundToInt

/**
 * The smallest predicted gain a change is mentioned for, and how many such mentions fit in 10 km.
 * docs/research/backtest.md shows what each threshold does on 15 days of public lane data.
 */
enum class Mode(val thresholdS: Int, val maxChangesPer10Km: Int) {
    CALM(180, 1), FLOW(120, 2), RUSH(60, 3);

    fun calmer(): Mode = entries[(ordinal - 1).coerceAtLeast(0)]
}

enum class DriveMode { ECO, NORMAL, SPORT }

fun modeFor(drive: DriveMode, passenger: Boolean, calmOnPassenger: Boolean, recentRisk: RiskLevel?): Mode {
    if (passenger && calmOnPassenger) return Mode.CALM
    val base = when (drive) {
        DriveMode.ECO -> Mode.CALM
        DriveMode.NORMAL -> Mode.FLOW
        DriveMode.SPORT -> Mode.RUSH
    }
    return if (recentRisk == RiskLevel.HIGH) base.calmer() else base
}

sealed interface Advice {
    /** No screen change, no speech. */
    data object Silent : Advice
    data class Stay(val gainS: Int, val text: String) : Advice
    data class Change(val side: Side, val gainS: Int, val text: String) : Advice
}

data class AdviceInput(
    val estimate: LaneEstimate,
    /** The turn signal the driver just switched on. */
    val signal: Side,
    /** Predicted speed per lane, index 0 = lane 1. null = no usable speed (bus lane): never an answer. */
    val laneSpeedsKmh: List<Double?>,
    /** Distance the comparison covers: remaining distance on this road, capped by the caller. */
    val horizonKm: Double,
    val mode: Mode,
    /** "Change" answers already given in the last 10 km. */
    val changeAdviceLast10Km: Int,
    val sinceLastChangeS: Double?,
    /** The route needs this signal (exit, junction), so it is not a question. */
    val signalIsForRoute: Boolean,
)

const val MIN_CONFIDENCE = 0.6
const val MIN_DWELL_S = 60.0

/**
 * Answers the turn signal with speed information only. It never judges whether the gap is safe.
 * Stay is the default answer; Change needs the gain to clear the mode threshold and both guards.
 */
fun advise(i: AdviceInput): Advice {
    val lane = i.estimate.lane
    if (i.signalIsForRoute || lane == null || i.estimate.confidence < MIN_CONFIDENCE) return Advice.Silent
    val target = lane + if (i.signal == Side.LEFT) -1 else 1
    val here = i.laneSpeedsKmh.getOrNull(lane - 1)
    val there = i.laneSpeedsKmh.getOrNull(target - 1)
    if (target !in 1..i.estimate.laneCount || here == null || there == null) return Advice.Silent

    val gainS = ((i.horizonKm / here.coerceAtLeast(5.0) - i.horizonKm / there.coerceAtLeast(5.0)) * 3600).roundToInt()
    val side = i.signal.ko
    if (gainS <= -5) return Advice.Stay(gainS, "${side}이 지금 더 느려요")
    if (gainS < 5) return Advice.Stay(gainS, "차이가 거의 없어요, 유지하세요")
    if (gainS < i.mode.thresholdS) return Advice.Stay(gainS, "옮겨도 ${spoken(gainS)} 차이예요, 유지하세요")

    val justChanged = i.sinceLastChangeS != null && i.sinceLastChangeS < MIN_DWELL_S
    if (justChanged) return Advice.Stay(gainS, "방금 바꿨어요, 조금 더 유지하세요")
    if (i.changeAdviceLast10Km >= i.mode.maxChangesPer10Km) return Advice.Stay(gainS, "변경은 충분했어요, 이대로 가세요")
    return Advice.Change(i.signal, gainS, "${side}이 ${spoken(gainS)} 빨라요")
}

fun spoken(seconds: Int) = if (seconds < 60) "${seconds}초" else "${seconds / 60}분"
