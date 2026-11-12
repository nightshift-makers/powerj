package com.beat.powerj

import java.time.LocalDate
import kotlin.math.abs

/** Pure decision logic, no Android/SDK types, so it is unit-testable. */

enum class Seat(val label: String) { PASSENGER("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽") }

enum class Who(val label: String) { CHILD("아이"), PARENT("부모님"), SPOUSE("배우자"), PARTNER("연인"), FRIEND("친구") }

/** A place; in a trip plan it also carries its day and whether it is that night's stay (숙소). */
/** Korean particle by the last syllable's final consonant: "불국사".j("을", "를") = "불국사를". Non-Hangul endings count as no final. */
fun String.j(withFinal: String, without: String): String {
    val f = lastOrNull()?.takeIf { it in '가'..'힣' }?.let { (it - '가') % 28 } ?: 0
    return this + if (f != 0) withFinal else without
}

/** 으로/로: a final ㄹ also takes 로. (j("으로","로") also works for most words; use ro() when ㄹ matters.) */
fun String.ro(): String {
    val f = lastOrNull()?.takeIf { it in '가'..'힣' }?.let { (it - '가') % 28 } ?: 0
    return this + if (f == 0 || f == 8) "로" else "으로"
}

/** A stop. [park] is the parking lot chosen for it; guidance goes there instead of the place itself. */
data class Place(val name: String, val lat: Double, val lon: Double, val day: Int = 1, val stay: Boolean = false, val park: Place? = null) {
    /** Where navigation should go. */
    fun target() = park ?: this
    /** Navigation reports its destination by name: the place's or its parking lot's. */
    fun named(n: String?) = n != null && (n == name || n == park?.name)
}

data class Trip(
    val seats: Set<Seat>,
    val who: Map<Seat, Who>,
    val weekend: Boolean,
    val hour: Int,
    val startCell: String,
    val dest: Place?,
)

/** A daily drive like 출근 or 등교: where, which days (1 = Mon .. 7 = Sun), about what hour, and who usually comes along. */
data class Routine(val id: Int, val name: String, val dest: Place, val days: Set<Int>, val hour: Int, val who: List<Who> = emptyList(), val seen: Int = 0)

val WEEKDAYS = setOf(1, 2, 3, 4, 5)
val WEEKEND = setOf(6, 7)

/** "평일" / "주말" / "매일" / "월·수·금". */
fun daysLabel(days: Set<Int>): String = when (days) {
    WEEKDAYS -> "평일"; WEEKEND -> "주말"; WEEKDAYS + WEEKEND -> "매일"
    else -> days.sorted().joinToString("·") { "월화수목금토일"[it - 1].toString() }
}

/** The routine for leaving now: its day, within an hour of its time; the closest time wins. */
fun routineNow(routines: List<Routine>, dow: Int, hour: Int): Routine? =
    routines.filter { dow in it.days && abs(it.hour - hour) <= 1 }.minByOrNull { abs(it.hour - hour) }

/** A name to start from: what the place is for. */
fun routineName(dest: String): String = when (kindOf(dest)) {
    Kind.SCHOOL -> if (Regex("학원").containsMatchIn(dest)) "학원" else "등교"
    Kind.HOSPITAL -> "병원"; Kind.MART -> "장보기"; Kind.GOLF -> "골프"
    else -> dest
}

/** Drives repeated 2+ times (same weekday/weekend, hour, destination) that aren't a routine yet: offered as "자동으로 찾은 루틴". */
fun findRoutines(trips: List<Trip>, have: List<Routine>): List<Routine> =
    trips.filter { it.dest != null }.groupBy { Triple(it.weekend, it.hour, it.dest!!.name) }
        .filter { (k, v) -> v.size >= 2 && have.none { r -> r.dest.name == k.third && abs(r.hour - k.second) <= 1 } }
        .map { (k, v) ->
            val who = v.flatMap { it.who.values }.groupingBy { it }.eachCount().filterValues { it * 2 >= v.size }.keys.toList()
            Routine(0, routineName(k.third), v.last().dest!!, if (k.first) WEEKEND else WEEKDAYS, k.second, who, v.size)
        }
        .sortedByDescending { it.seen }

