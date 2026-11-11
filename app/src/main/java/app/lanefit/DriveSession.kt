package app.lanefit

import app.lanefit.engine.Advice
import app.lanefit.engine.AdviceInput
import app.lanefit.engine.DriveMode
import app.lanefit.engine.LaneChange
import app.lanefit.engine.LaneEstimate
import app.lanefit.engine.LaneEstimator
import app.lanefit.engine.Mode
import app.lanefit.engine.RiskInput
import app.lanefit.engine.RiskLevel
import app.lanefit.engine.RiskResult
import app.lanefit.engine.Side
import app.lanefit.engine.advise
import app.lanefit.engine.modeFor
import app.lanefit.engine.scoreLaneChange
import kotlin.math.roundToInt

data class VehicleSample(
    val tMs: Long,
    val speedKmh: Double,
    /** Steering wheel angle in degrees, positive = left. */
    val steeringDeg: Double,
    val signal: Side?,
    val brakeOn: Boolean,
    val driveMode: DriveMode,
    val passenger: Boolean,
    /** Gear in P when the car reports it; null falls back to "stopped for a while". */
    val inPark: Boolean? = null,
)

sealed interface Card {
    data object Idle : Card
    data class Stay(val side: Side, val text: String, val gainS: Int) : Card
    data class Change(val side: Side, val text: String, val gainS: Int) : Card
    data class Risk(val result: RiskResult) : Card
    data object PassengerCalm : Card
}

data class Report(
    val distanceKm: Double = 0.0,
    val stayShown: Int = 0,
    val stayKept: Int = 0,
    val laneChanges: Int = 0,
    /** Sum of predicted gains for recommended changes the driver made. An estimate, never a billing basis. */
    val savedS: Int = 0,
    val riskScores: List<Int> = emptyList(),
) {
    val smoothness: Int? get() = if (riskScores.isEmpty()) null else 100 - riskScores.average().roundToInt()
}

data class UiState(
    val parked: Boolean = true,
    val estimate: LaneEstimate = LaneEstimate(null, 0, 0.0),
    val mode: Mode = Mode.FLOW,
    val pinnedMode: Mode? = null,
    val calmOnPassenger: Boolean = true,
    val passenger: Boolean = false,
    val card: Card = Card.Idle,
    val report: Report = Report(),
)

/** Turns vehicle samples into screen state and one-sentence speech. Read-only: it never commands the car. */
class DriveSession(private val speak: (String) -> Unit = {}) {
    var state = UiState()
        private set

    /** Set by the navigation adapter when the route itself needs a signal (exit, junction). */
    var routeSignal: Side? = null

    private val estimator = LaneEstimator()
    private var laneSpeeds = emptyList<Double>()
    private var horizonKm = 0.0
    private var prev: VehicleSample? = null
    private var cardUntilMs = 0L
    private var stoppedSinceMs: Long? = null
    private var lastRisk: RiskLevel? = null
    private val changes = ArrayDeque<Pair<Long, Double>>() // time, odometer km
    private var odometerKm = 0.0
    private val speedLog = ArrayDeque<Pair<Long, Double>>() // time, km/h over the last BRAKE_WINDOW_MS
    private var pendingRisk: PendingRisk? = null
    private var pendingAdvice: PendingAdvice? = null

    private class PendingRisk(val change: LaneChange, val dueMs: Long, var hardBrake: Boolean)
    private class PendingAdvice(val side: Side, val gainS: Int, val wasStay: Boolean, val dueMs: Long)

    // Anchors and lane speeds come from outside feeds: a bad value is dropped and the last good one stays.
    fun onAnchor(laneCount: Int, lane: Int, reliability: Double) {
        if (lane !in 1..laneCount || !reliability.isFinite()) return
        estimator.onAnchor(laneCount, lane, reliability)
        state = state.copy(estimate = estimator.estimate)
    }

    fun onLaneSpeeds(speedsKmh: List<Double>, horizonKm: Double) {
        if (speedsKmh.any { !it.isFinite() || it < 0 } || !horizonKm.isFinite() || horizonKm <= 0) return
        laneSpeeds = speedsKmh
        this.horizonKm = horizonKm
    }

    fun pinMode(mode: Mode?) {
        state = state.copy(pinnedMode = mode, mode = mode ?: state.mode)
    }

    fun setCalmOnPassenger(on: Boolean) {
        state = state.copy(calmOnPassenger = on)
    }

