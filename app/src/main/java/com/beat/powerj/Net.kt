package com.beat.powerj

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/** Public APIs (KMA weather, Korea Tourism place info, Firebase). Call off the main thread. Pure parsers are unit-tested. */
object Net {
    fun get(url: String, auth: String? = null): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 5000; readTimeout = 15000
        auth?.let { setRequestProperty("Authorization", it) }
        try { inputStream.bufferedReader().readText() } finally { disconnect() }
    }

    fun send(method: String, url: String, body: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        requestMethod = method; doOutput = true; connectTimeout = 5000; readTimeout = 8000
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(body.toByteArray()) }
        try { inputStream.bufferedReader().readText() } finally { disconnect() }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ---------- weather (KMA) ----------

/** Rain chance for one day, morning and afternoon (%), plus temperatures when known. */
data class DayWeather(val date: LocalDate, val rainAm: Int, val rainPm: Int, val minT: Int? = null, val maxT: Int? = null)

/** KMA Lambert conformal grid (5 km) from lat/lon, per the KMA short-term forecast guide. */
fun kmaGrid(lat: Double, lon: Double): Pair<Int, Int> {
    val re = 6371.00877 / 5.0; val slat1 = 30.0; val slat2 = 60.0; val olon = 126.0; val olat = 38.0; val xo = 43.0; val yo = 136.0
    val d = PI / 180
    val sn = ln(cos(slat1 * d) / cos(slat2 * d)) / ln(tan(PI * 0.25 + slat2 * d * 0.5) / tan(PI * 0.25 + slat1 * d * 0.5))
    val sf = tan(PI * 0.25 + slat1 * d * 0.5).pow(sn) * cos(slat1 * d) / sn
    val ro = re * sf / tan(PI * 0.25 + olat * d * 0.5).pow(sn)
    val ra = re * sf / tan(PI * 0.25 + lat * d * 0.5).pow(sn)
    var theta = lon * d - olon * d
    if (theta > PI) theta -= 2 * PI
    if (theta < -PI) theta += 2 * PI
    theta *= sn
    return (ra * sin(theta) + xo + 0.5).toInt() to (ro - ra * cos(theta) + yo + 0.5).toInt()
}

/** Latest short-term forecast issue (02,05,...,23 h, available ~10 min after). Returns base_date, base_time. */
fun kmaBase(now: LocalDateTime): Pair<String, String> {
    val t = now.minusMinutes(10)
    val h = listOf(23, 20, 17, 14, 11, 8, 5, 2).firstOrNull { it <= t.hour }
    val day = if (h == null) t.toLocalDate().minusDays(1) else t.toLocalDate()
    return day.format(DateTimeFormatter.BASIC_ISO_DATE) to "%02d00".format(h ?: 23)
}

/** Short-term forecast JSON -> one DayWeather per date (max rain chance before/after noon, TMN/TMX). */
fun parseShort(json: String): List<DayWeather> {
    val items = JSONObject(json).getJSONObject("response").getJSONObject("body").getJSONObject("items").getJSONArray("item")
    data class Acc(var am: Int = 0, var pm: Int = 0, var mn: Int? = null, var mx: Int? = null)
    val by = sortedMapOf<String, Acc>()
    for (i in 0 until items.length()) {
        val o = items.getJSONObject(i)
        val a = by.getOrPut(o.getString("fcstDate")) { Acc() }
        val v = o.getString("fcstValue").toDoubleOrNull() ?: continue
        when (o.getString("category")) {
            "POP" -> if (o.getString("fcstTime") < "1200") a.am = max(a.am, v.toInt()) else a.pm = max(a.pm, v.toInt())
            "TMN" -> a.mn = v.roundToInt()
            "TMX" -> a.mx = v.roundToInt()
        }
    }
    return by.map { (d, a) -> DayWeather(LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE), a.am, a.pm, a.mn, a.mx) }
}

/** Mid-term land forecast JSON (issued at [issued]) -> days 4..10 rain chance. Days 8+ have a single value. */
fun parseMid(json: String, issued: LocalDate): List<DayWeather> {
    val o = JSONObject(json).getJSONObject("response").getJSONObject("body").getJSONObject("items").getJSONArray("item").getJSONObject(0)
    return (4..10).mapNotNull { n ->
        val am = o.optInt("rnSt${n}Am", -1).takeIf { it >= 0 } ?: o.optInt("rnSt$n", -1).takeIf { it >= 0 } ?: return@mapNotNull null
        val pm = o.optInt("rnSt${n}Pm", -1).takeIf { it >= 0 } ?: am
        DayWeather(issued.plusDays(n.toLong()), am, pm)
    }
}

