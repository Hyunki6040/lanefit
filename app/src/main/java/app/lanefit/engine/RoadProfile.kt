package app.lanefit.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot

private const val M_PER_DEG = 111_320.0
// ponytail: a section is the straight line between two mileposts, not the road's real shape, so the corridor is
// wide. Narrow it once sections carry map geometry or a link id from the navigation.
private const val MATCH_M = 200.0
private const val MATCH_DEG = 40.0
private const val MIN_MOVE_M = 5.0
private const val MAX_KMH = 250.0
private const val FIX_STEP_MS = 1_000L
private const val FIX_STALE_MS = 10_000L
private const val SMOOTH_MS = 60_000.0

// Same range as SCALE in scripts/lane_profile.py: the backtest numbers describe this exact rule.
private const val MIN_SCALE = 0.4
private const val MAX_SCALE = 1.3

/** East and north offset in metres from (lat0, lon0). Flat earth: fine for the few km compared here. */
private fun offsetM(lat0: Double, lon0: Double, lat: Double, lon: Double) =
    (lon - lon0) * M_PER_DEG * cos(Math.toRadians(lat0)) to (lat - lat0) * M_PER_DEG

fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    offsetM(lat1, lon1, lat2, lon2).let { (e, n) -> hypot(e, n) }

/** Compass course from point 1 to point 2: 0 = north, 90 = east. */
fun courseDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    offsetM(lat1, lon1, lat2, lon2).let { (e, n) -> (Math.toDegrees(atan2(e, n)) + 360) % 360 }

fun courseGapDeg(a: Double, b: Double): Double = abs((a - b + 540) % 360 - 180)

/**
 * Course and speed from position fixes one second apart. The location SDK reports no speed, and its heading
 * convention is undocumented, so the heading is used only until the car has moved.
 * ponytail: straight difference of two fixes, no filtering. Add a Kalman filter if the speed is ever shown.
 */
class FixTracker {
    data class Fix(val courseDeg: Double?, val speedKmh: Double?)

    private class Point(val lat: Double, val lon: Double, val tMs: Long)

    private var ref: Point? = null
    private var course: Double? = null
    private var speed: Double? = null

    fun onFix(lat: Double, lon: Double, tMs: Long, headingDeg: Double?): Fix {
        val r = ref
        if (r == null || tMs < r.tMs || tMs - r.tMs > FIX_STALE_MS) {
            ref = Point(lat, lon, tMs)
            speed = null
        } else if (tMs - r.tMs >= FIX_STEP_MS) {
            val d = metresBetween(r.lat, r.lon, lat, lon)
            speed = (d / ((tMs - r.tMs) / 1000.0) * 3.6).takeIf { it <= MAX_KMH }
            if (d >= MIN_MOVE_M) course = courseDeg(r.lat, r.lon, lat, lon)
            ref = Point(lat, lon, tMs)
        }
        return Fix(course ?: headingDeg, speed)
    }
}

/**
 * One detector section of a road, driven from a to b, with the typical car speed of every lane for each
 * quarter hour. Built by scripts/lane_profile.py from the Korea Expressway Corporation's public lane data.
 */
class RoadSection(
    val id: String,
    val name: String,
    private val latA: Double,
    private val lonA: Double,
    private val latB: Double,
    private val lonB: Double,
    val laneCount: Int,
    private val weekdayRows: Map<Int, List<Double?>>,
    private val weekendRows: Map<Int, List<Double?>>,
) {
    /** True when the car is on this section and driving its way. */
    fun contains(lat: Double, lon: Double, course: Double): Boolean {
        val (bx, by) = offsetM(latA, lonA, latB, lonB)
        val (px, py) = offsetM(latA, lonA, lat, lon)
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return false
        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        return hypot(px - t * bx, py - t * by) <= MATCH_M &&
            courseGapDeg(course, courseDeg(latA, lonA, latB, lonB)) <= MATCH_DEG
    }

    /** Index 0 = lane 1. A null lane has no usable speed (bus lane, empty night): it is never recommended. */
    fun speeds(slot: Int, weekend: Boolean): List<Double?>? =
        (if (weekend) weekendRows else weekdayRows)[slot]?.takeIf { it.size == laneCount }
}

class RoadProfile(val sections: List<RoadSection>) {
    fun match(lat: Double, lon: Double, course: Double): RoadSection? = sections.firstOrNull { it.contains(lat, lon, course) }

    companion object {
        /** Reads lane_profile.txt. A malformed line is skipped, never thrown: the app then stays silent there. */
        fun parse(lines: List<String>): RoadProfile {
            val sections = mutableListOf<RoadSection>()
            var head: List<String>? = null
            var weekday = mutableMapOf<Int, List<Double?>>()
            var weekend = mutableMapOf<Int, List<Double?>>()

            fun flush() {
                val h = head ?: return
                val lanes = h[7].toIntOrNull()?.takeIf { it >= 1 } ?: return
                val c = h.subList(3, 7).map { it.toDoubleOrNull() ?: return }
                sections += RoadSection(h[1], h[2], c[0], c[1], c[2], c[3], lanes, weekday, weekend)
            }
            for (line in lines) {
                val f = line.split('|')
                if (f[0] == "S" && f.size >= 8) {
                    flush()
                    head = f
                    weekday = mutableMapOf()
                    weekend = mutableMapOf()
                } else if ((f[0] == "W" || f[0] == "E") && f.size == 3) {
                    val slot = f[1].toIntOrNull() ?: continue
                    val row = f[2].split(',').map { v -> v.toDoubleOrNull()?.takeIf { it > 0 } }
                    (if (f[0] == "W") weekday else weekend)[slot] = row
                }
            }
            flush()
            return RoadProfile(sections)
        }
    }
}

/**
 * Finds the section the car is on and brings its lane profile to today's level: every lane is scaled by
 * (how fast my lane is moving now) / (how fast my lane usually moves at this hour).
 * ponytail: the gap between lanes is assumed to keep its usual ratio. Replace the scale with live per-lane
 * speeds when a public feed for them exists.
 */
class RoadMatcher(private val profile: RoadProfile) {
    private var matched: RoadSection? = null
    private var fixMs = 0L
    private var ownKmh: Double? = null
    private var speedMs = 0L

    fun onLocation(tMs: Long, lat: Double, lon: Double, course: Double?) {
        matched = course?.let { profile.match(lat, lon, it) }
        fixMs = tMs
    }

    /** One-minute moving average: the profile is a 15-minute mean, one reading in stop-and-go traffic is not. */
    fun onSpeed(tMs: Long, speedKmh: Double) {
        val prev = ownKmh
        val dt = tMs - speedMs
        ownKmh = if (prev == null || dt !in 0..5_000) speedKmh else prev + (speedKmh - prev) * (1 - exp(-dt / SMOOTH_MS))
        speedMs = tMs
    }

    /** null once the position is older than ten seconds: an old match is not where the car is. */
    fun section(tMs: Long): RoadSection? = matched.takeIf { tMs - fixMs in 0..FIX_STALE_MS }

    /** Lane speeds for the recommender, or an empty list (which keeps it silent) off the profiled roads. */
    fun laneSpeeds(tMs: Long, lane: Int?, slot: Int, weekend: Boolean): List<Double?> {
        val row = section(tMs)?.speeds(slot, weekend) ?: return emptyList()
        val typical = lane?.let { row.getOrNull(it - 1) }
        val own = ownKmh
        if (typical == null || own == null) return row
        val scale = (own / typical).coerceIn(MIN_SCALE, MAX_SCALE)
        return row.map { it?.times(scale) }
    }
}
