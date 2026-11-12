#!/bin/zsh
# End-to-end regression on the Pleos emulator via the Virtual Console.
# Usage: tools/regress.sh [runs]   (needs: adb forward tcp:8089 tcp:8088 on emulator-5556)
set -u
RUNS=${1:-1}
# adb from $ADB, then PATH, then the SDK in local.properties (works on any OS with Android Studio)
ADB=${ADB:-$(command -v adb || echo "$(sed -n 's/^sdk.dir=//p' "${0:A:h}/../local.properties")/platform-tools/adb")}
a() { $ADB -s ${ANDROID_SERIAL:-emulator-5556} "$@"; }
B=http://127.0.0.1:8089
put() { curl -s -X PUT "$B$1" -o /dev/null -w "%{http_code}"; }
U=$(a shell am get-current-user | tr -d '\r')
APP=com.beat.powerj/.MainActivity
log() { a logcat -d -s POWERJ | cut -c33- ; }
TOTAL_FAIL=0

nocheck() { # name, pattern that must NOT appear
  if log | grep -qE "$2"; then echo "  FAIL $1  (unexpected /$2/)"; FAIL=$((FAIL+1)); else echo "  PASS $1"; fi
}

check() { # name, pattern
  if log | grep -qE "$2"; then echo "  PASS $1"; else echo "  FAIL $1  (want /$2/)"; FAIL=$((FAIL+1)); fi
}

# keep the console session alive
( while true; do curl -s -X POST "$B/browser/session?client=regress" -o /dev/null; sleep 1; done ) &
HB=$!
trap "kill $HB 2>/dev/null" EXIT

for pm in pleos.car.permission.CAR_INFO pleos.car.permission.CAR_LIGHTS pleos.car.permission.PRIVATE_CAR_SAFETY android.car.permission.CAR_SPEED pleos.car.permission.NAVI_ROUTE pleos.car.permission.NAVI_ROUTE_SEARCH; do
  a shell pm grant --user $U com.beat.powerj $pm >/dev/null 2>&1
done

