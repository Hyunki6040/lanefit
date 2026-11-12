package app.lanefit

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.lanefit.engine.Ask
import app.lanefit.engine.FixTracker
import app.lanefit.engine.RoadMatcher
import app.lanefit.engine.RoadProfile
import app.lanefit.engine.RouteWatch
import app.lanefit.engine.parseAsk
import app.lanefit.ui.LaneFitApp
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the numbers on screen come from. Shown on screen whenever it is not the car and the road data alone. */
enum class Source(val label: String?) {
    CAR(null),
    SAMPLE_ROAD("예시 도로 데이터"),
    SIMULATION("시뮬레이션"),
}

class MainActivity : ComponentActivity() {
    private var ui by mutableStateOf(UiState())
    private var source by mutableStateOf(Source.CAR)
    private var ttsStatus by mutableStateOf("준비 중")
    private var voiceOn by mutableStateOf(false)

    private lateinit var signals: VehicleSignals
    private lateinit var navi: NaviFeed
    private lateinit var location: LocationFeed
    private lateinit var voice: VoiceAsk
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var session = DriveSession(::say)
    private var simulation: Job? = null

    private val matcher by lazy { RoadMatcher(loadProfile()) }
    private val fixes = FixTracker()
    private val route = RouteWatch()
    private var gpsKmh: Double? = null
    private var gpsMs = 0L
    private var roadMs = 0L
    private var debugLane: Int? = null

