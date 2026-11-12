# POWER J

여행 계획을 통째로 차에 담아 두면, 시동을 다시 걸 때마다 다음 장소로 알아서 이어 가는 Pleos Connect 앱입니다.
출근·등교 같은 루틴은 그 시간에 타면 먼저 제안하고, 비 소식·영업시간·모자란 주행 거리·경고등은 출발 전에 알려 줍니다.

- 출품: 현대자동차그룹 차량용 앱 개발 공모전 (Pleos App Market)
- APK: `apk/POWERJ_release.apk`

## 주요 기능

| 묶음 | 기능 | 확인 |
|---|---|---|
| ① 계획을 차에 심기 | 차가 배운 목적지 제안(함께 탄 사람·요일·시간·출발지) | 회귀 테스트 |
| | 루틴(출근·등교·학원 등): 그 시간 앞뒤 1시간 안에 출발하면 먼저 제안, 반복 주행에서 자동으로 찾은 루틴 | 회귀 테스트 |
| | 여행 계획 담기(일차·숙소), 시동을 다시 켜면 이어서 안내, 멀리 주차 시 도착 확인, 동선 자동 정렬 | 회귀 테스트 |
| | 출발 위치 지정, 되돌리기(최대 10번)·수정 취소, 늦어지면 내일로 미루기 | 회귀 테스트 |
| ② 출발 전 점검 | 일주일 날씨로 일정 조정·우산·실내 장소 추천 | 실제 API |
| | 오늘 일정 거리 대비 주행 가능 거리, 경고등 한 줄, 장소 마감·휴무 경고 | 회귀 테스트 |
| ③ 같이 가는 사람 | 탑승 추정(조수석 착석·뒷문 이력·뒷좌석 벨트 → 아이·부모님·배우자·연인·친구), 사람별 조수석 온도·뒷좌석 열선, 준비물, 아이 1시간 휴식 제안 | 회귀 테스트 |
| | 두 대 함께 가기: 6자리 공유 코드, 계획은 코드를 만든 차만 수정, 동행 차 위치, 5번 틀리면 1분 잠금 | 실제 API |
| ④ 가는 길 | 출발 브리핑(도착 시간·통행료·준비물·왕복 배터리), 도착 3분 전 안내 | 회귀 테스트 |
| | 장소 카드(영업시간·전화·주변 주차장 3곳), 담아 둔 곳 근처 경유 제안, 차들이 많이 간 곳 | 실제 API |
| | 가는 길의 가장 싼 주유소·빈 급속 충전기 경유, 여행 경비(통행료·충전량) 자동 기록 | 실제 API |

주행 중 사용 가능 앱(Distraction Optimized)으로 선언했습니다. 영상·애니메이션 없이 음성 위주로 안내하고, 화면 조작은 한 번 누르면 끝납니다.

## 실행 방법

Android Studio(Ladybug 이상)로 이 폴더를 열고 Run을 누르면 됩니다. 터미널에서는 Android Studio에 들어 있는 JDK 17을 씁니다(Settings › Build Tools › Gradle › Gradle JDK). JDK 24 이상이면 Gradle 8.10.2가 돌지 않습니다.

```bash
./gradlew :app:testDebugUnitTest      # 판단 로직 단위 테스트 (Windows: gradlew.bat)
./gradlew :app:installDebug           # 에뮬레이터 설치
./gradlew :app:assembleRelease        # 제출용 APK (아래 키와 서명 파일 필요)
```

### 다른 PC에서 빌드

저장소에 없는 파일 두 가지를 옮겨야 같은 APK가 나옵니다. 없으면 release 빌드는 무엇이 빠졌는지 알려 주고 멈춥니다(debug 빌드는 키 없이도 됩니다).

- `keys.properties` → 이 폴더
- `powerj-signing/` (`keystore.properties`, `powerj-release.jks`) → 이 폴더와 같은 위치(`../powerj-signing`)

처음 빌드할 때 Gradle과 라이브러리를 받으므로 `services.gradle.org`, `dl.google.com`, `repo.maven.apache.org`, `nexus-playground.pleos.ai`에 접속할 수 있어야 합니다. 한 번 받은 뒤에는 `--offline`으로 빌드됩니다.

에뮬레이터에서 Pleos 권한을 허용합니다(실차 배포 시에는 App Market 사전 승인 범위).

```bash
U=$(adb shell am get-current-user | tr -d '\r')
for p in pleos.car.permission.CAR_INFO pleos.car.permission.CAR_LIGHTS pleos.car.permission.PRIVATE_CAR_SAFETY \
         android.car.permission.CAR_SPEED pleos.car.permission.NAVI_ROUTE pleos.car.permission.NAVI_ROUTE_SEARCH; do
  adb shell pm grant --user $U com.beat.powerj $p
done
```

### API 키 (선택)