/** Mid-term land forecast region by nearest reference city. ponytail: nearest-city lookup; a region polygon fixes border towns. */
fun midRegion(lat: Double, lon: Double): String = listOf(
    Triple("11B00000", 37.57, 126.98), Triple("11D10000", 37.88, 127.73), Triple("11D20000", 37.75, 128.90),
    Triple("11C20000", 36.35, 127.38), Triple("11C10000", 36.64, 127.49), Triple("11F20000", 35.16, 126.85),
    Triple("11F10000", 35.82, 127.15), Triple("11H10000", 35.87, 128.60), Triple("11H20000", 35.18, 129.08),
    Triple("11G00000", 33.50, 126.53),
).minBy { (_, la, lo) -> (la - lat).pow(2) + (lo - lon).pow(2) }.first

/** Indoor places for rainy days. ponytail: name keywords; the tourism API category (문화시설) replaces this when present. */
fun indoor(name: String) = Regex("박물관|미술관|전시|과학관|아쿠아|수족관|실내|백화점|아울렛|몰|시장|카페|식당|맛집|숙소|호텔|리조트").containsMatchIn(name)

private val dow = listOf("월", "화", "수", "목", "금", "토", "일")
fun dayName(d: LocalDate) = dow[d.dayOfWeek.value - 1] + "요일"

/** Weather advice for the plan: per rainy day, umbrella plus moving outdoor stops to a dry day or swapping in indoor ones. */
fun weatherAdvice(plan: List<Place>, done: Int, start: LocalDate, weather: List<DayWeather>): List<String> {
    val byDate = weather.associateBy { it.date }
    val days = plan.drop(done).groupBy { it.day }.toSortedMap()
    val dry = days.keys.filter { (byDate[start.plusDays(it - 1L)]?.let { w -> max(w.rainAm, w.rainPm) } ?: 100) < 40 }
    return days.mapNotNull { (day, stops) ->
        val w = byDate[start.plusDays(day - 1L)] ?: return@mapNotNull null
        val rain = max(w.rainAm, w.rainPm)
        if (rain < 60) return@mapNotNull null
        val part = if (w.rainAm >= 60 && w.rainPm >= 60) "하루 종일" else if (w.rainAm >= 60) "오전" else "오후"
        val outdoor = stops.filter { !it.stay && !indoor(it.name) }
        buildString {
            append("${dayName(w.date)}(${day}일차) $part 비 ${rain}%예요. 우산 챙기세요.")
            val to = dry.firstOrNull { it != day }
            if (outdoor.isNotEmpty()) append(
                if (to != null) " ${outdoor.first().name.j("은", "는")} 맑은 ${dayName(start.plusDays(to - 1L))}(${to}일차)로 옮겨 보세요."
                else " ${outdoor.first().name} 대신 박물관 같은 실내 장소를 추천해요.")
        }
    }
}

// ---------- place card (Korea Tourism Organization) ----------

/** Hours and phone for a place card. Either may be missing; the screen shows "정보 없음" for that one. */
data class PlaceInfo(val name: String, val hours: String?, val phone: String?, val closed: String?)

/** Pick the first non-blank field among the type-specific names the tourism API uses (관광지/문화시설/음식점/레포츠/쇼핑). */
private fun JSONObject.first(vararg keys: String) = keys.map { optString(it).trim() }.firstOrNull { it.isNotEmpty() }
    ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")?.replace(Regex("<[^>]+>"), "")?.trim()

fun parseIntro(name: String, json: String, tel: String?): PlaceInfo {
    val items = JSONObject(json).getJSONObject("response").getJSONObject("body").optJSONObject("items")
    val o = items?.optJSONArray("item")?.optJSONObject(0) ?: items?.optJSONObject("item") ?: JSONObject()
    return PlaceInfo(
        name,
        hours = o.first("usetime", "usetimeculture", "opentimefood", "usetimeleports", "opentime"),
        phone = o.first("infocenter", "infocenterculture", "infocenterfood", "infocenterleports", "infocentershopping") ?: tel?.takeIf { it.isNotBlank() },
        closed = o.first("restdate", "restdateculture", "restdatefood", "restdateleports", "restdateshopping"),
    )
}