    fun onSample(s: VehicleSample) {
        val p = prev
        // A bad reading must not move the lane estimate or the odometer: drop it and keep the last good state.
        if (!s.speedKmh.isFinite() || !s.steeringDeg.isFinite() || s.speedKmh < 0 || (p != null && s.tMs <= p.tMs)) return
        prev = s
        // A long gap means nobody was watching (the app was behind another one): it is not distance driven.
        val dtS = if (p == null) 0.0 else ((s.tMs - p.tMs) / 1000.0).let { if (it > MAX_GAP_S) 0.0 else it }
        odometerKm += s.speedKmh * dtS / 3600.0
        // Published in 0.1 km steps so the screen state only changes when something visible does.
        var report = state.report.copy(distanceKm = (odometerKm * 10).roundToInt() / 10.0)
        var card = state.card

        if (dtS == 0.0) speedLog.clear()
        speedLog.addLast(s.tMs to s.speedKmh)
        while (speedLog.size > 1 && s.tMs - speedLog[1].first >= BRAKE_WINDOW_MS) speedLog.removeFirst()

        stoppedSinceMs = if (s.speedKmh < 1.0) stoppedSinceMs ?: s.tMs else null
        val parked = s.inPark ?: (stoppedSinceMs?.let { s.tMs - it >= PARK_AFTER_MS } ?: false)

        val mode = state.pinnedMode ?: modeFor(s.driveMode, s.passenger, state.calmOnPassenger, lastRisk)
        if (s.passenger && p?.passenger == false && state.pinnedMode == null && state.calmOnPassenger) {
            card = show(Card.PassengerCalm, s.tMs)
            speak("동승자가 탔어요, Calm 모드로 바꿀게요")
        }

        val change = estimator.onSample(s.tMs, s.speedKmh, s.steeringDeg, s.signal)
        if (change != null) {
            changes.addLast(s.tMs to odometerKm)
            while (changes.first().let { it.first < s.tMs - 60_000 && it.second < odometerKm - 10.0 }) changes.removeFirst()
            report = report.copy(laneChanges = report.laneChanges + 1)
            pendingAdvice?.takeIf { it.side == change.side }?.let {
                if (!it.wasStay) report = report.copy(savedS = report.savedS + it.gainS)
                pendingAdvice = null
            }
            pendingRisk = PendingRisk(change, s.tMs + BRAKE_WATCH_MS, hardBrake = false)
        }

        // ponytail: braking is judged from speed loss over the last second while the pedal is on. The speed
        // property arrives in steps, so a per-sample difference reads any step as a hard stop.
        // Use brake pressure or longitudinal acceleration if the car exposes one.
        pendingRisk?.let { pr ->
            val (t0, v0) = speedLog.first()
            val decel = if (s.tMs - t0 < BRAKE_WINDOW_MS) 0.0 else (v0 - s.speedKmh) / 3.6 / ((s.tMs - t0) / 1000.0)
            if (s.brakeOn && decel >= HARD_BRAKE_MS2) pr.hardBrake = true
            if (s.tMs >= pr.dueMs) {
                val recent = changes.count { it.first < pr.change.tMs && it.first >= pr.change.tMs - 60_000 }
                val c = pr.change
                val result = scoreLaneChange(
                    RiskInput(c.signalLeadMs, c.speedKmh, c.peakSteeringRateDegS, pr.hardBrake, recent),
                )
                lastRisk = result.level
                report = report.copy(riskScores = report.riskScores + result.score)
                card = show(Card.Risk(result), s.tMs)
                if (result.needsCoaching) speak(result.coaching)
                pendingRisk = null
            }
        }

        val signal = s.signal
        if (signal != null && signal != p?.signal && pendingAdvice?.side != signal) {
            val since = changes.lastOrNull()?.let { (s.tMs - it.first) / 1000.0 }
            val last10Km = changes.count { it.second > odometerKm - 10.0 }
            val input = AdviceInput(
                estimator.estimate, signal, laneSpeeds, horizonKm, mode, last10Km, since, routeSignal == signal,
            )
            when (val advice = advise(input)) {
                Advice.Silent -> Unit
                is Advice.Stay -> {
                    report = report.copy(stayShown = report.stayShown + 1)
                    pendingAdvice = PendingAdvice(signal, advice.gainS, wasStay = true, s.tMs + FOLLOW_WINDOW_MS)
                    card = show(Card.Stay(signal, advice.text, advice.gainS), s.tMs)
                    speak(advice.text)
                }
                is Advice.Change -> {
                    pendingAdvice = PendingAdvice(signal, advice.gainS, wasStay = false, s.tMs + FOLLOW_WINDOW_MS)
                    card = show(Card.Change(advice.side, advice.text, advice.gainS), s.tMs)
                    speak(advice.text)
                }
            }
        }

        pendingAdvice?.takeIf { s.tMs >= it.dueMs }?.let {
            if (it.wasStay) report = report.copy(stayKept = report.stayKept + 1)
            pendingAdvice = null
        }
        if (card != Card.Idle && s.tMs >= cardUntilMs) card = Card.Idle

        state = state.copy(
            parked = parked, estimate = estimator.estimate, mode = mode,
            passenger = s.passenger, card = card, report = report,
        )
    }

    private fun show(card: Card, tMs: Long): Card {
        cardUntilMs = tMs + CARD_MS
        return card
    }

    private companion object {
        const val CARD_MS = 8_000L
        const val PARK_AFTER_MS = 3_000L
        const val BRAKE_WATCH_MS = 3_000L
        const val FOLLOW_WINDOW_MS = 20_000L
        const val HARD_BRAKE_MS2 = 3.0
        const val BRAKE_WINDOW_MS = 1_000L
        const val MAX_GAP_S = 1.0
    }
}