기능을 모두 보려면 키가 들어 있는 `apk/POWERJ_release.apk`를 설치하면 됩니다. 소스에서 빌드할 때는 키가 없어도 debug 빌드가 되고, 키가 필요한 기능(날씨·장소·주유소·충전소·함께 가기)만 꺼집니다.

모든 기능을 켜고 빌드하려면 개발자에게 `keys.properties`를 받아 이 폴더(README와 같은 위치)에 넣고 `./gradlew :app:installDebug`를 실행합니다. `keys.properties.example`을 `keys.properties`로 복사해 채웁니다(저장소에 올라가지 않음).

| 키 | 쓰는 곳 |
|---|---|
| `data_go_kr` | 공공데이터포털: 기상청 단기·중기예보, 한국관광공사 관광정보, 전국주차장정보표준데이터, 환경공단 전기차 충전소 |
| `firebase` | Realtime Database 주소: 두 대 함께 가기, 장소별 방문 횟수. 규칙은 `tools/firebase.rules.json` |
| `opinet`, `kakao` | 가는 길의 가장 싼 주유소(오피넷), 경로 좌표(카카오모빌리티) |

주차장 데이터(18,243곳)는 앱에 들어 있습니다. 갱신할 때만 키를 넣고 `python3 tools/fetch_parking.py`를 실행합니다.

제출 APK에는 시연용 무료 키(개발자 개인 명의, 결제 수단 미연결)가 들어 있으며 심사가 끝나면 폐기합니다.

### 시연 (제출 APK)

차 상태(기어 P·D, 문, 좌석, 벨트)는 에뮬레이터의 Virtual Console에서 바꿉니다. 맥이나 PC 브라우저에서 열려면 포트를 연결합니다.

```bash
adb forward tcp:8089 tcp:8088   # 그다음 브라우저에서 http://127.0.0.1:8089 (에뮬레이터가 여러 대면 -s emulator-5554처럼 지정)
```

예시 데이터는 앱 오른쪽 위 `···` → 시연 데이터 → 넣기 → [주행 기록] / [여행 계획]으로 넣고, [기록 모두 지우기]로 지웁니다.

### 회귀 테스트 (디버그 빌드)

스크립트가 디버그 빌드 전용 명령을 쓰므로 `./gradlew :app:installDebug`로 설치한 앱에서 돌립니다. 위처럼 Virtual Console을 8089로 연결하고, Pleos 권한을 허용한 뒤 실행합니다. zsh, curl, python3가 필요합니다(Windows는 WSL).

```bash
tools/regress.sh 5    # 등교·루틴·2일 여행 시나리오를 5번 반복, 63개 항목 확인
```

- 기본 대상은 `emulator-5556`입니다. 에뮬레이터를 하나만 켰다면 보통 `emulator-5554`이므로 `ANDROID_SERIAL=emulator-5554 tools/regress.sh 5`로 실행합니다(`adb devices`로 확인).
- 날씨·장소·충전·함께 가기 항목은 `keys.properties`가 있어야 통과합니다(동행 차는 `tools/companion.py`가 Firebase에 직접 써서 재현).
- 디버그 빌드에서는 `···` 메뉴에 테스트 항목(오늘 날짜, 시동, 기어, 속도, 도착, 조수석, 뒷좌석)이 더 보이고, `adb shell am start-foreground-service -n com.beat.powerj/.JService --es cmd seed`처럼 명령으로도 시연 데이터를 넣을 수 있습니다.

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (API 34, Automotive, arm64)
- 화면 규격: 2560 × 1440, 160 dpi (가로)
- Vehicle SDK 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1
- minSdk 28, targetSdk 34, Android Studio JBR 17
- 결과(2026-11-12): 회귀 테스트 63개 항목 × 5회 = 315개 모두 통과(실제 API 키), 단위 테스트 46개 통과

## 구조

```
app/src/main/java/com/beat/powerj/
├── Rules.kt        판단 로직(순수 함수): 탑승 추정, 계획 진행·도착 판정, 루틴, 준비물, 시간 경고, 왕복 배터리
├── JService.kt     포그라운드 서비스: Vehicle·NaviHelper·TTS 연결, 기어·내비 이벤트 처리
├── Live.kt         인터넷이 필요한 기능: 날씨, 장소 카드, 주유소·충전소, 함께 가기, 방문 횟수
├── Net.kt          공공 API·Firebase(REST) 호출과 응답 파싱
├── Store.kt        화면 상태, 기기 내 저장(SharedPreferences)
├── MainActivity.kt Pleos 기본 앱과 같은 구성의 화면(낮/밤 자동 전환, 주행 중 사용 가능)
└── Voice.kt        Pleos TTS
```

Pleos 차량에는 구글 플레이 서비스가 없어 Firebase는 라이브러리 대신 REST로 연결합니다.

## 개인정보

탑승자, 사람별 설정, 루틴, 주행 기록은 차 안에만 저장합니다. 두 대 함께 가기 중에만 계획과 위치를 동행 차와 주고받고, 방문 기록은 경로 없이 장소별 횟수만 익명으로 집계합니다.