/** First keyword hit: content id, content type id, tel. */
fun parseSearch(json: String): Triple<String, String, String?>? {
    val items = JSONObject(json).getJSONObject("response").getJSONObject("body").optJSONObject("items") ?: return null
    val o = items.optJSONArray("item")?.optJSONObject(0) ?: items.optJSONObject("item") ?: return null
    return Triple(o.getString("contentid"), o.getString("contenttypeid"), o.optString("tel").ifBlank { null })
}

/** Digits a dialer can take: "054-746-9913 / 문의" -> "054-746-9913". */
fun dialable(phone: String?) = phone?.let { Regex("0\\d{1,2}-?\\d{3,4}-?\\d{4}|1\\d{3}-?\\d{4}").find(it)?.value }

// ---------- shared trip (Firebase Realtime Database over REST; no Play services on Pleos) ----------

/** 4-digit code another car types on its keypad to join, e.g. "4821". ponytail: 10,000 codes is plenty for a demo; longer codes for production. */
fun shareCode(r: kotlin.random.Random = kotlin.random.Random.Default) = "%06d".format(r.nextInt(1_000_000))

fun placeJson(p: Place): JSONObject = JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("day", p.day).put("stay", p.stay)
    .apply { p.park?.let { put("park", JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) } }

fun placeOf(o: JSONObject): Place = Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optInt("day", 1), o.optBoolean("stay"),
    o.optJSONObject("park")?.let { Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon")) })

fun planJson(plan: List<Place>, done: Int, by: String, title: String = "", start: String = "", days: Int = 0): String = JSONObject()
    .put("done", done).put("by", by).put("at", System.currentTimeMillis())
    .put("title", title).put("start", start).put("days", days)
    .put("stops", JSONArray(plan.map(::placeJson)))
    .toString()

fun planOf(json: String): Pair<List<Place>, Int>? {
    if (json.isBlank() || json == "null") return null
    val o = JSONObject(json)
    val a = o.optJSONArray("stops") ?: return null
    return List(a.length()) { placeOf(a.getJSONObject(it)) } to o.optInt("done")
}

/** The shared trip's name and dates, so a joined car shows the same trip. */
data class TripInfo(val title: String, val start: String, val days: Int)
fun tripInfo(json: String): TripInfo = JSONObject(json).let { TripInfo(it.optString("title"), it.optString("start"), it.optInt("days")) }

/** What changed between our plan and the companion's, in one spoken line. */
fun planDiff(old: List<Place>, new: List<Place>): String? {
    val added = new.filter { n -> old.none { it.name == n.name } }
    val removed = old.filter { o -> new.none { it.name == o.name } }
    return when {
        added.isNotEmpty() && removed.isNotEmpty() -> "동행 차가 계획을 바꿨어요. ${removed.first().name} 대신 ${added.first().name.j("이에요", "예요")}."
        added.isNotEmpty() -> "동행 차가 ${added.first().name.j("을", "를")} 계획에 넣었어요."
        removed.isNotEmpty() -> "동행 차가 ${removed.first().name.j("을", "를")} 계획에서 뺐어요."
        old.map { it.name } != new.map { it.name } -> "동행 차가 순서를 바꿨어요."
        else -> null
    }
}

/** "동행 차는 3.2km 뒤" style line; [ahead] is true when the companion is closer to the next stop. */
fun companionLine(meters: Double, ahead: Boolean) = if (meters < 150) "동행 차가 바로 옆에 있어요" else
    "동행 차는 " + (if (meters < 1000) "${(meters / 100).roundToInt() * 100}m" else "%.1fkm".format(meters / 1000)) + if (ahead) " 앞" else " 뒤"

// ---------- parking near a stop (national parking standard data, bundled as assets/parking.tsv) ----------

data class Parking(
    val name: String, val public: Boolean, val lat: Double, val lon: Double,
    val baseMin: Int, val baseFee: Int, val addMin: Int, val addFee: Int, val dayFee: Int, val phone: String?, val free: Boolean,
)

/** One row of parking.tsv: name, 공영/민영, lat, lon, basicTime, basicCharge, addUnitTime, addUnitCharge, dayCmmtkt, phone, 무료/유료. */
fun parkingOf(line: String): Parking? = line.split('\t').takeIf { it.size >= 11 }?.let { f ->
    val lat = f[2].toDoubleOrNull() ?: return null; val lon = f[3].toDoubleOrNull() ?: return null
    fun n(i: Int) = f[i].trim().toIntOrNull() ?: 0
    Parking(f[0], f[1].contains("공영"), lat, lon, n(4), n(5), n(6), n(7), n(8), f[9].ifBlank { null }, f[10].contains("무료"))
}

/** Three lots within 1 km of [p]: public first, then by distance. */
fun nearParking(all: List<Parking>, p: Place, n: Int = 3, radiusM: Double = 1000.0): List<Parking> =
    all.map { it to meters(p, it.lat, it.lon) }.filter { it.second <= radiusM }
        .sortedWith(compareBy({ !it.first.public }, { it.second })).take(n).map { it.first }

/** A parking lot to pick for a stop, with its one-line description. */
data class Lot(val place: Place, val line: String)

/** Bundled public lots (with fees) plus Kakao lots (often private, no fees), nearest first; a Kakao lot within 40 m of a public one is the same lot. */
fun mergeLots(stop: Place, public: List<Parking>, kakao: List<Place>, n: Int = 8): List<Lot> {
    val pub = public.map { Lot(Place(it.name, it.lat, it.lon), parkingLine(it, stop)) }
    val other = kakao.filter { k -> public.none { meters(k, it.lat, it.lon) < 40 } }
        .map { Lot(it, (if ("공영" in it.name) "공영" else "주차장") + " · 도보 ${walkMin(stop, it)}분") }
    return (pub + other).sortedBy { meters(stop, it.place.lat, it.place.lon) }.take(n)
}

/** Walking minutes at 4 km/h, at least 1. */
fun walkMin(a: Place, b: Place): Int = (meters(a, b.lat, b.lon) / 67).roundToInt().coerceAtLeast(1)

/** "공영 · 도보 4분 · 30분 1,000원, 10분당 300원 · 054-779-6077" */
fun parkingLine(k: Parking, from: Place): String = listOfNotNull(
    if (k.public) "공영" else "민영",
    "도보 ${walkMin(from, Place(k.name, k.lat, k.lon))}분",
    when {
        k.free -> "무료"
        k.baseFee > 0 -> "${k.baseMin}분 %,d원".format(k.baseFee) + (if (k.addFee > 0) ", ${k.addMin}분당 %,d원".format(k.addFee) else "") + (if (k.dayFee > 0) ", 하루 %,d원".format(k.dayFee) else "")
        else -> "요금 정보 없음"
    },
    k.phone ?: "전화번호 없음",
).joinToString(" · ")

// ---------- cheapest fuel / a fast charger on the way ----------

/** Kakao Mobility directions JSON -> route polyline as (lat, lon). vertexes are flat [lon, lat, lon, lat, ...]. */
/** Kakao directions summary as a leg: road km, toll in won, driving minutes. */
fun parseLeg(json: String): Leg? {
    val s = JSONObject(json).optJSONArray("routes")?.optJSONObject(0)?.takeIf { it.optInt("result_code") == 0 }?.optJSONObject("summary") ?: return null
    return Leg(s.optInt("distance") / 1000.0, s.optJSONObject("fare")?.optInt("toll") ?: 0, (s.optInt("duration") + 30) / 60)
}

fun parseRoute(json: String): List<Pair<Double, Double>> {
    val out = mutableListOf<Pair<Double, Double>>()
    val sections = JSONObject(json).getJSONArray("routes").getJSONObject(0).optJSONArray("sections") ?: return out
    for (i in 0 until sections.length()) {
        val roads = sections.getJSONObject(i).optJSONArray("roads") ?: continue
        for (j in 0 until roads.length()) {
            val v = roads.getJSONObject(j).getJSONArray("vertexes")
            for (k in 0 until v.length() - 1 step 2) out += v.getDouble(k + 1) to v.getDouble(k)
        }
    }
    return out
}

/** Points every [stepKm] along the route up to [maxKm], each with its km from the start. */
fun samplePoints(poly: List<Pair<Double, Double>>, maxKm: Double, stepKm: Double = 5.0): List<Triple<Double, Double, Double>> {
    val out = mutableListOf<Triple<Double, Double, Double>>()
    var km = 0.0; var next = stepKm
    for (i in 1 until poly.size) {
        val (la0, lo0) = poly[i - 1]; val (la, lo) = poly[i]
        val seg = meters(Place("", la0, lo0), la, lo) / 1000
        while (seg > 0 && km + seg >= next && next <= maxKm) { // interpolate inside long segments
            val t = (next - km) / seg
            out += Triple(la0 + (la - la0) * t, lo0 + (lo - lo0) * t, next); next += stepKm
        }
        km += seg
        if (km > maxKm) break
    }
    return out
}

// KATEC (Opinet): TM on Bessel, origin 38N 128E, k0 0.9999, false E/N 400000/600000.
// Datum shift measured against Kakao place search for 6 Seoul stations: WGS84 = Bessel + (0.00278, -0.00210) deg (~300 m).
private const val KA = 6377397.155
private const val KF = 1 / 299.1528128
private const val KE2 = 2 * KF - KF * KF
private const val KEP2 = KE2 / (1 - KE2)
private const val K0 = 0.9999
private const val DLAT = 0.00278
private const val DLON = -0.00210
private fun km(p: Double) = KA * ((1 - KE2 / 4 - 3 * KE2 * KE2 / 64 - 5 * KE2.pow(3) / 256) * p - (3 * KE2 / 8 + 3 * KE2 * KE2 / 32 + 45 * KE2.pow(3) / 1024) * sin(2 * p) +
    (15 * KE2 * KE2 / 256 + 45 * KE2.pow(3) / 1024) * sin(4 * p) - (35 * KE2.pow(3) / 3072) * sin(6 * p))

/** WGS84 lat/lon -> KATEC x, y. */
fun katec(lat: Double, lon: Double): Pair<Double, Double> {
    val d = PI / 180; val phi = (lat - DLAT) * d; val lam = (lon - DLON - 128.0) * d
    val n = KA / sqrt(1 - KE2 * sin(phi).pow(2)); val t = tan(phi).pow(2); val c = KEP2 * cos(phi).pow(2); val aa = lam * cos(phi)
    val x = 400000 + K0 * n * (aa + (1 - t + c) * aa.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * KEP2) * aa.pow(5) / 120)
    val y = 600000 + K0 * (km(phi) - km(38.0 * d) + n * tan(phi) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24 + (61 - 58 * t + t * t + 600 * c - 330 * KEP2) * aa.pow(6) / 720))
    return x to y
}

