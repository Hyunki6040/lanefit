# LaneFit · 레인핏

방향지시등을 켜면 차로 간 예상 시간 차이를 한 문장으로 답하는 Pleos Connect 읽기 전용 차선 비서.

**개발용 프로토타입입니다. 실도로 운전 판단에 사용하지 마세요.** 주변 차량 간격이나 차선 변경 안전성을 판단하지 않습니다. 차로별 속도와 시작 차선은 예시 값이며, 위험 지수는 검증 전 휴리스틱입니다.

![Stay 안내 — 시뮬레이션](docs/design/screens/sim-stay.png)

## 실행

필요 환경: JDK 17, Android SDK Platform 34, Android SDK Build Tools, 라이선스를 확인하고 설치한 Pleos Connect Emulator v2.1.0. SDK 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am get-current-user
# 10은 현재 사용자 ID에 맞추세요.
adb shell am start --user 10 -n app.lanefit/.MainActivity
```

일반 Android 기기에서는 Pleos 서비스가 없어 차량 기능을 검증할 수 없습니다. 차속 권한을 허용해야 합니다. 차량 제어 권한은 요청하지 않습니다.

### 데이터 출처

주차 화면에서 선택합니다. 출처 변경 시 세션을 초기화합니다. 앱 종료 후 리포트는 보존되지 않습니다.

| 출처 | 차량 신호 | 차로 속도·시작 차선 |
| --- | --- | --- |
| 차량 신호 | Pleos SDK / AAOS | 연결되지 않음. 추천 없음 |
| 예시 도로 | Pleos SDK / AAOS | 4차로 예시 값 |
| 시뮬레이션 | 앱 내부 66초 시나리오 | 예시 값 |

시뮬레이션 직접 실행:

```sh
adb shell am start --user 10 -n app.lanefit/.MainActivity --ez demo true
```

Virtual Console 시연은 **에뮬레이터에서만** 실행합니다. 앱에서 `예시 도로` 선택 후:

```sh
adb forward tcp:8088 tcp:8088
python3 scripts/drive_emulator.py
```

스크립트는 기어·차속·방향지시등·조향·브레이크·착석 값을 바꿉니다. 실제 차량 연결 환경에서는 실행하지 마세요.

## APK

- 제출용 서명 APK: [apk/양주쀼_LaneFit_release.apk](apk/양주쀼_LaneFit_release.apk)
- 버전: `0.1.0`, application ID: `app.lanefit`
- SHA-256: `8b20f32d2c0ac608ab483b3ec1aeab48225fab5061a88a2e00dfec58d7fc7cc8`
- 서명 인증서 SHA-256: `60a5f94dbd4df79d2b9299127cba7ef9bd3eb8dbc3562dd81faed2f77605a852`
- 서명 키·비밀번호는 저장소에 포함하지 않습니다. `keystore.properties`가 없으면 로컬 release 빌드는 debug 키로 서명됩니다. 제출 APK와 동일한 서명이 아닙니다.
- debug APK와 제출 APK는 서명이 달라 덮어 설치할 수 없습니다. 기존 앱 삭제 시 앱 데이터가 삭제되므로 필요한 자료를 먼저 보관하세요.

## 검증 상태 · 2026-11-12

환경: Pleos Connect Emulator v2.1.0, Android 14 / API 34, 2560×1440, 160dpi. 앱 영역 1682×1210dp와 840×1184dp에서 확인했습니다.

| 항목 | 확인 결과 |
| --- | --- |
| JVM 단위 테스트 | 14개 통과 |
| Debug / Release 빌드 | 성공 |
| 제출 APK 서명 | apksigner 검증, 로컬 키스토어 인증서와 일치 |
| Debug APK 실행 | 에뮬레이터 설치·시연 확인 |
| Release APK 실행 | 미확인 |
| 차속·기어 | AAOS 수신. 화면 전환·거리 반영 |
| 방향지시등·조향 | Vehicle SDK 수신. 카드·차선 변경 감지 |
| 브레이크 | Vehicle SDK 수신. 변경 직후 급감속 코칭 확인 |
| 조수석 착석 | Vehicle SDK 수신. Calm 전환 확인 |
| 주행 모드 | 에뮬레이터 미제공. NORMAL 기본값 |
| Pleos TTS | 초기화 성공. 실제 발화 청취 미확인 |
| 주야간 전환 | 화면·세션 유지 확인 |
| 테스트 커버리지 | 미측정. 80% 달성 주장 없음 |

TypeScript·정적 라우트·Cloudflare Pages 검증은 해당 없음: 네이티브 Android 앱입니다.

### 알려진 한계

- 실도로 백테스트·실차 검증 미실행. 차로 속도 피드, 경로 연동, 자동 기준점 입력 미구현.
- 조향 부호·착석 값 방향과 차량 권한은 실차에서 재검증해야 합니다.
- 주차 판정은 기어 P 우선, 기어 미제공 시 정지 3초 대체 방식입니다. CarUxRestrictions 연동 전에는 실제 차량용 주행 제한을 충족했다고 볼 수 없습니다.
- 앱이 화면 뒤로 가면 추적이 중단됩니다. 복귀 시 비관측 구간의 거리를 더하지 않지만 그 구간의 차선 변화는 알 수 없습니다.
- 최소 창 628×855dp, TalkBack, 큰 글꼴 배율 미검증.
- 연속 변경의 위험 지수 집계와 센서 단절 시 오래된 값 처리에는 추가 검증이 필요합니다.

## 자료

- [디자인 적용값·출처·캡처](docs/design/DESIGN.md)
- [프로젝트 소개 본문](docs/submission/proposal.md)
- [백테스트 상태](docs/research/backtest.md)
- [SDK 조사 기록](docs/research/pleos-sdk.md)
- [변경 이력](CHANGELOG.md)

Pleos 공식 디자인 가이드를 기준으로 구현했습니다. 참고용 클론은 제3자 추출 저장소이며 공식 배포물이 아닙니다. 공식 Figma와 일부 토큰의 원본 대조는 남아 있습니다. 공식 인증·전체 준수를 주장하지 않습니다.

참가신청서·개인정보·동의·서명·이메일 초안은 `private/`에만 보관하며 공개 저장소에서 제외합니다. 최종 제출과 이메일 발송은 아직 하지 않았습니다.