/** ~1 km grid so "home" or "office" match across trips. */
fun cell(lat: Double, lon: Double) = "%.2f,%.2f".format(lat, lon)

/**
 * Hyundai cars have a passenger occupancy sensor but none in the rear (manuals: rear reminder only knows door history).
 * So a rear seat counts as taken if its door opened before departure or its belt is buckled.
 */
fun occupiedSeats(passengerSeat: Boolean, rearDoorOpened: Set<Seat>, rearBelts: Set<Seat>): Set<Seat> =
    buildSet {
        if (passengerSeat) add(Seat.PASSENGER)
        addAll(rearDoorOpened.filter { it != Seat.PASSENGER })
        addAll(rearBelts.filter { it != Seat.PASSENGER })
    }

private fun similar(t: Trip, weekend: Boolean, hour: Int, startCell: String) =
    t.weekend == weekend && abs(t.hour - hour) <= 1 && t.startCell == startCell

/** Who usually sits in [seat] at this time and place. Needs 2+ confirmations and a clear majority. */
fun guessWho(trips: List<Trip>, seat: Seat, weekend: Boolean, hour: Int, startCell: String): Who? {
    val seen = trips.filter { similar(it, weekend, hour, startCell) }.mapNotNull { it.who[seat] }
    val (top, n) = seen.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
    return top.takeIf { n >= 2 && n * 10 >= seen.size * 6 }
}

/** Usual destination for this company, time and start point. */
fun predictDest(trips: List<Trip>, seats: Set<Seat>, weekend: Boolean, hour: Int, startCell: String): Place? {
    val seen = trips.filter { it.seats == seats && similar(it, weekend, hour, startCell) }.mapNotNull { it.dest }
    val (name, n) = seen.groupingBy { it.name }.eachCount().maxByOrNull { it.value } ?: return null
    return seen.last { it.name == name }.takeIf { n >= 2 && n * 2 >= seen.size }
}

// ---------- trip plan ----------

/** Meters between two points. Equirectangular: fine for the few-km checks we do. */
fun meters(a: Place, lat: Double, lon: Double): Double {
    val k = Math.PI / 180
    val x = (lon - a.lon) * k * kotlin.math.cos((lat + a.lat) / 2 * k)
    val y = (lat - a.lat) * k
    return kotlin.math.sqrt(x * x + y * y) * 6_371_000
}

/** First stop not reached yet, or null when the plan is finished. */
fun nextStop(plan: List<Place>, done: Int): Place? = plan.getOrNull(done)

/** Share of the trip behind a car, 0..1: stops reached plus how far along the leg toward the next one. */
fun tripProgress(plan: List<Place>, done: Int, lat: Double?, lon: Double?): Float {
    if (plan.isEmpty()) return 0f
    if (done >= plan.size) return 1f
    val leg = if (lat == null || lon == null || done == 0) 0.0 else { // ponytail: the leg to the first stop counts as 0, start point isn't stored
        val prev = plan[done - 1]; val next = plan[done]
        val whole = meters(prev, next.lat, next.lon)
        if (whole < 1) 0.0 else (1 - meters(next, lat, lon) / whole).coerceIn(0.0, 1.0)
    }
    return ((done + leg) / plan.size).toFloat()
}

/** Parking near the next stop counts as reaching it; a lunch or fuel stop elsewhere does not advance the plan. */
fun reached(stop: Place, lat: Double, lon: Double, radiusM: Double = 500.0) = meters(stop, lat, lon) <= radiusM || stop.park?.let { meters(it, lat, lon) <= radiusM } == true