/** KATEC x, y -> WGS84 lat/lon (inverse of [katec]). */
fun katecToWgs(x: Double, y: Double): Pair<Double, Double> {
    val d = PI / 180
    val mu = (km(38.0 * d) + (y - 600000) / K0) / (KA * (1 - KE2 / 4 - 3 * KE2 * KE2 / 64 - 5 * KE2.pow(3) / 256))
    val e1 = (1 - sqrt(1 - KE2)) / (1 + sqrt(1 - KE2))
    val p1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) + (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
        (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
    val c1 = KEP2 * cos(p1).pow(2); val t1 = tan(p1).pow(2); val n1 = KA / sqrt(1 - KE2 * sin(p1).pow(2))
    val r1 = KA * (1 - KE2) / (1 - KE2 * sin(p1).pow(2)).pow(1.5); val dd = (x - 400000) / (n1 * K0)
    val lat = p1 - (n1 * tan(p1) / r1) * (dd * dd / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * KEP2) * dd.pow(4) / 24 +
        (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * KEP2 - 3 * c1 * c1) * dd.pow(6) / 720)
    val lon = 128 * d + (dd - (1 + 2 * t1 + c1) * dd.pow(3) / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * KEP2 + 24 * t1 * t1) * dd.pow(5) / 120) / cos(p1)
    return lat / d + DLAT to lon / d + DLON
}

