package app.lanefit

import app.lanefit.engine.DriveMode
import app.lanefit.engine.Mode
import app.lanefit.engine.RiskLevel
import kotlin.math.floor
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
}
