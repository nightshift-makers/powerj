package com.beat.powerj

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

/**
 * Features that need the internet and an API key: weather, place card, shared trip, visit counts.
 * Each one is off when its key is blank, so the app still builds and runs without keys.
 */
class Live(private val ctx: Context, private val log: (String) -> Unit) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dataKey = BuildConfig.DATA_GO_KR
    private val fb = BuildConfig.FIREBASE.trimEnd('/')
    val weatherOn get() = dataKey.isNotBlank()
    val placeOn get() = dataKey.isNotBlank()
    val shareOn get() = fb.isNotBlank()
    val fuelOn get() = BuildConfig.KAKAO.isNotBlank()

    private fun bg(work: () -> Unit) = io.execute { runCatching(work).onFailure { log("live error: $it") } }
    private fun ui(f: () -> Unit) = main.post(f)

    // ---------- weather ----------

    /** Short-term (3 days, 5 km grid) plus mid-term (to day 10, region) for the area of the next stop. */
    fun weather(at: Place, done: (List<DayWeather>) -> Unit) {
        if (!weatherOn) return
        bg {
            val (nx, ny) = kmaGrid(at.lat, at.lon)
            val (bd, bt) = kmaBase(LocalDateTime.now())
            val short = parseShort(Net.get("https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst" +
                "?serviceKey=${Net.enc(dataKey)}&pageNo=1&numOfRows=1000&dataType=JSON&base_date=$bd&base_time=$bt&nx=$nx&ny=$ny"))
            val now = LocalDateTime.now()
            val issued = if (now.hour >= 18) now.toLocalDate() else if (now.hour >= 6) now.toLocalDate() else now.toLocalDate().minusDays(1)
            val tm = issued.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + if (now.hour in 6..17) "0600" else "1800"
            val mid = runCatching {
                parseMid(Net.get("https://apis.data.go.kr/1360000/MidFcstInfoService/getMidLandFcst" +
                    "?serviceKey=${Net.enc(dataKey)}&pageNo=1&numOfRows=10&dataType=JSON&regId=${midRegion(at.lat, at.lon)}&tmFc=$tm"), issued)
            }.getOrElse { log("mid forecast: $it"); emptyList() }
            val all = (short + mid.filter { m -> short.none { it.date == m.date } }).sortedBy { it.date }
            ui { done(all) }
        }
    }

    // ---------- place card ----------

    private val parking: List<Parking> by lazy {
        runCatching { ctx.assets.open("parking.tsv").bufferedReader().readLines().mapNotNull(::parkingOf) }.getOrElse { emptyList() }
    }

    /** Hours and phone from the tourism API, and the 3 nearest lots (public first) from the bundled parking data. */
    fun placeCard(p: Place, done: (PlaceInfo?, List<String>) -> Unit) = bg {
        val lots = nearParking(parking, p).map { "${it.name} · ${parkingLine(it, p)}" }
        val info = if (!placeOn) null else runCatching {
            val base = "https://apis.data.go.kr/B551011/KorService2"
            val common = "serviceKey=${Net.enc(dataKey)}&MobileOS=AND&MobileApp=POWERJ&_type=json"
            val hit = parseSearch(Net.get("$base/searchKeyword2?$common&numOfRows=1&pageNo=1&keyword=${Net.enc(p.name)}")) ?: return@runCatching null
            parseIntro(p.name, Net.get("$base/detailIntro2?$common&contentId=${hit.first}&contentTypeId=${hit.second}"), hit.third)
        }.getOrElse { log("place card: $it"); null }
        log("place card ${p.name} hours=${info?.hours} phone=${info?.phone} lots=${lots.size}")
        ui { done(info, lots) }
    }

    /** Parking lots within 800 m of [p]: bundled public data plus Kakao's "주차장" category. */
    fun lots(p: Place, done: (List<Lot>) -> Unit) = bg {
        val pub = nearParking(parking, p, n = 6, radiusM = 800.0)
        val kakao = if (!fuelOn) emptyList() else runCatching {
            parseKakaoPlaces(Net.get("https://dapi.kakao.com/v2/local/search/category.json?category_group_code=PK6&x=${p.lon}&y=${p.lat}&radius=800&sort=distance&size=10",
                "KakaoAK ${BuildConfig.KAKAO}")).map { it.place }
        }.getOrElse { log("lots kakao: $it"); emptyList() }
        val all = mergeLots(p, pub, kakao)
        ui { done(all) }
    }

    // ---------- place search for "+" ----------

    /** Kakao keyword search, biased to [near] when known. */
    fun search(query: String, near: Place?, done: (List<Hit>) -> Unit) {
        if (!fuelOn || query.isBlank()) return
        bg {
            val at = near?.let { "&x=${it.lon}&y=${it.lat}&sort=accuracy" } ?: ""
            val hits = parseKakaoPlaces(Net.get("https://dapi.kakao.com/v2/local/search/keyword.json?query=${Net.enc(query)}&size=10$at", "KakaoAK ${BuildConfig.KAKAO}"))
            log("search '$query' -> ${hits.size}")
            ui { done(hits) }
        }
    }

    /** Quick picks around [p]: places other cars went to most, then restaurants and sights within 5 km. */
    fun nearby(p: Place, done: (List<Hit>) -> Unit) = bg {
        val crowd = if (!shareOn) emptyList() else runCatching {
            val o = JSONObject(Net.get("$fb/visits/${area(p.lat, p.lon)}.json").let { if (it == "null") "{}" else it })
            o.keys().asSequence().map { it to o.getJSONObject(it) }.sortedByDescending { it.second.optInt("n") }.take(5)
                .map { (name, v) -> Hit(Place(name, v.getDouble("lat"), v.getDouble("lon")), "", "차 ${v.optInt("n")}대가 다녀갔어요") }.toList()
        }.getOrElse { log("nearby crowd: $it"); emptyList() }
        val local = if (!fuelOn) emptyList() else listOf("FD6", "CE7", "AT4").flatMap { code -> // restaurants, cafes, sights
            runCatching { parseKakaoPlaces(Net.get("https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$code" +
                "&x=${p.lon}&y=${p.lat}&radius=5000&sort=accuracy&size=6", "KakaoAK ${BuildConfig.KAKAO}")) }.getOrElse { log("nearby $code: $it"); emptyList() }
        }
        val all = crowd + local.filter { l -> crowd.none { it.place.name == l.place.name } }
        ui { done(all) }
    }

    /** Road km and tolls over consecutive legs; a leg the route API can't answer counts as straight line x 1.3, no toll. */
    fun estimate(points: List<Place>, done: (legs: List<Leg>) -> Unit) {
        if (points.size < 2) return
        bg {
            val legs = points.zipWithNext().map { (a, b) ->
                val leg = if (!fuelOn) null else runCatching {
                    parseLeg(Net.get("https://apis-navi.kakaomobility.com/v1/directions?origin=${a.lon},${a.lat}&destination=${b.lon},${b.lat}&summary=true", "KakaoAK ${BuildConfig.KAKAO}"))
                }.getOrElse { log("estimate leg: $it"); null }
                leg ?: (meters(a, b.lat, b.lon) * 1.3 / 1000).let { km -> Leg(km, 0, (km / 40 * 60).toInt()) } // ponytail: 40 km/h when the route API fails
            }
            ui { done(legs) }
        }
    }

    // ---------- fuel / charger on the way ----------

    /** Route from Kakao, then the cheapest station (Opinet) or a free fast charger (Korea Environment Corp.) within range. */
    fun fuelStop(from: Place, to: Place, rangeKm: Int, ev: Boolean, done: (Station?) -> Unit) {
        if (!fuelOn) return
        bg {
            val poly = parseRoute(Net.get("https://apis-navi.kakaomobility.com/v1/directions?origin=${from.lon},${from.lat}&destination=${to.lon},${to.lat}",
                "KakaoAK ${BuildConfig.KAKAO}"))
            val best = if (!ev) {
                val pts = samplePoints(poly, rangeKm * 0.8, 10.0).take(20)
                cheapest(pts.flatMap { p ->
                    val (x, y) = katec(p.first, p.second)
                    runCatching { parseOpinet(Net.get("https://www.opinet.co.kr/api/aroundAll.do?code=${BuildConfig.OPINET}&out=json&x=$x&y=$y&radius=3000&prodcd=B027&sort=1"), p) }.getOrElse { emptyList() }
                }, rangeKm)
            } else {
                val pts = samplePoints(poly, 1e9, 1.0)
                val chargers = pts.map { zcode(it.first, it.second) }.distinct().take(3).flatMap { z ->
                    runCatching { parseChargers(Net.get("https://apis.data.go.kr/B552584/EvCharger/getChargerInfo?serviceKey=${Net.enc(dataKey)}&pageNo=1&numOfRows=9999&zcode=$z&dataType=JSON")) }
                        .getOrElse { log("chargers $z: $it"); emptyList() }
                }
                chargerOnWay(chargers, pts, rangeKm, pts.lastOrNull()?.third ?: 0.0)
            }
            log("fuel stop ev=$ev range=$rangeKm best=$best")
            ui { done(best) }
        }
    }

    // ---------- shared trip and visit counts (Firebase Realtime Database, REST) ----------

    val me: String = ctx.getSharedPreferences("powerj", Context.MODE_PRIVATE).let { p ->
        p.getString("me", null) ?: java.util.UUID.randomUUID().toString().take(8).also { p.edit().putString("me", it).apply() }
    }

    /** The host ended sharing: remove the trip so joined cars notice and leave. */
    fun end(code: String) = if (shareOn) bg { Net.send("DELETE", "$fb/trips/$code.json", "") } else Unit

    /** Does a shared trip with this code exist? null = couldn't reach the server. */
    fun exists(code: String, done: (Boolean?) -> Unit) {
        if (!shareOn) return done(null)
        io.execute {
            val r = runCatching { Net.get("$fb/trips/$code/plan.json") != "null" }.onFailure { log("live error: $it") }.getOrNull()
            ui { done(r) }
        }
    }

    fun push(code: String, plan: List<Place>, done: Int, title: String, start: String, days: Int) =
        if (shareOn) bg { Net.send("PUT", "$fb/trips/$code/plan.json", planJson(plan, done, me, title, start, days)) } else Unit

    fun pushPosition(code: String, lat: Double, lon: Double, progress: Float, done: Int, host: Boolean) = if (shareOn) bg {
        Net.send("PUT", "$fb/trips/$code/cars/$me.json", JSONObject().put("lat", lat).put("lon", lon).put("at", System.currentTimeMillis())
            .put("p", progress.toDouble()).put("done", done).put("host", host).toString())
    } else Unit

    data class Car(val id: String, val lat: Double, val lon: Double, val at: Long, val progress: Float, val done: Int, val host: Boolean)

    /** The host's plan (when someone else changed it after [since]) and every other car, newest first. */
    fun pull(code: String, since: Long, done: (plan: Pair<List<Place>, Int>?, at: Long, others: List<Car>, info: TripInfo?, gone: Boolean) -> Unit) {
        if (!shareOn) return
        bg {
            val o = JSONObject(Net.get("$fb/trips/$code.json").let { if (it == "null") "{}" else it })
            val p = o.optJSONObject("plan")
            val changed = p?.takeIf { it.optString("by") != me && it.optLong("at") > since }
            val cars = o.optJSONObject("cars")?.let { c -> c.keys().asSequence().filter { it != me }.map { k -> c.getJSONObject(k).let {
                Car(k, it.getDouble("lat"), it.getDouble("lon"), it.optLong("at"), it.optDouble("p", 0.0).toFloat(), it.optInt("done"), it.optBoolean("host"))
            } }.filter { System.currentTimeMillis() - it.at < 120_000 } // cars push every 10 s; silent ones left or were reset
                .sortedByDescending { it.at }.toList() } ?: emptyList()
            ui { done(changed?.let { planOf(it.toString()) }, p?.optLong("at") ?: since, cars, changed?.let { tripInfo(it.toString()) }, p == null) }
        }
    }

    private fun area(lat: Double, lon: Double) = "%d_%d".format((lat * 10).toInt(), (lon * 10).toInt()) // ~10 km
    private fun keyOf(name: String) = name.replace(Regex("[.$#\\[\\]/]"), " ").trim()

    /** One more car reached this place. Only the place and a +1 go up; no route, no car id. */
    fun visit(p: Place) = if (shareOn) bg {
        val url = "$fb/visits/${area(p.lat, p.lon)}/${Net.enc(keyOf(p.name)).replace("+", "%20")}.json"
        val n = JSONObject(Net.get(url).let { if (it == "null") "{}" else it }).optInt("n")
        Net.send("PUT", url, JSONObject().put("n", n + 1).put("lat", p.lat).put("lon", p.lon).toString())
    } else Unit

    /** Places other cars drove to around [p], most visited first: "첨성대 · 128대". */
    fun popular(p: Place, done: (List<String>) -> Unit) {
        if (!shareOn) return
        bg {
            val o = JSONObject(Net.get("$fb/visits/${area(p.lat, p.lon)}.json").let { if (it == "null") "{}" else it })
            val top = o.keys().asSequence().map { it to o.getJSONObject(it).optInt("n") }.sortedByDescending { it.second }.take(5)
                .map { (name, n) -> "$name · ${n}대" }.toList()
            ui { done(top) }
        }
    }
}

/** Plan day dates: day 1 is [start]. */
fun planDate(start: LocalDate, day: Int): LocalDate = start.plusDays(day - 1L)
