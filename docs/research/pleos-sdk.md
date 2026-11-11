# Pleos Connect SDK integration facts for LaneFit

Researched 2026-11-11. Read-only. Nothing here was compiled or run on the emulator by this research; "ran on emulator" always means another team's repo says so.

## Sources and tag legend

Every fact carries `[confirmed: <source>]` or `[inferred]`. Short source names:

| Short name | What it is |
|---|---|
| `sample-vehicle` | github.com/Pleos-Playground/pleos-sample-vehicle-app @ 959981d (official) |
| `sample-navi` | github.com/Pleos-Playground/pleos-sample-navi-app @ 76ed592 (official) |
| `sample-fused` | github.com/Pleos-Playground/pleos-sample-fusedlocation-app @ c955c3a (official) |
| `forum #N` | github.com/Pleos-Playground/pleos-playground-api/discussions/N (official support forum) |
| `jar:<Artifact>` | `javap` / manifest of the AAR downloaded from the Pleos Nexus (bytecode is the ground truth for signatures) |
| `nexus` | anonymous `curl` against `nexus-playground.pleos.ai` on 2026-11-11 |
| `docs:<path>` | `https://document.pleos.ai/en/docs/<path>` |
| `mulmizero`, `ChabakON`, `routinegongbang`, `yeopjari26` | contestant repos `hwiery/mulmizero` @ e7e13ca, `yg440041-hue/ChabakON` @ d4ab4a5, `LcokHcak/routinegongbang` @ 782ad15, `yeopjari-app/yeopjari26` @ 8fc06b4 |
| `local` | checked on this Mac |

Emulator evidence quality: `mulmizero` (arm64 Mac), `ChabakON`, `routinegongbang` (x86_64) ran on Pleos Connect Emulator v2.1.0. `yeopjari26` Pleos build was **never run** on the emulator (its README says so), so its code proves "compiles", not "works".

---

## 1. Gradle setup

### Repository

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}
```
[confirmed: sample-vehicle `settings.gradle.kts`; identical line in sample-navi, sample-fused and all four contestant repos; docs:pleos-only/connect-sdk-pleos/Vehicle/project-setup]

- **No credentials or token.** [confirmed: sample-navi `README.md` — "No repository token or `credentials.gradle` file is required."; nexus — `maven-metadata.xml`, `.pom`, `.aar` all HTTP 200 without auth]

### Coordinates and versions

| Artifact | Coordinate | Versions published | Source |
|---|---|---|---|
| Vehicle | `ai.pleos.playground:Vehicle:2.3.2` | 2.0.3, 2.3.2 | [confirmed: nexus `Vehicle/maven-metadata.xml`] |
| NaviHelper | `ai.pleos.playground:NaviHelper:3.0.5` | 2.0.2, 2.0.3, 3.0.5 | [confirmed: nexus] |
| SpeechToText | `ai.pleos.playground:SpeechToText:3.3.1` | 2.1.2 … 3.3.0, 3.3.1 | [confirmed: nexus] |
| TextToSpeech | `ai.pleos.playground:TextToSpeech:3.3.1` | 2.1.2 … 3.3.0, 3.3.1 | [confirmed: nexus] |
| FusedLocation | `ai.pleos.playground:FusedLocation:1.1.3` | 1.0.4, 1.1.3 | [confirmed: nexus] |

**Disagreement — Vehicle version.**
- `2.3.1`: docs:pleos-only/getting-started says `implementation("ai.pleos.playground:Vehicle:2.3.1")`; sample-vehicle `gradle/libs.versions.toml` has `vehicleSdk = "2.3.1"`.
- `2.3.2`: sample-vehicle `README.md`, docs:…/Vehicle/project-setup, all four contestant repos.
- Resolution: `Vehicle-2.3.1.pom` returns **HTTP 404**, `Vehicle-2.3.2.pom` returns 200. Use **2.3.2**. [confirmed: nexus] The official sample as checked in would not resolve its own dependency. [inferred]

Transitive dependencies (resolved automatically, all on the same Nexus or Maven Central): `vehicle-aidl:1.1.1`, `vehicle-data:1.1.11` (which lists `hilt-android:2.51.1`), `kotlin-reflect:1.9.25`, `gson:2.10.1`, `core-ktx:1.8.0`, `kotlinx-coroutines-android:1.6.3`; NaviHelper pulls `navi-data:3.0.4` (needs `kotlinx-serialization-json:1.6.3`), `navi-aidl:3.0.1`; STT/TTS pull `ai.pleos.playground.security:universal-access:0.6.0`. [confirmed: nexus POMs and `Vehicle-2.3.2.module`]

### Toolchain

| Source | AGP | Kotlin | Gradle | compileSdk | minSdk | targetSdk | JVM |
|---|---|---|---|---|---|---|---|
| sample-vehicle (official) | 8.8.2 | 2.0.0 | 8.10.2 | 35 | 23 | 35 | 17 |
| sample-fused (official) | 8.8.2 | 2.0.0 | — | 35 | 24 | 35 | 17 |
| sample-navi (official) | 8.8.0 | 1.9.22 | — | 35 | 33 | 35 | 11 |
| ChabakON, routinegongbang, yeopjari26 | 8.8.2 | 2.0.0 | 8.10.2 | 35 | 28 | 35 | 17 |
| mulmizero | 8.7.3 | 2.0.21 | 8.11.1 | 35 | 28 | 34 | 17 |

[confirmed: each repo's `gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`]

- Hard floor from the SDK itself: every AAR declares `minSdkVersion="23"` and `minCompileSdk=1`. [confirmed: jar AAR `AndroidManifest.xml` and `META-INF/…/aar-metadata.properties`]
- Docs floor: "Minimum SDK: Android API 28+", "Gradle 8.0+". [confirmed: docs:pleos-only/getting-started; docs:connect/guide/getting-started/application-development/setup-connect-sdk-emulator]
- Compose plugin is `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.x). [confirmed: sample-vehicle `libs.versions.toml`]
- `android.useAndroidX=true` in `gradle.properties`. [confirmed: sample-vehicle `gradle.properties`]
- For the AAOS Car API (speed, gear): `android { useLibrary("android.car") }`. [confirmed: routinegongbang and yeopjari26 `app/build.gradle.kts`]
- SDK proguard rules ship inside the AAR (consumer rules); nothing to add. [confirmed: jar:Vehicle `proguard.txt`]

Recommended for LaneFit: AGP 8.8.2, Kotlin 2.0.0, Gradle 8.10.2, compileSdk 35, minSdk 28, targetSdk 35, JDK 17 — the exact official-sample combination, with the docs' minSdk. [inferred]

---

## 2. AndroidManifest

### Which permission each signal needs

Taken from the `@RequiresPermission` annotation on every method of the SDK interface (bytecode), cross-checked with the official permission table.