/** Destination type, guessed from the name navigation gives us. ponytail: keyword match; a map category API can replace it. */
enum class Kind(val label: String, val pattern: Regex, val kit: List<String>, val soon: String) {
    AIRPORT("공항", Regex("공항|항공|터미널\\d"), listOf("여권·신분증", "탑승권"), "내리기 전에 주차 구역 번호를 사진으로 남겨 두세요."),
    STATION("역", Regex("역|KTX|SRT|고속버스|버스터미널"), listOf("승차권 시간"), "승차권 시간 한 번 더 확인하세요."),
    HOSPITAL("병원", Regex("병원|의원|치과|한의원|약국|의료원"), listOf("신분증", "진료 예약 시간"), "신분증 챙기세요. 주차권은 접수할 때 같이 받으세요."),
    SCHOOL("학교·학원", Regex("학교|학원|유치원|어린이집"), listOf("아이 가방·준비물"), "아이 가방 챙겼는지 봐 주세요."),
    MART("마트", Regex("마트|코스트코|트레이더스|홈플러스|이마트|시장"), listOf("장바구니", "트렁크 공간"), "주차 위치를 기억해 두세요. 무료 주차는 영수증 기준이에요."),
    GOLF("골프장", Regex("골프|CC|컨트리클럽"), listOf("골프백·장갑"), "골프백 트렁크에 있는지 확인하세요."),
    CAMPING("캠핑장", Regex("캠핑|글램핑"), listOf("캠핑 장비", "보조배터리"), "도착 전에 장보기가 끝났는지 확인하세요."),
}

fun kindOf(dest: String): Kind? = Kind.entries.firstOrNull { it.pattern.containsMatchIn(dest) }

/** Things to bring, from the destination name and who is aboard. */
fun checklist(dest: String, who: Collection<Who>): List<String> = buildList {
    Kind.entries.filter { it.pattern.containsMatchIn(dest) }.forEach { addAll(it.kit) }
    if (Who.CHILD in who) add("아이 물·간식")
    if (Who.PARENT in who) add("부모님 복용 약")
}.distinct()

/** Korean big marts must close on the 2nd and 4th Sunday in most regions. ponytail: some cities use weekdays; a store-hours API fixes it. */
fun martHoliday(dayOfMonth: Int, sunday: Boolean) = sunday && (dayOfMonth in 8..14 || dayOfMonth in 22..28)

/**
 * One warning when the arrival time clashes with usual hours. [arrive] is minutes since midnight.
 * ponytail: typical hours (clinics 18:00, Sat 13:00; marts 22:00), not the actual place's hours.
 */
fun timeNote(kind: Kind?, arrive: Int, saturday: Boolean, sunday: Boolean, dayOfMonth: Int): String? {
    val at = "도착 예정 %d시 %02d분".format(arrive / 60 % 24, arrive % 60)
    return when (kind) {
        Kind.HOSPITAL -> when {
            sunday -> "오늘은 일요일이라 문을 닫은 병원이 많아요. 미리 전화해 보세요."
            saturday && arrive >= 12 * 60 + 30 -> "$at, 토요일엔 보통 13시에 접수를 마감해요. 미리 전화해 보세요."
            !saturday && arrive >= 17 * 60 + 30 -> "$at, 보통 18시에 접수를 마감해요. 미리 전화해 보세요."
            else -> null
        }
        Kind.MART -> when {
            martHoliday(dayOfMonth, sunday) -> "오늘은 대형마트 의무휴업일일 수 있어요. 문 여는지 확인해 보세요."
            arrive >= 21 * 60 + 30 -> "$at, 대형마트는 보통 22시에 닫아요."
            else -> null
        }
        Kind.AIRPORT -> "국제선이면 출발 2시간 전까지 도착하는 게 좋아요."
        else -> null
    }
}

/** Spoken about 3 minutes before arrival: what this place needs, plus a family touch. */
fun soonLine(dest: String, kind: Kind?, who: Collection<Who>, rideMin: Int): String = buildString {
    append("곧 ${dest}에 도착해요. ")
    kind?.let { append(it.soon).append(' ') }
    if (Who.CHILD in who && rideMin >= 20 && kind != Kind.SCHOOL) append("아이가 자고 있다면 지금 살살 깨워 주세요.")
}.trim()

enum class Level { STOP, SOON }

data class Alert(val level: Level, val name: String, val action: String)