data class Station(val name: String, val price: Int, val lat: Double, val lon: Double, val km: Double)

/** Opinet aroundAll JSON (out=json) -> stations with price, at their own position (KATEC -> WGS84). */
fun parseOpinet(json: String, sample: Triple<Double, Double, Double>): List<Station> {
    val a = JSONObject(json).getJSONObject("RESULT").optJSONArray("OIL") ?: return emptyList()
    return List(a.length()) { a.getJSONObject(it) }.map { o ->
        val (la, lo) = if (o.has("GIS_X_COOR")) katecToWgs(o.getDouble("GIS_X_COOR"), o.getDouble("GIS_Y_COOR")) else sample.first to sample.second
        Station(o.getString("OS_NM"), o.optDouble("PRICE").toInt(), la, lo, sample.third + o.optDouble("DISTANCE") / 1000)
    }
}

/** Korea Environment Corporation charger list JSON -> fast chargers that are free now (stat 2), as stations with price 0. */
fun parseChargers(json: String): List<Station> {
    val items = JSONObject(json).optJSONObject("items")?.optJSONArray("item")
        ?: JSONObject(json).optJSONObject("response")?.optJSONObject("body")?.optJSONObject("items")?.optJSONArray("item") ?: return emptyList()
    return List(items.length()) { items.getJSONObject(it) }
        .filter { it.optString("stat") == "2" && (it.optString("output").toIntOrNull() ?: 0) >= 50 }
        .mapNotNull { o -> Station(o.getString("statNm"), 0, o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null, o.optString("lng").toDoubleOrNull() ?: return@mapNotNull null, 0.0) }
        .distinctBy { it.name }
}

