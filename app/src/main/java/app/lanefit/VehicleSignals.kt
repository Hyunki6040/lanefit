package app.lanefit

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import app.lanefit.engine.DriveMode
import app.lanefit.engine.Side
import kotlin.math.abs

/**
 * Reads the car. Read-only: no CONTROL permission, no setter is ever called.
 * Turn signal, steering angle, brake, driving mode and seat come from the Pleos Vehicle SDK;
 * speed and gear come from the AAOS car properties because Vehicle SDK 2.3.2 has no speed API.
 */
class VehicleSignals(private val context: Context) {
    enum class Signal(val ko: String) {
        SPEED("차속"), GEAR("기어"), TURN("방향지시등"), STEERING("조향각"),
        BRAKE("브레이크 페달"), MODE("주행 모드"), SEAT("동승석 착석"),
    }

    /** Last value of each signal as text, for the on-screen check. No entry means nothing has arrived. */
    val status = mutableStateMapOf<Signal, String>()

    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null

    // Written on the main thread only (see got()).
    private var speedKmh: Double? = null
    private var steeringDeg: Double? = null
    var inPark: Boolean? = null
        private set
    private var signal: Side? = null
    private var brakeOn = false
    private var driveMode = DriveMode.NORMAL
    private var passenger = false

    /** The current reading, or null until both speed and steering angle have arrived: no guessing. */
    fun sample(tMs: Long): VehicleSample? {
        val speed = speedKmh ?: return null
        val steering = steeringDeg ?: return null
        return VehicleSample(tMs, speed, steering * STEERING_LEFT_SIGN, signal, brakeOn, driveMode, passenger, inPark)
    }

    private fun got(s: Signal, text: String, set: () -> Unit) {
        main.post { set(); status[s] = text }
    }

    private fun failed(s: Signal, e: Exception, clear: () -> Unit = {}) {
        Log.w(TAG, "${s.name} failed", e)
        // The Pleos service reports a property the car does not have as "... is not supported".
        val text = if (e.message?.contains("not supported") == true) UNSUPPORTED else "오류 (${e.javaClass.simpleName})"
        main.post { clear(); status[s] = text }
    }

    private fun onTurn(v: TurnSignalValue?) {
        v ?: return
        got(Signal.TURN, v.name) {
            signal = when (v) {
                TurnSignalValue.LEFT -> Side.LEFT
                TurnSignalValue.RIGHT -> Side.RIGHT
                else -> null
            }
        }
    }

    private fun onSteering(v: Float?) {
        if (v == null || !v.isFinite()) return
        got(Signal.STEERING, "%.0f°".format(v)) { steeringDeg = v.toDouble() }
    }

    private fun onBrake(v: Boolean?) {
        v ?: return
        got(Signal.BRAKE, if (v) "밟음" else "뗌") { brakeOn = v }
    }

    private fun onMode(v: DrivingModeType?) {
        v ?: return
        got(Signal.MODE, v.name) {
            driveMode = when (v) {
                DrivingModeType.ECO -> DriveMode.ECO
                DrivingModeType.SPORT -> DriveMode.SPORT
                else -> DriveMode.NORMAL
            }
        }
    }

    private fun onSeat(v: Boolean?) {
        v ?: return
        val occupied = v != SEAT_INVERTED
        got(Signal.SEAT, if (occupied) "착석" else "비어 있음") { passenger = occupied }
    }

