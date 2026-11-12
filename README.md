# POWER J

"아빠, 어디 가?" 이제 차가 대답합니다. 오늘 일정이든 2박 3일 여행이든, 계획을 아는 차가 먼저 챙기는 Pleos Connect 앱입니다.

- 출품: 현대자동차그룹 차량용 앱 개발 공모전 (Pleos App Market)
- APK: `apk/POWERJ_release.apk`

## 주요 기능

| 묶음 | 기능 | 상태 |
|---|---|---|
| 계획을 차에 심기 | 일차별 계획 담기(숙소·다음 날), 동선 정리, 시동을 다시 켜면 이어서 안내, 멀리 주차 시 도착 확인, 늦으면 내일로 미루기 | 구현·회귀 테스트 |
| 출발 전 점검 | 오늘 일정 거리 대비 주행 가능 거리, 경고등 한 줄, 장소 시간 경고 | 구현·회귀 테스트 |
| | 일주일 날씨로 일정 조정·우산 | 구현·실제 API 확인 |
| 같이 가는 사람 | 탑승 추정(아이·부모님·배우자·연인·친구), 사람별 온도·열선, 준비물, 아이 1시간 휴식 제안 | 구현·회귀 테스트 |
| | 두 대 함께 가기(4자리 코드, 계획 변경·위치 공유) | 구현·실제 API 확인 |
| 가는 길 | 출발 브리핑, 도착 3분 전 안내, 가 볼 곳 근처 알림·경유지, 여행 차량비 | 구현·회귀 테스트 |
| | 장소 카드(영업시간·전화·주변 주차장), 차들이 많이 간 곳, 가장 싼 주유소·빈 급속 충전기 경유 | 구현·실제 API 확인 |

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

키가 없어도 빌드되고, 키가 필요한 기능만 꺼집니다. `keys.properties.example`을 `keys.properties`로 복사해 채웁니다(저장소에 올라가지 않음).

| 키 | 쓰는 곳 |
|---|---|
| `data_go_kr` | 공공데이터포털: 기상청 단기·중기예보, 한국관광공사 관광정보, 전국주차장정보표준데이터 |
| `firebase` | Realtime Database 주소: 두 대 함께 가기, 장소별 방문 횟수. 규칙은 `tools/firebase.rules.json` |
| `opinet`, `kakao` | 가는 길의 가장 싼 주유소(오피넷), 경로 좌표(카카오모빌리티) |

주차장 데이터는 키를 넣은 뒤 `python3 tools/fetch_parking.py`로 한 번 받아 앱에 넣습니다.

### 시연 / 회귀 테스트

Virtual Console을 `adb forward tcp:8089 tcp:8088`로 연결한 뒤:

```bash
tools/regress.sh 5    # 등교 + 2일 여행 시나리오를 5번 반복, 39개 항목 확인 (날씨·장소·충전·공유는 keys.properties 필요)
```

시연 기록은 화면 오른쪽 위 `···` → 시연 기록 넣기, 또는 디버그 빌드에서
`adb shell am start-foreground-service -n com.beat.powerj/.JService --es cmd seed`.

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (API 34, Automotive)
- Vehicle SDK 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1
- Android Studio JBR 17

## 구조

```
app/src/main/java/com/beat/powerj/
├── Rules.kt        판단 로직(순수 함수): 탑승 추정, 계획 진행·도착 판정, 준비물, 시간 경고, 왕복 배터리
├── JService.kt     포그라운드 서비스: Vehicle·NaviHelper·TTS 연결
├── Store.kt        화면 상태, 기기 내 저장(SharedPreferences)
├── MainActivity.kt Pleos 기본 앱과 같은 구성의 화면(주행 중 사용 가능)
└── Voice.kt        Pleos TTS
```

## 개인정보

주행 기록과 사람별 설정은 차 안에만 저장하며 외부로 보내지 않습니다.
