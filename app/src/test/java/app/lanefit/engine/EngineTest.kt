package app.lanefit.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
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
        speeds: List<Double>, lane: Int = 3, confidence: Double = 0.9, mode: Mode = Mode.FLOW,
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
        assertNotNull(advise(input(speeds, lane = 4)))
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
}