private val names = mapOf(
    "ENGINE_OIL_PRESSURE" to "엔진오일 압력", "ENGINE_TEMPERATURE" to "엔진 과열", "ENGINE" to "엔진 점검",
    "ABS" to "ABS", "ESC" to "차체 자세 제어", "MDPS" to "전동 조향", "AIRBAG" to "에어백", "TPMS" to "타이어 공기압",
    "LOW_VOLT_BATTERY" to "배터리 충전", "EV_SYSTEM" to "전기차 시스템", "HEV_SYSTEM" to "하이브리드 시스템",
    "POWER_DOWN" to "출력 제한", "MASTER_WARNING" to "종합 경고", "LOW_FUEL" to "연료 부족", "LOW_EV_BATTERY" to "배터리 부족",
    "PARTICLE_FILTER" to "매연 필터", "ICY_ROAD" to "도로 결빙", "PARKING_BRAKE" to "주차 브레이크",
)

/** Red = stop now, yellow = fine for now, check soon. Telltale color is the SDK enum ordinal (YELLOW=3, RED=4). */
fun triage(type: String, color: Int): Alert? {
    val name = names[type] ?: return null
    return when (color) {
        4 -> Alert(Level.STOP, name, "안전한 곳에 바로 차를 세우세요.")
        3 -> Alert(Level.SOON, name, "지금 바로 위험하진 않아요. 가까운 시일 안에 점검받으세요.")
        else -> null
    }
}

/** Round trip check from navigation's predicted energy at arrival. Returns % left at arrival and back home. */
fun roundTrip(capacityWh: Float, socPct: Int, arrivalWh: Int): Pair<Int, Int>? {
    if (capacityWh <= 0f || arrivalWh <= 0) return null
    val now = capacityWh * socPct / 100f
    val back = arrivalWh - (now - arrivalWh)
    return (arrivalWh * 100 / capacityWh).toInt() to (back * 100 / capacityWh).toInt().coerceAtLeast(0)
}

fun energyLine(arrivePct: Int, backPct: Int) =
    if (backPct < 15) "왕복하면 돌아올 때 배터리가 ${backPct}%예요. 가는 길에 충전소를 들르는 게 좋아요."
    else "도착하면 배터리 ${arrivePct}%, 왕복해도 ${backPct}% 남아요."

/** One spoken line at departure. */
fun briefing(dest: String, etaMin: Int, toll: Int, kit: List<String>, energy: String?, note: String? = null): String = buildString {
    append("${dest}까지 ${etaMin}분이에요. ")
    if (toll > 0) append("통행료는 %,d원. ".format(toll))
    energy?.let { append("$it ") }
    note?.let { append("$it ") }
    if (kit.isNotEmpty()) append("${kit.joinToString(", ")} 챙기셨나요?")
}.trim()


// ---------- multi-day plan ----------

/** Nearest-neighbour order from [start]; the stay stays last. ponytail: straight-line greedy, fine for under 10 stops a day. */
fun orderDay(stops: List<Place>, start: Place?): List<Place> {
    val (stay, left) = stops.partition { it.stay }.let { it.first to it.second.toMutableList() }
    val out = mutableListOf<Place>()
    var cur = start
    while (left.isNotEmpty()) {
        val n = cur?.let { c -> left.minBy { meters(c, it.lat, it.lon) } } ?: left.first()
        out += n; left -= n; cur = n
    }
    return out + stay
}

/** Reorder the stops not reached yet, day by day; each day starts where the previous one ended. */
fun orderPlan(plan: List<Place>, done: Int, start: Place?): List<Place> {
    val out = plan.take(done).toMutableList()
    var from = start ?: out.lastOrNull()
    plan.drop(done).groupBy { it.day }.toSortedMap().values.forEach { d -> orderDay(d, from).also { out += it; from = it.lastOrNull() } }
    return out
}

/** Move one stop to the start of the next day. */
fun postpone(plan: List<Place>, index: Int): List<Place> {
    val p = plan[index]
    val rest = plan.filterIndexed { i, _ -> i != index }
    val at = rest.indexOfFirst { it.day > p.day }.let { if (it < 0) rest.size else it }
    return rest.take(at) + p.copy(day = p.day + 1, stay = false) + rest.drop(at)
}

