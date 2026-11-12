# LaneFit 개발·실행 문서

서비스 소개는 [README](../README.md)에 있습니다. 이 문서는 빌드, 실행, 데이터 출처, 권한, 코드 구조, 테스트, APK를 다룹니다.

LaneFit은 차로별 속도 정보만 안내합니다. 옆 차와의 간격이나 차선 변경의 안전 여부는 판단하지 않으며 운전자가 직접 확인합니다. 차량 제어 권한은 사용하지 않습니다.

## 요구 사항

| 항목 | 값 |
| --- | --- |
| JDK | 17 |
| Android SDK | Platform 34, Build Tools (`compileSdk 34`, `minSdk 29`, `targetSdk 34`) |
| 빌드 도구 | Gradle 8.8 (저장소의 `./gradlew`), Android Gradle Plugin 8.5.2, Kotlin 2.0.20 |
| 실행 환경 | Pleos Connect Emulator v2.1.0 (Android 14, API 34) |
| Pleos SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (인증 없이 내려받습니다) |
| Python | 3 (`scripts/lane_profile.py`, `scripts/drive_emulator.py`는 표준 라이브러리만 사용합니다) |

첫 빌드에서 SDK 의존성을 내려받으므로 인터넷 연결이 필요합니다.

앱이 사용하는 Pleos SDK는 다섯 가지입니다.

| SDK | 버전 | 용도 |
| --- | --- | --- |
| Vehicle | 2.3.2 | 방향지시등, 조향각, 브레이크 페달, 주행 모드, 좌석 착석 |
| TextToSpeech | 3.3.1 | 한 문장 음성 안내 |
| NaviHelper | 3.0.5 | 경로의 다음 기동 방향과 거리 |
| FusedLocation | 1.1.3 | 도로 구간 매칭, 위치 기반 차속 |
| SpeechToText | 3.3.1 | 음성 질문과 차로 지정 |

차속과 기어는 AAOS `CarPropertyManager`(`PERF_VEHICLE_SPEED`, `GEAR_SELECTION`)에서 읽습니다.

## 빌드

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

이 명령 하나가 빌드 검증입니다. JVM 단위 테스트를 실행하고 debug·release APK를 만듭니다.

| 산출물 | 경로 |
| --- | --- |
| debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| release APK | `app/build/outputs/apk/release/app-release.apk` |

## 실행

Pleos Connect Emulator v2.1.0을 켠 뒤 설치하고 실행합니다.

```sh
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am get-current-user
# 10은 위 명령이 알려 준 현재 사용자 ID에 맞춥니다.
adb shell am start --user 10 -n app.lanefit/.MainActivity
```

`-g` 옵션은 매니페스트의 런타임 권한을 설치할 때 함께 허용합니다.

### 차량 신호 넣기

에뮬레이터의 Virtual Console HTTP API로 기어·차속·방향지시등·조향각·브레이크·착석 값을 넣습니다. 값은 Pleos SDK와 AAOS를 거쳐 앱에 도달합니다.

```sh
adb forward tcp:8088 tcp:8088
python3 scripts/drive_emulator.py
```

`scripts/drive_emulator.py`는 한 번의 주행을 재생합니다. 60km/h 주행, 왼쪽 방향지시등, 왼쪽으로 한 차로 이동, 변경 직후 급감속, 조수석 착석, 정차와 P 순서입니다. `--shots DIR`을 주면 단계마다 화면을 `DIR`에 저장합니다. 이 스크립트는 에뮬레이터의 차량 값을 바꾸는 용도입니다.

### 위치 옮기기

`adb emu geo fix <lon> <lat>`로 위치를 옮깁니다. 경도가 먼저입니다. 앱은 1초 이상 간격으로 들어온 두 위치에서 진행 방향을 계산하므로, 구간에 들어가려면 진행 방향으로 위치를 이어서 보냅니다. 위치가 10초 넘게 갱신되지 않으면 구간 매칭을 해제합니다. 구간에 들어서면 주차 화면의 `데이터 출처` 카드와 주행 화면의 알약에 구간 이름이 표시됩니다.

아래는 경부선 신갈JC→서울TG 구간을 북쪽으로 시속 약 80km로 지나는 예입니다.

