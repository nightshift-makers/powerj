package com.beat.powerj

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private val home = cell(37.4127, 127.0946)
    private val school = Place("OO초등학교", 37.40, 127.10)

    private fun trip(seats: Set<Seat>, who: Map<Seat, Who> = emptyMap(), hour: Int = 8, dest: Place? = school) =
        Trip(seats, who, weekend = false, hour = hour, startCell = home, dest = dest)

    @Test fun rearSeatComesFromDoorOrBelt() {
        assertEquals(setOf(Seat.PASSENGER, Seat.REAR_RIGHT), occupiedSeats(true, setOf(Seat.REAR_RIGHT), emptySet()))
        assertEquals(setOf(Seat.REAR_LEFT), occupiedSeats(false, emptySet(), setOf(Seat.REAR_LEFT)))
        assertTrue(occupiedSeats(false, emptySet(), emptySet()).isEmpty())
    }

    @Test fun whoNeedsTwoConfirmations() {
        val kid = mapOf(Seat.REAR_RIGHT to Who.CHILD)
        assertNull(guessWho(listOf(trip(setOf(Seat.REAR_RIGHT), kid)), Seat.REAR_RIGHT, false, 8, home))
        val two = List(2) { trip(setOf(Seat.REAR_RIGHT), kid) }
        assertEquals(Who.CHILD, guessWho(two, Seat.REAR_RIGHT, false, 8, home))
        assertNull(guessWho(two, Seat.REAR_RIGHT, false, 15, home)) // different time
        assertNull(guessWho(two, Seat.REAR_RIGHT, true, 8, home))   // weekend
    }

    @Test fun destinationNeedsSameCompany() {
        val t = List(3) { trip(setOf(Seat.REAR_RIGHT)) }
        assertEquals(school, predictDest(t, setOf(Seat.REAR_RIGHT), false, 8, home))
        assertNull(predictDest(t, emptySet(), false, 8, home))
        val split = t + List(3) { trip(setOf(Seat.REAR_RIGHT), dest = Place("회사", 0.0, 0.0)) } + trip(setOf(Seat.REAR_RIGHT), dest = Place("카페", 0.0, 0.0))
        assertNull(predictDest(split, setOf(Seat.REAR_RIGHT), false, 8, home)) // no majority
    }

    @Test fun checklistFromDestinationAndPeople() {
        assertEquals(listOf("여권·신분증", "탑승권"), checklist("인천국제공항 터미널1", emptyList()))
        assertTrue("승차권 시간" in checklist("서울역 KTX", emptyList()))
        assertEquals(listOf("신분증", "진료 예약 시간", "부모님 복용 약"), checklist("분당서울대병원", listOf(Who.PARENT)))
        assertEquals(listOf("아이 물·간식"), checklist("스타필드", listOf(Who.CHILD)))
    }

    @Test fun redStopsYellowWaits() {
        assertEquals(Level.STOP, triage("ENGINE_OIL_PRESSURE", 4)!!.level)
        assertEquals(Level.SOON, triage("TPMS", 3)!!.level)
        assertNull(triage("HEADLAMP_LOW", 1))
        assertNull(triage("TPMS", 1))
    }

    @Test fun roundTripFromArrivalEnergy() {
        // 77.4 kWh at 100%, navigation says 74,329 Wh left on arrival
        val (arrive, back) = roundTrip(77400f, 100, 74329)!!
        assertEquals(96, arrive)
        assertEquals(92, back)
        assertEquals(0, roundTrip(77400f, 20, 2000)!!.second)
        assertTrue(energyLine(10, 3).contains("충전소"))
        assertNull(roundTrip(0f, 50, 100))
    }

    @Test fun destinationKindChangesTheBriefing() {
        assertEquals(Kind.AIRPORT, kindOf("인천국제공항 터미널1"))
        assertEquals(Kind.SCHOOL, kindOf("판교초등학교"))
        assertNull(kindOf("스타필드"))
        // clinic at 17:40 on a weekday → closing warning; at 10:00 → nothing
        assertTrue(timeNote(Kind.HOSPITAL, 17 * 60 + 40, false, false, 3)!!.contains("17시 40분"))
        assertNull(timeNote(Kind.HOSPITAL, 10 * 60, false, false, 3))
        assertTrue(timeNote(Kind.HOSPITAL, 13 * 60, true, false, 3)!!.contains("13시에"))
        // marts: 2nd/4th Sunday holiday, late arrival
        assertTrue(martHoliday(9, true) && martHoliday(23, true))
        assertFalse(martHoliday(2, true) || martHoliday(9, false))
        assertTrue(timeNote(Kind.MART, 11 * 60, false, true, 9)!!.contains("의무휴업"))
        assertTrue(timeNote(Kind.MART, 21 * 60 + 45, false, false, 3)!!.contains("22시"))
        assertNull(timeNote(null, 23 * 60, false, false, 3))
    }

    @Test fun tripProgressCountsStopsAndTheCurrentLeg() {
        val plan = listOf(Place("A", 35.0, 129.0), Place("B", 35.0, 129.1), Place("C", 35.0, 129.2), Place("D", 35.0, 129.3))
        assertEquals(0f, tripProgress(emptyList(), 0, null, null))
        assertEquals(0f, tripProgress(plan, 0, 35.0, 129.05))           // first leg isn't counted
        assertEquals(0.25f, tripProgress(plan, 1, null, null))          // no position: stops only
        assertEquals(0.375f, tripProgress(plan, 1, 35.0, 129.15), 0.01f) // halfway B -> C
        assertEquals(0.25f, tripProgress(plan, 1, 35.0, 128.9))         // behind B: leg clamps to 0
        assertEquals(1f, tripProgress(plan, 4, 35.0, 129.3))
    }

    @Test fun planAdvancesOnlyAtTheNextStop() {
        val bulguksa = Place("불국사", 35.7900, 129.3320)
        val plan = listOf(bulguksa, Place("첨성대", 35.8347, 129.2190))
        assertEquals(bulguksa, nextStop(plan, 0))
        assertNull(nextStop(plan, 2))
        assertTrue(reached(bulguksa, 35.7920, 129.3330))   // parking lot ~240 m away
        assertFalse(reached(bulguksa, 35.8347, 129.2190))  // lunch in town, ~11 km away
        assertEquals(11_000.0, meters(bulguksa, 35.8347, 129.2190), 1_000.0)
    }

    @Test fun soonLineAddsFamilyTouch() {
        val s = soonLine("인천공항", Kind.AIRPORT, listOf(Who.CHILD), 50)
        assertTrue(s.startsWith("곧 인천공항에 도착해요.") && s.contains("주차 구역") && s.contains("깨워"))
        assertFalse(soonLine("판교초등학교", Kind.SCHOOL, listOf(Who.CHILD), 25).contains("깨워"))
        assertEquals("곧 집에 도착해요.", soonLine("집", null, emptyList(), 10))
    }

    @Test fun briefingReadsNaturally() {
        assertEquals("병원까지 20분이에요. 미리 전화해 보세요. 신분증 챙기셨나요?", briefing("병원", 20, 0, listOf("신분증"), null, "미리 전화해 보세요."))
        assertEquals("서울역까지 43분이에요. 통행료는 700원. 승차권 시간 챙기셨나요?", briefing("서울역", 43, 700, listOf("승차권 시간"), null))
    }

    private val bulguksa = Place("불국사", 35.7900, 129.3320)
    private val cheom = Place("첨성대", 35.8347, 129.2190)
    private val hwang = Place("황리단길", 35.8380, 129.2105)
    private val hotel = Place("숙소", 35.8400, 129.2860, stay = true)

    @Test fun dayOrderIsNearestFirstAndStayLast() {
        // from Hwangnidan-gil side: Cheomseongdae is next door, Bulguksa is far
        val o = orderDay(listOf(hotel, bulguksa, hwang, cheom), Place("시내", 35.8370, 129.2120))
        assertEquals(listOf("황리단길", "첨성대", "불국사", "숙소"), o.map { it.name })
    }

    @Test fun planOrderKeepsDoneStopsAndDays() {
        val plan = listOf(bulguksa, hwang, cheom, hotel, bulguksa.copy(name = "석굴암", day = 2))
        val o = orderPlan(plan, 1, null)
        assertEquals(listOf("불국사", "첨성대", "황리단길", "숙소", "석굴암"), o.map { it.name })
        assertTrue(newDay(o, 0) && !newDay(o, 1) && newDay(o, 4))
    }

    @Test fun postponeMovesToNextDay() {
        val plan = listOf(bulguksa, cheom, hotel, hwang.copy(day = 2))
        val p = postpone(plan, 1)
        assertEquals(listOf("불국사", "숙소", "첨성대", "황리단길"), p.map { it.name })
        assertEquals(2, p[2].day)
    }

    @Test fun dayCheckComparesRange() {
        val km = todayKm(listOf(bulguksa, cheom), 0, 37.39, 127.11) // from Pangyo: ~300km
        assertTrue(km in 250..400)
        assertTrue(dayCheck(km, 150, emptyList())!!.contains("필요해요"))
        assertTrue(dayCheck(km, 500, listOf("타이어 공기압"))!!.let { it.contains("충분") && it.contains("타이어 공기압 경고등") })
        assertNull(dayCheck(0, 0, emptyList()))
        assertEquals(387, rangeKm(0f, 77400f, 100))
    }

    @Test fun lateStopsCanMoveToTomorrow() {
        assertNotNull(lateLine(cheom, 20 * 60 + 40, null))
        assertNull(lateLine(cheom, 14 * 60, null))
        assertNull(lateLine(hotel, 22 * 60, null))
    }

    @Test fun wishAndCost() {
        val cafe = Place("판교 카페거리", 37.3945, 127.1080)
        assertEquals(cafe, nearWish(listOf(cafe), 37.4200, 127.1000, emptySet()))
        assertNull(nearWish(listOf(cafe), 37.4200, 127.1000, setOf("판교 카페거리")))
        assertNull(nearWish(listOf(cafe), 35.8, 129.2, emptySet()))
        assertEquals("통행료 8,400원, 충전 약 21,000원, 1대당 14,700원", costLine(8400, 60.6f, cars = 2))
        assertNull(costLine(0, 0f))
    }

    @Test fun particlesFollowTheLastSyllable() {
        assertEquals("불국사를", "불국사".j("을", "를"))
        assertEquals("첨성대예요", "첨성대".j("이에요", "예요"))
        assertEquals("황리단길로", "황리단길".ro())
        assertEquals("석굴암으로", "석굴암".ro())
        assertEquals("CC를", "CC".j("을", "를"))
    }

    @Test fun tripListLabels() {
        assertEquals("오늘 · 1일차 · 11/13(금)", dayLabel("2026-11-13", 1, "2026-11-13"))
        assertEquals("내일 · 2일차 · 11/14(토)", dayLabel("2026-11-13", 2, "2026-11-13"))
        assertEquals("3일차", dayLabel("", 3, "2026-11-13"))
        assertEquals("경주 2일 여행", tripTitle(PlanCard(1, "경주 2일 여행", "", emptyList(), 0, true)))
        assertEquals("불국사 외 2곳", tripTitle(PlanCard(2, "", "", listOf(bulguksa, cheom, hotel), 0, false)))
        assertEquals("새 여행", tripTitle(PlanCard(3, "", "", emptyList(), 0, false)))
    }

    @Test fun plusAddsIntoTheRightDay() {
        val plan = listOf(bulguksa, hotel, cheom.copy(day = 2))
        assertEquals(listOf("불국사", "황리단길", "숙소", "첨성대"), insertStop(plan, hwang, 1).map { it.name })
        assertEquals(listOf("불국사", "숙소", "첨성대", "황리단길"), insertStop(plan, hwang, 2).map { it.name })
        assertEquals(listOf("불국사", "숙소", "첨성대", "황리단길"), insertStop(plan, hwang, 3).map { it.name }.also { assertEquals(3, insertStop(plan, hwang, 3).last().day) })
        val newStay = insertStop(plan, Place("한옥호텔", 35.81, 129.19, stay = true), 1)
        assertEquals(listOf("불국사", "한옥호텔", "첨성대"), newStay.map { it.name })
        assertEquals(listOf("황리단길"), insertStop(emptyList(), hwang, 1).map { it.name })
    }

    @Test fun removeKeepsProgressRight() {
        val plan = listOf(bulguksa, cheom, hwang)
        assertEquals(listOf("첨성대", "황리단길") to 0, removeStop(plan, 1, 0).let { (p, d) -> p.map { it.name } to d })
        assertEquals(listOf("불국사", "첨성대") to 1, removeStop(plan, 1, 2).let { (p, d) -> p.map { it.name } to d })
        assertEquals(2, PlanCard(1, "", "", listOf(bulguksa), 0, true, days = 2).dayCount)
        assertEquals(3, PlanCard(1, "", "", listOf(bulguksa.copy(day = 3)), 0, true, days = 1).dayCount)
    }

    @Test fun moveStopsUpDownAndAcrossDays() {
        val seok = Place("석굴암", 35.79, 129.34, day = 2)
        val plan = listOf(bulguksa, cheom, hwang, hotel, seok)
        fun names(l: List<Place>) = l.map { "${it.day}:${it.name}" }
        // swap inside a day
        assertEquals(listOf("1:불국사", "1:황리단길", "1:첨성대", "1:숙소", "2:석굴암"), names(moveStop(plan, 0, 1, +1, 2)))
        // bottom of day 1 goes to the start of day 2
        assertEquals(listOf("1:불국사", "1:첨성대", "1:숙소", "2:황리단길", "2:석굴암"), names(moveStop(plan, 0, 2, +1, 2)))
        // top of day 2 goes to the end of day 1, before the stay
        assertEquals(listOf("1:불국사", "1:첨성대", "1:황리단길", "1:석굴암", "1:숙소"), names(moveStop(plan, 0, 4, -1, 2)))
        // not allowed: visited stop, stay, above visited, past the last day
        assertEquals(plan, moveStop(plan, 1, 0, +1, 2))
        assertEquals(plan, moveStop(plan, 0, 3, -1, 2))
        assertEquals(plan, moveStop(plan, 1, 1, -1, 2))
        assertEquals(plan, moveStop(plan, 0, 4, +1, 2))
    }

    @Test fun guideOnlyTodaysStops() {
        val d = java.time.LocalDate.of(2026, 11, 12)
        val plan = listOf(Place("A", 0.0, 0.0, day = 1), Place("B", 0.0, 0.0, day = 3))
        assertEquals("A", stopForToday(plan, 0, "2026-11-12", d)?.name)
        assertNull(stopForToday(plan, 1, "2026-11-12", d))                  // B is day 3; today is day 1
        assertEquals("B", stopForToday(plan, 1, "2026-11-12", d.plusDays(2))?.name)
        assertEquals("A", stopForToday(plan, 0, "2026-11-12", d.plusDays(2))?.name) // behind: still guide A
        assertNull(stopForToday(plan, 0, "2026-11-12", d.minusDays(1)))     // before the trip
    }

    @Test fun orderOneDayOnly() {
        // day 1 in a zig-zag: A(0) C(2) B(1) then stay; day 2 untouched
        val a = Place("A", 35.80, 129.20); val b = Place("B", 35.81, 129.20); val c = Place("C", 35.82, 129.20)
        val stay = Place("숙소", 35.83, 129.20, stay = true); val x = Place("X", 35.0, 129.0, day = 2); val y = Place("Y", 35.9, 129.9, day = 2)
        val plan = listOf(a, c, b, stay, x, y)
        assertEquals(listOf("A", "B", "C", "숙소", "X", "Y"), orderOneDay(plan, 0, 1).map { it.name })
        assertEquals(plan, orderOneDay(plan, 0, 3))                       // no such day
        assertTrue(dayKm(orderOneDay(plan, 0, 1), 1) < dayKm(plan, 1))
    }

    @Test fun tripOfTheDay() {
        val d = java.time.LocalDate.of(2026, 11, 17)
        val gyeongju = PlanCard(1, "경주", "2026-11-14", listOf(bulguksa, hotel), 0, true, 2)
        val gangneung = PlanCard(900, "강릉", "2026-11-17", listOf(bulguksa), 0, false, 1)
        assertEquals(900, tripForToday(listOf(gyeongju, gangneung), d))
        assertNull(tripForToday(listOf(gyeongju, gangneung), d.minusDays(3)))          // active trip covers 11/14
        assertNull(tripForToday(listOf(gyeongju, gangneung.copy(done = 1)), d))        // finished trips stay put
        assertTrue(started("2026-11-14", d) && !started("2026-11-18", d) && started("", d))
    }

    @Test fun otherTripsBlockDates() {
        val a = PlanCard(1, "경주", "2026-11-14", listOf(bulguksa), 0, true, 2)
        val b = PlanCard(2, "강릉", "2026-11-17", listOf(bulguksa), 0, false, 1)
        val taken = takenDays(listOf(a, b), except = 1)
        assertEquals(mapOf(java.time.LocalDate.of(2026, 11, 17) to "강릉"), taken)
        assertEquals(2, takenDays(listOf(a, b), except = 2).size)
    }

    @Test fun calendarRangeShortensTrip() {
        val seok = Place("석굴암", 35.79, 129.34, day = 2)
        val hotel2 = Place("2일 숙소", 35.8, 129.3, day = 2, stay = true)
        val museum = Place("박물관", 35.82, 129.22, day = 3)
        val plan = listOf(bulguksa, hotel, seok, hotel2, museum)
        val (p, n) = fitDays(plan, 0, 2)
        assertEquals(2, n)
        assertEquals(listOf("1:불국사", "1:숙소", "2:석굴암", "2:2일 숙소"), p.map { "${it.day}:${it.name}" })
        assertEquals(listOf("박물관"), lostByDays(plan, 0, 2).map { it.name })
        assertEquals(plan to 5, fitDays(plan, 0, 5))           // longer keeps places
        assertEquals(3, fitDays(plan, 5, 1).second)             // visited day 3 is never cut
        // remove day 2: its places go, day 3 becomes day 2
        assertEquals(listOf("1:불국사", "1:숙소", "2:박물관"), removeDay(plan, 0, 2)!!.map { "${it.day}:${it.name}" })
        assertNull(removeDay(plan, 1, 1))                       // 불국사 already visited
    }

    @Test fun dragAndDropStops() {
        val seok = Place("석굴암", 35.79, 129.34, day = 2)
        val plan = listOf(bulguksa, cheom, hwang, hotel, seok)
        fun names(l: List<Place>) = l.map { "${it.day}:${it.name}" }
        assertEquals(listOf("1:황리단길", "1:불국사", "1:첨성대", "1:숙소", "2:석굴암"), names(dropStop(plan, 0, 2, 1, 0)))   // up to the top
        assertEquals(listOf("1:첨성대", "1:황리단길", "1:불국사", "1:숙소", "2:석굴암"), names(dropStop(plan, 0, 0, 1, null))) // end of day, before stay
        assertEquals(listOf("1:불국사", "1:황리단길", "1:숙소", "2:첨성대", "2:석굴암"), names(dropStop(plan, 0, 1, 2, 4)))    // into day 2, before 석굴암
        assertEquals(listOf("1:첨성대", "1:황리단길", "1:숙소", "2:석굴암", "3:불국사"), names(dropStop(plan, 0, 0, 3, null)))  // into an empty day 3
        assertEquals(plan, dropStop(plan, 1, 0, 2, null))  // visited stays put
        assertEquals(plan, dropStop(plan, 0, 3, 2, null))  // stay stays put
        assertEquals(plan, dropStop(plan, 1, 2, 1, 0))     // can't land above visited
    }

    @Test fun hardwareKeyboardHangul() {
        fun type(keys: String) = hangul(keys.mapNotNull { jamoOf(it, it.isUpperCase()) })
        assertEquals("경주", type("rudwn"))
        assertEquals("불국사", type("qnfrnrtk"))
        assertEquals("닭갈비", type("ekfrrkfql"))
        assertEquals("값어치", type("rkqtdjcl"))  // ㅄ splits: 값 + 어
        assertEquals("앉아", type("dkswdk"))
        assertEquals("과자", type("rhkwk"))
        assertEquals("쌍", type("Tkd"))
        assertEquals("ㄱ", type("r"))
        assertEquals("ㅏ", type("k"))
        assertNull(jamoOf('1', false))
    }

    @Test fun routines() {
        val school = Place("판교초등학교", 37.39, 127.11)
        val r = Routine(1, "등교", school, WEEKDAYS, 8)
        assertEquals(r, routineNow(listOf(r), 3, 9))      // Wednesday 9시: within an hour
        assertNull(routineNow(listOf(r), 6, 8))           // Saturday: not its day
        assertNull(routineNow(listOf(r), 3, 11))          // too late
        assertEquals("평일", daysLabel(WEEKDAYS)); assertEquals("월·수", daysLabel(setOf(3, 1)))
        val ride = Trip(setOf(Seat.REAR_RIGHT), mapOf(Seat.REAR_RIGHT to Who.CHILD), false, 8, "", school)
        val found = findRoutines(listOf(ride, ride, ride.copy(dest = Place("마트", 0.0, 0.0))), emptyList()).single()
        assertEquals("등교", found.name); assertEquals(listOf(Who.CHILD), found.who); assertEquals(2, found.seen)
        assertTrue(findRoutines(listOf(ride, ride), listOf(r)).isEmpty()) // already a routine
    }
}
