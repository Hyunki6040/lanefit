package app.lanefit

import app.lanefit.engine.DriveMode
import app.lanefit.engine.Side
import kotlin.math.PI
import kotlin.math.sin

/** Made-up per-lane speeds (lane 1 first) for a 4-lane road. Used by the simulation and the sample-road switch. */
val SAMPLE_LANE_SPEEDS = listOf(100.0, 96.0, 87.8, 80.0)

sealed interface DemoEvent {
    val tMs: Long

    data class Sample(val sample: VehicleSample) : DemoEvent {
        override val tMs get() = sample.tMs
    }

    data class Anchor(override val tMs: Long, val laneCount: Int, val lane: Int, val reliability: Double) : DemoEvent
    data class LaneSpeeds(override val tMs: Long, val speedsKmh: List<Double>, val horizonKm: Double) : DemoEvent
}

fun DriveSession.apply(e: DemoEvent) = when (e) {
    is DemoEvent.Sample -> onSample(e.sample)
    is DemoEvent.Anchor -> onAnchor(e.laneCount, e.lane, e.reliability)
    is DemoEvent.LaneSpeeds -> onLaneSpeeds(e.speedsKmh, e.horizonKm)
}

/**
 * SIMULATION. A scripted 66 s drive used for the demo and for the session test. Signals, lane speeds and
 * the ramp anchor are made-up sample values, not measurements.
 *
 *  0 s  merge from a ramp: lane 4 of 4
 *  8 s  left signal, next lane only 40 s faster over 10 km -> Stay
 * 28 s  own lane slows down
 * 32 s  left signal, next lane 150 s faster -> Change; steering starts 3.6 s later (about 60 m of signal)
 * 41 s  lane change detected, risk card 3 s later
 * 48 s  passenger seat occupied -> Calm
 * 52 s  brake to a stop -> report
 */
fun demoScenario(): List<DemoEvent> {
    val out = mutableListOf<DemoEvent>(
        DemoEvent.Anchor(0, laneCount = 4, lane = 4, reliability = 0.95),
        DemoEvent.LaneSpeeds(0, SAMPLE_LANE_SPEEDS, horizonKm = 10.0),
    )
    for (tMs in 0L..66_000L step 50L) {
        val t = tMs / 1000.0
        if (tMs == 28_000L) out += DemoEvent.LaneSpeeds(tMs, listOf(100.0, 92.0, 80.0, 60.0), horizonKm = 10.0)
        val speed = when {
            t < 24 -> 80.0
            t < 28 -> 80.0 - 20.0 * (t - 24) / 4
            t < 41 -> 60.0
            t < 46 -> 60.0 + 20.0 * (t - 41) / 5
            t < 52 -> 80.0
            t < 60 -> 80.0 * (60 - t) / 8
            else -> 0.0
        }
        // One sine period moves the car one lane to the left at 60 km/h.
        val steering = if (t in 35.6..40.6) 7.36 * sin(2 * PI * (t - 35.6) / 5.0) else 0.0
        val signal = if (t in 8.0..11.0 || t in 32.0..41.0) Side.LEFT else null
        out += DemoEvent.Sample(
            VehicleSample(
                tMs = tMs, speedKmh = speed, steeringDeg = steering, signal = signal,
                brakeOn = t in 24.0..28.0 || t in 52.0..60.0,
                driveMode = DriveMode.NORMAL, passenger = t >= 48.0,
            ),
        )
    }
    return out
}