/** Cheapest station on the way, keeping a 20% reserve of [rangeKm]. Ties go to the earlier one. */
fun cheapest(stations: List<Station>, rangeKm: Int): Station? =
    stations.filter { it.km <= rangeKm * 0.8 && it.price > 0 }.minWithOrNull(compareBy({ it.price }, { it.km }))

/** For EVs: the fast charger nearest to the point where ~60% of range is used (or the route midpoint if shorter). */
fun chargerOnWay(chargers: List<Station>, samples: List<Triple<Double, Double, Double>>, rangeKm: Int, routeKm: Double): Station? {
    val target = minOf(rangeKm * 0.6, routeKm / 2)
    return chargers.mapNotNull { c ->
        val s = samples.minByOrNull { meters(Place("", it.first, it.second), c.lat, c.lon) } ?: return@mapNotNull null
        val off = meters(Place("", s.first, s.second), c.lat, c.lon)
        if (off > 2000) null else c.copy(km = s.third)
    }.filter { it.km <= rangeKm * 0.8 }.minByOrNull { abs(it.km - target) }
}

fun fuelLine(rangeKm: Int, s: Station) =
    "지금 ${rangeKm}km 갈 수 있어요. " + if (s.price > 0) "${s.km.roundToInt()}km 앞 ${s.name.j("이", "가")} 리터당 %,d원으로 가장 싸요. 경유지로 넣을까요?".format(s.price)
    else "${s.km.roundToInt()}km 앞 ${s.name}에 급속 충전기가 비어 있어요. 경유지로 넣을까요?"

/** Region code (zcode) for the charger API by nearest city. ponytail: nearest-city lookup like midRegion. */
fun zcode(lat: Double, lon: Double): String = listOf(
    Triple("11", 37.57, 126.98), Triple("26", 35.18, 129.08), Triple("27", 35.87, 128.60), Triple("28", 37.46, 126.71),
    Triple("29", 35.16, 126.85), Triple("30", 36.35, 127.38), Triple("31", 35.54, 129.31), Triple("36", 36.48, 127.29),
    Triple("41", 37.27, 127.01), Triple("51", 37.88, 127.73), Triple("43", 36.64, 127.49), Triple("44", 36.66, 126.67),
    Triple("52", 35.82, 127.15), Triple("46", 34.82, 126.46), Triple("47", 36.02, 129.34), Triple("48", 35.24, 128.69), Triple("50", 33.50, 126.53),
).minBy { (_, la, lo) -> (la - lat).pow(2) + (lo - lon).pow(2) }.first

// ---------- place search for "+" (Kakao Local keyword search) ----------

/** One search result: the place plus what helps choose it. */
data class Hit(val place: Place, val address: String, val category: String) {
    val lodging get() = category == "숙박"
}

fun parseKakaoPlaces(json: String): List<Hit> {
    val a = JSONObject(json).optJSONArray("documents") ?: return emptyList()
    return List(a.length()) { a.getJSONObject(it) }.mapNotNull { o ->
        val lat = o.optString("y").toDoubleOrNull() ?: return@mapNotNull null
        val lon = o.optString("x").toDoubleOrNull() ?: return@mapNotNull null
        Hit(Place(o.getString("place_name"), lat, lon), o.optString("road_address_name").ifBlank { o.optString("address_name") }, o.optString("category_group_name"))
    }
}
