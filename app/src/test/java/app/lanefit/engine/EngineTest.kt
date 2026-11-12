package app.lanefit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EngineTest {
    private val stepMs = 50L

    /** One sine period of steering: the textbook single lane change. Positive amplitude = left. */
    private fun LaneEstimator.drive(
        fromMs: Long, seconds: Double, speedKmh: Double = 100.0, signalAt: (Long) -> Side? = { null },
        steer: (Double) -> Double,
    ): List<LaneChange> {
        val out = mutableListOf<LaneChange>()
        var t = fromMs
        while (t <= fromMs + (seconds * 1000).toLong()) {
            onSample(t, speedKmh, steer((t - fromMs) / 1000.0), signalAt(t))?.let(out::add)
            t += stepMs
        }
        return out
    }

    private fun laneChangeSteer(amplitudeDeg: Double, periodS: Double) = { s: Double ->
        if (s in 1.0..(1.0 + periodS)) amplitudeDeg * sin(2 * PI * (s - 1.0) / periodS) else 0.0
    }

    @Test fun `signalled lane change left moves one lane and keeps confidence high`() {
        val e = LaneEstimator()
        e.onAnchor(laneCount = 4, lane = 4, reliability = 0.95)
        // Signal on from t=0, steering starts at t=1 s.
        val changes = e.drive(0, 9.0, signalAt = { if (it < 5000) Side.LEFT else null }, steer = laneChangeSteer(2.65, 5.0))
        assertEquals(1, changes.size)
        assertEquals(Side.LEFT, changes[0].side)
        assertEquals(1, changes[0].lanes)
        assertEquals(3, e.estimate.lane)
        assertTrue("lead ${changes[0].signalLeadMs}", (changes[0].signalLeadMs ?: -1) in 800..1300)
        assertTrue(e.estimate.confidence > 0.85)
    }

    @Test fun `unsignalled change right is tracked with lower confidence`() {
        val e = LaneEstimator()
        e.onAnchor(4, 2, 0.95)
        val changes = e.drive(0, 9.0, steer = laneChangeSteer(-2.65, 5.0))
        assertEquals(listOf(Side.RIGHT), changes.map { it.side })
        assertNull(changes[0].signalLeadMs)
        assertEquals(3, e.estimate.lane)
        assertTrue(e.estimate.confidence < 0.8)
    }

    @Test fun `steering reported in whole degrees does not read as a fast turn`() {
        val e = LaneEstimator()
        e.onAnchor(4, 2, 0.95)
        val smooth = laneChangeSteer(2.65, 5.0) // true peak rate 3.3 deg/s
        val changes = e.drive(0, 9.0) { s -> Math.round(smooth(s)).toDouble() }
        assertEquals(listOf(Side.LEFT), changes.map { it.side })
        assertTrue("rate ${changes[0].peakSteeringRateDegS}", changes[0].peakSteeringRateDegS < 10.0)
    }

    @Test fun `entering a steady curve is not a lane change`() {
        val e = LaneEstimator()
        e.onAnchor(4, 2, 0.95)
        val changes = e.drive(0, 30.0) { s -> if (s > 1.0) 6.0 else 0.0 }
        assertTrue(changes.isEmpty())
        assertEquals(2, e.estimate.lane)
    }

    @Test fun `lane change inside a steady curve is still counted`() {
        val e = LaneEstimator()
        e.onAnchor(4, 2, 0.95)
        e.drive(0, 30.0) { s -> if (s > 1.0) 6.0 else 0.0 }
        val wiggle = laneChangeSteer(2.65, 5.0)
        val changes = e.drive(30_050, 9.0) { s -> 6.0 + wiggle(s) }
        assertEquals(listOf(Side.LEFT), changes.map { it.side })
        assertEquals(1, e.estimate.lane)
    }

    @Test fun `risk follows the 100 m signal rule`() {
        // 100 km/h * 3.6 s = 100 m: the legal distance, so nothing to coach.
        val clean = scoreLaneChange(RiskInput(3600, 100.0, 3.3, hardBrakeAfter = false, recentChanges = 0))
        assertEquals(RiskLevel.LOW, clean.level)
        assertEquals(100.0, clean.leadDistanceM ?: -1.0, 0.5)
        assertEquals("부드럽게 바꿨어요", clean.coaching)

        val late = scoreLaneChange(RiskInput(1200, 100.0, 3.3, false, 0))
        assertTrue(late.score in 15..25)
        assertEquals("신호를 조금 더 일찍 켜보세요", late.coaching)

        val none = scoreLaneChange(RiskInput(null, 100.0, 3.3, false, 0))
        assertTrue(none.score >= 40)
        assertEquals("방향지시등을 켜고 바꿔보세요", none.coaching)

        val worst = scoreLaneChange(RiskInput(null, 100.0, 40.0, true, 3))
        assertEquals(RiskLevel.HIGH, worst.level)
        assertTrue(worst.score <= 100)
    }

    private fun input(
        speeds: List<Double?>, lane: Int = 3, confidence: Double = 0.9, mode: Mode = Mode.FLOW,
        signal: Side = Side.LEFT, changes: Int = 0, since: Double? = null, route: Boolean = false,
    ) = AdviceInput(LaneEstimate(lane, 4, confidence), signal, speeds, 10.0, mode, changes, since, route)

    @Test fun `stay is the answer unless the gain clears the mode threshold`() {
        // 10 km at 80 km/h = 450 s; at 87.8 km/h = 410 s.
        val stay = advise(input(listOf(90.0, 87.8, 80.0, 70.0))) as Advice.Stay
        assertTrue(stay.gainS in 39..41)
        assertEquals("옮겨도 ${stay.gainS}초 차이예요, 유지하세요", stay.text)

        // 10 km at 60 km/h = 600 s; at 80 km/h = 450 s: 150 s clears Flow (120) but not Calm (180).
        val speeds = listOf(90.0, 80.0, 60.0, 70.0)
        assertEquals("왼쪽이 2분 빨라요", (advise(input(speeds)) as Advice.Change).text)
        assertTrue(advise(input(speeds, mode = Mode.CALM)) is Advice.Stay)

        val slower = advise(input(speeds, signal = Side.RIGHT, lane = 2)) as Advice.Stay
        assertEquals("오른쪽이 지금 더 느려요", slower.text)
    }

    @Test fun `guards and silence`() {
        val speeds = listOf(90.0, 80.0, 60.0, 70.0)
        assertTrue(advise(input(speeds, since = 20.0)) is Advice.Stay)
        assertTrue(advise(input(speeds, changes = 2)) is Advice.Stay)
        assertEquals(Advice.Silent, advise(input(speeds, confidence = 0.5)))
        assertEquals(Advice.Silent, advise(input(speeds, route = true)))
        assertEquals(Advice.Silent, advise(input(speeds, lane = 1)))
        assertEquals(Advice.Silent, advise(input(speeds, lane = 4, signal = Side.RIGHT)))
        assertTrue(advise(input(speeds, lane = 4)) is Advice.Stay)
        // Four speeds but a three-lane road: lane 4 does not exist.
        val threeLanes = AdviceInput(LaneEstimate(3, 3, 0.9), Side.RIGHT, speeds, 10.0, Mode.FLOW, 0, null, false)
        assertEquals(Advice.Silent, advise(threeLanes))
    }

    @Test fun `mode follows drive mode, passenger and risk`() {
        assertEquals(Mode.RUSH, modeFor(DriveMode.SPORT, passenger = false, calmOnPassenger = true, recentRisk = null))
        assertEquals(Mode.CALM, modeFor(DriveMode.SPORT, passenger = true, calmOnPassenger = true, recentRisk = null))
        assertEquals(Mode.RUSH, modeFor(DriveMode.SPORT, passenger = true, calmOnPassenger = false, recentRisk = null))
        assertEquals(Mode.FLOW, modeFor(DriveMode.SPORT, false, true, RiskLevel.HIGH))
        assertEquals(Mode.CALM, modeFor(DriveMode.ECO, false, true, RiskLevel.HIGH))
    }

    @Test fun `other roads are scored against the 30 m rule`() {
        // 60 km/h * 1.8 s = 30 m.
        val street = scoreLaneChange(RiskInput(1800, 60.0, 3.3, false, 0, highway = false))
        assertEquals(30.0, street.requiredM, 0.0)
        assertEquals("부드럽게 바꿨어요", street.coaching)
        assertEquals(100.0, scoreLaneChange(RiskInput(1800, 60.0, 3.3, false, 0)).requiredM, 0.0)
    }

    @Test fun `the 10 km cap counts change answers and says so`() {
        val speeds = listOf(90.0, 80.0, 60.0, 70.0)
        assertEquals("변경은 충분했어요, 이대로 가세요", (advise(input(speeds, changes = 2)) as Advice.Stay).text)
        assertEquals("방금 바꿨어요, 조금 더 유지하세요", (advise(input(speeds, since = 20.0)) as Advice.Stay).text)
        // A lane without a usable speed (bus lane) is never an answer.
        assertEquals(Advice.Silent, advise(input(listOf(90.0, null, 60.0, 70.0))))
    }

    // Two detector sections on one north-south road, opposite ways, like the real asset.
    private val profileLines = listOf(
        "S|N|북행|37.2400|127.1050|37.2670|127.1040|5|1",
        "W|32|,80,60,60,50",
        "E|32|,100,100,100,100",
        "W|33|,80,x",
        "S|S|남행|37.2670|127.1040|37.2400|127.1050|5|1",
        "W|32|,90,90,90,90",
        "garbage",
    )

    @Test fun `the lane profile is matched by position and course, then scaled to today`() {
        val profile = RoadProfile.parse(profileLines)
        assertEquals(listOf("N", "S"), profile.sections.map { it.id })
        assertEquals("N", profile.match(37.2500, 127.1046, course = 358.0)?.id)
        assertEquals("S", profile.match(37.2500, 127.1046, course = 178.0)?.id)
        assertNull(profile.match(37.2500, 127.1046, course = 90.0))      // crossing over it
        assertNull(profile.match(37.2500, 127.1100, course = 358.0))     // 480 m to the side
        assertNull(profile.match(37.3000, 127.1040, course = 358.0))     // past the end

        val m = RoadMatcher(profile)
        m.onLocation(1_000, 37.2500, 127.1046, 358.0)
        assertEquals(5, m.section(1_000)?.laneCount)
        // Lane 3 usually does 60; today it does 30: every lane is halved, and the bus lane stays empty.
        m.onSpeed(1_000, 30.0)
        assertEquals(listOf(null, 40.0, 30.0, 30.0, 25.0), m.laneSpeeds(1_000, lane = 3, slot = 32, weekend = false))
        assertEquals(listOf(null, 100.0, 100.0, 100.0, 100.0), m.laneSpeeds(1_000, lane = null, slot = 32, weekend = true))
        // The scale is clamped like the backtest's: 5 km/h against 60 is 0.4, not 0.083.
        val jam = RoadMatcher(profile).apply { onLocation(0, 37.2500, 127.1046, 358.0); onSpeed(0, 5.0) }
        assertEquals(24.0, jam.laneSpeeds(0, 3, 32, false)[2] ?: -1.0, 1e-9)
        // No data for this quarter hour, a short row, or an old position: an empty list, which keeps the app silent.
        assertTrue(m.laneSpeeds(1_000, 3, slot = 40, weekend = false).isEmpty())
        assertTrue(m.laneSpeeds(1_000, 3, slot = 33, weekend = false).isEmpty())
        assertTrue(m.laneSpeeds(20_000, 3, slot = 32, weekend = false).isEmpty())
        assertNull(m.section(20_000))
    }

    @Test fun `the real lane profile parses into four five-lane sections`() {
        val file = listOf("src/main/assets/lane_profile.txt", "app/src/main/assets/lane_profile.txt")
            .map { File(it) }.first { it.exists() }
        val profile = RoadProfile.parse(file.readLines())
        assertEquals(4, profile.sections.size)
        for (s in profile.sections) {
            assertEquals(5, s.laneCount)
            val rush = requireNotNull(s.speeds(slot = 32, weekend = false)) { s.id } // 08:00 on a weekday
            assertNull("bus lane of ${s.id}", rush[0])
            assertTrue(rush.drop(1).all { it != null && it in 5.0..150.0 })
        }
    }

    @Test fun `speed and course come from position fixes`() {
        val t = FixTracker()
        assertEquals(FixTracker.Fix(90.0, null), t.onFix(37.0, 127.0, 0, headingDeg = 90.0))
        // 0.00025 deg of latitude north in one second = 27.83 m/s = 100.2 km/h.
        val fix = t.onFix(37.00025, 127.0, 1_000, headingDeg = 90.0)
        assertEquals(100.2, fix.speedKmh ?: -1.0, 0.1)
        assertTrue(abs(courseGapDeg(fix.courseDeg ?: 180.0, 0.0)) < 0.1)
        assertNull(t.onFix(38.0, 127.0, 2_000, null).speedKmh)   // a 111 km jump is not a speed
        assertNull(t.onFix(38.0, 127.0, 60_000, null).speedKmh)  // nor is a fix after a long gap
        assertEquals(350.0, courseGapDeg(355.0, 5.0) + 340.0, 1e-9)
    }

    @Test fun `the route marks its own signals and the moment of a ramp merge`() {
        assertEquals(Side.RIGHT, sideOfTurn("TURN_EXIT_IC_R"))
        assertEquals(Side.LEFT, sideOfTurn("TURN_KEEP_L"))
        assertNull(sideOfTurn("TURN_STRAIGHT"))
        assertNull(sideOfTurn("TURN_ENTER_IC_C"))

        val r = RouteWatch()
        assertTrue(!r.onNext("TURN_ENTER_IC_R", 2_500.0))
        assertNull(r.signal)                                   // too far ahead to explain a signal
        assertTrue(!r.onNext("TURN_ENTER_IC_R", 200.0))
        assertEquals(Side.RIGHT, r.signal)
        assertNull(r.motorway)
        assertTrue(r.onNext("TURN_EXIT_IC_R", 12_000.0))       // the entry is behind: merged
        assertEquals(true, r.motorway)
        assertNull(r.signal)
        assertTrue(!r.onNext("TURN_EXIT_IC_R", 150.0))
        assertTrue(!r.onNext("TURN_ARRIVE", 800.0))            // the exit is behind
        assertEquals(false, r.motorway)
        r.onNext("TURN_LEFT", 100.0)
        assertTrue(!r.onNext(null, 0.0))                       // route cancelled
        assertNull(r.signal)
        assertNull(r.motorway)                                 // no route: the road class falls back to speed
        assertTrue(!r.onNext("TURN_LEFT", Double.NaN))
    }

    @Test fun `spoken questions and lane statements are told apart`() {
        assertEquals(Ask.Change(null), parseAsk("지금 바꿀까?"))
        assertEquals(Ask.Change(Side.LEFT), parseAsk("왼쪽으로 옮겨도 돼"))
        assertEquals(Ask.Change(Side.RIGHT), parseAsk("오른쪽 갈까"))
        assertEquals(Ask.Lane(3), parseAsk("지금 3차로야"))
        assertEquals(Ask.Lane(2), parseAsk("이 차선이에요"))
        assertEquals(Ask.Lane(4), parseAsk("나 사 차로에 있어"))
        assertNull(parseAsk("이 차선 왜 이렇게 막혀"))           // "this lane", not "lane 2"
        assertNull(parseAsk("오늘 날씨 어때"))
        assertNull(parseAsk("집에 갈까"))                        // everyday talk on an open microphone
        assertNull(parseAsk("5차로에 있어야 해"))
    }
}