/** True when the next stop opens a new day (first stop of the trip, or the day after a stay). */
fun newDay(plan: List<Place>, done: Int) = done < plan.size && (done == 0 || plan[done].day != plan[done - 1].day)

/** Road km left today from here through the rest of today's stops. ponytail: straight line x1.3; a route API gives real km. */
fun todayKm(plan: List<Place>, done: Int, lat: Double, lon: Double): Int {
    val day = plan.getOrNull(done)?.day ?: return 0
    var m = 0.0; var la = lat; var lo = lon
    plan.drop(done).takeWhile { it.day == day }.forEach { m += meters(it, la, lo); la = it.lat; lo = it.lon }
    return (m * 1.3 / 1000).toInt()
}

/** Range in km: the car's own figure when it has one, else EV energy x efficiency. ponytail: 5 km/kWh average EV. */
fun rangeKm(carKm: Float, capacityWh: Float, socPct: Int) =
    if (carKm > 0f) carKm.toInt() else (capacityWh * socPct / 100f / 1000f * 5f).toInt()

/** Departure check for the day: distance vs range, plus any warning light that is on. */
fun dayCheck(todayKm: Int, rangeKm: Int, warnings: List<String>): String? = buildList {
    if (todayKm > 0 && rangeKm > 0) add(
        if (rangeKm < todayKm * 1.1) "오늘 일정은 약 ${todayKm}km인데 지금 ${rangeKm}km 갈 수 있어요. 중간에 충전이나 주유가 필요해요."
        else "오늘 일정 약 ${todayKm}km, 지금 ${rangeKm}km 갈 수 있어서 충분해요.")
    if (warnings.isNotEmpty()) add("${warnings.joinToString(", ")} 경고등이 켜져 있어요. 장거리 전에 점검을 권해요.")
}.joinToString(" ").ifEmpty { null }

/** Late arrival worth rescheduling: closing-time clash or after 20:00 at a non-stay stop. [arrive] is minutes since midnight. */
fun lateLine(stop: Place, arrive: Int, note: String?): String? = when {
    stop.stay -> null
    note != null -> "${stop.name.j("은", "는")} 내일로 미룰까요?"
    arrive % (24 * 60) >= 20 * 60 -> "도착이 %d시 %02d분이라 늦어요. ${stop.name.j("은", "는")} 내일로 미룰까요?".format(arrive / 60 % 24, arrive % 60)
    else -> null
}

/** First saved "maybe" place within [radiusM] that we have not mentioned yet. */
fun nearWish(wish: List<Place>, lat: Double, lon: Double, told: Set<String>, radiusM: Double = 5000.0): Place? =
    wish.filter { it.name !in told }.minByOrNull { meters(it, lat, lon) }?.takeIf { meters(it, lat, lon) <= radiusM }

/** Trip car costs. ponytail: 347원/kWh (public fast charging, 2026); fuel cars use the same energy-drop idea once fuel % is read. */
fun costLine(tolls: Int, kWh: Float, cars: Int = 1): String? {
    val charge = spentWon(0, kWh)
    if (tolls <= 0 && charge <= 0) return null
    val total = tolls + charge
    return "통행료 %,d원, 충전 약 %,d원".format(tolls, charge) + if (cars > 1) ", 1대당 %,d원".format(total / cars) else ", 합계 %,d원".format(total)
}

/** Expected trip car cost before driving. ponytail: 5 km/kWh x 347원/kWh for EVs, 12 km/L x 1,650원/L (gasoline average) otherwise. */
fun energyWon(km: Double, ev: Boolean): Int = (if (ev) km / 5 * 347 else km / 12 * 1650).toInt() / 100 * 100

/** An expected cost: road km, tolls, and charging (EV) or fuel, in won. */
data class Est(val km: Double, val toll: Int, val energy: Int, val ev: Boolean, val min: Int = 0) { val total get() = toll + energy }