```sh
for i in $(seq 0 60); do
  adb emu geo fix 127.10365 "$(awk "BEGIN { printf \"%.5f\", 37.29000 + 0.0002 * $i }")"
  sleep 1
done
```

### debug 빌드 전용 실행 옵션

debug 빌드는 실행 인텐트의 extra 두 가지를 읽습니다. release 빌드는 이 값을 무시합니다. extra는 앱이 새로 시작될 때 읽으므로 실행 중인 앱은 먼저 종료합니다.

```sh
adb shell am force-stop --user 10 app.lanefit
# 66초 시뮬레이션으로 바로 시작합니다.
adb shell am start --user 10 -n app.lanefit/.MainActivity --ez demo true
```

```sh
adb shell am force-stop --user 10 app.lanefit
# 차로 프로필 구간에 들어섰을 때의 시작 차로를 3차로로 지정합니다.
adb shell am start --user 10 -n app.lanefit/.MainActivity --ei lane 3
```

`--ei lane N`은 데이터 출처가 `차량 신호`이고 위치가 차로 프로필 구간에 들어왔을 때 한 번 적용됩니다. `N`은 그 구간의 차로 수 이하여야 합니다.

## 데이터 출처

주차 화면의 `데이터 출처`에서 고릅니다. 기본값은 `차량 신호`입니다. 출처를 바꾸면 새 주행 세션을 시작하며 추천 모드와 동승자 Calm 설정은 이어집니다.

| 출처 | 차량 신호 | 차로 속도·내 차로 | 화면 표시 |
| --- | --- | --- | --- |
| 차량 신호 | Pleos SDK, AAOS | 앱에 내장한 차로 속도 프로필. 위치가 프로필 구간 안일 때 그 시각의 칸을 불러옵니다. 구간 밖에서는 안내하지 않습니다 | 구간 안에서는 주행 화면 알약에 구간 이름(예: "경부선 신갈JC→서울TG")을 표시합니다. 구간 밖에서는 알약이 없습니다 |
| 예시 도로 | Pleos SDK, AAOS | 4차로 예시 속도(1차로부터 100·92·80·60km/h), 시작 차로 4차로 | 주행·주차 화면 알약 "예시 도로 데이터", 리포트에 "예시 도로 데이터 기준이며 실제 측정값이 아니에요" |
| 시뮬레이션 | 앱 안의 66초 시나리오 | 시나리오에 포함된 예시 값 | 주행·주차 화면 알약 "시뮬레이션", 리포트에 "시뮬레이션 기준이며 실제 측정값이 아니에요" |

주차 화면의 출처 카드에는 다음 설명이 나옵니다.

| 출처 | 설명 문구 |
| --- | --- |
| 차량 신호 (구간 안) | "차량 신호와 위치에 한국도로공사 공개 자료로 만든 차로별 속도를 더해요. 지금 경부선 신갈JC→서울TG 구간이에요." |
| 차량 신호 (구간 밖) | "차량 신호와 위치에 한국도로공사 공개 자료로 만든 차로별 속도를 더해요. 차로 자료가 있는 구간에 들어서면 알려드려요." |
| 예시 도로 | "차량 신호에 예시로 넣은 4차로 속도를 더해요. 실제 도로 데이터가 아니에요." |
| 시뮬레이션 | "미리 만든 66초 주행을 재생해요. 측정값이 아니에요." |

시뮬레이션은 실제 차량이 P가 아니거나 1km/h 이상으로 움직이면 바로 `차량 신호`로 돌아갑니다.

`차량 신호` 출처에서 내 차로는 세 가지 기준점으로 정해집니다.

- 경로의 진입로를 지나 본선에 합류한 직후에는 맨 오른쪽 차로로 잡습니다.
- 음성으로 "지금 3차로야"라고 말하면 그 차로로 맞춥니다.
- debug 빌드에서는 `--ei lane N`으로 지정합니다.

기준점 사이에서는 조향각과 차속으로 차선 변경을 추적합니다.

## 권한

`app/src/main/AndroidManifest.xml`에 선언한 권한 10개입니다. 모두 읽기 전용이며 `CONTROL_CAR_*` 권한은 선언하지 않습니다. 앱이 실행 중에 요청하는 권한은 아래 3개입니다.

