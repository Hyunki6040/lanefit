package app.lanefit

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.pm.ApplicationInfo
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
import app.lanefit.ui.LaneFitApp
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the numbers on screen come from. Shown on screen whenever it is not the car alone. */
enum class Source(val label: String?) {
    CAR(null),
    SAMPLE_ROAD("예시 도로 데이터"),
    SIMULATION("시뮬레이션"),
}

class MainActivity : ComponentActivity() {
    private var ui by mutableStateOf(UiState())
    private var source by mutableStateOf(Source.CAR)
    private var ttsStatus by mutableStateOf("준비 중")

    private lateinit var signals: VehicleSignals
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var session = DriveSession(::say)
    private var simulation: Job? = null

    private val askSpeed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { signals.startCar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signals = VehicleSignals(this)
        signals.start()
        askSpeed.launch("android.car.permission.CAR_SPEED")
        startTts()

        // ponytail: lanes are tracked only while this screen is started. Move the loop to a foreground
        // service when the app has to keep counting behind the navigation app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val sample = signals.sample(SystemClock.elapsedRealtime())
                    if (source == Source.SIMULATION) {
                        // The simulation ends on its own parked (touch) screen: leave it the moment the real car is not parked.
                        if (signals.inPark == false || (sample?.speedKmh ?: 0.0) >= 1.0) use(Source.CAR)
                    } else {
                        if (sample != null) {
                            session.onSample(sample)
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
                signals = VehicleSignals.Signal.entries.map { it.ko to (signals.status[it] ?: "응답 없음") } +
                    ("음성 안내 (Pleos TTS)" to ttsStatus),
                onPinMode = { mode -> change { pinMode(mode) } },
                onCalmOnPassenger = { on -> change { setCalmOnPassenger(on) } },
                onSource = ::use,
            )
        }
        // The activity is exported: any app can send this extra, so only debuggable builds honour it.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra(EXTRA_DEMO, false)) use(Source.SIMULATION)
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
            Source.CAR -> Unit
            // No lane-level traffic feed is wired in yet, so the lane count, start lane and speeds are sample values.
            Source.SAMPLE_ROAD -> {
                session.onAnchor(laneCount = SAMPLE_ROAD_SPEEDS.size, lane = SAMPLE_ROAD_SPEEDS.size, reliability = 0.9)
                session.onLaneSpeeds(SAMPLE_ROAD_SPEEDS, horizonKm = 10.0)
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
            tts?.speak(text)
        } catch (e: RuntimeException) {
            Log.w(TAG, "speak failed", e)
            ttsStatus = "오류 (${e.javaClass.simpleName})"
        }
    }

    override fun onDestroy() {
        signals.stop()
        runCatching { tts?.stop(); tts?.release() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LaneFit"
        const val SAMPLE_MS = 50L
        const val EXTRA_DEMO = "demo"

        // Sample speeds, lane 1 first. From lane 4 the next lane is 150 s faster over 10 km: Change in Flow, Stay in Calm.
        val SAMPLE_ROAD_SPEEDS = listOf(100.0, 92.0, 80.0, 60.0)
    }
}
