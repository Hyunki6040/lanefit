package app.lanefit.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

enum class Side(val ko: String) { LEFT("왼쪽"), RIGHT("오른쪽") }

/** lane 1 = leftmost (1차로). lane == null until the first anchor. */
data class LaneEstimate(val lane: Int?, val laneCount: Int, val confidence: Double)

data class LaneChange(
    val side: Side,
    val lanes: Int,
    val tMs: Long,
    val speedKmh: Double,
    /** Signal-on to start of steering. null = no matching signal. */
    val signalLeadMs: Long?,
    val peakSteeringRateDegS: Double,
)

/**
 * Tracks the current lane without user input: anchors (ramp merge / exit) fix the lane,
 * steering dead-reckoning counts changes in between.
 *
 * Steering convention: positive = left. The vehicle adapter flips the sign if the car reports otherwise.
 *
 * ponytail: bicycle-model dead reckoning with a frozen straight-ahead baseline. It cannot tell a lane
 * change from a slowly tightening curve; those time out and cost confidence. Upgrade path: map curvature
 * from NaviHelper, or lane data from an ADAS feed once one is available.
 * Every constant below is a calibration knob: real steering sensors have offset and noise.
 */
class LaneEstimator(
    private val steeringRatio: Double = 14.0,
    private val wheelbaseM: Double = 2.9,
    private val laneWidthM: Double = 3.5,
    private val deadbandDeg: Double = 0.4,
    private val changeFraction: Double = 0.6,
    private val headingTolRad: Double = 0.02,
    private val settleS: Double = 0.6,
    private val abandonS: Double = 2.0,
    private val maxManoeuvreS: Double = 10.0,
    private val baselineTauS: Double = 8.0,
    private val minSpeedKmh: Double = 30.0,
    private val signalMemoryMs: Long = 5_000,
    private val rateWindowMs: Long = 500,
) {
    var estimate = LaneEstimate(null, 0, 0.0)
        private set

    private var lastMs = Long.MIN_VALUE
    private var rateRef: Pair<Long, Double>? = null
    private var baseline = 0.0
    private var startMs: Long? = null
    private var quietS = 0.0
    private var heading = 0.0
    private var lateral = 0.0
    private var peakRate = 0.0

    private var signalSide: Side? = null
    private var signalOnMs = 0L
    private var signalOffMs: Long? = null

    /** A point where the lane is known, e.g. merging from a ramp puts the car in the rightmost lane. */
    fun onAnchor(laneCount: Int, lane: Int, reliability: Double) {
        require(laneCount >= 1 && lane in 1..laneCount) { "lane $lane outside 1..$laneCount" }
        estimate = LaneEstimate(lane, laneCount, reliability.coerceIn(0.0, 1.0))
    }

    fun onSample(tMs: Long, speedKmh: Double, steeringDeg: Double, signal: Side?): LaneChange? {
        trackSignal(tMs, signal)
        val dt = if (lastMs == Long.MIN_VALUE) 0.0 else (tMs - lastMs) / 1000.0
        lastMs = tMs
        if (dt <= 0.0 || dt > 1.0 || speedKmh < minSpeedKmh) {
            reset(steeringDeg)
            return null
        }

        val hp = steeringDeg - baseline
        if (abs(hp) > deadbandDeg) {
            if (startMs == null) startMs = tMs
            quietS = 0.0
        } else {
            quietS += dt
            if (startMs == null) baseline += (steeringDeg - baseline) * (1 - exp(-dt / baselineTauS))
        }
        val started = startMs ?: return null

        val v = speedKmh / 3.6
        // The angle arrives in whole degrees: a per-sample difference would read one step as 20 deg/s.
        val ref = rateRef
        if (ref == null) {
            rateRef = tMs to steeringDeg
        } else if (tMs - ref.first >= rateWindowMs) {
            peakRate = maxOf(peakRate, abs(steeringDeg - ref.second) / ((tMs - ref.first) / 1000.0))
            rateRef = tMs to steeringDeg
        }
        heading += v * tan(Math.toRadians(hp / steeringRatio)) / wheelbaseM * dt
        lateral += v * sin(heading) * dt

        if (abs(lateral) >= laneWidthM * changeFraction && abs(heading) <= headingTolRad && quietS >= settleS) {
            val side = if (lateral > 0) Side.LEFT else Side.RIGHT
            val change = LaneChange(
                side = side,
                lanes = (abs(lateral) / laneWidthM).roundToInt().coerceAtLeast(1),
                tMs = tMs,
                speedKmh = speedKmh,
                signalLeadMs = signalLeadFor(side, started),
                peakSteeringRateDegS = peakRate,
            )
            apply(change)
            reset(baseline)
            return change
        }
        if ((tMs - started) / 1000.0 > maxManoeuvreS) {
            // Probably a curve, possibly a missed change: re-zero and trust the estimate a little less.
            estimate = estimate.copy(confidence = estimate.confidence * 0.9)
            reset(steeringDeg)
        } else if (quietS > abandonS) {
            reset(baseline)
        }
        return null
    }

    private fun apply(c: LaneChange) {
        val lane = estimate.lane ?: return
        val moved = lane + if (c.side == Side.LEFT) -c.lanes else c.lanes
        val trust = if (c.signalLeadMs != null) 0.95 else 0.8
        estimate = if (moved in 1..estimate.laneCount) {
            estimate.copy(lane = moved, confidence = estimate.confidence * trust)
        } else {
            // Moved off the road on paper: the estimate was already wrong.
            estimate.copy(lane = moved.coerceIn(1, estimate.laneCount), confidence = estimate.confidence * 0.5)
        }
    }

    private fun trackSignal(tMs: Long, signal: Side?) {
        if (signal != null && (signalSide != signal || signalOffMs != null)) {
            signalSide = signal
            signalOnMs = tMs
            signalOffMs = null
        } else if (signal == null && signalSide != null && signalOffMs == null) {
            signalOffMs = tMs
        }
    }

    /** One-touch signals cancel before the car finishes moving, so a recently ended signal still counts. */
    private fun signalLeadFor(side: Side, startedMs: Long): Long? {
        if (signalSide != side) return null
        val off = signalOffMs
        if (off != null && off < startedMs - signalMemoryMs) return null
        return (startedMs - signalOnMs).coerceAtLeast(0)
    }

    private fun reset(newBaseline: Double) {
        baseline = newBaseline
        startMs = null
        quietS = 0.0
        heading = 0.0
        lateral = 0.0
        peakRate = 0.0
        rateRef = null
    }
}
