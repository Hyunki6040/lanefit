package app.lanefit

import app.lanefit.engine.DriveMode
import app.lanefit.engine.Mode
import app.lanefit.engine.RiskLevel
import app.lanefit.engine.Side
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSessionTest {
    @Test fun `demo drive says stay first, recommends once, coaches, then calms for the passenger`() {
        val spoken = mutableListOf<String>()
        val session = DriveSession(spoken::add)
        val cards = mutableListOf<String>()
        var risk: Card.Risk? = null

        for (e in demoScenario()) {
            session.apply(e)
            val card = session.state.card
            if (card is Card.Risk) risk = card
            val name = card::class.simpleName.orEmpty()
            if (cards.lastOrNull() != name) cards += name
        }

        assertEquals(
            listOf(
                "옮겨도 40초 차이예요, 유지하세요",
                "왼쪽이 2분 빨라요",
                "신호를 조금 더 일찍 켜보세요",
                "동승자가 탔어요, Calm 모드로 바꿀게요",
            ),
            spoken,
        )
        assertEquals(listOf("Idle", "Stay", "Idle", "Change", "Idle", "Risk", "PassengerCalm", "Idle"), cards)

        val result = requireNotNull(risk).result
        assertEquals(RiskLevel.LOW, result.level)
        assertTrue("lead ${result.leadDistanceM}", (result.leadDistanceM ?: 0.0) in 55.0..66.0)

        val end = session.state
        assertEquals(3, end.estimate.lane)
        assertEquals(Mode.CALM, end.mode)
        assertTrue(end.parked)
        assertEquals(1, end.report.stayShown)
        assertEquals(1, end.report.stayKept)
        assertEquals(1, end.report.laneChanges)
        assertEquals(150, end.report.savedS)
        assertTrue(end.report.distanceKm in 1.0..1.4)
    }

    @Test fun `a gap between samples is not driving`() {
        val session = DriveSession()
        fun at(tMs: Long) = VehicleSample(tMs, 100.0, 0.0, null, brakeOn = false, DriveMode.NORMAL, passenger = false)
        session.onSample(at(0))
        session.onSample(at(600_000)) // ten minutes behind another app
        assertEquals(0.0, session.state.report.distanceKm, 1e-9)
    }

    @Test fun `speed that arrives in one-second steps is not read as hard braking`() {
        val session = DriveSession()
        var risk: Card.Risk? = null
        for (e in demoScenario()) {
            val t = e.tMs / 1000.0
            // 6 km/h lost per second (1.7 m/s2, gentle), delivered as one step per second like a 1 Hz speed property.
            val stepped = if (e is DemoEvent.Sample && t in 41.0..44.5) {
                DemoEvent.Sample(e.sample.copy(speedKmh = 60.0 - 6.0 * floor(t - 41.0), brakeOn = true))
            } else {
                e
            }
            session.apply(stepped)
            (session.state.card as? Card.Risk)?.let { risk = it }
        }
        assertEquals("신호를 조금 더 일찍 켜보세요", requireNotNull(risk).result.coaching)
    }

    @Test fun `bad feed values are dropped, not thrown`() {
        val session = DriveSession()
        session.onAnchor(laneCount = 3, lane = 5, reliability = 0.9)
        session.onAnchor(laneCount = 3, lane = 2, reliability = Double.NaN)
        assertEquals(null, session.state.estimate.lane)
        session.onLaneSpeeds(listOf(Double.NaN, 80.0), horizonKm = 10.0)
    }

    @Test fun `pinned mode beats the passenger switch`() {
        val spoken = mutableListOf<String>()
        val session = DriveSession(spoken::add)
        session.pinMode(Mode.RUSH)
        demoScenario().forEach(session::apply)
        assertEquals(Mode.RUSH, session.state.mode)
        assertTrue(spoken.none { "Calm" in it })
    }

    private fun driving(tMs: Long, speedKmh: Double = 80.0, signal: Side? = null) =
        VehicleSample(tMs, speedKmh, 0.0, signal, brakeOn = false, DriveMode.NORMAL, passenger = false)

    private fun DriveSession.cruise(fromMs: Long, toMs: Long, speedKmh: Double = 80.0, signal: Side? = null) {
        for (t in fromMs..toMs step 50) onSample(driving(t, speedKmh, signal))
    }

    @Test fun `a signal switched on while standing still is answered once the car moves`() {
        val spoken = mutableListOf<String>()
        val session = DriveSession(spoken::add)
        session.onAnchor(4, 4, 0.9)
        session.onLaneSpeeds(listOf(100.0, 92.0, 80.0, 60.0), 10.0)
        session.cruise(0, 2_000, speedKmh = 0.0, signal = Side.LEFT)
        assertTrue(spoken.isEmpty())
        session.cruise(2_050, 4_000, speedKmh = 40.0, signal = Side.LEFT)
        assertEquals(listOf("왼쪽이 2분 빨라요"), spoken)
    }

    @Test fun `the 10 km cap counts change answers, not lane changes`() {
        val spoken = mutableListOf<String>()
        val session = DriveSession(spoken::add)
        session.onAnchor(4, 4, 0.9)
        session.onLaneSpeeds(listOf(100.0, 92.0, 80.0, 60.0), 10.0)
        var t = 0L
        repeat(3) {
            session.cruise(t, t + 1_000)
            session.cruise(t + 1_050, t + 3_000, signal = Side.LEFT)
            session.cruise(t + 3_050, t + 29_950) // past the 20 s follow window, never changing lane
            t += 30_000
        }
        assertEquals(listOf("왼쪽이 2분 빨라요", "왼쪽이 2분 빨라요", "변경은 충분했어요, 이대로 가세요"), spoken)
        assertEquals(0, session.state.report.laneChanges)
    }

    @Test fun `a spoken question is answered for the better side, and says why when it cannot`() {
        val spoken = mutableListOf<String>()
        val session = DriveSession(spoken::add)
        session.cruise(0, 500)
        session.ask(null)
        session.cruise(550, 600)
        assertEquals(listOf("아직 내 차선을 확인하지 못했어요"), spoken)

        session.onAnchor(4, 3, 0.9)
        session.ask(null)
        session.cruise(650, 700)
        assertEquals("지금은 비교할 차로 자료가 없어요", spoken.last())

        session.onLaneSpeeds(listOf(100.0, 92.0, 60.0, 50.0), 10.0) // left 209 s faster, right slower
        session.routeSignal = Side.LEFT                              // a spoken question is still a question
        session.ask(null)
        session.cruise(750, 800)
        assertEquals("왼쪽이 3분 빨라요", spoken.last())

        session.onLaneSpeeds(listOf(100.0, 50.0, 60.0, 50.0), 10.0)
        session.ask(null)
        session.cruise(850, 900)
        assertEquals("지금 차선이 가장 빨라요, 유지하세요", spoken.last())
    }

    @Test fun `a ramp merge becomes the rightmost lane once the road is known`() {
        val session = DriveSession()
        session.cruise(0, 500)
        session.onMerge()
        assertEquals(null, session.state.estimate.lane)
        session.onRoad(laneCount = 5, name = "경부선")
        assertEquals(5, session.state.estimate.lane)
        assertEquals("경부선", session.state.road)
        assertTrue(session.state.estimate.confidence >= 0.6)
        // The road loses a lane: the number can no longer be trusted, so the app goes quiet until the next anchor.
        session.onRoad(laneCount = 4, name = "경부선")
        assertEquals(0.0, session.state.estimate.confidence, 0.0)
        session.onRoad(null, null)
        assertEquals(null, session.state.road)
    }

    private fun cruise(session: DriveSession, fromMs: Long, toMs: Long, steer: (Double) -> Double = { 0.0 }) {
        for (t in fromMs..toMs step 50) {
            session.onSample(VehicleSample(t, 100.0, steer(t / 1000.0), null, brakeOn = false, DriveMode.NORMAL, passenger = false))
        }
    }

    @Test fun `a lane change before the road is known moves the merge anchor`() {
        val session = DriveSession()
        session.onMerge()
        cruise(session, 0, 9_000) { s -> if (s in 1.0..6.0) 2.65 * sin(2 * PI * (s - 1.0) / 5.0) else 0.0 } // one lane left
        session.onRoad(laneCount = 5, name = "경부선")
        assertEquals(4, session.state.estimate.lane)
    }

    @Test fun `a gap with nobody watching, or a trip off the known roads, drops the lane to a guess`() {
        val session = DriveSession()
        session.onAnchor(laneCount = 5, lane = 3, reliability = 0.9)
        cruise(session, 0, 2_000)
        assertTrue(session.state.estimate.confidence >= 0.6)
        cruise(session, 60_000, 61_000) // the app was behind another one for a minute
        assertEquals(0.0, session.state.estimate.confidence, 0.0)

        session.onAnchor(laneCount = 5, lane = 3, reliability = 0.9)
        session.onRoad(laneCount = 5, name = "경부선")
        session.onRoad(null, null)
        cruise(session, 61_050, 61_050 + 250_000) // about 7 km at 100 km/h off the profiled road
        session.onRoad(laneCount = 5, name = "경부선")
        assertEquals(0.0, session.state.estimate.confidence, 0.0)
    }
}