    private val askCar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        signals.startCar()
        location.start(granted[FUSED_LOCATION] == true)
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setVoice(true) else voice.stop("권한 없음")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signals = VehicleSignals(this)
        navi = NaviFeed(this, onNext = { turn, distanceM -> if (route.onNext(turn, distanceM)) session.onMerge() }, onRouteEnd = route::clear)
        location = LocationFeed(this) { lat, lon, heading, tMs ->
            val fix = fixes.onFix(lat, lon, tMs, heading)
            fix.speedKmh?.let { gpsKmh = it; gpsMs = tMs }
            matcher.onLocation(tMs, lat, lon, fix.courseDeg)
        }
        voice = VoiceAsk(this, ::heard)
        signals.start()
        navi.start()
        askCar.launch(arrayOf("android.car.permission.CAR_SPEED", FUSED_LOCATION))
        startTts()
        voiceOn = getPreferences(MODE_PRIVATE).getBoolean(PREF_VOICE, false) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // ponytail: lanes are tracked only while this screen is started. Move the loop to a foreground
        // service when the app has to keep counting behind the navigation app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    // Speed from position fixes stands in only while the car reports none of its own.
                    val sample = signals.sample(now, gpsKmh.takeIf { now - gpsMs <= GPS_STALE_MS })
                    if (source == Source.SIMULATION) {
                        // The simulation ends on its own parked (touch) screen: leave it the moment the real car is not parked.
                        if (signals.inPark == false || (sample?.speedKmh ?: 0.0) >= 1.0) use(Source.CAR)
                    } else {
                        if (sample != null) {
                            if (source == Source.CAR && now - roadMs >= ROAD_MS) feedRoad(now, sample.speedKmh)
                            session.onSample(sample)
                            // The sample road has no anchors of its own: after a gap its start lane is given again.
                            if (source == Source.SAMPLE_ROAD && session.state.estimate.confidence == 0.0) {
                                session.onAnchor(SAMPLE_ROAD_SPEEDS.size, SAMPLE_ROAD_SPEEDS.size, 0.9)
                            }
                            ui = session.state
                        } else {
                            // No speed or steering (not yet, or lost): only a confirmed P may show the touch screen.
                            ui = ui.copy(parked = signals.inPark ?: false)
                        }
                    }
                    delay(SAMPLE_MS)
                }
            }
        }

        setContent {
            LaneFitApp(
                ui = ui,
                source = source,
                signals = VehicleSignals.Signal.entries.map { it.ko to (signals.status[it] ?: "응답 없음") } + listOf(
                    "음성 안내 (Pleos TTS)" to ttsStatus,
                    "경로 (Pleos NaviHelper)" to navi.status,
                    "위치 (Pleos FusedLocation)" to location.status,
                    "음성 질문 (Pleos STT)" to voice.status,
                ),
                voiceOn = voiceOn,
                onPinMode = { mode -> change { pinMode(mode) } },
                onCalmOnPassenger = { on -> change { setCalmOnPassenger(on) } },
                onVoice = { on -> if (on) askMic.launch(Manifest.permission.RECORD_AUDIO) else setVoice(false) },
                onSource = ::use,
            )
        }
        // The activity is exported: any app can send these extras, so only debuggable builds honour them.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra(EXTRA_DEMO, false)) use(Source.SIMULATION)
        if (debuggable) debugLane = intent.getIntExtra(EXTRA_LANE, 0).takeIf { it > 0 }
    }

    /** Once a second: where the car is, how fast each lane there usually is at this hour, and what the route needs. */
    private fun feedRoad(now: Long, speedKmh: Double) {
        roadMs = now
        matcher.onSpeed(now, speedKmh)
        val section = matcher.section(now)
        session.onRoad(section?.laneCount, section?.name)
        // Every road in the lane profile is a motorway; elsewhere the route says which side of a ramp the car is on.
        session.highway = if (section != null) true else route.motorway
        session.routeSignal = route.signal
        // Emulator runs have no ramp to merge from: a debug build may be told the start lane instead.
        val startLane = debugLane
        if (section != null && startLane != null && startLane <= section.laneCount) {
            session.onAnchor(section.laneCount, startLane, VOICE_RELIABILITY)
            debugLane = null
        }
        val clock = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")) // the profile's quarter hours are Korean time
        val slot = (clock[Calendar.HOUR_OF_DAY] * 60 + clock[Calendar.MINUTE]) / 15
        val weekend = clock[Calendar.DAY_OF_WEEK].let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
        session.onLaneSpeeds(matcher.laneSpeeds(now, session.state.estimate.lane, slot, weekend), HORIZON_KM)
    }

    private fun loadProfile(): RoadProfile = try {
        RoadProfile.parse(assets.open(PROFILE_ASSET).bufferedReader().use { it.readLines() })
    } catch (e: IOException) {
        Log.w(TAG, "lane profile missing", e)
        RoadProfile(emptyList())
    }

    /** A recognised sentence: a question is answered like a turn signal, a statement fixes the lane. */
    private fun heard(text: String) {
        when (val ask = parseAsk(text)) {
            null -> Unit
            is Ask.Change -> session.ask(ask.side)
            is Ask.Lane -> {
                val lanes = matcher.section(SystemClock.elapsedRealtime())?.laneCount
                    ?: session.state.estimate.laneCount.takeIf { it > 0 }
                if (lanes != null && ask.lane <= lanes) {
                    change { onAnchor(lanes, ask.lane, VOICE_RELIABILITY) }
                    say("${ask.lane}차로로 맞췄어요")
                } else {
                    say(if (lanes == null) "이 도로의 차로 수를 아직 몰라요" else "이 도로는 ${lanes}차로까지예요")
                }
            }
        }
    }

    private fun setVoice(on: Boolean) {
        voiceOn = on
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_VOICE, on).apply()
        if (on) voice.start() else voice.stop()
    }

    // The microphone is open only while the app is on screen.
    override fun onStart() {
        super.onStart()
        if (voiceOn) voice.start()
    }

    override fun onStop() {
        voice.stop()
        super.onStop()
    }

    private fun change(block: DriveSession.() -> Unit) {
        session.block()
        ui = session.state
    }

    /** Every switch starts a fresh session so simulated and measured values never mix in one report. */
    private fun use(next: Source) {
        if (next == source) return // tapping the selected tab must not wipe the report
        simulation?.cancel()
        val previous = session.state
        session = DriveSession(::say).apply {
            pinMode(previous.pinnedMode)
            setCalmOnPassenger(previous.calmOnPassenger)
        }
        source = next
        when (next) {
            Source.CAR -> roadMs = 0L
            // Made-up lane count, start lane and speeds, for trying the app away from the roads in the lane profile.
            Source.SAMPLE_ROAD -> {
                session.highway = true
                session.onAnchor(laneCount = SAMPLE_ROAD_SPEEDS.size, lane = SAMPLE_ROAD_SPEEDS.size, reliability = 0.9)
                session.onLaneSpeeds(SAMPLE_ROAD_SPEEDS, horizonKm = HORIZON_KM)
            }
            Source.SIMULATION -> simulation = lifecycleScope.launch {
                val startMs = SystemClock.elapsedRealtime()
                for (event in demoScenario()) {
                    delay(startMs + event.tMs - SystemClock.elapsedRealtime())
                    session.apply(event)
                    ui = session.state
                }
            }
        }
        ui = session.state
    }

    private fun startTts() {
        try {
            val t = TextToSpeech(applicationContext, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { t.setLocale(Locale.KOREA) }
                    ttsReady = true
                    ttsStatus = "사용 가능"
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS init failed: $errorType")
                    ttsStatus = "사용 불가 (${errorType.name})"
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "TTS unavailable", e)
            ttsStatus = "사용 불가 (${e.javaClass.simpleName})"
        }
    }

    /** Speech is a convenience on top of the card: a failure is logged and shown in the check list, never thrown. */
    private fun say(text: String) {
        if (!ttsReady) return
        try {
            voice.pause(SPEAK_PAUSE_MS) // or the app would hear its own sentence as the next question
            tts?.speak(text)
        } catch (e: RuntimeException) {
            Log.w(TAG, "speak failed", e)
            ttsStatus = "오류 (${e.javaClass.simpleName})"
        }
    }

    override fun onDestroy() {
        signals.stop()
        navi.stop()
        location.stop()
        voice.release()
        runCatching { tts?.stop(); tts?.release() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LaneFit"
        const val SAMPLE_MS = 50L
        const val ROAD_MS = 1_000L
        const val GPS_STALE_MS = 3_000L
        const val SPEAK_PAUSE_MS = 4_000L
        const val EXTRA_DEMO = "demo"
        const val EXTRA_LANE = "lane"
        const val PREF_VOICE = "voice"
        const val PROFILE_ASSET = "lane_profile.txt"
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"

        // Same horizon as the backtest (scripts/lane_profile.py): "how much faster over the next 10 km".
        const val HORIZON_KM = 10.0

        // The driver said which lane this is; a misheard number is still possible.
        const val VOICE_RELIABILITY = 0.9

        // Sample speeds, lane 1 first. From lane 4 the next lane is 150 s faster over 10 km: Change in Flow, Stay in Calm.
        val SAMPLE_ROAD_SPEEDS = listOf(100.0, 92.0, 80.0, 60.0)
    }
}
