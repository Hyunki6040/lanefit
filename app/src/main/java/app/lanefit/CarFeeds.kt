package app.lanefit

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

// Three more read-only inputs next to VehicleSignals. Every callback is moved to the main thread, and a
// failure only changes the status line on the parked screen: the turn-signal answer never depends on them.
private const val TAG = "LaneFit"
private const val CONNECT_WAIT_MS = 1_500L
private const val TBT_POLL_MS = 2_000L
private const val LISTEN_GAP_MS = 500L
private const val LISTEN_RETRY_MS = 5_000L

private fun unavailable(e: Throwable) = "사용 불가 (${e.javaClass.simpleName})"

/** The next manoeuvre of the car's navigation route (Pleos NaviHelper). */
class NaviFeed(
    context: Context,
    private val onNext: (turn: String?, distanceM: Double) -> Unit,
    private val onRouteEnd: () -> Unit,
) {
    var status by mutableStateOf("준비 중")
        private set

    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var navi: NaviHelper? = null

    private val listener = object : NaviHelperEventListener {
        // ponytail: the first entry is taken as the next manoeuvre; the SDK does not document the order.
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val next = tbtInfoList.firstOrNull()
            main.post {
                status = if (next == null) "경로 없음" else "안내 중 · ${next.description}"
                onNext(next?.type?.name, next?.distance ?: 0.0)
            }
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            if (status != NavigationStatus.ROUTE_DRIVE) end()
        }

        override fun onRouteCancelled() = end()
    }

    private fun end() {
        main.post {
            status = "경로 없음"
            onRouteEnd()
        }
    }

    // A listener is not told about a route that was already running, and the distance has to keep counting down.
    private val poll = object : Runnable {
        override fun run() {
            try {
                navi?.getTBTInfo()
            } catch (e: RuntimeException) {
                Log.w(TAG, "TBT request failed", e)
                status = unavailable(e)
            }
            main.postDelayed(this, TBT_POLL_MS)
        }
    }

    fun start() {
        try {
            val n = NaviHelper(appContext).also { navi = it }
            n.initialize(
                {
                    main.post {
                        status = "경로 없음"
                        n.addListener(listener)
                        poll.run()
                    }
                },
                { e ->
                    Log.w(TAG, "navigation unavailable", e)
                    main.post { status = unavailable(e) }
                },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "navigation unavailable", e)
            status = unavailable(e)
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { navi?.removeListener(listener) }
        runCatching { navi?.release() }
        navi = null
    }
}

/** Position fixes from Pleos FusedLocation. */
class LocationFeed(
    context: Context,
    private val onFix: (lat: Double, lon: Double, headingDeg: Double?, tMs: Long) -> Unit,
) {
    var status by mutableStateOf("준비 중")
        private set

    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private var fused: FusedLocation? = null
    private var registered = false

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val tMs = SystemClock.elapsedRealtime() // on arrival: a busy main thread must not bunch the fixes
            val data = location ?: return
            val lat = data.latitude
            val lon = data.longitude
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return // also drops NaN
            val heading = data.heading.toDouble().takeIf { it.isFinite() }
            main.post {
                status = "수신"
                onFix(lat, lon, heading, tMs)
            }
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "location failed", exception)
            main.post { status = "오류 (${exception.javaClass.simpleName})" }
        }
    }

    /** Call once pleos.car.permission.FUSED_LOCATION has been answered. */
    fun start(granted: Boolean) {
        if (!granted) {
            status = "권한 없음"
            return
        }
        try {
            val f = FusedLocation(appContext).also { fused = it }
            f.initialize()
            // initialize() has no callback, like Vehicle: register after a short wait.
            main.postDelayed({
                try {
                    f.registerFusedLocationCallback(listener)
                    registered = true
                    status = "위치 기다리는 중"
                } catch (e: RuntimeException) {
                    Log.w(TAG, "location unavailable", e)
                    status = unavailable(e)
                }
            }, CONNECT_WAIT_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "location unavailable", e)
            status = unavailable(e)
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (registered) runCatching { fused?.unregisterFusedLocationCallback(listener) }
        runCatching { fused?.release() }
        fused = null
    }
}

/**
 * Hands-free questions through Pleos SpeechToText. Off until the driver switches it on while parked;
 * then it listens in one-shot recognitions, one after another, only while the app is on screen.
 */
class VoiceAsk(private val context: Context, private val onHeard: (String) -> Unit) {
    var status by mutableStateOf("꺼짐")
        private set

    private val main = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var ready = false
    private var on = false
    private var quietUntilMs = 0L // callbacks must not reopen the microphone before this

    private val listen = Runnable {
        if (!on || !ready) return@Runnable
        try {
            stt?.request()
        } catch (e: RuntimeException) {
            Log.w(TAG, "listen failed", e)
            status = unavailable(e)
        }
    }

    private fun again(delayMs: Long) {
        main.removeCallbacks(listen)
        if (on) main.postDelayed(listen, maxOf(delayMs, quietUntilMs - SystemClock.elapsedRealtime()))
    }

    private val results = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            if (!completed) return
            main.post {
                if (on) onHeard(stt)
                again(LISTEN_GAP_MS)
            }
        }

        override fun onStartedRecognition() {
            main.post { if (on) status = "듣는 중" }
        }

        override fun onEndedRecognition() {
            main.post { again(LISTEN_GAP_MS) }
        }

        override fun onError(error: SttError) {
            Log.w(TAG, "recognition failed: $error")
            main.post {
                if (on) status = "오류 (${error.name})"
                quietUntilMs = SystemClock.elapsedRealtime() + LISTEN_RETRY_MS
                again(0)
            }
        }

        override fun onReady() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    fun start() {
        on = true
        if (stt != null) {
            if (ready) status = "듣는 중"
            again(0)
            return
        }
        status = "준비 중"
        try {
            val s = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
            stt = s
            s.initialize(object : SttInitListener {
                override fun onSuccess() {
                    main.post {
                        runCatching { s.setLocale(Locale.KOREA) }
                        s.addListener(results)
                        ready = true
                        again(0)
                    }
                }

                override fun onFailure(errorType: SttError) {
                    Log.w(TAG, "STT init failed: $errorType")
                    main.post {
                        status = "사용 불가 (${errorType.name})"
                        runCatching { s.release() }
                        if (stt === s) stt = null // the next start() tries again
                    }
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "STT unavailable", e)
            status = unavailable(e)
            runCatching { stt?.release() }
            stt = null
        }
    }

    /** The app is about to speak: stop listening so it does not hear itself, then resume. */
    fun pause(ms: Long) {
        if (!on) return
        quietUntilMs = SystemClock.elapsedRealtime() + ms
        runCatching { stt?.stop() }
        again(0)
    }

    fun stop(reason: String = "꺼짐") {
        on = false
        main.removeCallbacks(listen)
        runCatching { stt?.stop() }
        status = reason
    }

    fun release() {
        stop()
        runCatching { stt?.removeListener(results) }
        runCatching { stt?.release() }
        stt = null
        ready = false
    }
}
