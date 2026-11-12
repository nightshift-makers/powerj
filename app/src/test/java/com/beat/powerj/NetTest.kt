package com.beat.powerj

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NetTest {
    @Test fun kmaGridMatchesOfficialExamples() {
        assertEquals(60 to 127, kmaGrid(37.5665, 126.9780)) // Seoul City Hall
        assertEquals(98 to 76, kmaGrid(35.1796, 129.0756))  // Busan City Hall
    }

    @Test fun kmaBaseUsesLatestIssue() {
        assertEquals("20261110" to "1400", kmaBase(LocalDateTime.of(2026, 11, 10, 14, 30)))
        assertEquals("20261109" to "2300", kmaBase(LocalDateTime.of(2026, 11, 10, 1, 0)))
        assertEquals("20261110" to "1100", kmaBase(LocalDateTime.of(2026, 11, 10, 14, 5)))
    }

    private fun items(vararg rows: String) = """{"response":{"body":{"items":{"item":[${rows.joinToString(",")}]}}}}"""
    private fun fc(d: String, t: String, c: String, v: String) = """{"fcstDate":"$d","fcstTime":"$t","category":"$c","fcstValue":"$v"}"""

    @Test fun shortForecastSplitsMorningAndAfternoon() {
        val w = parseShort(items(fc("20261114", "0900", "POP", "20"), fc("20261114", "1500", "POP", "70"), fc("20261114", "0600", "TMN", "3.0"), fc("20261114", "1500", "TMX", "12.0")))
        assertEquals(DayWeather(LocalDate.of(2026, 11, 14), 20, 70, 3, 12), w.single())
    }

    @Test fun midForecastFromDay4() {
        val w = parseMid(items("""{"rnSt4Am":10,"rnSt4Pm":60,"rnSt8":30}"""), LocalDate.of(2026, 11, 10))
        assertEquals(DayWeather(LocalDate.of(2026, 11, 14), 10, 60), w.first())
        assertEquals(DayWeather(LocalDate.of(2026, 11, 18), 30, 30), w.last())
        assertEquals("11H10000", midRegion(35.83, 129.21)) // Gyeongju -> Daegu/Gyeongbuk
    }

    @Test fun rainyDayMovesOutdoorStop() {
        val fri = LocalDate.of(2026, 11, 13) // day 1 = Friday
        val plan = listOf(Place("첨성대", 35.83, 129.21), Place("석굴암", 35.79, 129.34, day = 2), Place("국립경주박물관", 35.82, 129.22, day = 2))
        val weather = listOf(DayWeather(fri, 10, 10), DayWeather(fri.plusDays(1), 20, 70))
        val a = weatherAdvice(plan, 0, fri, weather).single()
        assertTrue(a, a.startsWith("토요일(2일차) 오후 비 70%예요. 우산 챙기세요.") && a.contains("석굴암은 맑은 금요일(1일차)로"))
        assertTrue(indoor("국립경주박물관") && !indoor("석굴암"))
    }

    @Test fun placeCardFieldsByType() {
        val food = parseIntro("교리김밥", items("""{"opentimefood":"08:30~17:00","restdatefood":"매주 수요일","infocenterfood":"054-772-5130"}"""), null)
        assertEquals(PlaceInfo("교리김밥", "08:30~17:00", "054-772-5130", "매주 수요일"), food)
        val museum = parseIntro("박물관", items("""{"usetimeculture":"10:00~18:00<br>입장 마감 17:00"}"""), "054-740-7500")
        assertEquals("10:00~18:00 입장 마감 17:00", museum.hours)
        assertEquals("054-740-7500", museum.phone)
        assertNull(parseIntro("없음", items("""{"usetime":""}"""), null).hours)
        assertEquals("054-746-9913", dialable("054-746-9913 (문의)"))
        assertEquals("1588-2504", dialable("한국도로공사 1588-2504"))
        assertNull(dialable("문의 없음"))
    }

    @Test fun sharedPlanRoundTripAndDiff() {
        val plan = listOf(Place("불국사", 35.79, 129.33), Place("숙소", 35.84, 129.28, stay = true), Place("석굴암", 35.79, 129.34, day = 2))
        val (back, done) = planOf(planJson(plan, 1, "car1"))!!
        assertEquals(plan, back); assertEquals(1, done)
        assertEquals("동행 차가 계획을 바꿨어요. 불국사 대신 황리단길이에요.", planDiff(plan, listOf(Place("황리단길", 0.0, 0.0)) + plan.drop(1)))
        assertNull(planDiff(plan, plan))
        assertEquals("동행 차는 3.2km 뒤", companionLine(3210.0, false))
        assertEquals("동행 차가 바로 옆에 있어요", companionLine(0.0, true)) // not "0m 뒤"
        assertEquals(6, shareCode().length)
        assertNull(planOf("null"))
    }

    @Test fun parkingNearStopPublicFirst() {
        val stop = Place("첨성대", 35.8347, 129.2190)
        val rows = listOf(
            "첨성대 민영주차장\t민영\t35.8350\t129.2195\t30\t2000\t10\t500\t0\t\t유료",
            "첨성대 공영주차장\t공영\t35.8360\t129.2200\t30\t1000\t10\t300\t10000\t054-779-6077\t유료",
            "먼 공영주차장\t공영\t35.9000\t129.3000\t0\t0\t0\t0\t0\t\t무료",
            "깨진 줄",
        ).mapNotNull(::parkingOf)
        val near = nearParking(rows, stop)
        assertEquals(listOf("첨성대 공영주차장", "첨성대 민영주차장"), near.map { it.name })
        assertEquals("공영 · 도보 3분 · 30분 1,000원, 10분당 300원, 하루 10,000원 · 054-779-6077", parkingLine(near[0], stop))
        assertTrue(parkingLine(near[1], stop).endsWith("전화번호 없음"))
    }

    @Test fun fuelOnTheWay() {
        val route = parseRoute("""{"routes":[{"sections":[{"roads":[{"vertexes":[127.10,37.40,127.10,37.50,127.10,37.60]}]}]}]}""")
        assertEquals(listOf(37.40 to 127.10, 37.50 to 127.10, 37.60 to 127.10), route)
        val pts = samplePoints(route, 30.0)
        assertEquals(4, pts.size) // every 5 km over 22 km of route
        assertEquals(37.40 + 0.1 * 5 / 11.1, pts[0].first, 0.01)
        // Opinet KATEC of 전당앞주유소 vs its Kakao map position (37.48051, 127.01208)
        val (sla, slo) = katecToWgs(312816.7694, 542484.5787)
        assertEquals(37.48051, sla, 0.0003); assertEquals(127.01208, slo, 0.0003)
        val (kx, ky) = katec(sla, slo)
        assertEquals(312816.8, kx, 1.0); assertEquals(542484.6, ky, 1.0)
        val st = parseOpinet("""{"RESULT":{"OIL":[{"OS_NM":"A주유소","PRICE":1650,"DISTANCE":1200.5,"GIS_X_COOR":312816.7694,"GIS_Y_COOR":542484.5787},{"OS_NM":"B주유소","PRICE":1598,"DISTANCE":800}]}}""", pts[0])
        val best = cheapest(st + Station("먼주유소", 1500, 0.0, 0.0, 400.0), 150)!!
        assertEquals("B주유소", best.name)
        assertTrue(fuelLine(150, best).contains("B주유소가 리터당 1,598원으로 가장 싸요"))
        val ch = parseChargers("""{"items":{"item":[{"statNm":"충전소1","stat":"2","output":"100","lat":"37.45","lng":"127.101"},{"statNm":"완속","stat":"2","output":"7","lat":"37.45","lng":"127.10"},{"statNm":"사용중","stat":"3","output":"100","lat":"37.45","lng":"127.10"}]}}""")
        assertEquals(listOf("충전소1"), ch.map { it.name })
        assertEquals("충전소1", chargerOnWay(ch, samplePoints(route, 30.0, 1.0), 100, 22.0)?.name)
        assertEquals("47", zcode(35.83, 129.21))
    }

    @Test fun routeLegSummary() {
        assertEquals(Leg(312.4, 9_600, 201), parseLeg("""{"routes":[{"result_code":0,"summary":{"distance":312400,"duration":12040,"fare":{"toll":9600,"taxi":0}}}]}"""))
        assertNull(parseLeg("""{"routes":[{"result_code":104,"result_msg":"출발지와 도착지가 너무 가까움"}]}"""))
    }

    @Test fun costEstimate() {
        assertEquals(Est(312.4, 9600, 21600, true, 201), est(Leg(312.4, 9600, 201), ev = true))
        assertEquals(31200, est(Leg(312.4, 9600, 0), ev = true)!!.total)
        assertEquals(16500, est(Leg(120.0, 0, 0), ev = false)!!.energy)
        assertNull(est(Leg(0.1, 0, 0), ev = true))
        assertEquals("1시간 25분", durationText(85)); assertEquals("2시간", durationText(120)); assertEquals("40분", durationText(40))
        assertEquals(7600, spentWon(7000, 2f))
    }

    @Test fun sharedTripKeepsNameAndDates() {
        val j = planJson(listOf(Place("A", 1.0, 2.0)), 0, "me", "경주 2일 여행", "2026-11-18", 2)
        assertEquals(TripInfo("경주 2일 여행", "2026-11-18", 2), tripInfo(j))
        assertEquals(TripInfo("", "", 0), tripInfo("""{"stops":[]}""")) // older senders
    }

    @Test fun tripDays() {
        val t = PlanCard(1, "", "2026-11-18", listOf(Place("A", 1.0, 2.0), Place("B", 1.0, 2.0, day = 2)), 0, false, 2)
        val d = { s: String -> java.time.LocalDate.parse(s) }
        assertEquals(1, tripWhen(t, d("2026-11-17")))
        assertEquals(0, tripWhen(t, d("2026-11-19")))
        assertEquals(-1, tripWhen(t, d("2026-11-20")))
        assertEquals(-1, tripWhen(t.copy(done = 2), d("2026-11-18"))) // every stop reached
    }

    @Test fun costPerDay() {
        val car = Place("현재 위치", 37.4, 127.1, day = 0)
        val pts = listOf(car, Place("A", 37.3, 128.0), Place("숙소", 37.3, 128.1, day = 1, stay = true), Place("B", 37.7, 128.9, day = 2), car)
        val legs = listOf(Leg(150.0, 9000, 100), Leg(5.0, 0, 10), Leg(100.0, 2000, 80), Leg(250.0, 11000, 170))
        val by = legsByDay(pts, legs)
        assertEquals(Leg(155.0, 9000, 110), by[1]) // drive there + to the stay
        assertEquals(Leg(100.0, 2000, 80), by[2]) // stay → B only
        assertEquals(Leg(250.0, 11000, 170), by[0]) // the drive home, shown on its own
    }

    @Test fun parkingForAStop() {
        val stop = Place("고미담", 37.3279, 127.9876, park = Place("센트럴파크 주차장", 37.3285, 127.9880))
        assertEquals(stop, placeOf(placeJson(stop)))
        assertEquals(listOf(stop), planOf(planJson(listOf(stop), 0, "me"))!!.first)
        assertNull(placeOf(placeJson(stop.copy(park = null))).park)
        assertTrue(stop.named("센트럴파크 주차장") && stop.named("고미담") && !stop.named("다른 곳") && !stop.named(null))
        assertEquals("센트럴파크 주차장", stop.target().name)
        assertTrue(reached(stop.copy(lat = 0.0, lon = 0.0), 37.3285, 127.9880)) // parked at the lot counts as arrived
        val pub = parkingOf("반곡동 쌈지주차장\t공영\t37.3290\t127.9877\t30\t0\t0\t0\t0\t\t무료")!!
        val lots = mergeLots(stop, listOf(pub), listOf(Place("쌈지 공영주차장", 37.3291, 127.9878), Place("센트럴파크 주차장", 37.3285, 127.9880)))
        assertEquals(listOf("센트럴파크 주차장", "반곡동 쌈지주차장"), lots.map { it.place.name }) // the Kakao copy of the public lot is dropped
        assertTrue(lots[1].line.startsWith("공영 · 도보"))
    }

    @Test fun placeSearchResults() {
        val h = parseKakaoPlaces("""{"documents":[{"place_name":"황남관","road_address_name":"경북 경주시 포석로 1038","address_name":"x","category_group_name":"숙박","y":"35.83","x":"129.20"},{"place_name":"좌표없음","y":"","x":""}]}""")
        assertEquals(1, h.size)
        assertEquals(Hit(Place("황남관", 35.83, 129.20), "경북 경주시 포석로 1038", "숙박"), h[0])
        assertTrue(h[0].lodging)
    }
}
