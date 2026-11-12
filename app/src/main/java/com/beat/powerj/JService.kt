package com.beat.powerj

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Calendar

/** Reads the car and the navigation, decides with Rules.kt, speaks and updates Store. */
class JService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviHelper
    private lateinit var voice: Voice
    private lateinit var mem: Memory
    private lateinit var live: Live
    private var weather: List<DayWeather> = emptyList()
    private var sharedAt = 0L
    private var lastCardFor: String? = null
    private var joining = false
    private var joinFails = 0 // wrong codes in a row; 5 locks joining for a minute
    private val undo = mutableMapOf<Int, ArrayDeque<Memory.TripData>>() // per trip: states before each edit, newest last
    private val base = mutableMapOf<Int, Memory.TripData>() // per trip: the saved state "수정 취소" goes back to

    // ponytail: the Pleos emulator reports seat occupancy inverted; real cars use the raw value
    private val invertSeats = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

    private var gear = 0
    private var speed = 0f
    private var passenger = false
    private val rearDoorOpened = mutableSetOf<Seat>()
    private val rearBelts = mutableSetOf<Seat>()
    private var loc: CurrentLocationInfo? = null
    /** Debug builds: the emulator's navi GPS never moves, so a reached stop becomes our position until the next real move. */
    private var testAt: Place? = null
    private fun placed(l: CurrentLocationInfo) = testAt?.let { l.copy(latitude = it.lat, longitude = it.lon, address = it.name) } ?: l
    private var startCell = ""
    private var tbt: List<TBTInfo> = emptyList()
    private var routing = false
    private var routeDest: Place? = null
    private var capacityWh = 0f
    private var soc = 0
    private var socAtDepart = -1
    private var guiding: String? = null      // plan stop we routed to on this drive
    private val toldWish = mutableSetOf<String>()

    private fun log(s: String) = Log.i("POWERJ", s)
    /** Set while a joined car's edit is being undone, so the edit's own "담았어요" isn't spoken after the refusal. */
    private var muted = false
    private fun say(s: String) { if (muted) return; log("say: $s"); voice.speak(s); Store.set { copy(voice = s) } }
    private val err: (Exception) -> Unit = { log("sdk error: $it") }

    override fun onBind(i: Intent?) = null

    /** Debug builds only: adb shell am start-foreground-service -n com.beat.powerj/.JService --es cmd seed|clear|go|soon|plan|reach|reset|order|late|postpone|wish|visit|break|range */
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debug) when (i?.getStringExtra("cmd")) {
            "seed" -> Store.onSeed?.invoke()
            "clear" -> Store.onClear?.invoke()
            "reset" -> Store.onReset?.invoke()
            "go" -> Store.ui.value.suggestion?.let { Store.onGo?.invoke(it) }
            "soon" -> { h.removeCallbacks(soonTask); h.post(soonTask) } // demo: jump to "3 min before arrival"
            "plan" -> demoPlan()
            "resetcost" -> { mem.tolls = 0; mem.kWh = 0f; mem.tollFor = null; showPlan() } // test drives leave fake tolls behind
            "reach" -> reachNext() // demo: emulator GPS doesn't move, so mark the next stop reached
            "order" -> order()
            "late" -> nextStop(mem.plan, mem.planDone)?.let { offerLate(it, 20 * 60 + 40, null) } // demo: pretend we arrive at 20:40
            "wish" -> { mem.wish = listOf(Place("판교 카페거리", 37.3945, 127.1080)); toldWish.clear(); showPlan() }
            "postpone" -> postponeNext()
            "visit" -> Store.ui.value.wishNear?.let(::visitWish)
            "arrived" -> Store.onArrived?.invoke(true)
            "rain" -> { // demo/test: day 2 afternoon rain, without calling the weather API
                val start = runCatching { java.time.LocalDate.parse(mem.planStart) }.getOrElse { today() }
                applyWeather(listOf(DayWeather(start, 10, 20, 4, 15), DayWeather(start.plusDays(1), 20, 70, 6, 13), DayWeather(start.plusDays(2), 10, 10)))
            }
            "card" -> nextStop(mem.plan, mem.planDone)?.let(::fetchCard)
            "share" -> startShare()
            "newtrip" -> Store.onNewTrip?.invoke()
            "plus" -> { Store.set { copy(addDay = i.getStringExtra("day")?.toIntOrNull() ?: 1) }; i.getStringExtra("q")?.let { Store.onSearch?.invoke(it) } }
            "pick" -> Store.ui.value.hits.firstOrNull()?.let { Store.onAddPlace?.invoke(it, it.lodging) }
            "addday" -> Store.onAddDay?.invoke()
            "dates" -> Store.onSetDates?.invoke(i.getStringExtra("start") ?: today().toString(), i.getStringExtra("days")?.toIntOrNull() ?: 1)
            "drop" -> Store.onDropStop?.invoke(i.getStringExtra("i")?.toIntOrNull() ?: 0, i.getStringExtra("day")?.toIntOrNull() ?: 1, i.getStringExtra("before")?.toIntOrNull())
            "move" -> Store.onMoveStop?.invoke(i.getStringExtra("i")?.toIntOrNull() ?: 0, i.getStringExtra("dir")?.toIntOrNull() ?: 1)
            "delete" -> i.getStringExtra("id")?.toIntOrNull()?.let { Store.onDeleteTrip?.invoke(it) }
            "activate" -> i.getStringExtra("id")?.toIntOrNull()?.let { Store.onActivate?.invoke(it) }
            "join" -> i.getStringExtra("code")?.let(::join)
            "save" -> Store.onSave?.invoke()
            "routine" -> Store.onSaveRoutine?.invoke(Routine(0, i.getStringExtra("name") ?: "등교", // every day, this hour: fires on the next departure
                Place(i.getStringExtra("dest") ?: "판교초등학교", i.getStringExtra("lat")?.toDoubleOrNull() ?: 37.3925, i.getStringExtra("lon")?.toDoubleOrNull() ?: 127.1097),
                WEEKDAYS + WEEKEND, hour(), listOf(Who.CHILD)))
            "rmroutines" -> { mem.routines = emptyList(); showPlan() }
            "modify" -> Store.onEdit?.invoke(i.getStringExtra("id")?.toIntOrNull() ?: mem.activeId) // "수정하기"
            "testday" -> Store.onTestDay?.invoke(i.getStringExtra("n")?.toLongOrNull() ?: 0) // shift "today" by n days
            "fuelgas" -> nextStop(mem.plan, mem.planDone)?.let { findFuel(it, 150, ev = false) } // test the fuel-car path on the EV emulator
            "fuel" -> nextStop(mem.plan, mem.planDone)?.let { to ->
                vehicle.getEvBattery().getEvBatteryStateOfCharge({ soc = it ?: 0; h.post { findFuel(to, rangeKm(0f, capacityWh, soc)) } }, err)
            }
            "break" -> { h.removeCallbacks(breakTask); h.post(breakTask) }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("j", "POWER J", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "j").setContentTitle("POWER J").setSmallIcon(android.R.drawable.ic_menu_compass).build())

        mem = Memory(this)
        live = Live(this) { log(it) }
        voice = Voice(this) { log(it) }.also { it.start() }
        vehicle = Vehicle(applicationContext)
        runCatching { vehicle.initialize() }.onFailure { log("vehicle init failed: $it") }
        if (BuildConfig.DEBUG) testShift = getSharedPreferences("powerj", MODE_PRIVATE).getLong("testDays", 0)
        Store.set { copy(trips = mem.trips.size, testDays = testShift) }
        showPlan()
        refreshWeather()
        h.postDelayed({ tripOfToday(); estimateCost() }, 2000) // a saved trip's day may have come while the car was off

        Store.onDemoPlan = ::demoPlan
        Store.onPostpone = ::postponeNext
        Store.onVisitWish = ::visitWish
        Store.onShare = ::startShare
        Store.onSearch = { q ->
            val day = Store.ui.value.addDay ?: mem.editDay
            val stops = mem.trip(Store.ui.value.editId ?: mem.activeId)?.stops ?: mem.plan
            val near = stops.lastOrNull { it.day <= day } ?: loc?.let { Place("", it.latitude, it.longitude) }
            live.search(q, near) { hits -> Store.set { copy(hits = hits) } }
        }
        Store.onNearby = { day ->
            val stops = mem.trip(Store.ui.value.editId ?: mem.activeId)?.stops ?: mem.plan
            val at = stops.lastOrNull { it.day <= day } ?: stops.firstOrNull() // area is known once the trip has a place
            Store.set { copy(nearby = emptyList()) }
            at?.let { live.nearby(it) { all -> Store.set { copy(nearby = all.filter { h -> stops.none { s -> s.name == h.place.name } }) } } }
        }
        Store.onDropStop = { i, day, before ->
            val active = (Store.ui.value.editId ?: mem.activeId) == mem.activeId
            val next = nextStop(mem.plan, mem.planDone)?.name
            editTrip { d ->
                val moved = dropStop(d.stops, d.done, i, day, before)
                if (moved != d.stops) log("plan dropped ${d.stops[i].name} -> ${moved.map { "${it.day}:${it.name}" }}")
                d.copy(stops = moved, days = maxOf(d.days, day))
            }
            if (active && guiding != null && nextStop(mem.plan, mem.planDone)?.name != next) { dropRoute(); resumePlan() }
        }
        Store.onMoveStop = { i, dir ->
            val active = (Store.ui.value.editId ?: mem.activeId) == mem.activeId
            val before = nextStop(mem.plan, mem.planDone)?.name
            editTrip { d ->
                val moved = moveStop(d.stops, d.done, i, dir, d.days)
                d.copy(stops = moved)
            }
            // the active trip's next stop changed while guiding to the old one: guide to the new one
            if (active && guiding != null && nextStop(mem.plan, mem.planDone)?.name != before) { dropRoute(); resumePlan() }
        }
        Store.onDeleteTrip = { id ->
            Store.set { copy(guide = 0, editing = false) }
            val name = mem.cards().firstOrNull { it.id == id }?.let(::tripTitle) ?: "여행"
            if (id == mem.activeId) { leaveTripShare(); leavePlanRoute() }
            mem.delete(id); mem.drafts = mem.drafts - id; forget(id) // ids get reused, so a deleted draft mark must not stick
            if (mem.planStart.isEmpty()) mem.planStart = today().toString() // fresh empty trip starts today
            Store.set { copy(tips = emptyList(), dayWx = emptyMap(), late = null, check = null, popular = emptyList(), editId = null, addDay = null, hits = emptyList()) }
            planChanged()
            log("trip deleted $id $name")
            say("${name.j("을", "를")} 지웠어요.")
        }
        Store.onRename = { t -> editTrip { it.copy(title = t.trim()) } }
        Store.onAddDay = {
            val id = Store.ui.value.editId ?: mem.activeId
            val d0 = mem.trip(id)
            val next = d0?.let { maxOf(it.days, it.stops.maxOfOrNull { s -> s.day } ?: 1) + 1 }
            val date = d0?.let { runCatching { planDate(java.time.LocalDate.parse(it.start), next!!) }.getOrNull() }
            val other = date?.let { takenDays(mem.cards(), id)[it] }
            if (other != null) {
                say("${next}일차 날짜 ${dayLabel(date.toString(), 1, "").substringAfter(" · ")}에 '${other}' 여행이 있어요. 날짜 정하기로 먼저 옮겨 주세요.")
            } else {
                var day = 1
                editTrip { d -> day = maxOf(d.days, d.stops.maxOfOrNull { it.day } ?: 1) + 1; d.copy(days = day) }
                Store.set { copy(newDay = day) } // highlight the new day's "+ 장소 추가"; no keyboard until they tap it
            }
        }
        Store.onRemoveStop = { i ->
            editTrip { d -> if (i !in d.stops.indices) d else removeStop(d.stops, d.done, i).let { (p, dn) -> d.copy(stops = p, done = dn) } }
        }
        Store.onSetDates = { start, days ->
            if (Store.ui.value.guide == 2) Store.set { copy(guide = 3) }
            var lost = 0; var used = days
            editTrip { d ->
                val (stops, n) = fitDays(d.stops, d.done, days)
                lost = d.stops.size - stops.size; used = n
                d.copy(start = start, stops = stops, days = n)
            }
            log("plan dates $start x$used lost=$lost")
            say(when {
                used > days -> "다녀온 장소가 있어서 ${used}일로 맞췄어요."
                lost > 0 -> "${dayLabel(start, 1, "").substringAfter(" · ")}부터 ${used}일로 줄이고, 빠진 날의 장소 ${lost}곳을 지웠어요."
                else -> "${dayLabel(start, 1, "").substringAfter(" · ")}부터 ${used}일로 정했어요."
            })
        }
        Store.onTestDay = { n ->
            if (BuildConfig.DEBUG) {
                testShift = n
                getSharedPreferences("powerj", MODE_PRIVATE).edit().putLong("testDays", n).apply()
                Store.set { copy(testDays = n, tips = emptyList(), check = null, late = null) }
                say("테스트: 오늘을 ${dayLabel(today().toString(), 1, "").substringAfter(" · ")}로 바꿨어요.")
                showPlan(); refreshWeather(); tripOfToday()
            }
        }
        Store.onOrderDay = { day ->
            val active = (Store.ui.value.editId ?: mem.activeId) == mem.activeId
            val before = nextStop(mem.plan, mem.planDone)?.name
            editTrip { d -> d.copy(stops = orderOneDay(d.stops, d.done, day)) }
            say("${day}일차 동선을 가까운 순서로 바꿨어요.")
            if (active && guiding != null && nextStop(mem.plan, mem.planDone)?.name != before) { dropRoute(); resumePlan() }
        }
        Store.onLots = { p, done -> live.lots(p, done) }
        Store.onSetPark = { i, lot ->
            editTrip { d -> d.copy(stops = d.stops.mapIndexed { k, s -> if (k == i) s.copy(park = lot) else s }) }
            // guiding to that stop right now: go to the new spot
            if (routing && (Store.ui.value.editId ?: mem.activeId) == mem.activeId && i == mem.planDone) nextStop(mem.plan, mem.planDone)?.let { route(it) }
        }
        Store.onPickPark = { lot ->
            Store.set { copy(parkOffer = emptyList()) }
            val i = mem.planDone
            if (lot != null) nextStop(mem.plan, i)?.let { stop ->
                mem.plan = mem.plan.mapIndexed { k, s -> if (k == i) s.copy(park = lot) else s }
                planChanged()
                route(mem.plan[i])
                say("${lot.name.ro()} 안내할게요. ${stop.name}까지 걸어서 ${walkMin(stop, lot)}분이에요.")
            }
        }
        Store.onDemo = { cmd -> if (BuildConfig.DEBUG && cmd == "soon") { h.removeCallbacks(soonTask); h.post(soonTask) } }
        Store.onConsole = { path ->
            // Virtual Console inside the emulator; it ignores writes without a recent session ping
            if (BuildConfig.DEBUG) Thread {
                runCatching {
                    Net.send("POST", "http://127.0.0.1:8088/browser/session?client=powerj", "")
                    Net.send("PUT", "http://127.0.0.1:8088$path", "")
                }.onFailure { log("console $path failed: $it") }
            }.start()
        }
        Store.onRemoveDay = { day ->
            var ok = false
            editTrip { d ->
                val stops = removeDay(d.stops, d.done, day) ?: return@editTrip d
                val days = maxOf(d.days, d.stops.maxOfOrNull { it.day } ?: 1)
                if (days <= 1) return@editTrip d
                ok = true
                // removing day 1 keeps the other days on their dates
                val start = if (day == 1) runCatching { java.time.LocalDate.parse(d.start).plusDays(1).toString() }.getOrDefault(d.start) else d.start
                d.copy(stops = stops, days = days - 1, start = start)
            }
            Store.set { copy(newDay = null) }
            say(if (ok) "${day}일차를 뺐어요." else "${day}일차에 이미 다녀온 곳이 있어서 뺄 수 없어요.")
        }
        Store.onAddPlace = { hit, stay ->
            Store.set { copy(guide = 0, newDay = null) }
            val day = Store.ui.value.addDay ?: mem.editDay
            editTrip { d -> d.copy(stops = insertStop(d.stops, hit.place.copy(stay = stay), day), days = maxOf(d.days, day)) }
            log("plus added ${hit.place.name} day=$day stay=$stay")
            Store.set { copy(hits = emptyList(), addDay = null) }
            say("${day}일차${if (stay) " 숙소로" else "에"} ${hit.place.name.j("을", "를")} 담았어요.")
        }
        Store.onNewTrip = {
            leaveTripShare(); leavePlanRoute(); mem.switchTo(null); mem.drafts = mem.drafts + mem.activeId; forget(mem.activeId)
            val taken = takenDays(mem.cards(), mem.activeId) // first free day from today, so a new trip never overlaps another
            mem.planStart = generateSequence(today()) { it.plusDays(1) }.first { it !in taken }.toString(); mem.dayCount = 1; Store.set { copy(tips = emptyList(), dayWx = emptyMap(), late = null, check = null, popular = emptyList(), editId = null, addDay = null, hits = emptyList(), naming = true, guide = 2, editing = true) }; planChanged()
            say("새 여행을 만들었어요. 여행 이름을 정하고, 1일차에 장소를 추가해 주세요.")
        }
        Store.onActivate = { id ->
            Store.set { copy(guide = 0, newDay = null, editing = false) }
            leaveTripShare(); leavePlanRoute(); mem.switchTo(id); Store.set { copy(tips = emptyList(), dayWx = emptyMap(), late = null, check = null, popular = emptyList(), editId = null, addDay = null, hits = emptyList()) }; planChanged()
            val t = mem.cards().firstOrNull { it.active }?.let(::tripTitle) ?: "이 여행"
            log("trip activated $id $t")
            val first = nextStop(mem.plan, mem.planDone)?.name ?: "첫 장소"
            say("${t.ro()} 바꿨어요. " + if (started(mem.planStart, today())) "출발하면 ${first}부터 안내할게요."
                else "${dayLabel(mem.planStart, 1, "").substringAfter(" · ")} 출발이에요. 그날 시동을 걸면 ${first}부터 안내할게요.")
        }
        Store.onFuel = { st -> runCatching {
            navi.addWaypoint(RequestWaypointInfo(st.lat, st.lon, st.name, WaypointIndex.FIRST, "", "", "", ""))
            Store.set { copy(fuel = null) }; say("${st.name.j("을", "를")} 경유지로 넣었어요.")
        }.onFailure(::fail) }
        Store.onJoin = ::join
        Store.onEndShare = { leaveTripShare() }
        Store.onEdit = { id -> mem.trip(id)?.let { base.putIfAbsent(id, it) }; mem.drafts = mem.drafts + id; Store.set { copy(editing = true) }; showPlan() }
        Store.onUndo = { editingId().let { id -> undo[id]?.removeLastOrNull()?.let { restore(id, it) } } }
        Store.onCancelEdit = {
            val id = editingId()
            base[id]?.let { restore(id, it) }
            forget(id); mem.drafts = mem.drafts - id
            Store.set { copy(editing = false, guide = 0, newDay = null, addDay = null, hits = emptyList()) }
            showPlan(); log("edit cancelled $id"); say("수정한 내용을 취소했어요.")
        }
        Store.onSetOrigin = { o ->
            editTrip { it.copy(origin = o?.copy(day = 0, stay = false)) }
            Store.set { copy(addDay = null, hits = emptyList()) }
            log("origin ${o?.name ?: "car"}")
            say(o?.let { "출발 위치를 ${it.name.ro()} 정했어요." } ?: "출발 위치를 지금 차 위치로 바꿨어요.")
        }
        Store.onSaveRoutine = { r ->
            val all = mem.routines
            val id = if (r.id > 0) r.id else (all.maxOfOrNull { it.id } ?: 0) + 1
            mem.routines = all.filter { it.id != id } + r.copy(id = id, seen = 0)
            Store.set { copy(routineDraft = null, routineId = id) }; showPlan()
            log("routine saved ${r.name} ${daysLabel(r.days)} ${r.hour} ${r.dest.name}")
            say("${r.name} 루틴을 저장했어요. ${daysLabel(r.days)} ${r.hour}시쯤 출발하면 ${r.dest.name.ro()} 안내를 준비할게요.")
        }
        Store.onDeleteRoutine = { id ->
            val r = mem.routines.firstOrNull { it.id == id }
            mem.routines = mem.routines.filter { it.id != id }
            Store.set { copy(routineDraft = null, routineId = null) }; showPlan()
            r?.let { say("${it.name} 루틴을 지웠어요.") }
        }
        Store.onSave = {
            forget(editingId())
            mem.drafts = mem.drafts - (Store.ui.value.editId ?: mem.activeId)
            Store.set { copy(editing = false, guide = 0, newDay = null, addDay = null, hits = emptyList()) }
            showPlan()
            mem.share?.takeIf { mem.shareHost }?.let { pushShare(it) }
            log("trip saved")
            say(if (mem.share != null && mem.shareHost) "저장했어요. 동행 차에도 보냈어요." else "저장했어요.")
        }
        Store.onArrived = { yes -> Store.set { copy(askArrived = null) }; if (yes) reachNext() else say("알겠어요. 다시 출발하면 계속 안내할게요.") }
        Store.onAnswer = ::answer
        Store.onGo = { p -> route(p) }
        Store.onSeed = { seed() }
        Store.onClear = { mem.clear(); testAt = null; Store.set { Store.Ui() } }
        Store.onReset = { // test hook: rear seat heat off so each run starts clean
            listOf(Seat.REAR_LEFT, Seat.REAR_RIGHT).forEach { s -> runCatching { vehicle.getSeat().setHvacSeatTemperature(SeatArea(rearValue(s)), 0, { _, _ -> }, err) } }
            runCatching { navi.cancelRoute() }
        }

        h.postDelayed({ subscribe() }, 1500)
        watchAaos()
        startNavi()
    }

    // ---------- vehicle ----------

    private fun subscribe() {
        val seat = vehicle.getSeat()
        runCatching {
            seat.registerSeatOccupancy(SeatArea(SeatValue.ROW_1_RIGHT), object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(state: Boolean?) { passenger = (state == true) != invertSeats }
                override fun onFailed(e: Exception) = err(e)
            })
        }.onFailure(::fail)
        mapOf(SeatValue.ROW_2_LEFT to Seat.REAR_LEFT, SeatValue.ROW_2_RIGHT to Seat.REAR_RIGHT).forEach { (v, s) ->
            runCatching {
                seat.registerSeatBeltBuckledState(SeatArea(v), object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(state: Boolean?) { if (state == true) rearBelts += s else rearBelts -= s }
                    override fun onFailed(e: Exception) = err(e)
                })
            }.onFailure(::fail)
        }
        mapOf(DoorValue.ROW_2_LEFT to Seat.REAR_LEFT, DoorValue.ROW_2_RIGHT to Seat.REAR_RIGHT).forEach { (v, s) ->
            runCatching {
                vehicle.getDoor().registerDoorPosition(DoorArea(v), object : DoorPositionListener {
                    override fun onPositionUpdated(pos: Int?) { if ((pos ?: 0) > 0 && gear == PARK) { rearDoorOpened += s; log("rear door $s opened") } }
                    override fun onFailed(e: Exception) = err(e)
                })
            }.onFailure(::fail)
        }
        runCatching {
            vehicle.getSafety().registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(t: Telltales?) { onTelltales(t) }
                override fun onFailed(e: Exception) = err(e)
            })
        }.onFailure(::fail)
        runCatching { vehicle.getEvBattery().getEvBatteryCapacity({ capacityWh = (it ?: 0f) * if ((it ?: 0f) < 1000f) 1000f else 1f }, err) }.onFailure(::fail)
    }

    private fun fail(e: Throwable) = log("not supported on this car: $e")

    /** Gear and speed come from standard AAOS properties. */
    private fun watchAaos() = runCatching {
        val m = Car.createCar(this).getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        m.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) { onGear(v.value as Int) }
            override fun onErrorEvent(p: Int, a: Int) {}
        }, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        m.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) { speed = (v.value as Float) }
            override fun onErrorEvent(p: Int, a: Int) {}
        }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
    }.onFailure(::fail)

    private fun onGear(g: Int) {
        val was = gear; gear = g
        if (was == 0) { // service (re)started, e.g. after the engine was turned off and on
            if (g != PARK) { Store.set { copy(phase = Phase.DRIVING) }; h.postDelayed({ resumePlan() }, 3000) }
            return
        }
        if (was == PARK && g != PARK) depart()
        if (was != PARK && g == PARK) arrive()
    }

    // ---------- trip ----------

    private fun now() = Calendar.getInstance()
    private fun weekend() = now().get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
    private fun hour() = now().get(Calendar.HOUR_OF_DAY)

    private fun depart() {
        val seats = occupiedSeats(passenger, rearDoorOpened, rearBelts)
        startCell = loc?.let { cell(it.latitude, it.longitude) } ?: ""
        val trips = mem.trips
        tripOfToday()
        val planNext = todayStop()
        val dow = now().get(Calendar.DAY_OF_WEEK).let { (it + 5) % 7 + 1 } // 1 = Mon .. 7 = Sun
        val routine = if (routing || planNext != null) null else routineNow(mem.routines, dow, hour())
        val learned = seats.mapNotNull { s -> guessWho(trips, s, weekend(), hour(), startCell)?.let { s to it } }.toMap()
        // a registered routine names its usual company: seats we can't tell yet get them in order
        val extra = routine?.who.orEmpty().filter { it !in learned.values }
        val who = learned + seats.filter { it !in learned }.zip(extra).toMap()
        val ask = seats.firstOrNull { it !in who }
        val suggestion = if (routing || planNext != null) null else routine?.dest ?: predictDest(trips, seats, weekend(), hour(), startCell)
        log("depart seats=$seats who=$who ask=$ask suggestion=$suggestion routine=${routine?.name} routing=$routing planNext=${planNext?.name} cell=$startCell")
        Store.set { copy(phase = Phase.DRIVING, seats = seats, who = who, ask = ask, suggestion = suggestion, late = null, wishNear = null,
            kit = routine?.let { checklist(it.dest.name, who.values) } ?: kit) } // a routine knows what to bring before the route starts
        prepare(who)
        runCatching { vehicle.getEvBattery().getEvBatteryStateOfCharge({ soc = it ?: 0; socAtDepart = soc }, err) }
        if (Who.CHILD in who.values) h.postDelayed(breakTask, 60 * 60_000L)

        val greet = buildList {
            if (who.isNotEmpty()) add(who.values.joinToString(", ") { it.label } + " 함께 타셨네요.")
            if (routine != null) add("${routine.name} 시간이에요. ${routine.dest.name} 가시죠? 화면에서 누르면 안내할게요.")
            else suggestion?.let { add("평소처럼 ${it.name} 가시죠? 화면에서 누르면 안내할게요.") }
        }
        if (greet.isNotEmpty()) say(greet.joinToString(" "))
        h.postDelayed({ dayCheckNow(planNext) }, if (greet.isEmpty()) 600L else 4000L)
    }

    /** Once per trip day (and whenever a warning light is on): distance vs range, then continue the plan. */
    private fun dayCheckNow(next: Place?) {
        val warnings = Store.ui.value.alerts.map { it.name }
        val firstOfDay = next != null && mem.checkedDay != next.day
        val km = if (firstOfDay) loc?.let { todayKm(mem.plan, mem.planDone, it.latitude, it.longitude) } ?: 0 else 0
        val read: (Float) -> Unit = { carKm ->
            h.post {
                val line = listOfNotNull(dayCheck(km, rangeKm(carKm, capacityWh, soc), warnings), next?.takeIf { firstOfDay }?.let { todayWeather(it.day) })
                    .joinToString(" ").ifEmpty { null }
                log("day check km=$km range=${rangeKm(carKm, capacityWh, soc)} line=$line")
                if (firstOfDay) mem.checkedDay = next!!.day
                val range = rangeKm(carKm, capacityWh, soc)
                if (next != null && km > 0 && range in 1 until (km * 1.1).toInt()) findFuel(next, range)
                Store.set { copy(check = line) }
                line?.let { say(it) }
                if (next != null) h.postDelayed({ resumePlan() }, if (line == null) 0L else 5000L)
            }
        }
        if (!firstOfDay && warnings.isEmpty()) { if (next != null) resumePlan(); return }
        runCatching { vehicle.getOdometer().getRangeRemainingDistance({ read(it ?: 0f) }, { read(0f) }) }.onFailure { read(0f) }
    }

    private val breakTask = Runnable {
        say("아이와 1시간 달렸어요. 다음 휴게소에서 잠깐 쉬어 갈까요?")
    }

    // ---------- trip plan ----------

    /** Edit whichever trip the plan tab has selected; the active trip also refreshes guidance data. */
    private fun editingId() = Store.ui.value.editId ?: mem.activeId

    /** Put a trip back to an earlier state (undo / cancel); the active trip also refreshes guidance data. */
    private fun restore(id: Int, d: Memory.TripData) {
        mem.saveTrip(id, d)
        if (id == mem.activeId) planChanged() else showPlan()
    }

    private fun editTrip(f: (Memory.TripData) -> Memory.TripData) {
        val id = Store.ui.value.editId ?: mem.activeId
        val d = mem.trip(id) ?: return
        val n = f(d)
        if (n == d) return
        if (Store.ui.value.editing || id in mem.drafts) undo.getOrPut(id) { ArrayDeque() }.apply { addLast(d); if (size > 10) removeFirst() }
        mem.saveTrip(id, n)
        if (id == mem.activeId) planChanged() else showPlan()
    }

    private fun showPlan() = Store.set {
        copy(plan = mem.plan, planDone = mem.planDone, editDay = mem.editDay, wish = mem.wish, cost = costLine(mem.tolls, mem.kWh), spent = spentWon(mem.tolls, mem.kWh), share = mem.share, guest = mem.share != null && !mem.shareHost, plans = mem.cards(), drafts = mem.drafts,
            undos = undo.mapValues { it.value.size }.filterValues { it > 0 }, bases = base.keys.toSet(),
            routines = mem.routines, foundRoutines = findRoutines(mem.trips, mem.routines))
    }

    /** Leave edit mode for a trip: its undo history and saved copy are no longer needed. */
    private fun forget(id: Int) { undo.remove(id); base.remove(id) }

    /** A local edit: show it, send it to the companion car, refresh weather and popular places. */
    private fun planChanged() {
        if (guestEdit()) return
        if (mem.plan.isNotEmpty() && mem.planStart.isEmpty()) mem.planStart = today().toString()
        showPlan()
        estimateCost()
        if (mem.activeId !in mem.drafts) mem.share?.takeIf { mem.shareHost }?.let { pushShare(it) } // unsaved edits reach companions on 저장
        refreshWeather()
        nextStop(mem.plan, mem.planDone)?.let { n -> live.popular(n) { top -> Store.set { copy(popular = top) } } }
        syncShare() // progress bars move now, not at the next 10 s tick
    }

    /** Expected cost of the whole trip: from the car (and back) through every stop in order. */
    private fun estimateCost() {
        val plan = mem.plan
        val car = loc?.let { Place("현재 위치", it.latitude, it.longitude, day = 0) }
        val home = mem.origin(mem.activeId)?.copy(day = 0)
        // before the trip: from the 출발 위치 the user picked (else the car); once it has begun the car really is where it starts
        val first = if (started(mem.planStart, today()) && car != null) car else home ?: car
        val last = home ?: car
        val stops = plan.map { s -> s.target().let { t -> s.copy(lat = t.lat, lon = t.lon) } } // drive to the chosen lot
        val points = listOfNotNull(first) + stops + listOfNotNull(last)
        if (plan.isEmpty() || points.size < 2) { Store.set { copy(estimate = null, dayCost = emptyMap()) }; return }
        live.estimate(points) { legs ->
            if (mem.plan != plan) return@estimate // an answer for an older plan
            val ev = capacityWh > 0
            Store.set { copy(estimate = est(legs.reduce(Leg::plus), ev), from = when { first == null -> null; first === car && last === car -> "지금 위치에서 왕복"; first === car -> "지금 위치 출발 · 출발 위치로 복귀"; else -> "출발 위치에서 왕복" },
                dayCost = legsByDay(points, legs).mapNotNull { (d, v) -> est(v, ev)?.let { d to it } }.toMap()) }
        }
    }

    // ---------- weather ----------

    private fun refreshWeather() {
        val at = nextStop(mem.plan, mem.planDone) ?: return
        live.weather(at, ::applyWeather)
    }

    private fun applyWeather(days: List<DayWeather>) {
        weather = days
        val start = runCatching { java.time.LocalDate.parse(mem.planStart) }.getOrElse { today() }
        val tips = weatherAdvice(mem.plan, mem.planDone, start, days)
        val byDate = days.associateBy { it.date }
        val wx = mem.plan.map { it.day }.distinct().mapNotNull { d ->
            byDate[planDate(start, d)]?.let { w ->
                val r = maxOf(w.rainAm, w.rainPm)
                d to ((if (r >= 60) (if (w.rainAm >= 60 && w.rainPm >= 60) "비 $r%" else if (w.rainAm >= 60) "오전 비 $r%" else "오후 비 $r%") else "맑음·흐림 (비 $r%)") +
                    (if (w.minT != null && w.maxT != null) " · ${w.minT}~${w.maxT}°C" else ""))
            }
        }.toMap()
        log("weather tips=$tips")
        val fresh = tips.firstOrNull { it !in Store.ui.value.tips }
        Store.set { copy(tips = tips, dayWx = wx) }
        fresh?.let { say(it) }
    }

    /** Today's umbrella line for the departure check. */
    private fun todayWeather(day: Int): String? {
        val start = runCatching { java.time.LocalDate.parse(mem.planStart) }.getOrElse { return null }
        val w = weather.firstOrNull { it.date == planDate(start, day) } ?: return null
        val r = maxOf(w.rainAm, w.rainPm)
        return when {
            r >= 60 -> "오늘 ${if (w.rainAm >= 60) "오전" else "오후"} 비 ${r}%예요. 우산 챙기셨나요?"
            w.minT != null && w.minT <= 5 && Who.CHILD in Store.ui.value.who.values -> "아침 ${w.minT}°C예요. 아이 겉옷 챙기세요."
            else -> null
        }
    }

    // ---------- fuel ----------

    private fun findFuel(to: Place, range: Int, ev: Boolean = capacityWh > 0) {
        val from = loc?.let { Place("현재 위치", it.latitude, it.longitude) } ?: return
        live.fuelStop(from, to, range, ev) { st ->
            st ?: return@fuelStop
            Store.set { copy(fuel = st) }
            say(fuelLine(range, st))
        }
    }

    // ---------- place card ----------

    private fun fetchCard(p: Place) {
        if (lastCardFor == p.name) return
        lastCardFor = p.name
        live.placeCard(p) { info, lots -> Store.set { copy(card = info, lots = lots) } }
    }

    // ---------- shared trip ----------

    /** Plan the host sent last; a joined car's own edits snap back to it. */
    private var hostPlan: List<Place>? = null

    /** A joined car tried to change the shared plan: undo it. Reaching stops (planDone) is still its own. */
    private fun guestEdit(): Boolean {
        if (mem.share == null || mem.shareHost) return false
        val host = hostPlan ?: mem.plan.also { hostPlan = it }
        if (mem.plan == host) return false
        mem.plan = host
        showPlan()
        log("guest edit blocked")
        say("함께 가기 중에는 코드를 만든 차만 계획을 바꿀 수 있어요.")
        muted = true; h.post { muted = false } // ponytail: mutes only the rest of this tap's handler; async lines later still speak
        return true
    }

    /** Sharing belongs to the active trip: leaving that trip ends it (host) or leaves it (joined car). */
    private fun endShare(why: String) {
        val code = mem.share ?: return
        if (mem.shareHost) live.end(code)
        mem.share = null; mem.shareHost = false; hostPlan = null; sharedAt = 0L
        Store.set { copy(cars = emptyList(), companion = null) }
        showPlan()
        log("share ended $code")
        say(why)
    }
    private fun leaveTripShare() = endShare(if (mem.shareHost) "함께 가기를 끝냈어요. 동행 차에도 알렸어요." else "함께 가기에서 나왔어요. 받은 계획은 내 여행으로 남겨 두었어요.")

    private fun startShare() {
        if (mem.share != null && !mem.shareHost) return endShare("함께 가기에서 나왔어요. 받은 계획은 내 여행으로 남겨 두었어요.") // joined car: leave, keep the plan
        mem.share?.let { return announceShare(it) }
        freeCode(5) { code -> mem.share = code; mem.shareHost = true; announceShare(code) }
    }

    /** A 6-digit code no other shared trip uses (another car's trip must never be overwritten). */
    private fun freeCode(tries: Int, use: (String) -> Unit) {
        val code = shareCode()
        live.exists(code) { taken -> if (taken == true && tries > 0) freeCode(tries - 1, use) else use(code) }
    }

    private fun announceShare(code: String) {
        pushShare(code)
        showPlan()
        log("share code $code")
        say("함께 가기 코드는 ${code.toList().joinToString(" ")}이에요. 동행 차에서 이 번호를 눌러 주세요.")
    }

    private fun join(code: String) {
        if (code == mem.share) return say("이미 함께 가기 ${code.toList().joinToString(" ")}에 있어요.")
        val wait = (Store.ui.value.joinLock - System.currentTimeMillis()) / 1000
        if (wait > 0) return say("코드를 여러 번 틀려서 잠시 막아 두었어요. ${wait + 1}초 뒤에 다시 눌러 주세요.")
        live.exists(code) { found ->
            when (found) {
                true -> { joinFails = 0; joinNow(code) }
                false -> if (++joinFails >= 5) { // ponytail: lock lives in memory only; a server rule is the real guard (Firebase auth, later)
                    joinFails = 0; Store.set { copy(joinLock = System.currentTimeMillis() + 60_000) }
                    log("join locked"); say("코드를 5번 틀렸어요. 1분 뒤에 다시 시도해 주세요.")
                } else say("${code.toList().joinToString(" ")} 코드를 찾을 수 없어요. 동행 차의 화면에서 번호를 다시 확인해 주세요." + if (joinFails >= 3) " ${5 - joinFails}번 더 틀리면 1분 동안 막혀요." else "")
                null -> say("지금은 연결이 안 돼서 참여할 수 없어요. 잠시 뒤 다시 눌러 주세요.")
            }
        }
    }

    private fun pushShare(code: String) = live.push(code, mem.plan, mem.planDone, mem.title, mem.planStart, mem.dayCount)

    private fun joinNow(code: String) {
        if (mem.plan.isNotEmpty() || mem.title.isNotEmpty()) { log("join keeps own trip ${mem.cards().first { it.active }.let(::tripTitle)}"); leavePlanRoute(); mem.switchTo(null) } // the shared one arrives as a new trip
        mem.drafts = mem.drafts - mem.activeId // a received trip is saved as it is, never "수정 중"
        mem.share = code; mem.shareHost = false; hostPlan = null; sharedAt = 0L; joining = true
        showPlan()
        say("함께 가기 ${code.toList().joinToString(" ")}에 참여했어요. 계획을 받아 올게요.")
        syncShare()
    }

    private fun stopNote(done: Int) = nextStop(mem.plan, done)?.let { "${it.name} 가는 중 · ${done}/${mem.plan.size}" } ?: "도착 · ${mem.plan.size}/${mem.plan.size}"

    /** Every 10 s while sharing: send our position, take the companion's plan changes and position. */
    private fun syncShare() {
        val code = mem.share ?: return
        val mine = tripProgress(mem.plan, mem.planDone, loc?.latitude, loc?.longitude)
        loc?.let { live.pushPosition(code, it.latitude, it.longitude, mine, mem.planDone, mem.shareHost) }
        live.pull(code, sharedAt) { plan, at, others, info, gone ->
            if (gone && !mem.shareHost && !joining && mem.share == code) return@pull endShare("동행 차가 함께 가기를 끝냈어요. 받은 계획은 내 여행으로 남겨 두었어요.")
            sharedAt = maxOf(sharedAt, at)
            plan?.takeIf { !mem.shareHost }?.let { (stops, done) -> // only joined cars take the plan; the host's copy is the truth
                val line = if (joining || mem.plan.isEmpty()) "동행 차의 여행 계획을 받았어요." else planDiff(mem.plan, stops)
                if (joining) mem.planDone = done // after that each car counts the stops it reached itself
                joining = false
                hostPlan = stops
                mem.plan = stops
                info?.let { i -> log("share info title=${i.title} start=${i.start} days=${i.days}"); if (i.title.isNotBlank()) mem.title = i.title; if (i.start.isNotBlank()) mem.planStart = i.start; if (i.days > 0) mem.dayCount = i.days }
                if (mem.planStart.isEmpty()) mem.planStart = today().toString()
                showPlan(); refreshWeather(); estimateCost() // a received plan needs its own distances and costs too
                log("share applied ${stops.map { it.name }}")
                line?.let { say(it) }
            }
            var n = 0
            val bars = listOf(CarBar("내 차" + if (mem.shareHost) " · 코드 만든 차" else "", mine, stopNote(mem.planDone))) +
                others.map { CarBar(if (it.host) "코드 만든 차" else "동행 차 ${++n}", it.progress, stopNote(it.done)) }
            if (bars != Store.ui.value.cars) log("cars " + bars.joinToString { "${it.label}=${(it.progress * 100).toInt()}%" })
            Store.set { copy(cars = bars) }
            val other = others.firstOrNull()?.let { it.lat to it.lon }
            val me = loc; val next = nextStop(mem.plan, mem.planDone)
            if (other != null && me != null) {
                val gap = meters(Place("", other.first, other.second), me.latitude, me.longitude)
                val ahead = next != null && meters(next, other.first, other.second) < meters(next, me.latitude, me.longitude)
                val line = companionLine(gap, ahead)
                if (line != Store.ui.value.companion) log("companion $line")
                Store.set { copy(companion = line) }
            }
        }
    }



    /** Add the destination currently set in the navigation app (search there, then tap "담기" here). */
    private fun order() {
        mem.plan = orderPlan(mem.plan, mem.planDone, loc?.let { Place("현재 위치", it.latitude, it.longitude) })
        planChanged()
        log("plan ordered ${mem.plan.map { "${it.day}:${it.name}" }}")
        say("가까운 순서로 동선을 정리했어요.")
    }

    private fun visitWish(p: Place) = runCatching {
        navi.addWaypoint(RequestWaypointInfo(p.lat, p.lon, p.name, WaypointIndex.FIRST, "", "", "", ""))
        mem.wish = mem.wish.filter { it.name != p.name }
        Store.set { copy(wishNear = null) }
        showPlan()
        log("wish waypoint ${p.name}")
        say("${p.name.j("을", "를")} 경유지로 넣었어요.")
    }.onFailure(::fail)

    /** Navigation guiding the next plan stop but arriving late: offer to move it to tomorrow. */
    private fun offerLate(stop: Place, arrive: Int, note: String?) {
        val line = lateLine(stop, arrive, note) ?: return
        log("late offer ${stop.name}")
        Store.set { copy(late = line) }
        say(line)
    }

    private fun postponeNext() {
        if (mem.planDone >= mem.plan.size) return
        val p = mem.plan[mem.planDone]
        mem.plan = postpone(mem.plan, mem.planDone)
        Store.set { copy(late = null) }
        dropRoute()
        planChanged()
        log("plan postponed ${p.name} -> day ${p.day + 1}")
        say("${p.name.j("은", "는")} ${p.day + 1}일차로 옮겼어요.")
        h.postDelayed({ resumePlan() }, 2500)
    }

    /** Switching trips: stop guidance only if it was going to a stop of the old plan; a route the driver set stays. */
    private fun leavePlanRoute() {
        val d = routeDest?.name
        if (guiding != null || (d != null && mem.plan.any { it.name == d })) dropRoute()
        else { h.removeCallbacks(soonTask); Store.set { copy(dest = null, kit = emptyList(), kind = null, note = null, soon = null, energy = null) } }
        guiding = null
    }

    private fun dropRoute() {
        runCatching { navi.cancelRoute() }
        h.removeCallbacks(soonTask)
        routing = false; routeDest = null
        Store.set { copy(dest = null, kit = emptyList(), kind = null, note = null, soon = null, energy = null) }
    }

    /** Next stop, but only once the trip's start date has come. */
    private fun todayStop() = stopForToday(mem.plan, mem.planDone, mem.planStart, today())

    /** On a saved trip's day, make it the trip in use so "오늘" follows it. */
    private fun tripOfToday() {
        if (mem.share != null) return // never swap away from a trip that is being shared
        val id = tripForToday(mem.cards(), today()) ?: return
        leaveTripShare(); leavePlanRoute(); mem.switchTo(id)
        Store.set { copy(tips = emptyList(), dayWx = emptyMap(), late = null, check = null, popular = emptyList(), editId = null, addDay = null, hits = emptyList()) }
        planChanged()
        val t = mem.cards().firstOrNull { it.active }?.let(::tripTitle) ?: "여행"
        say("오늘은 ${t} 날이에요. ${todayStop()?.name?.let { "${it}부터 안내할게요." } ?: ""}".trim())
    }

    /** Route to the next unreached stop unless navigation is already guiding. */
    private fun resumePlan() {
        val next = todayStop() ?: return
        if (routing && next.named(routeDest?.name)) return // already guiding there; any other route gets replaced
        log("plan resume ${mem.planDone + 1}/${mem.plan.size} ${next.name}")
        guiding = next.name
        route(next)
        say(when {
            next.stay -> "오늘 일정 끝났어요. 숙소 ${next.name.ro()} 안내할게요."
            newDay(mem.plan, mem.planDone) && next.day > 1 -> "여행 ${next.day}일차예요. ${next.name.ro()} 안내할게요."
            else -> "여행 계획 ${mem.planDone + 1}번째, ${next.name.ro()} 이어서 안내할게요."
        })
    }

    private fun reachNext() {
        val stop = nextStop(mem.plan, mem.planDone) ?: return
        mem.planDone = mem.planDone + 1
        runCatching { navi.cancelRoute() }; routing = false // drop guidance to the stop we just reached
        if (!stop.stay) live.visit(stop)
        if (BuildConfig.DEBUG) { testAt = stop; loc = loc?.let(::placed); Store.set { copy(here = stop.lat to stop.lon) } }
        planChanged()
        val next = nextStop(mem.plan, mem.planDone)
        log("plan reached ${stop.name} next=${next?.name}")
        say(when {
            next == null -> "${stop.name}에 도착했어요. 여행 계획을 모두 마쳤어요." + (costLine(mem.tolls, mem.kWh)?.let { " 이번 여행 차량비는 $it 이에요." } ?: "")
            stop.stay -> "숙소에 도착했어요. 내일은 ${next.name}부터 안내할게요."
            else -> "${stop.name}에 도착했어요. 다음은 ${next.name.j("이에요", "예요")}. 출발하면 바로 안내할게요."
        })
    }

    /** Two days in Gyeongju, for the stage demo. */
    private fun demoPlan() {
        mem.plan = listOf(
            Place("불국사", 35.7900, 129.3320), Place("첨성대", 35.8347, 129.2190), Place("황리단길", 35.8380, 129.2105),
            Place("보문관광단지", 35.8400, 129.2860, stay = true),
            Place("석굴암", 35.7950, 129.3490, day = 2), Place("국립경주박물관", 35.8290, 129.2280, day = 2),
        )
        mem.planDone = 0; mem.editDay = 2; mem.dayCount = 2; mem.checkedDay = 0; mem.tolls = 0; mem.kWh = 0f; mem.tollFor = null
        mem.planStart = today().toString()
        mem.title = "경주 2일 여행"
        mem.keep(900, "강릉 당일치기", today().plusDays(7).toString(),
            listOf(Place("정동진", 37.6909, 129.0346), Place("안목해변 커피거리", 37.7726, 128.9475), Place("강릉중앙시장", 37.7545, 128.8990)))
        planChanged()
        log("demo plan set")
    }

    /** Apply remembered comfort for known people. */
    private fun prepare(who: Map<Seat, Who>) {
        who.forEach { (seat, w) ->
            val v = mem.comfort(w) ?: return@forEach
            when (seat) {
                Seat.PASSENGER -> runCatching {
                    vehicle.getHVAC().setHvacTemperature(SeatArea(SeatValue.ROW_1_RIGHT), v, TemperatureUnit.CELSIUS, { _, _ -> }, err)
                }.onFailure(::fail)
                else -> runCatching {
                    vehicle.getSeat().setHvacSeatTemperature(SeatArea(rearValue(seat)), v.toInt(), { _, l -> log("$seat heat $l") }, err)
                }.onFailure(::fail)
            }
        }
    }

    private fun rearValue(s: Seat) = if (s == Seat.REAR_LEFT) SeatValue.ROW_2_LEFT else SeatValue.ROW_2_RIGHT

    private fun answer(seat: Seat, who: Who?) {
        Store.set {
            val w = if (who != null) this.who + (seat to who) else this.who
            copy(who = w, ask = seats.firstOrNull { it !in w && it != seat && (ask == null || it.ordinal > seat.ordinal) })
        }
        who?.let { prepare(mapOf(seat to it)) }
    }

    private fun arrive() {
        val ui = Store.ui.value
        val wasGuiding = guiding.also { guiding = null }
        val dest = routeDest ?: loc?.let { Place(it.address.ifBlank { "도착지" }, it.latitude, it.longitude) }
        if (ui.seats.isNotEmpty() || dest != null) {
            mem.trips = mem.trips + Trip(ui.seats, ui.who, weekend(), hour(), startCell, dest)
        }
        rememberComfort(ui.who)
        log("arrive dest=$dest")
        log("found routines " + findRoutines(mem.trips, mem.routines).map { "${it.name}:${it.dest.name}:${it.seen}" })
        rearDoorOpened.clear()
        h.removeCallbacks(soonTask); h.removeCallbacks(breakTask)
        routeDest = null; routing = false
        if (mem.plan.isNotEmpty() && socAtDepart >= 0) runCatching {
            val from = socAtDepart
            vehicle.getEvBattery().getEvBatteryStateOfCharge({ now -> if (now != null && now < from) { mem.kWh = mem.kWh + (from - now) * capacityWh / 100f / 1000f; h.post { showPlan() } } }, err)
        }
        socAtDepart = -1
        Store.set { Store.Ui(trips = mem.trips.size, place = place, alerts = ui.alerts) }
        showPlan()
        // Parked within 500 m counts as arrived; farther away (a mountain trailhead, a big park) we ask instead of guessing.
        nextStop(mem.plan, mem.planDone)?.let { stop ->
            val l = loc
            if (l != null && reached(stop, l.latitude, l.longitude)) reachNext()
            else if (wasGuiding == stop.name || stop.named(routeDest?.name) || stop.named(dest?.name)) h.postDelayed({
                log("ask arrived ${stop.name}")
                Store.set { copy(askArrived = stop) }
                say("${stop.name}에 도착하셨나요? 잠깐 들른 곳이면 아니요를 눌러 주세요.")
            }, 1500)
        }
    }

    /** What people chose this ride becomes next ride's default. */
    private fun rememberComfort(who: Map<Seat, Who>) = who.forEach { (seat, w) ->
        runCatching {
            if (seat == Seat.PASSENGER) vehicle.getHVAC().getHvacTemperature(SeatArea(SeatValue.ROW_1_RIGHT), TemperatureUnit.CELSIUS, { it?.let { t -> mem.saveComfort(w, t) } }, err)
            else vehicle.getSeat().getHvacSeatTemperature(SeatArea(rearValue(seat)), { it?.let { l -> mem.saveComfort(w, l.toFloat()) } }, err)
        }.onFailure(::fail)
    }

    // ---------- navigation ----------

    private fun startNavi() {
        navi = NaviHelper(applicationContext)
        runCatching {
            navi.initialize({
                navi.addListener(object : NaviHelperEventListener {
                    override fun onRouteStarted(info: RouteStartInfo) = onRoute(info)
                    override fun onNavigationStatus(status: NavigationStatus) { routing = status == NavigationStatus.ROUTE_DRIVE }
                    override fun onDestinationInfo(d: ai.pleos.playground.navi.data.DestinationInfo) {
                        if (d.name.isNotBlank()) {
                            routeDest = Place(d.name, d.latitude, d.longitude)
                            fetchCard(nextStop(mem.plan, mem.planDone)?.takeIf { it.named(d.name) } ?: routeDest!!) // the place's card, not its lot's
                        }
                    }
                    override fun onTBTInfo(list: List<TBTInfo>) { tbt = list }
                    override fun onCurrentLocationInfo(raw: CurrentLocationInfo) { val l = placed(raw); loc = l; Store.set { copy(place = l.address, here = l.latitude to l.longitude) } }
                })
                navi.getCurrentLocationInfo(); navi.getRouteStateInfo(); navi.getDestinationInfo()
                h.post(object : Runnable {
                    override fun run() { runCatching { navi.getCurrentLocationInfo(); navi.getTBTInfo() }; checkWish(); syncShare(); h.postDelayed(this, 10_000) }
                })
            }, { log("navi init failed: $it") })
        }.onFailure(::fail)
    }

    /** While driving, mention a saved "maybe" place once when we pass within 5 km. */
    private fun checkWish() {
        val l = loc ?: return
        if (Store.ui.value.phase != Phase.DRIVING) return
        val w = nearWish(mem.wish, l.latitude, l.longitude, toldWish) ?: return
        toldWish += w.name
        val km = (meters(w, l.latitude, l.longitude) / 1000).let { if (it < 1) "1km 안" else "%.0fkm".format(it) }
        log("wish near ${w.name}")
        Store.set { copy(wishNear = w) }
        say("담아 둔 ${w.name.j("이", "가")} $km 거리예요. 들를까요?")
    }

    private fun route(p: Place) = runCatching {
        val t = p.target()
        navi.requestRoute(RouteInfo(t.lat, t.lon, null, null, t.name, "", "", "", RouteOption.RECOMMENDED, "", false))
        Store.set { copy(suggestion = null) }
    }.onFailure(::fail)

    /** Route started, by us or by the driver in the navigation app. */
    private fun onRoute(info: RouteStartInfo) {
        routing = true
        h.postDelayed({ runCatching { navi.getDestinationInfo() } }, 1000)
        val ui = Store.ui.value
        val kit = checklist(info.name, ui.who.values)
        val kind = kindOf(info.name)
        val c = now()
        val arrive = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + info.duration / 60
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val note = timeNote(kind, arrive, dow == Calendar.SATURDAY, dow == Calendar.SUNDAY, c.get(Calendar.DAY_OF_MONTH))
        log("route kind=$kind note=$note")
        nextStop(mem.plan, mem.planDone)?.takeIf { it.named(info.name) || it.named(routeDest?.name) }?.let { stop ->
            val id = "${mem.planDone}:${stop.name}"
            if (mem.tollFor != id) { mem.tolls = mem.tolls + info.tollFare; mem.tollFor = id; showPlan() }
            h.postDelayed({ offerLate(stop, arrive, note) }, 7000)
        }
        runCatching { vehicle.getEvBattery().getEvBatteryStateOfCharge({ soc = it ?: 0 }, err) }
        h.postDelayed({
            val energy = roundTrip(capacityWh, soc, info.remainDestinationChargingValue)?.let { energyLine(it.first, it.second) }
            Store.set { copy(dest = info.name, etaMin = info.duration / 60, toll = info.tollFare, kit = kit, kind = kind?.label, note = note, soon = null, energy = energy, suggestion = null) }
            say(briefing(info.name, info.duration / 60, info.tollFare, kit, energy, note))
        }, 600)
        scheduleSoon(info.name, kind, info.duration)
    }

    private val soonTask = Runnable {
        val ui = Store.ui.value
        val d = ui.dest ?: return@Runnable
        val line = soonLine(d, kindOf(d), ui.who.values, ui.etaMin)
        Store.set { copy(soon = line, parkOffer = emptyList()) }
        say(line)
        // a plan stop with no lot chosen: offer the two nearest lots (one tap, no map while driving)
        nextStop(mem.plan, mem.planDone)?.takeIf { it.named(d) && it.park == null && !it.stay }?.let { stop ->
            live.lots(stop) { lots ->
                if (lots.isEmpty() || Store.ui.value.soon == null) return@lots
                Store.set { copy(parkOffer = lots.take(2)) }
                say("주차장으로 안내할까요? ${lots.first().place.name.j("이", "가")} 가장 가까워요.")
            }
        }
    }

    /** ponytail: timed from the ETA at route start, not live remaining time; switch to DestinationInfo remaining time once verified. */
    private fun scheduleSoon(dest: String, kind: Kind?, durationSec: Int) {
        h.removeCallbacks(soonTask)
        if (kind == null && Who.CHILD !in Store.ui.value.who.values) return
        h.postDelayed(soonTask, ((durationSec - 180).coerceAtLeast(60)) * 1000L)
    }

    // ---------- warnings ----------

    /** Warning lights are only kept for the departure check, not read out while driving. */
    private fun onTelltales(t: Telltales?) {
        val on = t?.telltales.orEmpty().filter { it.state != TelltaleState.OFF }.mapNotNull { triage(it.type.name, it.color) }
        Store.set { copy(alerts = on) }
    }

    // ---------- demo ----------

    /** Two weekday school runs from here with a child in the back, so prediction can be shown on stage. */
    private fun seed(tries: Int = 10) {
        if (loc == null && tries > 0) { runCatching { navi.getCurrentLocationInfo() }; h.postDelayed({ seed(tries - 1) }, 1000); return } // wait for a position
        val c = loc?.let { cell(it.latitude, it.longitude) } ?: ""
        val school = Place("판교초등학교", 37.3925, 127.1097)
        val runs = List(2) { Trip(setOf(Seat.REAR_RIGHT), mapOf(Seat.REAR_RIGHT to Who.CHILD), weekend(), hour(), c, school) }
        mem.trips = mem.trips + runs
        mem.saveComfort(Who.CHILD, 2f)
        Store.set { copy(trips = mem.trips.size) }
        log("seeded demo history")
    }

    override fun onDestroy() {
        voice.release()
        runCatching { navi.release() }
        super.onDestroy()
    }

    companion object { const val PARK = 4 }
}