/** One drive between two points. */
data class Leg(val km: Double, val toll: Int, val min: Int) {
    operator fun plus(o: Leg) = Leg(km + o.km, toll + o.toll, min + o.min)
}

fun est(leg: Leg, ev: Boolean): Est? = if (leg.km < 0.5) null else Est(leg.km, leg.toll, energyWon(leg.km, ev), ev, leg.min)

/** "1시간 25분", "40분". */
fun durationText(min: Int): String = if (min >= 60) "${min / 60}시간" + (if (min % 60 > 0) " ${min % 60}분" else "") else "${min}분"

/** What the trip has cost so far (tolls + charging), 0 before any drive. */
fun spentWon(tolls: Int, kWh: Float): Int = tolls + (kWh * 347).toInt() / 100 * 100

/** Legs (km, toll) summed per day: a leg counts for the day of the place it drives to. The car's spot has day 0,
 *  so the drive home counts for the day it leaves from. */
/** Legs summed per day of their destination; the drive back to the car (day 0) is kept apart under key 0. */
fun legsByDay(points: List<Place>, legs: List<Leg>): Map<Int, Leg> =
    legs.indices.groupBy { i -> points[i + 1].day }
        .mapValues { (_, ix) -> ix.map { legs[it] }.reduce(Leg::plus) }

/** List title: the name the user gave, else "첫 장소 외 N곳". */
fun tripTitle(c: PlanCard): String = c.title.ifBlank {
    c.stops.firstOrNull()?.let { if (c.stops.size > 1) "${it.name} 외 ${c.stops.size - 1}곳" else it.name } ?: "새 여행"
}

/** "오늘 · 1일차 · 11/13(금)" from the trip start date (ISO) and day number; [today] is ISO too. */
fun dayLabel(start: String, day: Int, today: String): String {
    val d = runCatching { java.time.LocalDate.parse(start).plusDays(day - 1L) }.getOrNull() ?: return "${day}일차"
    val t = runCatching { java.time.LocalDate.parse(today) }.getOrNull()
    val rel = when (t?.let { java.time.temporal.ChronoUnit.DAYS.between(it, d) }) { 0L -> "오늘 · "; 1L -> "내일 · "; -1L -> "어제 · "; else -> "" }
    return "$rel${day}일차 · ${d.monthValue}/${d.dayOfMonth}(${"월화수목금토일"[d.dayOfWeek.value - 1]})"
}

/** Put [p] into day [day]: after that day's other stops but before its stay; a new stay replaces the old one. Later days keep their order. */
fun insertStop(plan: List<Place>, p: Place, day: Int): List<Place> {
    val stop = p.copy(day = day)
    val rest = if (stop.stay) plan.filterNot { it.day == day && it.stay } else plan
    val at = if (stop.stay) rest.indexOfLast { it.day <= day } + 1
    else rest.indexOfFirst { it.day > day || (it.day == day && it.stay) }.let { if (it < 0) rest.size else it }
    return rest.take(at) + stop + rest.drop(at)
}

/** Remove the stop at [index]; [done] shifts down when an already-visited stop goes. Returns plan and new done. */
fun removeStop(plan: List<Place>, done: Int, index: Int): Pair<List<Place>, Int> =
    plan.filterIndexed { i, _ -> i != index } to if (index < done) done - 1 else done

/**
 * Move a stop one step (dir -1 up, +1 down). Inside a day it swaps with its neighbour; past the top or bottom of a day it
 * moves to the end of the previous day (before its stay) or the start of the next day (up to [days]).
 * Visited stops ([done]) and stays don't move, and nothing moves above the visited ones. Returns the plan unchanged when not allowed.
 */