| 권한 | 용도 | 요청 방식 |
| --- | --- | --- |
| `pleos.car.permission.CAR_LIGHTS` | 방향지시등 | 매니페스트 선언 |
| `pleos.car.permission.CAR_DRIVE` | 조향각, 브레이크 페달, 주행 모드 | 매니페스트 선언 |
| `pleos.car.permission.CAR_SEATS` | 조수석·뒷좌석 착석 | 매니페스트 선언 |
| `pleos.car.permission.NAVI_ROUTE` | 경로의 다음 기동 방향과 거리 | 매니페스트 선언 |
| `pleos.car.permission.TTS_SERVICE` | 음성 안내 | 매니페스트 선언 |
| `pleos.car.permission.STT_SERVICE` | 음성 질문 인식 | 매니페스트 선언 |
| `android.car.permission.CAR_POWERTRAIN` | 기어 (P에서 주차 화면 표시) | 매니페스트 선언 |
| `android.car.permission.CAR_SPEED` | 차속 | 런타임 요청 · 앱 시작 시 |
| `pleos.car.permission.FUSED_LOCATION` | 도로 구간 매칭, 위치 기반 차속 | 런타임 요청 · 앱 시작 시 |
| `android.permission.RECORD_AUDIO` | 음성 질문용 마이크 | 런타임 요청 · 주차 화면의 `음성으로 묻기` 스위치를 켤 때 |

런타임 권한을 허용하지 않으면 주차 화면의 `차량 신호 점검`에서 해당 항목이 "권한 없음"으로 표시됩니다. 경로·위치·음성 입력을 쓸 수 없을 때에도 앱은 계속 동작하며 해당 항목의 상태가 `차량 신호 점검`에 표시됩니다.

## 코드 구조

소스는 `app/src/main/java/app/lanefit/`에 있습니다.

```text
app/lanefit/
├── engine/              순수 Kotlin. Android 의존성이 없어 JVM 테스트로 실행합니다
│   ├── Recommender.kt       모드와 유지·변경 판단
│   ├── LaneEstimator.kt     내 차선 추정
│   ├── LaneChangeRisk.kt    변경 후 위험 지수
│   ├── RoadProfile.kt       구간 매칭과 차로 속도 프로필
│   └── Inputs.kt            경로 기동 해석, 음성 문장 해석
├── VehicleSignals.kt    차량 신호 어댑터 (Pleos Vehicle, AAOS)
├── CarFeeds.kt          경로·위치·음성 질문 어댑터 (NaviHelper, FusedLocation, SpeechToText)
├── DriveSession.kt      신호를 화면 상태·음성 문장·리포트로 바꿉니다
├── DemoScenario.kt      66초 시뮬레이션 시나리오
├── MainActivity.kt      어댑터와 세션을 연결합니다. 권한 요청, TextToSpeech
└── ui/
    ├── Pleos.kt             색 토큰, 타입 스케일
    └── LaneFitApp.kt        주행 화면, 주차 화면
```

### engine

| 파일 | 역할 |
| --- | --- |
| `Recommender.kt` | `Mode`는 Calm 180초·10km당 1회, Flow 120초·2회, Rush 60초·3회입니다. `modeFor`는 차량 주행 모드(ECO→Calm, NORMAL→Flow, SPORT→Rush)와 동승자 여부로 모드를 정하고 직전 변경의 위험 지수가 높음이면 한 단계 차분한 모드를 씁니다. `advise`는 옆 차로의 10km 예상 이득이 모드 기준 이상이고, 마지막 변경 뒤 60초가 지났고, 10km 안의 변경 안내 횟수가 상한 미만일 때만 변경을 안내합니다. 그 밖에는 유지로 답합니다. 차선 신뢰도가 0.6 미만이거나, 비교할 차로 속도가 없거나, 경로 때문에 켠 방향지시등이면 답하지 않습니다 |
| `LaneEstimator.kt` | 기준점에서 차로를 정하고 그 사이는 조향각과 차속으로 횡이동을 적분해 차선 변경을 셉니다. 주행 중에는 10km/h 이상에서 추적합니다. 변경마다 방향지시등 선행 시간과 최대 조향 속도를 함께 기록합니다 |
| `LaneChangeRisk.kt` | `scoreLaneChange`가 0~100점을 냅니다. 방향지시등 선행 거리의 기준은 고속도로 100m, 일반도로 30m입니다. 30점 미만은 낮음, 60점 미만은 보통, 그 이상은 높음입니다 |
| `RoadProfile.kt` | `RoadProfile.parse`가 `lane_profile.txt`를 읽습니다. `RoadSection.contains`는 구간 직선에서 좌우 200m 이내, 진행 방향 40도 이내일 때 구간 안으로 봅니다. `RoadMatcher`는 내 차로 속도의 1분 이동평균과 프로필의 비율(0.4~1.3배)로 모든 차로를 현재 수준에 맞춥니다. `FixTracker`는 연속한 위치에서 진행 방향과 속도를 계산합니다 |
| `Inputs.kt` | `RouteWatch`는 다음 기동이 1,000m 이내이면 그쪽 방향지시등을 경로용으로 보고 진입로 통과를 합류로, 진출로 통과를 고속도로 이탈로 판단합니다. `parseAsk`는 "지금 바꿀까?", "왼쪽으로 갈까?" 같은 질문과 "지금 3차로야" 같은 차로 지정을 구분합니다 |