    private val turnL = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = onTurn(value)
        override fun onFailed(exception: Exception) = failed(Signal.TURN, exception) { signal = null }
    }
    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) = onSteering(p0)
        override fun onFailed(p0: Exception) = failed(Signal.STEERING, p0) { steeringDeg = null }
    }
    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) = onBrake(p0)
        override fun onFailed(p0: Exception) = failed(Signal.BRAKE, p0)
    }
    private val modeL = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) = onMode(state)
        override fun onFailed(e: Exception) = failed(Signal.MODE, e)
    }
    private val seatL = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(p0: Boolean?) = onSeat(p0)
        override fun onFailed(p0: Exception) = failed(Signal.SEAT, p0)
    }

    private val carCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onCar(value)
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                failed(Signal.SPEED, IllegalStateException("property error")) { speedKmh = null }
            }
        }
    }

    private fun onCar(v: CarPropertyValue<*>?) {
        if (v == null || v.status != CarPropertyValue.STATUS_AVAILABLE) return
        when (v.propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> (v.value as? Float)?.takeIf { it.isFinite() }?.let { ms ->
                val kmh = abs(ms) * 3.6 // m/s per AAOS; negative when reversing
                got(Signal.SPEED, "%.0f km/h".format(kmh)) { speedKmh = kmh }
            }
            VehiclePropertyIds.GEAR_SELECTION -> (v.value as? Int)?.let { gear ->
                val park = gear == VehicleGear.GEAR_PARK
                got(Signal.GEAR, if (park) "P" else "P 아님") { inPark = park }
            }
        }
    }

    fun start() {
        if (vehicle != null) return
        val v = Vehicle(context.applicationContext).also { it.initialize(); vehicle = it }
        // initialize() has no callback: register after a short wait, then read again in case the first read was dropped.
        main.postDelayed({ register(v); read(v) }, 1_500)
        main.postDelayed({ read(v) }, 6_000)
    }

    private fun register(v: Vehicle) {
        attempt(Signal.TURN) { v.getTurnSignal().registerTurnSignalState(turnL) }
        attempt(Signal.STEERING) { v.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        attempt(Signal.BRAKE) { v.getBrake().registerBrakePedalStatus(brakeL) }
        attempt(Signal.MODE) { v.getDrivingMode().registerDrivingModeState(modeL) }
        attempt(Signal.SEAT) { v.getSeat().registerSeatOccupancy(PASSENGER_SEAT, seatL) }
    }

    /** A new listener does not always get the current value, so ask for it once. */
    private fun read(v: Vehicle) {
        attempt(Signal.TURN) { v.getTurnSignal().getCurrentTurnSignalState({ onTurn(it) }, { failed(Signal.TURN, it) }) }
        attempt(Signal.STEERING) {
            v.getSteeringWheel().getCurrentSteeringWheelAngle({ onSteering(it) }, { failed(Signal.STEERING, it) })
        }
        attempt(Signal.BRAKE) { v.getBrake().getCurrentBrakePedalStatus({ onBrake(it) }, { failed(Signal.BRAKE, it) }) }
        attempt(Signal.MODE) { v.getDrivingMode().getCurrentDrivingModeState({ onMode(it) }, { failed(Signal.MODE, it) }) }
        attempt(Signal.SEAT) { v.getSeat().getCurrentSeatOccupancy(PASSENGER_SEAT, { onSeat(it) }, { failed(Signal.SEAT, it) }) }
    }

    private fun attempt(s: Signal, call: () -> Unit) {
        try {
            call()
        } catch (e: RuntimeException) {
            failed(s, e)
        }
    }

    /** Call once android.car.permission.CAR_SPEED has been answered (granted or not). */
    fun startCar() {
        if (car != null) return
        try {
            // Without a listener the platform finishes the activity whenever the car service restarts (seen on
            // the emulator). With one, speed and gear are dropped until the service is back, then watched again.
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                main.post { if (ready) onCarReady(c) else onCarLost() }
            }
        } catch (e: RuntimeException) {
            failed(Signal.SPEED, e)
        }
    }

    private fun onCarReady(c: Car) {
        try {
            val p = (c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager) ?: return
            props = p
            watch(p, VehiclePropertyIds.PERF_VEHICLE_SPEED, Signal.SPEED)
            watch(p, VehiclePropertyIds.GEAR_SELECTION, Signal.GEAR)
        } catch (e: RuntimeException) {
            failed(Signal.SPEED, e)
        }
    }

    // ponytail: only the AAOS side recovers. Whether the Pleos Vehicle listeners survive a car service restart is untested.
    private fun onCarLost() {
        props = null
        speedKmh = null // sample() returns null: no stale speed, and no parked screen without a confirmed P
        inPark = null
        status.remove(Signal.SPEED)
        status.remove(Signal.GEAR)
    }

    private fun watch(p: CarPropertyManager, id: Int, s: Signal) {
        try {
            if (p.getCarPropertyConfig(id) == null) {
                status[s] = UNSUPPORTED
                return
            }
            // 10 Hz for speed (braking is judged from it); the rate is ignored for on-change properties like gear.
            p.registerCallback(carCb, id, CarPropertyManager.SENSOR_RATE_FAST)
            onCar(p.getProperty<Any>(id, 0)) // a continuous property can stay silent while unchanged
        } catch (e: SecurityException) {
            Log.w(TAG, "${s.name} not permitted", e)
            status[s] = "권한 없음"
        } catch (e: RuntimeException) {
            failed(s, e)
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        vehicle?.let { v ->
            runCatching { v.getTurnSignal().unregisterTurnSignalState(turnL) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(brakeL) }
            runCatching { v.getDrivingMode().unregisterDrivingModeState(modeL) }
            runCatching { v.getSeat().unregisterSeatOccupancy(PASSENGER_SEAT, seatL) }
            runCatching { v.release() }
        }
        vehicle = null
        runCatching { props?.unregisterCallback(carCb) }
        runCatching { car?.disconnect() }
        car = null
        props = null
    }

    private companion object {
        const val TAG = "LaneFit"
        const val UNSUPPORTED = "차량이 제공하지 않음"

        // ponytail: sign taken from the AAOS steering property (left is negative); Pleos does not document it.
        // Flip to 1.0 if a real car reports left as positive. Lane direction is wrong otherwise.
        const val STEERING_LEFT_SIGN = -1.0

        // Left-hand-drive car: the front passenger sits at row 1 right.
        val PASSENGER_SEAT = SeatArea(SeatValue.ROW_1_RIGHT)

        // ponytail: emulator v2.1.0 reports occupancy inverted (three teams found it). Real-car polarity is unverified.
        val SEAT_INVERTED = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    }
}