fun moveStop(plan: List<Place>, done: Int, index: Int, dir: Int, days: Int): List<Place> {
    val p = plan.getOrNull(index) ?: return plan
    if (index < done || p.stay) return plan
    val sameDay = plan.indices.filter { plan[it].day == p.day && !plan[it].stay }
    val k = sameDay.indexOf(index)
    val swapWith = sameDay.getOrNull(k + dir)
    val out = when {
        swapWith != null -> plan.toMutableList().apply { this[index] = plan[swapWith]; this[swapWith] = p }
        dir < 0 && p.day > 1 -> insertStop(plan.filterIndexed { i, _ -> i != index }, p, p.day - 1)
        dir > 0 && p.day < days -> {
            val rest = plan.filterIndexed { i, _ -> i != index }
            val at = rest.indexOfFirst { it.day > p.day }.let { if (it < 0) rest.size else it }
            rest.take(at) + p.copy(day = p.day + 1) + rest.drop(at)
        }
        else -> return plan
    }
    return if (out.take(done) == plan.take(done)) out else plan // never reorder what was already visited
}

/**
 * Drag and drop: put the stop at [from] into [day], just before the stop at [before] (or at the end of that day, before its stay,
 * when [before] is null). Visited stops and stays don't move, and nothing lands above the visited ones.
 */
fun dropStop(plan: List<Place>, done: Int, from: Int, day: Int, before: Int?): List<Place> {
    val p = plan.getOrNull(from) ?: return plan
    if (from < done || p.stay || before == from) return plan
    val rest = plan.filterIndexed { i, _ -> i != from }
    val at = if (before != null) before - if (before > from) 1 else 0
    else rest.indexOfFirst { it.day > day || (it.day == day && it.stay) }.let { if (it < 0) rest.size else it }
    val out = rest.take(at) + p.copy(day = day) + rest.drop(at)
    return if (out.take(done) == plan.take(done)) out else plan
}

/**
 * New trip length from the calendar. Places on days that no longer exist are dropped (the app asks first).
 * Days with visited places are never cut. Returns the plan and the day count actually used.
 */
fun fitDays(plan: List<Place>, done: Int, days: Int): Pair<List<Place>, Int> {
    val n = maxOf(days, 1, plan.take(done).maxOfOrNull { it.day } ?: 1)
    return plan.filter { it.day <= n } to n
}

/** Places that shortening the trip to [days] would drop (not yet visited). */
fun lostByDays(plan: List<Place>, done: Int, days: Int): List<Place> = plan.drop(done).filter { it.day > days }

/** Drop one day with its places; later days move up one. Null when the day has a visited place. */
fun removeDay(plan: List<Place>, done: Int, day: Int): List<Place>? {
    if (plan.take(done).any { it.day == day }) return null
    return plan.filter { it.day != day }.map { if (it.day > day) it.copy(day = it.day - 1) else it }
}

/** The plan only guides from its start date on (a blank date means "now"). */
fun started(start: String, today: LocalDate): Boolean =
    runCatching { LocalDate.parse(start) <= today }.getOrDefault(true)

/**
 * When today falls inside a saved trip's dates and the trip in use doesn't cover today, the saved trip to switch to.
 * Finished trips are skipped. Null means keep the current one.
 */
/** -1 = the trip's days are over (or all stops reached), 0 = today is one of its days, 1 = it starts later. */
fun tripWhen(t: PlanCard, today: LocalDate): Int {
    if (t.stops.isNotEmpty() && t.done >= t.stops.size) return -1
    val s = runCatching { LocalDate.parse(t.start) }.getOrNull() ?: return 0
    return when { today < s -> 1; today > s.plusDays(t.dayCount - 1L) -> -1; else -> 0 }
}

fun tripForToday(trips: List<PlanCard>, today: LocalDate): Int? {
    fun covers(t: PlanCard) = t.done < t.stops.size && tripWhen(t, today) == 0
    if (trips.any { it.active && covers(it) }) return null
    return trips.firstOrNull { !it.active && covers(it) }?.id
}

/** Days already taken by other trips (date -> trip name), so the calendar can show them and refuse overlaps. */
fun takenDays(trips: List<PlanCard>, except: Int): Map<LocalDate, String> =
    trips.filter { it.id != except }.flatMap { t ->
        val s = runCatching { LocalDate.parse(t.start) }.getOrNull() ?: return@flatMap emptyList()
        (0 until t.dayCount).map { s.plusDays(it.toLong()) to tripTitle(t) }
    }.toMap()