### 어댑터

| 파일 | 역할 |
| --- | --- |
| `VehicleSignals.kt` | 방향지시등·조향각·브레이크 페달·주행 모드는 Pleos Vehicle SDK의 리스너로, 좌석 착석(조수석, 뒷좌석 왼쪽·가운데·오른쪽)은 2초 주기 조회로 읽습니다. 차속과 기어는 AAOS `CarPropertyManager`로 읽습니다. 차량이 차속을 보고하지 않으면 위치 기반 차속을 대신 씁니다. 신호별 마지막 값을 주차 화면의 `차량 신호 점검`에 제공합니다 |
| `CarFeeds.kt` | `NaviFeed`는 2초마다 다음 기동을 조회합니다. `LocationFeed`는 위치와 방위를 전달합니다. `VoiceAsk`는 스위치가 켜져 있고 앱이 화면에 표시되는 동안 한 문장씩 인식하며 앱이 음성 안내를 하는 동안에는 듣기를 멈춥니다 |

### 세션과 화면

- `DriveSession.kt`는 50ms마다 받은 신호 표본으로 화면 상태(`UiState`)를 만듭니다. 방향지시등을 켜면 그쪽 차로를 평가해 카드와 음성으로 답합니다. 10km/h 미만에서 켠 방향지시등은 차가 움직이기 시작하면 한 번 답합니다. 카드는 8초 동안 표시됩니다. 차선 변경 3초 뒤에 위험 지수 카드를 표시합니다.
- `MainActivity.kt`는 `차량 신호` 출처에서 1초마다 구간, 차로 속도, 경로 정보를 세션에 전달합니다. 15분 칸과 평일·주말 구분은 한국 표준시 기준입니다.
- 주차 화면은 기어 P에서 표시됩니다. 차량이 기어를 보고하지 않으면 3초 정지를 주차로 봅니다.
- `ui/LaneFitApp.kt`의 화면 구성과 문구는 [디자인 스펙](design/DESIGN.md)에 있습니다.

### 내장 자료

`app/src/main/assets/lane_profile.txt`가 차로 속도 프로필입니다. `S|` 줄이 구간(ID, 이름, 시작·끝 좌표, 차로 수, 버스전용차로), `W|` 줄이 평일, `E|` 줄이 주말의 15분 칸별 차로 속도(km/h)입니다. 값이 빈 칸의 차로는 안내 대상에서 제외합니다.

## 테스트

JVM 단위 테스트 27개가 있습니다.

| 파일 | 개수 | 대상 |
| --- | --- | --- |
| `app/src/test/java/app/lanefit/engine/EngineTest.kt` | 16 | 추천, 차선 추정, 위험 지수, 구간 매칭과 프로필, 경로·음성 입력 해석 |
| `app/src/test/java/app/lanefit/DriveSessionTest.kt` | 11 | 66초 시나리오 전체 흐름, 표본 간격과 잘못된 입력 처리, 모드 고정, 정지 중 방향지시등, 10km 상한, 음성 질문, 진입로 합류, 합류 뒤 차선 변경, 표본 공백과 구간 이탈 |

```sh
./gradlew :app:testDebugUnitTest
```