| Signal | SDK interface | Permission | Source |
|---|---|---|---|
| Turn signal | `TurnSignal` | `pleos.car.permission.CAR_LIGHTS` | [confirmed: jar:Vehicle `api.TurnSignal` annotations; docs:pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission "Light, TurnSignal → CAR_LIGHTS"] |
| Steering angle | `SteeringWheel` | `pleos.car.permission.CAR_DRIVE` | [confirmed: jar:Vehicle `api.SteeringWheel` annotations] |
| Brake pedal | `Brake` | `pleos.car.permission.CAR_DRIVE` | [confirmed: jar:Vehicle; docs vehicle-permission] |
| Driving mode, cruise control | `DrivingMode` | `pleos.car.permission.CAR_DRIVE` | [confirmed: jar:Vehicle; docs vehicle-permission] |
| Gear (Pleos API, deprecated) | `Gear` | `pleos.car.permission.CAR_DRIVE` | [confirmed: jar:Vehicle] |
| Seat occupancy, seat belt buckled | `Seat` | `pleos.car.permission.CAR_SEATS` | [confirmed: jar:Vehicle; docs vehicle-permission] |
| Safety warnings (belt warning, airbag, oil, washer, power-down) | `Safety` | `pleos.car.permission.CAR_SAFETY` | [confirmed: jar:Vehicle; docs vehicle-permission] |
| Telltales (LKA, LFA, ELK, DRIVER_ATTENTION, …) | `Safety.registerTelltales` | `pleos.car.permission.PRIVATE_CAR_SAFETY` | [confirmed: jar:Vehicle — `checkTelltalesCapabilities` / `registerTelltales` / `unregisterTelltales`] |
| Vehicle speed (AAOS) | `CarPropertyManager` | `android.car.permission.CAR_SPEED` | [confirmed: routinegongbang `app/src/main/AndroidManifest.xml`, `README.md` line 79] |
| Gear (AAOS `GEAR_SELECTION`) | `CarPropertyManager` | `android.car.permission.CAR_POWERTRAIN` | [confirmed: routinegongbang and yeopjari26 manifests] |
| Navigation | `NaviHelper` | `pleos.car.permission.NAVI_ROUTE`, `pleos.car.permission.NAVI_ROUTE_SEARCH` | [confirmed: sample-navi `AndroidManifest.xml`] |
| Pleos TTS | `TextToSpeech` | `pleos.car.permission.TTS_SERVICE` | [confirmed: docs:pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions] |
| Pleos STT | `SpeechToText` | `pleos.car.permission.STT_SERVICE` + `android.permission.RECORD_AUDIO` | [confirmed: ChabakON `app/src/main/AndroidManifest.xml`] |
| Fused location | `FusedLocation` | `pleos.car.permission.FUSED_LOCATION` | [confirmed: sample-fused `README.md`, `AndroidManifest.xml`] |

**The names CAR_SPEED / READ_CAR_STEERING / READ_ADAS_STATES in the `pleos.car.permission.*` namespace.**
- They are declared in the official sample manifest, alongside `CAR_POWERTRAIN`, `CAR_MILEAGE`, `CAR_TIRES`, `READ_CAR_DISPLAY_UNITS`, `READ_WINDSHIELD_WIPERS`, `CAR_CONNECT_VENDOR_CONTROL`. [confirmed: sample-vehicle `app/src/main/AndroidManifest.xml`]
- No method in Vehicle SDK 2.3.2 requires them: the only `pleos.car.permission.*` strings in the SDK jars are `CAR_*`, `CONTROL_CAR_*` and `PRIVATE_CAR_*` for CLIMATE, DOORS, DRIVE, ENERGY, INFO, LIGHTS, MIRRORS, SAFETY, SEATS, TOLL, WINDOWS. [confirmed: jar:Vehicle + jar:vehicle-data string scan, `constant.PleosPermission`]
- So steering angle needs `CAR_DRIVE` (not `READ_CAR_STEERING`), and there is nothing for `CAR_SPEED` or `READ_ADAS_STATES` to unlock in the SDK. Declaring them is harmless. [inferred]

### Runtime vs install-time

| Permission group | Type | Source |
|---|---|---|
| `pleos.car.permission.CAR_*`, `CONTROL_CAR_*`, `READ_*` (Vehicle Service) | install-time, no dialog | [confirmed: sample-vehicle `README.md` — "These Pleos Vehicle Service permissions have `protectionLevel=\"normal\"`, so they do not use an Android dangerous-permission runtime dialog."; yeopjari26 `app/src/pleos/AndroidManifest.xml` comment] |
| `pleos.car.permission.PRIVATE_*` | private; excluded from the official sample ("It does not declare any `PRIVATE_*` permission") | [confirmed: sample-vehicle `README.md`, `explorer/PublicApiPolicy.kt`] |
| `pleos.car.permission.FUSED_LOCATION` | **runtime request** | [confirmed: sample-fused `README.md` "granted at runtime", `MainActivity.kt` uses `ActivityResultContracts.RequestPermission()`] |
| `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` | install-time (official sample never requests them at runtime) | [inferred from sample-navi `MainActivity.kt`] |
| `android.car.permission.CAR_SPEED` | **runtime request**; the dialog shows it under the "위치 정보" (location) group | [confirmed: routinegongbang `README.md` line 79] |
| `android.car.permission.CAR_POWERTRAIN` | treated as possibly-missing by routinegongbang `platform/PermissionGate.kt`; AOSP declares it normal | [inferred] |
| `android.permission.RECORD_AUDIO`, `ACCESS_FINE_LOCATION` | runtime (standard Android) | [confirmed: mulmizero `README.md` — `adb install -r -g` "마이크·위치 런타임 권한 자동 허용"] |

Check on the emulator instead of trusting the table: `adb shell dumpsys package <applicationId> | grep -A40 "runtime permissions"`. [inferred]

### uses-feature / uses-library / meta-data