/** Days the test button moved "today" by (debug builds only; 0 in release). */
@Volatile var testShift = 0L

/** Today as the app sees it. Every date rule goes through this so the test button can move it. */
fun today(): LocalDate = LocalDate.now().plusDays(testShift)

/** Trip day number for [today] (day 1 = start date). */
fun dayNo(start: String, today: LocalDate): Int =
    runCatching { (today.toEpochDay() - LocalDate.parse(start).toEpochDay()).toInt() + 1 }.getOrDefault(1)

/**
 * The stop to guide to today: the next unreached one, unless it belongs to a later day of the trip.
 * Leftovers from earlier days still count (running behind is fine; running ahead waits for its day).
 */
fun stopForToday(plan: List<Place>, done: Int, start: String, today: LocalDate): Place? {
    val next = nextStop(plan, done) ?: return null
    return if (start.isBlank() || next.day <= dayNo(start, today)) next else null
}

/**
 * Nearest-first order for one day's places not reached yet. It starts from the place before them (yesterday's stay or a
 * visited stop); with nothing before, the day's first place stays first. The stay stays last.
 */
fun orderOneDay(plan: List<Place>, done: Int, day: Int): List<Place> {
    val idx = plan.indices.filter { it >= done && plan[it].day == day }
    if (idx.count { !plan[it].stay } < 2) return plan
    val ordered = orderDay(idx.map { plan[it] }, plan.take(idx.first()).lastOrNull())
    return plan.toMutableList().also { out -> idx.forEachIndexed { k, i -> out[i] = ordered[k] } }
}

/** Straight-line km along one day's places in order (for the before/after note). */
fun dayKm(plan: List<Place>, day: Int): Double =
    plan.filter { it.day == day }.zipWithNext { a, b -> meters(a, b.lat, b.lon) }.sum() / 1000

// ---------- hardware keyboard Hangul (2-beolsik) ----------
// The car IME doesn't compose Hangul from a physical keyboard (the emulator's), so the text field does it.

private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
private val VV = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
private val CC = mapOf("ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
    "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ')
private const val KEYS = "qwertyuiopasdfghjklzxcvbnm"
private const val LOWER = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"
private const val UPPER = "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"

/** The jamo a QWERTY key types on a 2-beolsik keyboard, or null for a non-letter. */
fun jamoOf(key: Char, shift: Boolean): Char? = KEYS.indexOf(key.lowercaseChar()).takeIf { it >= 0 }?.let { (if (shift) UPPER else LOWER)[it] }

/** Jamo typed in order -> Hangul text, e.g. ㄱㅕㅇㅈㅜ -> 경주. A final consonant moves to the next syllable when a vowel follows. */
fun hangul(jamo: List<Char>): String {
    val out = StringBuilder()
    var cho: Char? = null; var jung: Char? = null; var jong: Char? = null
    fun flush() {
        val c = cho; val v = jung
        if (c != null && v != null) out.append((0xAC00 + (CHO.indexOf(c) * 21 + JUNG.indexOf(v)) * 28 + JONG.indexOf(jong ?: ' ')).toChar())
        else { c?.let(out::append); v?.let(out::append) }
        cho = null; jung = null; jong = null
    }
    for (j in jamo) {
        if (j in JUNG) {
            val v = jung
            when {
                v == null -> jung = j
                jong == null && VV["$v$j"] != null -> jung = VV["$v$j"]
                jong != null -> {
                    val f = jong!!
                    val split = CC.entries.firstOrNull { it.value == f }?.key
                    jong = split?.get(0)
                    flush(); cho = split?.get(1) ?: f; jung = j
                }
                else -> { flush(); jung = j }
            }
        } else {
            val f = jong
            when {
                cho == null && jung == null -> cho = j
                jung == null -> { flush(); cho = j }
                cho == null -> { flush(); cho = j } // a lone vowel can't take a final
                f == null && j in JONG -> jong = j
                f != null && CC["$f$j"] != null -> jong = CC["$f$j"]
                else -> { flush(); cho = j }
            }
        }
    }
    flush()
    return out.toString()
}