결과 보고서는 `app/build/reports/tests/testDebugUnitTest/index.html`에 생성됩니다.

## 차로 속도 프로필 다시 만들기

```sh
python3 -I scripts/lane_profile.py fetch
python3 -I scripts/lane_profile.py build
```

- `fetch`는 한국도로공사 고속도로 공공데이터 포털의 AVC 차로별 15분 집계 자료를 `data/raw/ex-avc-lane-15min/`에 받습니다. 포털 문서에 실린 공개 샘플 키를 사용하며 요청 사이에 1초를 쉽니다. 이미 받은 파일은 건너뜁니다.
- `build`는 `app/src/main/assets/lane_profile.txt`와 `docs/research/lane-profile-backtest.json`을 새로 씁니다.
- `scripts/lane_profile.py`의 `SCALE`과 `engine/RoadProfile.kt`의 `MIN_SCALE`·`MAX_SCALE`은 같은 값(0.4, 1.3)으로 유지합니다.

방법과 결과는 [데이터 분석과 백테스트](research/backtest.md)에 있습니다.

## APK

- 서명된 release APK: [apk/양주쀼_LaneFit_release.apk](../apk/양주쀼_LaneFit_release.apk)
- 버전: `0.2.0` (versionCode 2), application ID: `app.lanefit`
- SHA-256: `962d93df67bbe14e9b525883cb9f9dcfeaa03eeecc0748e146b23426796ffdf1`
- 서명 인증서 SHA-256: `60a5f94dbd4df79d2b9299127cba7ef9bd3eb8dbc3562dd81faed2f77605a852`
- 서명 키와 비밀번호는 저장소에 포함하지 않습니다. `keystore.properties`가 없으면 로컬 release 빌드는 debug 키로 서명됩니다.
- debug APK와 서명된 release APK는 서명이 달라 서로 덮어 설치되지 않습니다. 바꿔 설치할 때는 `adb uninstall app.lanefit`으로 먼저 삭제합니다.

```sh
shasum -a 256 apk/*.apk
adb install -r -g apk/*.apk
```

## 지원 범위

- 실행 환경은 Pleos Connect(Android Automotive, API 34)이며 Pleos Connect Emulator v2.1.0에서 실행합니다.
- 차로 속도 프로필은 경부선 4개 구간을 지원합니다: 기흥IC→수원신갈IC, 수원신갈IC→기흥IC, 신갈JC→서울TG, 서울TG→신갈JC. 각 구간은 검지 지점 앞뒤 1.5km이고 5차로입니다.
- 프로필은 평일·주말별, 15분 칸 96개, 차로별 속도로 구성됩니다. 1차로(버스전용차로)와 자료가 없는 칸은 안내하지 않습니다.
- 프로필 구간 밖에서는 `차량 신호` 출처가 변경을 안내하지 않으며 차선 변경 감지와 위험 지수, 리포트가 동작합니다. `예시 도로` 출처는 위치와 관계없이 동작합니다.
- 위험 지수의 방향지시등 선행 거리 기준은 고속도로 100m, 일반도로 30m입니다. 프로필 구간은 고속도로로, 그 밖에서는 경로의 진출입 통과 여부로 판단하며 둘 다 없으면 80km/h 이상의 변경을 고속도로 기준으로 채점합니다.
- 음성 질문은 한국어를 지원합니다. 변경 질문("지금 바꿀까?", "왼쪽으로 갈까?")과 차로 지정("지금 3차로야")을 인식합니다. 기본 조작은 방향지시등입니다.
- 동승자 Calm은 조수석과 뒷좌석 왼쪽·가운데·오른쪽 착석을 읽습니다.
- 차선 추적과 음성 질문은 앱이 화면에 표시되는 동안 동작합니다.
- 화면은 주간·야간 테마와 가로·세로 윈도우를 지원합니다. 폭 1000dp를 기준으로 배치를 바꿉니다.
- 주행 리포트는 주행 세션 단위로 집계합니다.

## 자료

- [디자인 스펙](design/DESIGN.md)
- [프로젝트 소개 본문](submission/proposal.md)
- [데이터 분석과 백테스트](research/backtest.md)
- [Pleos SDK 조사 기록](research/pleos-sdk.md)
- [변경 이력](../CHANGELOG.md)