for run in $(seq 1 $RUNS); do
  FAIL=0
  echo "=== run $run"
  # reset car
  put "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0" >/dev/null
  for t in EngineOilPressure TPMS; do put "/vehicle/telltales/simulator?type=$t&state=Off&color=Red" >/dev/null; done
  put "/vehicle/gear?gear=Park" >/dev/null
  for d in door_fl door_fr door_rl door_rr; do put "/vehicle/body?$d=false" >/dev/null; done
  a shell am force-stop --user $U com.beat.powerj
  # the emulator navi can get stuck mid-cancel ("cancelTrip is already working") and then keeps an old route; restart it
  a shell am force-stop --user $U ai.umos.maps.android.navigation.app
  a shell monkey -p ai.umos.maps.android.navigation.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8 # back up, so the app gets a position again
  a logcat -c
  a shell am start --user $U -n $APP >/dev/null; sleep 6
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd reset >/dev/null; sleep 2
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd testday --es n 0 >/dev/null; sleep 1 # a run cut short may have left the day shifted
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd clear >/dev/null; sleep 1
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd seed >/dev/null; sleep 4
  check "seed history" "seeded demo history"

  # 1. rear passenger boards (no rear occupancy sensor on Hyundai cars → door history)
  put "/vehicle/body?door_rr=true" >/dev/null; sleep 1.5; put "/vehicle/body?door_rr=false" >/dev/null; sleep 1
  check "rear door seen" "rear door REAR_RIGHT opened"

  # 2. depart
  put "/vehicle/gear?gear=Drive" >/dev/null; sleep 4
  check "seat inferred" "depart seats=\[REAR_RIGHT\]"
  check "child recognised" "who=\{REAR_RIGHT=CHILD\}"
  check "destination predicted" "suggestion=Place\(name=판교초등학교"
  check "greeting spoken" "say: 아이 함께 타셨네요. 평소처럼 판교초등학교"
  check "rear heat applied" "REAR_RIGHT heat 2"
  RR=$(curl -s $B/vehicle/state | python3 -c "import json,sys; print(json.load(sys.stdin)['hvac']['row2']['right_seat_temp'])")
  [[ $RR == 2 ]] && echo "  PASS console rear seat heat = 2" || { echo "  FAIL console rear seat heat = $RR"; FAIL=$((FAIL+1)); }

  # 3. accept suggestion → navigation route → briefing
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd go >/dev/null; sleep 9
  check "briefing spoken" "say: 판교초등학교까지 [0-9]+분이에요"
  check "checklist for school + child" "아이 가방·준비물, 아이 물·간식"
  check "round-trip battery" "왕복해도 [0-9]+% 남아요|돌아올 때 배터리"
  check "destination kind" "route kind=SCHOOL"
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd soon >/dev/null; sleep 3
  check "pre-arrival line" "say: 곧 판교초등학교에 도착해요. 아이 가방 챙겼는지"

  # 4. arrive
  put "/vehicle/gear?gear=Park" >/dev/null; sleep 3
  check "trip saved with destination" "arrive dest=Place\(name=판교초등학교"

  # 4b. routines: three school runs are found by themselves; a registered routine is offered on the next departure
  check "routine found automatically" "found routines \[등교:판교초등학교:3\]"
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd routine --es name 학원 --es dest 판교수학학원 --es lat 37.3950 --es lon 127.1100 >/dev/null; sleep 2
  check "routine saved" "routine saved 학원 매일"
  put "/vehicle/body?door_rr=true" >/dev/null; sleep 1.5; put "/vehicle/body?door_rr=false" >/dev/null; sleep 1
  put "/vehicle/gear?gear=Drive" >/dev/null; sleep 4
  check "routine offered on departure" "routine=학원"
  check "routine greeting" "say: 아이 함께 타셨네요. 학원 시간이에요. 판교수학학원 가시죠"
  put "/vehicle/gear?gear=Park" >/dev/null; sleep 3
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd rmroutines >/dev/null; sleep 1

  # 5. two-day trip plan: departure check (distance vs range, warning light), then guidance resumes
  svc() { a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd $1 >/dev/null; sleep ${2:-1}; }
  svcx() { a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd "$@" >/dev/null; sleep 2; } # with extras
  svc plan
  check "plan set" "demo plan set"
  put "/vehicle/telltales/simulator?type=TPMS&state=On&color=Yellow" >/dev/null; sleep 2
  put "/vehicle/gear?gear=Drive" >/dev/null; sleep 9
  check "day check: distance vs range" "day check km=[0-9]+ range=[0-9]+ line=오늘 일정"
  check "day check: warning light" "say: .*타이어 공기압 경고등이 켜져 있어요"
  check "plan resumes on departure" "plan resume 1/6 불국사"
  put "/vehicle/telltales/simulator?type=TPMS&state=Off&color=Yellow" >/dev/null

  # 6. running late -> move the stop to tomorrow, guidance moves on
  svc late
  check "late offer" "late offer 불국사"
  svc postpone 4
  check "postponed to day 2" "plan postponed 불국사 -> day 2"
  check "next stop guided" "plan resume 1/6 첨성대"

  # 7. order the route: stay stays last in day 1, day 2 after it
  svc order
  check "route ordered" "plan ordered \[1:[^,]+, 1:[^,]+, 1:보문관광단지, 2:"

  # 8. saved "maybe" place near the route -> offered once -> added as waypoint
  svc wish 12
  check "wish offered while driving" "wish near 판교 카페거리"
  svc visit 2
  check "wish added as waypoint" "wish waypoint 판교 카페거리"

  # 9. a child aboard for an hour -> rest suggestion (demo trigger)
  svc break
  check "rest suggestion" "say: 아이와 1시간 달렸어요"

  # 10. reach stop 1, engine off, app killed, engine on -> continues at stop 2
  svc reach
  check "plan advances" "plan reached [^ ]+ next="
  put "/vehicle/gear?gear=Park" >/dev/null; sleep 2
  a shell am force-stop --user $U com.beat.powerj
  a shell am start --user $U -n $APP >/dev/null; sleep 10
  put "/vehicle/gear?gear=Drive" >/dev/null; sleep 8
  check "plan survives restart" "plan resume 2/6|routing=true planNext=첨성대" # navi may still be guiding to stop 2: then no new route is needed

  # 11. park 2+ km from stop 2 (emulator GPS stays put) -> app asks "도착했나요?" -> yes advances
  put "/vehicle/gear?gear=Park" >/dev/null; sleep 4
  check "asks when parked away from the stop" "ask arrived "
  svc arrived
  check "confirmed arrival advances" "plan reached [^ ]+ next=보문관광단지"

  # 12. drive to the stay, reach it, park: no question about tomorrow's first stop; next morning starts day 2
  put "/vehicle/gear?gear=Drive" >/dev/null; sleep 8
  check "guided to the stay" "say: 오늘 일정 끝났어요. 숙소 보문관광단지로"
  svc reach
  check "stay reached" "plan reached 보문관광단지"
  a logcat -c
  put "/vehicle/gear?gear=Park" >/dev/null; sleep 4
  nocheck "no arrival question at the stay" "ask arrived"
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd testday --es n 1 >/dev/null; sleep 2 # the next morning: day 2's places only start on day 2
  put "/vehicle/gear?gear=Drive" >/dev/null; sleep 10
  check "day 2 starts" "say: 여행 2일차예요"
  put "/vehicle/gear?gear=Park" >/dev/null; sleep 2
  a shell am start-foreground-service --user $U -n com.beat.powerj/.JService --es cmd testday --es n 0 >/dev/null; sleep 1

  # 13. live data (needs keys.properties): weather advice, place card + parking, charger on the way
  svc rain 3
  check "weather advice with umbrella" "weather tips=\[.*우산 챙기세요"
  svc card 8
  check "place card with hours, phone, parking" "place card [^ ]+ hours=.+ phone=.+ lots=[1-9]"
  svc fuel 40
  log | grep -qE "fuel stop ev=true range=[0-9]+ best=Station" || svc fuel 40 # live charger API: one retry when it is slow
  check "fast charger on the way" "fuel stop ev=true range=[0-9]+ best=Station"

  # 14. shared trip (Firebase REST). a) this car is host: another car's plan write is ignored
  svc share 4
  CODE=$(log | grep -o "share code [0-9]*" | tail -1 | awk '{print $3}')
  python3 tools/companion.py change "$CODE" >/dev/null; sleep 22
  nocheck "host ignores other car's plan" "share applied"
  svc modify 1; svcx move --es i 1 --es dir 1; sleep 3 # host edits: companions see nothing until 저장
  BEFORE=$(python3 tools/companion.py get "$CODE")
  svc save 4
  AFTER=$(python3 tools/companion.py get "$CODE")
  [[ $BEFORE != "$AFTER" ]] && echo "  PASS host edit sent only on save" || { echo "  FAIL host edit sent only on save ($BEFORE)"; FAIL=$((FAIL+1)); }
  svcx activate --es id 900; sleep 3 # host switches away from the shared trip: sharing ends and the code is removed
  check "host ends share on trip switch" "share ended $CODE"
  [[ $(python3 tools/companion.py get "$CODE") == none ]] && echo "  PASS shared trip removed" || { echo "  FAIL shared trip removed"; FAIL=$((FAIL+1)); }
  python3 tools/companion.py delete "$CODE" >/dev/null
  svc clear 1; svc plan 3
  # b) this car joins car B's code: gets the plan read-only, sees car B's progress; a wrong code is refused
  BAD=$(printf "88%04d" $((RANDOM % 10000)))
  svcx join --es code "$BAD"; sleep 4
  check "wrong code refused" "say: $(echo $BAD | sed 's/./& /g;s/ $//') 코드를 찾을 수 없어요"
  JOIN=$(printf "99%04d" $((RANDOM % 10000)))
  python3 tools/companion.py create "$JOIN" >/dev/null
  svcx join --es code "$JOIN"; sleep 12
  check "own trip kept on join" "join keeps own trip 경주 2일 여행"
  check "guest got host plan" "share applied \[불국사, 첨성대, 황리단길\]"
  check "trip name shared" "share info title=친구들과 경주"
  check "progress bars" "cars 내 차=[0-9]+%, 코드 만든 차=50%"
  svcx move --es i 0 --es dir 1
  check "guest edit blocked" "guest edit blocked"
  check "block read out" "say: 함께 가기 중에는 코드를 만든 차만"
  python3 tools/companion.py change "$JOIN" >/dev/null; sleep 22
  check "host change applied" "share applied .*대릉원"
  check "change read out" "say: 동행 차가 계획을 바꿨어요. 첨성대 대신 대릉원이에요"
  check "companion position" "companion 동행 차는 [0-9.]+(km|m) (앞|뒤)"
  python3 tools/companion.py delete "$JOIN" >/dev/null; sleep 22
  check "guest leaves when host ends" "say: 동행 차가 함께 가기를 끝냈어요"
  svc clear 1; svc plan 3 # leave the shared trip; demo plan brings back the saved trips section 15 uses

  # 15. trip list: switch to the saved Gangneung trip, then make a new empty one
  svcx activate --es id 900; sleep 2
  check "switch to another saved trip" "trip activated 900 강릉 당일치기"
  check "switch read out" "say: 강릉 당일치기로 바꿨어요. .* 출발이에요. 그날 시동을 걸면 정동진부터"
  svc newtrip 2
  svcx plus --es day 1 --es q "'경주 황리단길'"; sleep 4
  check "plus: place search" "search '경주 황리단길' -> [1-9]"
  svc pick 2
  check "plus: added to day 1" "plus added .*황리단길.* day=1"
  svc save 1
  check "new trip saved" "trip saved"
  svc addday 1
  svcx drop --es i 0 --es day 2
  check "drag a stop to another day" "plan dropped .*황리단길 -> \[2:황리단길"
  svcx delete --es id 900
  svcx dates --es start 2026-12-01 --es days 1
  check "calendar range shortens trip" "plan dates 2026-12-01 x1 lost=1"
  check "delete a saved trip" "trip deleted 900 강릉 당일치기"
  svc clear 1

  svc reset 2 # drop any route left in the navigation app so the next run starts clean
  echo "  run $run: $FAIL failed"
  TOTAL_FAIL=$((TOTAL_FAIL+FAIL))
done
echo "=== total failed checks: $TOTAL_FAIL over $RUNS runs"
exit $(( TOTAL_FAIL > 0 ))