- `<uses-feature android:name="android.hardware.type.automotive" android:required="true"/>` — used by mulmizero and routinegongbang; **absent** from all three official samples and from ChabakON. Optional. [confirmed: those manifests]
- `<uses-library android:name="android.car" android:required="false"/>` inside `<application>` when using the Car API. [confirmed: yeopjari26 `app/src/pleos/AndroidManifest.xml`] routinegongbang uses the Car API with only the Gradle `useLibrary("android.car")` and no manifest entry. [confirmed: routinegongbang manifest and `app/build.gradle.kts`]
- `<meta-data android:name="distractionOptimized" android:value="true"/>` inside the `<activity>` so the screen stays usable while moving. [confirmed: mulmizero and yeopjari26 manifests; rationale in forum #21]
- Added automatically by manifest merge, do not declare: `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` permission and the `<queries>` entries for `ai.pleos.playground.vehicle.VEHICLE_START`, `ai.pleos.playground.intent.action.NAVI_START`, `ai.pleos.playground.caas.intent.action.SPEECH_START`. [confirmed: jar AAR manifests of Vehicle, NaviHelper, TextToSpeech, SpeechToText]
- `CONTROL_CAR_*`: not needed for a read-only app.

---

## 3. Kotlin API

All signatures below are from the 2.3.2 / 3.0.5 / 3.3.1 bytecode unless tagged otherwise. Callback and listener values are nullable (`Float?`, `Boolean?`, enum`?`) — the contestant code overriding them with nullable parameters compiles. [confirmed: mulmizero `sdk/VehicleBridge.kt`, routinegongbang `pleos/PleosPhase2.kt`]

### 3.0 Common pattern

```kotlin
import ai.pleos.playground.vehicle.Vehicle

val vehicle = Vehicle(context.applicationContext)
vehicle.initialize()          // no callback, binds asynchronously
// ... domain calls ...
vehicle.release()             // unregister listeners first
```
[confirmed: sample-vehicle `README.md` and `vehicle/VehicleClient.kt`; jar:Vehicle `Vehicle` — `initialize()` and `release()` take no arguments]

- Domain getters on `Vehicle`: `getBrake()`, `getCarInfo()`, `getDisplay()`, `getDoor()`, `getDrivingMode()`, `getEvBattery()`, `getFuel()`, `getGear()`, `getHVAC()`, `getLight()`, `getOdometer()`, `getSafety()`, `getSeat()`, `getSideMirror()`, `getSteeringWheel()`, `getTire()`, `getToll()`, `getTurnSignal()`, `getWindow()`, `getWiper()`. [confirmed: jar:Vehicle]
- Per property: `check…Capabilities(onComplete, onFailed)`, `getCurrent…(onComplete, onFailed)`, `register…(listener)`, `unregister…(listener)`. Named arguments `onComplete = …, onFailed = …` are used by the official sample. [confirmed: sample-vehicle `README.md` lines 191–242]
- Every listener extends `VehicleListener`, whose only method is `onFailed(Exception)`; each listener therefore has exactly two methods to override. [confirmed: jar:Vehicle `listener.VehicleListener`]
- `CapabilityConfig.accessType` is `AccessType { NONE, READ, WRITE, READ_WRITE }`; `AreaBounds<T>` has `minValue`, `maxValue`. [confirmed: jar:Vehicle]
- Readiness after `initialize()` — sources disagree:
  - official: "`Vehicle.initialize()` must complete before calling a vehicle property API. If the service is unavailable or initialization fails, surface the error and do not continue with property calls." [confirmed: sample-vehicle `README.md` line 172]
  - mulmizero waits a fixed 1500 ms and re-subscribes at 6000 ms if nothing arrived; ChabakON waits 800 ms. [confirmed: mulmizero `sdk/VehicleBridge.kt` lines 87–88; ChabakON `vehicle/PleosGateway.kt` line 70]
  - routinegongbang: "`Vehicle.initialize()` has no callback. The SDK binds with its own retries and queues calls"; it fires one cheap capability call and treats any answer as "connected", with a 20 s timeout (`PING_TIMEOUT_MS = 20_000L`). [confirmed: routinegongbang `pleos/PleosVehicle.kt` lines 25, 155]
- Capability-callback arity (three contestant variants explained): the SDK has **overloads**. Area-less properties take `(CapabilityConfig) -> Unit`; area properties take `(CapabilityConfig, List<Area>) -> Unit` or `(CapabilityConfig, AreaBounds<T>) -> Unit`; the older one-argument forms such as `checkSeatOccupancyCapabilities((List<SeatArea>) -> Unit, …)` still exist but are `@Deprecated`. [confirmed: jar:Vehicle `api.Seat`, `api.SteeringWheel`, `api.Brake`]

### 3.1 Turn signal

```kotlin
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue      // OFF, RIGHT, LEFT
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener

val ts = vehicle.getTurnSignal()
ts.checkTurnSignalStateCapabilities({ cfg -> /* cfg.accessType */ }, { e -> })
ts.getCurrentTurnSignalState({ v -> /* TurnSignalValue? */ }, { e -> })
val l = object : TurnSignalStateListener {
    override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {}
    override fun onFailed(exception: Exception) {}
}
ts.registerTurnSignalState(l)
ts.unregisterTurnSignalState(l)
```
[confirmed: jar:Vehicle `api.TurnSignal` (exactly these 4 methods), `constant.area.TurnSignalValue`, `listener.TurnSignalStateListener`; listener override shape compiles in yeopjari26 `app/src/pleos/java/ai/yeopjari/app/PlatformFactory.kt`]

- No hazard value and no hazard API. [confirmed: jar:Vehicle enum has only OFF/RIGHT/LEFT; yeopjari26 `PlatformFactory.kt` comment]
- Nobody has shown this firing on the emulator. See Unverified.

### 3.2 Steering wheel angle

```kotlin
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener

val sw = vehicle.getSteeringWheel()
sw.checkSteeringWheelAngleCapabilities({ cfg, bounds -> /* AreaBounds<Float> */ }, { e -> })
sw.getCurrentSteeringWheelAngle({ deg -> /* Float? */ }, { e -> })
val l = object : SteeringWheelAngleListener {
    override fun onSteeringWheelAngleUpdated(p0: Float?) {}
    override fun onFailed(p0: Exception) {}
}
sw.registerSteeringWheelAngle(l)
sw.unregisterSteeringWheelAngle(l)
```
[confirmed: jar:Vehicle `api.SteeringWheel`; ran on emulator in mulmizero `sdk/VehicleBridge.kt`]

- Unit: degrees, `Float`. mulmizero formats it as `"%.0f°"` and drives it from the Virtual Console with values −170 … 210. [confirmed: mulmizero `sdk/VehicleBridge.kt`, `tools/live_demo.sh`]
- Sign convention (which side is positive): Unverified.

### 3.3 Brake

```kotlin
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener

val br = vehicle.getBrake()
br.checkBrakePedalStatusCapabilities({ cfg -> }, { e -> })
br.getCurrentBrakePedalStatus({ pressed -> /* Boolean? */ }, { e -> })
val l = object : BrakePedalStatusListener {
    override fun onBrakePedalStatusUpdated(p0: Boolean?) {}
    override fun onFailed(p0: Exception) {}
}
br.registerBrakePedalStatus(l)
br.unregisterBrakePedalStatus(l)
```
[confirmed: jar:Vehicle `api.Brake` (exactly these 4 methods); ran on emulator in mulmizero]

- Value is a **Boolean (pressed)**, not pressure. The Virtual Console takes a number; "0 초과" (> 0) means pressed. [confirmed: jar:Vehicle; ChabakON `README.md` line 62]

### 3.4 Driving mode

```kotlin
import ai.pleos.playground.vehicle.constant.control.DrivingModeType   // NORMAL, ECO, SPORT
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener

val dm = vehicle.getDrivingMode()
dm.checkDrivingModeStateCapabilities({ cfg -> }, { e -> })
dm.getCurrentDrivingModeState({ mode -> /* DrivingModeType? */ }, { e -> })
val l = object : DrivingModeStateListener {
    override fun onDrivingModeStateUpdated(state: DrivingModeType?) {}
    override fun onFailed(e: Exception) {}
}
dm.registerDrivingModeState(l)
dm.unregisterDrivingModeState(l)
```
[confirmed: jar:Vehicle `api.DrivingMode`, `constant.control.DrivingModeType`; same calls in routinegongbang `pleos/PleosPhase2.kt`]

- **Enum has only `NORMAL`, `ECO`, `SPORT`. There is no COMFORT.** Raw values `NORMAL = 0`, `ECO = 1`, `SPORT = 2`. [confirmed: jar:Vehicle `DrivingModeType`; jar:vehicle-data `VehicleDrivingModeValue`]
- `DRIVE_MODE_COMFORT`, `DRIVE_MODE_SPORT`, `DRIVE_MODE_ECO`, `DRIVE_MODE_TERRAIN` exist only as `TelltaleType` values, which need the private permission (3.7). [confirmed: jar:Vehicle `constant.telltale.TelltaleType`]
- Same interface also exposes cruise control: `getCruiseControlState` → `CruiseControl { OTHER, ENABLED, ACTIVATED, USER_OVERRIDE, SUSPENDED, FORCED_DEACTIVATION_WARNING }`, `getCruiseControlTargetSpeed` → `Float?`, each with `check…Capabilities` and `register…/unregister…`. [confirmed: jar:Vehicle `api.DrivingMode`]

### 3.5 Seat occupancy

```kotlin
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener

val seat = vehicle.getSeat()
val area = SeatArea(SeatValue.ROW_1_RIGHT)                 // vararg constructor
seat.checkSeatOccupancyCapabilities({ cfg, areas -> /* List<SeatArea> */ }, { e -> })
seat.getCurrentSeatOccupancy(area, { occupied -> /* Boolean? */ }, { e -> })
val l = object : SeatOccupancyListener {
    override fun onSeatOccupancyUpdated(p0: Boolean?) {}
    override fun onFailed(p0: Exception) {}
}
seat.registerSeatOccupancy(area, l)
seat.unregisterSeatOccupancy(area, l)
```
[confirmed: jar:Vehicle `api.Seat`, `api.model.area.SeatArea`; ran on emulator in mulmizero, ChabakON, routinegongbang]

- `SeatValue`: `ROW_1_LEFT`, `ROW_1_CENTER`, `ROW_1_RIGHT`, `ROW_2_LEFT`, `ROW_2_CENTER`, `ROW_2_RIGHT`, `ROW_3_LEFT`, `ROW_3_CENTER`, `ROW_3_RIGHT`. [confirmed: jar:Vehicle]
- Seat belt is the same shape: `checkSeatBeltBuckledCapabilities`, `getCurrentSeatBeltBuckledState(area, …)`, `registerSeatBeltBuckledState(area, SeatBeltBuckledStateListener)`. [confirmed: jar:Vehicle `api.Seat`]
- The Boolean is **inverted on the emulator** — see section 5.

### 3.6 Vehicle speed

- **The Vehicle SDK 2.3.2 has no vehicle-speed API.** Across all 20 `api.*` interfaces the only "speed" methods are `DrivingMode.*CruiseControlTargetSpeed` (and HVAC fan / wiper). [confirmed: jar:Vehicle `javap` of every interface; yeopjari26 `PlatformFactory.kt` line 78 "공개 Vehicle SDK(2.3.2)에는 차속과 비상등 API 가 없다"; forum #33 — a developer could not find one either, unanswered as of 2026-11-07]
- Option A, AAOS property (routinegongbang; README marks it "동작(읽기)" on the emulator):
  ```kotlin
  import android.car.Car
  import android.car.VehiclePropertyIds
  import android.car.hardware.CarPropertyValue
  import android.car.hardware.property.CarPropertyManager

  val car = Car.createCar(context)
  val props = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
  val cb = object : CarPropertyManager.CarPropertyEventCallback {
      override fun onChangeEvent(value: CarPropertyValue<*>) {
          if (value.status == CarPropertyValue.STATUS_AVAILABLE) { /* (value.value as? Float) = m/s */ }
      }
      override fun onErrorEvent(propertyId: Int, areaId: Int) {}
  }
  if (props?.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) != null)
      props.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
  // props.getProperty<Any>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)  // one-shot read, area 0 = global
  // props.unregisterCallback(cb); car.disconnect()
  ```
  [confirmed: routinegongbang `app/src/main/kotlin/kr/routinegongbang/app/aaos/AaosVehicleProperties.kt`, `README.md` line 146]
  - Value: `Float`, metres per second. [confirmed: routinegongbang source casts to `Float`; AOSP unit inferred — forum #33 asks Pleos to confirm m/s, unanswered]
  - Needs `android.car.permission.CAR_SPEED`, requested at runtime; wrap calls in `try/catch (SecurityException, IllegalArgumentException)`. A continuous property may send nothing while unchanged, so routinegongbang also polls. [confirmed: routinegongbang `AaosVehicleProperties.kt`]
  - Official stance: Pleos aligns with AAOS where an equivalent exists, and the Pleos `Gear` API is deprecated with the message "This API is deprecated, use the Google GEAR_SELECTION property." [confirmed: forum #27; jar:Vehicle `api.Gear`] That the same holds for speed is [inferred].
- Option B, location-derived: FusedLocation `LocationData` has `latitude`, `longitude`, `altitude`, `heading`, `confidence`, `timestampMillis` and **no speed field**; yeopjari26 computes speed from two consecutive fixes. [confirmed: jar:FusedLocation `model.LocationData`; yeopjari26 `core/…/GpsSpeed.kt` — compile-only] `adb emu geo fix <lon> <lat>` moves FusedLocation on the emulator. [confirmed: sample-fused `README.md`; forum #29 comment]

### 3.7 Safety API

Public (`CAR_SAFETY`) [confirmed: jar:Vehicle `api.Safety`]:

| Method family | Value |
|---|---|
| `hasSeatBeltNotBuckledWarning(SeatArea, …)`, `registerSeatBeltNotBuckledWarning(SeatArea, SeatBeltNotBuckledWarningListener)` | `Boolean?` |
| `hasSeatAirbagEnabled`, `hasSeatAirbagBeenDeployed` (+ register) | `Boolean?` |
| `getEngineOilLevelState`, `getBrakeFluidLevelState`, `getTransmissionOilLevelState` (+ register, `OilLevelStateListener`) | `OilLevelState` |
| `hasLowWasherFluidWarning`, `registerLowWasherFluidWaringState(LowWasherFluidWaringListener)` — "Waring" is the real SDK spelling | `Boolean?` |
| `hasPowerDownWarningIndicator` (+ register) | `Boolean?` |

Private — the part a lane-keeping app would want:

```kotlin
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState   // OFF, ON, BLINK
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType    // … LKA, LFA, LCA_LEFT, LCA_RIGHT, ELK, FCA, ICC, DRIVER_ATTENTION, TURN_SIGNAL_LEFT, TURN_SIGNAL_RIGHT, OVER_SPEED …
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener

val l = object : TelltaleInfoListener {
    override fun onTelltaleInfoUpdated(value: Telltales?) { value?.telltales?.forEach { /* it.type, it.state, it.color, it.priority, it.warningCount */ } }
    override fun onFailed(exception: Exception) {}
}
vehicle.getSafety().registerTelltales(l)      // PRIVATE_CAR_SAFETY
vehicle.getSafety().unregisterTelltales(l)
```
[confirmed: jar:Vehicle `api.Safety`, `api.model.telltale.*`, `constant.telltale.*`; compiles in yeopjari26 `PlatformFactory.kt`]

- `checkTelltalesCapabilities`, `registerTelltales`, `unregisterTelltales` are annotated `PRIVATE_CAR_SAFETY`; `getTelltalesInfo` is annotated `CAR_SAFETY`. [confirmed: jar:Vehicle]
- The official sample **blocks all four** telltale methods as non-public (`PublicApiPolicy.blockedApis`) and states "Private, hidden, and deprecated APIs are intentionally excluded." [confirmed: sample-vehicle `explorer/PublicApiPolicy.kt`, `README.md`]
- A developer reports using `PRIVATE_CAR_SAFETY` + `registerTelltales` on the emulator and asked whether a contest or store app may hold it; unanswered. [confirmed: forum #33]
- Treat LKA / LFA / driver-attention telltales as **not available to LaneFit** unless Pleos says otherwise. [inferred]

### 3.8 NaviHelper

```kotlin
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.NaviErrorCode

val navi = NaviHelper(context)
navi.initialize()                       // or initialize({ /* connected */ }, { t: Throwable -> })
navi.addListener(listener)
navi.requestRoute(RouteInfo(
    latitude = 37.48544722, longitude = 127.03636666,
    poiId = "36khp37hh8gqcm5nqhe5", poiName = "42dot", poiSubId = "0",
    address = "서울시 강남구 남부순환로 2621"))
navi.cancelRoute()
navi.removeListener(listener); navi.release()

val listener = object : NaviHelperEventListener {
    override fun onRouteStarted(routeStartInfo: RouteStartInfo) {}   // duration, distance, tollFare, name
    override fun onRoutingCompleted() {}
    override fun onRouteCancelled() {}
    override fun onError(errorCode: NaviErrorCode) {}
}
```
[confirmed: sample-navi `screen/vm/NaviHelperViewModel.kt`, `data/sample/NaviTestData.kt`; jar:NaviHelper `helper.NaviHelper`, jar:navi-data `data.RouteStartInfo`]

- All 26 listener methods have default bodies; override only what is needed. [confirmed: jar:NaviHelper — `javap` shows 26 `default` methods, 0 abstract]
- Full callback list: `onRouteStarted(RouteStartInfo)`, `onRoutingCompleted()`, `onRouteEnded()` (deprecated), `onReRouteEnded()`, `onRouteCancelled()`, `onRouteOptionChanged(RouteOption)`, `onWaypointChanged(WaypointChangedInfo)`, `onWayPointArrived(WaypointArrivedInfo)`, `onDestinationArrived(DestinationArrivedInfo)`, `onDestinationChanged()`, `onBookMarkChanged()`, `onError(NaviErrorCode)`, `onNavigationStatus(NavigationStatus)`, `onCurrentRoadSpeedLimit(Int)`, `onCameraAlert(List<CameraAlert>)`, `onDrivingInfo(DrivingInfo)`, `onTripInfo(TripInfo)`, plus replies to getters: `onCurrentLocationInfo`, `onRouteStateInfo`, `onDestinationInfo`, `onWayPointInfo`, `onRecentDestinationInfo`, `onBookmarkInfo`, `onTBTInfo(List<TBTInfo>)`, `onChargerOperatorInfo`, `onCautionPointInfo`. [confirmed: sample-navi `NaviHelperViewModel.kt`]
- Requests: `requestRoute(RouteInfo)`, `requestRouteWithWaypoints(RouteWithWaypointsRequest)`, `cancelRoute()`, `addWaypoint(RequestWaypointInfo)`, `removeWaypoint(WaypointIndex)`, `changeRouteOption(RouteOption)`, `requestReRoute()`, `requestNavigationScreen(NavigationScreenRequest)`, `requestTrip(TripRequest)`. Getters return nothing and answer through the listener: `getRouteStateInfo()`, `getCurrentLocationInfo()`, `getDestinationInfo()`, `getWaypointInfo()`, `getTBTInfo()`, `getBookmarkInfo()`, `getRecentDestinationInfo()`, `getChargerOperatorInfo()`. [confirmed: jar:NaviHelper]
- Enums: `RouteOption { RECOMMENDED, SHORT_DISTANCE, SHORT_TIME, FREE, HIGHWAY, COMFORTABLE, SCHOOL_ZONE }`, `NavigationStatus { INACTIVE, INITIALIZING, FREE_DRIVE, ROUTE_DRIVE }`, `NaviErrorCode { CAUSE_LOW_BATTERY, CAUSE_SEARCH_ROUTE_OVER_DISTANCE, CAUSE_SEARCH_ROUTE, CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE, CAUSE_SEARCH_TRIP }`. [confirmed: jar:navi-data]
- Data fields: `RouteStateInfo { isRouting, routeOption, naviMode, termsAgreement, trafficStatus, … }`, `TBTInfo { type: TurnType, distance: Double, description }`, `CurrentLocationInfo { latitude, longitude, heading, address, routeId, currentRouteIndex }`. [confirmed: jar:navi-data]
- **Route start vs calculation complete:** `onRouteStarted` = "Only the search began; the route is set at onRoutingCompleted (NaviHelper 3.0.5)"; `onRouteEnded` is the deprecated name of `onRoutingCompleted` and does **not** mean navigation ended. [confirmed: routinegongbang `pleos/PleosNavigator.kt` lines 159–168, `pleos/RouteRequest.kt`] Official wording for these two events was not found; ordering on the emulator is Unverified.
- Replies are not correlated with requests: "One destination request at a time: bookmark and route answers are not correlated by the SDK." [confirmed: routinegongbang `pleos/PleosNavigator.kt` line 43]
- Calling `addWaypoint` / `changeRouteOption` when no route is active brings the navigation app forward with "경로를 얻지 못했습니다". [confirmed: mulmizero `README.md` line 159, `sdk/NaviBridge.kt` line 129]
- The helper only relays to the default navigation app: "Responses and events depend on that app's supported features and current state." [confirmed: sample-navi `README.md`]

### 3.9 TextToSpeech / SpeechToText

Pleos TTS (no contestant repo uses it — all four use Android `android.speech.tts.TextToSpeech`):

```kotlin
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode                 // SERVER, ON_DEVICE, HYBRID (default)
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener

val tts = TextToSpeech(applicationContext, Mode.ON_DEVICE)
tts.initialize(object : OnInitializeListener {
    override fun onSuccess() {}
    override fun onFailure(errorType: ErrorType) {}
})
val ev = object : EventListener {
    override fun onReady() = Unit
    override fun onStart() = Unit
    override fun onUpdatedRms(rms: Double) = Unit
    override fun onDone() = Unit
    override fun onStop() = Unit
    override fun onError(errorType: ErrorType) {}
}
tts.addEventListener(ev)
tts.setLocale(java.util.Locale.KOREA)
tts.speak("Hello. How can I help you?")           // speak(text, isFinal = …) for streaming chunks
tts.stop()
tts.removeEventListener(ev); tts.release()
```
[confirmed: docs:pleos-only/connect-sdk-pleos/TextToSpeech/intro, …/initialization-and-release, …/event-listeners, …/start-and-stop; jar:TextToSpeech `tts.TextToSpeech` (methods `initialize`, `addEventListener`, `removeEventListener`, `setLocale(Locale)`, `speak(String, boolean)`, `stop`, `release`, `registerApp`, `setAudioAttributes`, `setSpeakerType(String)`, `setAudioZoneId(int)`), package paths `tts.constant.*`, `tts.listener.*`]

- Permission: `pleos.car.permission.TTS_SERVICE`; without it callbacks report `PERMISSION_DENIED`. [confirmed: docs …/TextToSpeech/declaring-permissions]
- `ErrorType`: `AUDIO_ERROR, MIC_ERROR, SERVER_INTERNAL_ERROR, NETWORK_ERROR, INVALID_REQUEST_ERROR, TIMEOUT, UNAUTHENTICATED, PERMISSION_DENIED, ON_DEVICE_ENGINE_ERROR, UNKNOWN, UNAVAILABLE_LANGUAGE, NOT_SUPPORTED_API`. [confirmed: jar:TextToSpeech]
- **`SERVER` and `HYBRID` need app registration**: `tts.registerApp(clientID, clientSecret, OnServerConnectionListener { onConnected(); onFailed(ErrorType) })`, with the Client ID / Client Secret obtained when you "create My Project in Pleos Playground". `ON_DEVICE` needs no registration. [confirmed: docs …/TextToSpeech/app-registration]
- The AAR requires the device speech service (`ai.pleos.playground.caas`) at version ≥ 4.1.29 and merges `android.permission.INTERNET`. [confirmed: jar:TextToSpeech `AndroidManifest.xml`]
- Disagreement on availability: routinegongbang's README calls TTS/STT "아직 열리지 않은 SDK" (not open yet) and mulmizero's says it will add them "Pleos TTS/STT SDK가 공개되면" (once published); the artifacts are publicly downloadable and documented, and ChabakON runs STT on the emulator. [confirmed: routinegongbang `README.md` line 155; mulmizero `README.md` line 160; nexus; ChabakON `README.md` line 90]

Pleos STT (ran on the emulator in ChabakON):

```kotlin
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener

val stt = SpeechToText(context, Mode.ON_DEVICE)
stt.initialize(object : OnInitializeListener {
    override fun onSuccess() {}
    override fun onFailure(errorType: ErrorType) {}
})
stt.addListener(object : ResultListener {
    override fun onReady() {}
    override fun onStartedRecognition() {}
    override fun onUpdated(stt: String, completed: Boolean) {}
    override fun onUpdatedEpdData(on: Long, off: Long) {}
    override fun onUpdatedRms(rms: Double) {}
    override fun onEndedRecognition() {}
    override fun onError(errorType: ErrorType) {}
})
stt.setLocale(java.util.Locale.KOREA)
stt.request()                    // one recognition per request(); call again after onEndedRecognition
stt.stop()
// emulator workaround: stt.sendAudio(pcm16kMonoBytes); stt.completeAudioSend()
stt.release()
```
[confirmed: ChabakON `VoiceStop.kt`; jar:SpeechToText `stt.SpeechToText` — `initialize`, `addListener`, `removeListener`, `request`, `stop`, `sendAudio(byte[])`, `completeAudioSend`, `setLocale`, `registerApp`, `release`]

- Same `registerApp(clientID, clientSecret, listener)` exists on STT; that it is required for non-`ON_DEVICE` modes like TTS is [inferred].

### 3.10 Gleo AI integration

1. Ship `assets/schema.json` — "Must be named exactly `schema.json`" in the app's `assets/` directory. Gleo AI scans installed apps with `AssetManager` at boot and on install/uninstall. [confirmed: docs:pleos-only/app-integration-guide/guide/deliver-schema]
   ```json
   {
     "packageName": "com.example.lanefit",
     "appDescription": "…",
     "version": "1.0.0",
     "actionCalls": [
       { "type": "function",
         "function": {
           "name": "START_COACHING",
           "description": "…",
           "parameters": { "type": "object", "properties": { "level": { "type": "string", "enum": ["LOW","HIGH"], "description": "…" } }, "required": ["level"] },
           "component": "service" } }
     ]
   }
   ```
   - `actionCalls`: up to three. `function.name`: uppercase words joined by underscores. `component`: `"activity"` → Gleo calls `context.startActivity()`; `"service"` → Gleo calls `context.sendBroadcast()`. [confirmed: docs …/guide/app-capability; shape also in docs …/use-cases/radio-app and routinegongbang `app/src/main/assets/schema.json`]
2. Manifest: an `<intent-filter>` whose `<action android:name>` is the function name (plus `<category android:name="android.intent.category.DEFAULT"/>` for an activity). [confirmed: docs …/guide/deliver-schema] For `"service"` use an exported `<receiver>`. [confirmed: routinegongbang `app/src/main/AndroidManifest.xml` `GleoReceiver`]
3. Parameters: string extra `"ACTION_PARAM"` holding JSON — `intent.getStringExtra("ACTION_PARAM")` then `JSONObject(rawJson)`. [confirmed: docs …/guide/examples] routinegongbang unwraps one extra layer of quoting defensively. [confirmed: routinegongbang `gleo/GleoReceiver.kt`]
4. Result broadcast:
   ```kotlin
   Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").apply {
       putExtra("result", status)
       putExtra("packageName", packageName)
       putExtra("response", jsonObject.toString())
   }.also { sendBroadcast(it) }
   ```
   [confirmed: docs …/guide/examples]
   - `response` JSON: `functionName` (required), `status` (`"success" | "asking" | "fail" | "error"`, required), `message` (required, an English sentence), `mode` (`"voice"` default, `"beep"`, `"silent"`), `data` (optional, strongly recommended). [confirmed: docs …/guide/action_callback]
   - Disagreement on `data`: the callback-schema page defines it as "a string of a JSON array of objects" (and routinegongbang sends a string); the official Kotlin example puts a `JSONArray` object. The example also sends an extra `"result"` that the schema page does not mention; routinegongbang omits it. [confirmed: docs …/guide/action_callback vs …/guide/examples; routinegongbang `gleo/Gleo.kt`]
5. Timeout: "Gleo considers the Action timed out after 7 seconds and cancels it." [confirmed: docs …/guide/action_callback]
6. Going live: the schema is submitted to Pleos for review; "After approval, the integration will become available automatically"; contact `partnership@pleos.ai`. [confirmed: docs …/guide/deliver-schema]
7. Local test without Gleo: `adb shell am broadcast -n <pkg>/.gleo.GleoReceiver -a START_ROUTINE --es ACTION_PARAM '{"routineName":"…"}'`, adding `--user 10` on the multi-user emulator. routinegongbang's integration was tested only this way and never registered with Pleos. [confirmed: routinegongbang `README.md` line 108]

---

## 4. Emulator

### Getting it, and whether a login is needed

- System image is installed through Android Studio's SDK Manager from a public update-site XML:
  - Mac: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
  - Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
  [confirmed: docs:connect/guide/getting-started/application-development/setup-connect-sdk-emulator]
- **No login for the download.** Both XMLs return HTTP 200 anonymously; the archives they point to also answer anonymously (HEAD 200, ranged GET 206):
  - `…/connect-avd/2.x/crp_car_arm64_emulator.zip` — 4,669,762,816 bytes, SHA-1 `24d28eb2f18eba5e87c861a7eb2bdccebb049419`
  - `…/connect-avd/2.x/crp_car_emulator.zip` — 4,087,751,964 bytes, SHA-1 `b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa`
  [confirmed: nexus]
- Disagreement: the official FAQ says "After registering a developer account, you can test using our Emulator", and docs:pleos-only/getting-started calls it "The Pleos Connect Emulator download URL provided to you". The URL is nevertheless printed in the public docs and open. [confirmed: forum #23; docs getting-started]
- The package carries the 42dot `pleos-connect-sdk-license`, which must be accepted in the SDK Manager dialog. [confirmed: nexus `crp-arm64-sys-img.xml`]
- Package identity: `system-images;android-34;pleos_car_emulator;arm64-v8a` (or `;x86_64`), tag `android-automotive`, display "Pleos Connect v2.1.0 APIs", API 34, revision 2, requires Android Emulator ≥ 24. [confirmed: nexus XMLs]

### Intel vs Apple Silicon

- The docs list one "URL for Mac", and it is the **arm64-v8a** image. They make no Intel-Mac distinction. [confirmed: docs setup page; nexus XML `<abi>arm64-v8a</abi>`]
- The x86_64 image ("Intel x86_64 Atom System Image") is published only under the "Windows/Linux" URL; using that URL on an Intel Mac is [inferred].
- This Mac: Apple M3, arm64, Android Emulator 35.2.10 (arm64 binary) → use the Mac / arm64 URL. [confirmed: local `sysctl`, `emulator/source.properties`]

### Install and AVD steps (Android Studio)

1. Tools > SDK Manager > **SDK Update Sites** > **+** → Name `Pleos Connect System Image`, URL as above > OK.
2. **SDK Platforms** tab > tick **Show Package Details** > select the Pleos Connect system image under Android API 34 > OK > Finish > restart Android Studio.
3. Tools > Device Manager > Create device > Form Factor **Automotive** > **Pleos Connect v2.1.0**.
4. Advanced Settings: Default boot **Cold**, RAM **4 GB**; system image API 34 > **Pleos Connect v2.1.0 APIs** > Finish. Start the AVD from Device Manager.
- "If a previous version of Pleos Connect Emulator is installed, remove the existing version" first.
[confirmed: docs setup page]

- Cold boot is mandatory: "quick boot에서는 VHAL을 쓸 수 없음". [confirmed: mulmizero `README.md` line 76]
- Display 2560 × 1440. [confirmed: sample-vehicle `README.md`; mulmizero `README.md`]
- Services on the image: Vehicle Service 2.3.22 (package `ai.pleos.playground.service.vehicle`; SDK 2.3.2 needs ≥ 2.3.13), Navi Service 3.0.4. [confirmed: sample-vehicle `README.md`, `vehicle/RuntimeVersionProvider.kt`; jar:Vehicle AAR manifest; forum #29 comment]
- CLI-only route (no Android Studio): unpack the zip into `$ANDROID_HOME/system-images/android-34/pleos_car_emulator/arm64-v8a/`; in the AVD `config.ini` set `image.sysdir.1=system-images/android-34/pleos_car_emulator/arm64-v8a/`, `hw.lcd.width=2560`, `hw.lcd.height=1440`, `hw.lcd.density=160`, `hw.ramSize=4096`; run `emulator -avd <name> -no-snapshot`. [confirmed: mulmizero `README.md` line 77] The zip's top-level folder is `crp_avd_out_arm64-v8a/` (contains `system.img`, `vendor.img`, `source.properties`, `devices.xml`, …), so its **contents** go into `arm64-v8a/`. [confirmed: nexus — zip central directory]
- Install the app: `adb install -r -g <apk>` grants runtime permissions in one go. [confirmed: mulmizero `README.md` line 82]

### This machine [confirmed: local]

- No Android Studio in `/Applications`. SDK exists at `~/Library/Android/sdk` with `cmdline-tools/latest` (`sdkmanager`, `avdmanager`), `platform-tools` (adb 35.0.2), `build-tools` 34.0.0 and 35.0.0, `emulator`.
- `platforms/` holds only `android-34` → `compileSdk = 35` needs `platforms;android-35` installed first.
- JDK 17 present (Corretto 17.0.5, an x86_64 build running under Rosetta). No `gradle` on PATH, so a Gradle wrapper must come from Android Studio or a Gradle install.
- 23 GiB free; the image zip alone is 4.7 GB before unpacking.

### Virtual Console

- Minimum emulator version 2.1.0. After the emulator boots:
  ```bash
  adb forward tcp:8088 tcp:8088
  ```
  then open `http://127.0.0.1:8088/`. Re-run the forward if the page does not connect. [confirmed: docs:connect/guide/getting-started/application-development/use-vehicle-emulator]
- **8088 vs 18088:** the device port is always 8088. `adb forward tcp:18088 tcp:8088` is only mulmizero's choice of a free local port ("문서 기준은 8088. 로컬 8088이 사용 중이면 다른 포트로"). [confirmed: mulmizero `README.md` line 94, `tools/vc_heartbeat.sh`]
- Emulation Controls domains, official wording [confirmed: docs use-vehicle-emulator]:
  - **Vehicle** — vehicle notifications shown/hidden
  - **Motion** — "Sets gear, brake, steering wheel angle, and driving distance", steering-wheel heating
  - **ADAS** — "ready and active states of cruise control and the target speed"
  - **Convenience** — "doors, windows, trunk, seats, lights, and wipers", ETCS, glove box; seat occupancy is at Convenience > Seat Occupancy [confirmed: ChabakON `README.md` line 58]
  - **Safety** — "vehicle warning lights such as the parking brake, seat belt, and ABS to off, on, or blinking"
  - **Energy**, **Climate**
- Against the signals LaneFit needs:

  | Signal | Drivable from the console? |
  |---|---|
  | Gear | Yes — Motion [confirmed: docs; mulmizero, ChabakON] |
  | Steering angle | Yes — Motion [confirmed: docs; mulmizero] |
  | Brake | Yes — Motion [confirmed: docs; mulmizero, ChabakON] |
  | Seat occupancy | Yes — Convenience > Seat Occupancy [confirmed: ChabakON, mulmizero, routinegongbang] |
  | Turn signal | Not named anywhere; possibly under Convenience "lights" — **Unverified** |
  | Drive mode | Not named in any source — **Unverified** |
  | Vehicle speed | Not named in the docs; a developer says the console has an "ACCEL" item [confirmed: forum #33] — effect on `PERF_VEHICLE_SPEED` Unverified |
- The console applies values only while a browser session is alive ("콘솔은 브라우저 세션이 살아 있을 때만 값을 반영함"); keep the tab open. [confirmed: mulmizero `README.md` line 98]
- Undocumented REST endpoints used by mulmizero for scripted demos (POST, on the forwarded port): `/browser/session?client=<name>` once per second as heartbeat; `/vehicle/gear?gear=Park|Drive`; `/vehicle/controls?steering=<deg>`; `/vehicle/controls?brake=<n>`; `/vehicle/seat?kind=occupied&area=rear_left|rear_right|passenger&value=true|false`; `/vehicle/hvac?power=true` etc. [confirmed: mulmizero `tools/vc_heartbeat.sh`, `tools/live_demo.sh` — unofficial, may change]

---

## 5. Known issues

| # | Issue | Source |
|---|---|---|
| 1 | **Seat occupancy Boolean is inverted on emulator v2.1.0 with SDK 2.3.2**: occupied → `false`, vacant → `true`. Three teams found it independently; routinegongbang inverts only when `Build.HARDWARE` is `ranchu` or `goldfish` ("A real car is not known to share them"). Real-car polarity unknown. | [confirmed: mulmizero `sdk/VehicleBridge.kt` lines 471–474 `OCCUPANCY_BOOLEAN_INVERTED = true`; ChabakON `README.md` line 94; routinegongbang `README.md` line 96, `pleos/EmulatorCorrection.kt`, `service/DebugController.kt` line 69] |
| 2 | With several per-seat occupancy listeners, later changes reach only one of them; poll `getCurrentSeatOccupancy` (mulmizero: every 2 s). The listener callback carries no area id, so use one listener object per seat. | [confirmed: mulmizero `sdk/VehicleBridge.kt` lines 320–321] |
| 3 | Emulator default state has row 2 occupied. | [confirmed: ChabakON `README.md` line 63] |
| 4 | Concurrent capability checks go unanswered on the emulator; run them one at a time and retry a silent one once. | [confirmed: routinegongbang `pleos/PleosVehicle.kt` line 80] |
| 5 | A new listener does not always receive the current value; call the getter once right after registering. | [confirmed: routinegongbang `pleos/PleosShapes.kt` `subscribeWithRead`] |
| 6 | Quick boot leaves VHAL unusable; always cold boot. | [confirmed: mulmizero `README.md`] |
| 7 | `Odometer.getTripCurrentDrivingTime` returns `ERROR_NOT_SUPPORTED_PROPERTY` on the emulator. | [confirmed: mulmizero `README.md` lines 58, 158] |
| 8 | Official sample on the same AVD: Door Lock read rejected for `ROW_1_LEFT`; `setDoorPosition(1)` acknowledged but the follow-up read still returned 0. Writes acknowledge without changing state. | [confirmed: sample-vehicle `README.md`] |
| 9 | Vehicle control (`CONTROL_CAR_*`) needs Pleos App Market approval on real cars; the emulator grants it at install. Irrelevant to a read-only app. | [confirmed: mulmizero manifest comment and `README.md`] |
| 10 | Emulator navigation: route could not be computed and no TBT arrived (mulmizero); `requestRoute` works when a "home" bookmark exists (routinegongbang); the navigation app's position ignores `adb emu geo fix` and `geo nmea`, `remainTime`/`remainDistance` stay frozen, `isRouting` stays `false` during guidance; the background map does not render. Pleos has not answered. | [confirmed: mulmizero `README.md`; routinegongbang `README.md` line 151; forum #29 comment, #32, #34] |
| 11 | No Korean voice in the emulator's Android TTS and no Android speech-recognition engine; Pleos STT does not hear the host microphone, so ChabakON feeds PCM through `sendAudio()`. | [confirmed: mulmizero `README.md`; ChabakON `README.md` line 90] |
| 12 | The emulator is multi-user; `adb shell am …` may need `--user 10`. | [confirmed: routinegongbang `README.md` line 108] |
| 13 | Official sample pins `vehicleSdk = "2.3.1"`, which is not published (404). Its README also declares a known failing test: `PublicApiPolicyTest` "still pins the SDK `2.2.10` catalog to 441 methods, while the current `2.3.2` dependency exposes 442". | [confirmed: sample-vehicle `gradle/libs.versions.toml`, `README.md` line 382; nexus] |
| 14 | Orientation: "Connect S/L/W의 경우 Portrait로 실행되는것을 기준으로 하며 Landscape는 허용되지 않습니다", while the emulator display is 2560 × 1440 landscape. | [confirmed: forum #28; sample-vehicle `README.md`] |
| 15 | A forum user states only corporate developers are currently supported for accounts ("현재는 법인 개발자만 지원"); no official reply. | [confirmed: forum #31 — user claim] |
| 16 | `Gear` API in 2.3.2 is deprecated in favour of AAOS `GEAR_SELECTION`, and absent from the official sample catalog ("Gear is not part of this sample's catalog"); it still works on the emulator. | [confirmed: jar:Vehicle `api.Gear`; sample-vehicle `README.md`; mulmizero, ChabakON] |

---

## Unverified

Could not be confirmed from any source; do not treat as fact.

1. Whether the Virtual Console can drive **turn signal** and **drive mode**. Neither is named in the docs or any repo. The detailed "Pleos Connect Virtual Console User Guide" the docs refer to was not found (guessed URL returns 404).
2. Whether `TurnSignalStateListener` and `DrivingModeStateListener` ever fire on the emulator. Turn signal appears only in yeopjari26 (never run on the emulator); driving mode is bound in routinegongbang but no document shows a value changing.
3. Fallback if the console lacks them: `adb shell cmd car_service inject-vhal-event <propId> <value>` with AOSP `TURN_SIGNAL_STATE` (0x11400408; 0 none, 1 right, 2 left). Standard AAOS tooling [inferred]; not tried by anyone on the Pleos image, and the Pleos Vehicle Service may not read that property.
4. Steering angle sign convention and the emulator's reported bounds.
5. Whether `PERF_VEHICLE_SPEED` changes on the emulator (console "ACCEL" item), its unit as defined by Pleos, and whether third-party apps get `android.car.permission.CAR_SPEED` on real cars — all asked in forum #33, unanswered.
6. Protection levels of `android.car.permission.*` on the Pleos image; verify with `adb shell dumpsys package`.
7. What `pleos.car.permission.CAR_SPEED`, `READ_CAR_STEERING`, `READ_ADAS_STATES` actually gate. No SDK 2.3.2 method references them.
8. Whether `PRIVATE_CAR_SAFETY` (telltales: LKA, LFA, ELK, DRIVER_ATTENTION) is grantable to a contest or store app.
9. Pleos TextToSpeech on the emulator: whether `ON_DEVICE` mode has a Korean voice, and whether the emulator's speech service meets the 4.1.29 minimum.
10. Whether the Gleo AI app exists on the emulator and whether it invokes an app whose schema Pleos has not approved.
11. Seat occupancy polarity on a real vehicle.
12. The correct image for an Intel Mac.
13. Exact ordering and meaning of `onRouteStarted` / `onRoutingCompleted` as defined by Pleos (only a contestant's reading was found).
14. Whether `avdmanager create avd -k "system-images;android-34;pleos_car_emulator;arm64-v8a"` works after a manual unpack. mulmizero's `config.ini` edit is the only documented CLI route.
15. Names of the lambda parameters (`onComplete`, `onFailed`) for interfaces other than `Door`; the skeleton below passes lambdas positionally to avoid depending on them.

---

## Minimal skeleton

Assembled from the confirmed signatures above. **Not compiled or run here.** `com.example.lanefit` is a placeholder.

### settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}

rootProject.name = "LaneFit"
include(":app")
```
[confirmed: sample-vehicle `settings.gradle.kts`, name changed]

### build.gradle.kts (root), gradle.properties, wrapper

```kotlin
plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
```
```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```
```properties
# gradle/wrapper/gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
```
[confirmed: sample-vehicle `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`]

### app/build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.lanefit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.lanefit"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    useLibrary("android.car")          // CarPropertyManager for PERF_VEHICLE_SPEED
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // implementation("ai.pleos.playground:SpeechToText:3.3.1")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
```
[confirmed: versions and options from sample-vehicle `app/build.gradle.kts` + `libs.versions.toml`; `minSdk = 28` from docs; `useLibrary` from routinegongbang `app/build.gradle.kts`]

### AndroidManifest.xml (permission block)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.automotive" android:required="true" />

    <!-- Pleos Vehicle Service, read-only, install-time (protectionLevel normal) -->
    <uses-permission android:name="pleos.car.permission.CAR_DRIVE" />   <!-- steering angle, brake, driving mode, gear -->
    <uses-permission android:name="pleos.car.permission.CAR_LIGHTS" />  <!-- turn signal -->
    <uses-permission android:name="pleos.car.permission.CAR_SEATS" />   <!-- seat occupancy, seat belt -->
    <uses-permission android:name="pleos.car.permission.CAR_SAFETY" />  <!-- public safety warnings -->

    <!-- Declared by the official sample; no SDK 2.3.2 method needs them -->
    <uses-permission android:name="pleos.car.permission.CAR_SPEED" />
    <uses-permission android:name="pleos.car.permission.READ_CAR_STEERING" />
    <uses-permission android:name="pleos.car.permission.READ_ADAS_STATES" />

    <!-- AAOS Car API: speed is requested at runtime -->
    <uses-permission android:name="android.car.permission.CAR_SPEED" />
    <uses-permission android:name="android.car.permission.CAR_POWERTRAIN" />

    <!-- NaviHelper -->
    <uses-permission android:name="pleos.car.permission.NAVI_ROUTE" />
    <uses-permission android:name="pleos.car.permission.NAVI_ROUTE_SEARCH" />

    <!-- Only if Pleos TTS / STT are used -->
    <!-- <uses-permission android:name="pleos.car.permission.TTS_SERVICE" /> -->
    <!-- <uses-permission android:name="pleos.car.permission.STT_SERVICE" /> -->
    <!-- <uses-permission android:name="android.permission.RECORD_AUDIO" /> -->

    <application>
        <uses-library android:name="android.car" android:required="false" />
        <activity android:name=".MainActivity" android:exported="true">
            <meta-data android:name="distractionOptimized" android:value="true" />
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
[confirmed: permission-to-signal mapping from jar:Vehicle annotations; block shape from sample-vehicle, sample-navi, mulmizero, yeopjari26, routinegongbang manifests]

### Kotlin — one snippet per signal

```kotlin
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
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class VehicleSignals(private val context: Context) {
    private val tag = "LaneFit"
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null

    // ponytail: emulator v2.1.0 reports occupancy inverted; real-car polarity unverified, re-check on hardware
    private val isEmulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    private val passenger = SeatArea(SeatValue.ROW_1_RIGHT)
    private val fail: (Exception) -> Unit = { Log.w(tag, "vehicle call failed", it) }

    // --- turn signal: CAR_LIGHTS, TurnSignalValue OFF / RIGHT / LEFT
    private val turnL = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { Log.d(tag, "turn=$value") }
        override fun onFailed(exception: Exception) = fail(exception)
    }

    // --- steering wheel angle: CAR_DRIVE, Float degrees
    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) { Log.d(tag, "steer=$p0") }
        override fun onFailed(p0: Exception) = fail(p0)
    }

    // --- brake pedal: CAR_DRIVE, Boolean pressed
    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) { Log.d(tag, "brake=$p0") }
        override fun onFailed(p0: Exception) = fail(p0)
    }

    // --- driving mode: CAR_DRIVE, DrivingModeType NORMAL / ECO / SPORT
    private val modeL = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) { Log.d(tag, "mode=$state") }
        override fun onFailed(e: Exception) = fail(e)
    }

    // --- seat occupancy: CAR_SEATS, Boolean (inverted on the emulator)
    private val seatL = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(p0: Boolean?) { Log.d(tag, "passenger=${p0?.let { it != isEmulator }}") }
        override fun onFailed(p0: Exception) = fail(p0)
    }

    // --- vehicle speed: AAOS PERF_VEHICLE_SPEED, Float m/s, runtime permission android.car.permission.CAR_SPEED
    private val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status == CarPropertyValue.STATUS_AVAILABLE) Log.d(tag, "speed m/s=${value.value as? Float}")
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {}
    }

    fun start() {
        val v = Vehicle(context.applicationContext).also { it.initialize(); vehicle = it }
        // ponytail: fixed delay as mulmizero does; switch to a capability-call probe with retry if the first read is silent
        main.postDelayed({ subscribe(v) }, 1500)
        startSpeed()
    }

    private fun subscribe(v: Vehicle) {
        // register, then read once: the service does not always push the current value to a new listener
        v.getTurnSignal().registerTurnSignalState(turnL)
        v.getTurnSignal().getCurrentTurnSignalState({ turnL.onTurnSignalStateUpdated(it) }, fail)

        v.getSteeringWheel().registerSteeringWheelAngle(steerL)
        v.getSteeringWheel().getCurrentSteeringWheelAngle({ steerL.onSteeringWheelAngleUpdated(it) }, fail)

        v.getBrake().registerBrakePedalStatus(brakeL)
        v.getBrake().getCurrentBrakePedalStatus({ brakeL.onBrakePedalStatusUpdated(it) }, fail)

        v.getDrivingMode().registerDrivingModeState(modeL)
        v.getDrivingMode().getCurrentDrivingModeState({ modeL.onDrivingModeStateUpdated(it) }, fail)

        v.getSeat().registerSeatOccupancy(passenger, seatL)
        v.getSeat().getCurrentSeatOccupancy(passenger, { seatL.onSeatOccupancyUpdated(it) }, fail)
    }

    private fun startSpeed() {
        try {
            val c = Car.createCar(context).also { car = it }
            val p = (c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager).also { props = it } ?: return
            if (p.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) != null)
                p.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        } catch (e: SecurityException) {
            Log.w(tag, "CAR_SPEED not granted", e)          // request android.car.permission.CAR_SPEED first
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "speed property unavailable", e)
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        vehicle?.let { v ->
            runCatching { v.getTurnSignal().unregisterTurnSignalState(turnL) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(brakeL) }
            runCatching { v.getDrivingMode().unregisterDrivingModeState(modeL) }
            runCatching { v.getSeat().unregisterSeatOccupancy(passenger, seatL) }
            runCatching { v.release() }
        }
        vehicle = null
        runCatching { props?.unregisterCallback(speedCb) }
        runCatching { car?.disconnect() }
    }
}
```
[confirmed: every SDK call and override signature from jar:Vehicle; register-then-read from routinegongbang `pleos/PleosShapes.kt`; delay and `runCatching { release() }` from mulmizero `sdk/VehicleBridge.kt`; emulator detection from routinegongbang `service/DebugController.kt` line 69; Car API block from routinegongbang `aaos/AaosVehicleProperties.kt`. The class as a whole is [inferred] until it is built.]

Runtime request for speed, in the activity:

```kotlin
val askSpeed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) signals.start() }
askSpeed.launch("android.car.permission.CAR_SPEED")
```
[confirmed: same mechanism as sample-fused `MainActivity.kt` for `FUSED_LOCATION`; applying it to `CAR_SPEED` follows routinegongbang `README.md` line 79]

First things to check on the emulator, in order: (1) the Gradle sync resolves `Vehicle:2.3.2`; (2) cold-booted AVD, `adb forward tcp:8088 tcp:8088`, console tab open; (3) Motion > steering and brake produce log lines; (4) Convenience > Seat Occupancy toggles the passenger line; (5) look for a turn-signal and a drive-mode control — if absent, items 1–3 under Unverified are the open risk for the demo.
