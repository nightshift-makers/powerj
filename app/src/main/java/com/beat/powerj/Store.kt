package com.beat.powerj

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class Phase { PARKED, DRIVING }

/** One car's progress through the shared trip, 0..1. */
data class CarBar(val label: String, val progress: Float, val note: String)

/** One saved trip for the plan list. [start] is ISO date of day 1 ("" = not set). */
data class PlanCard(val id: Int, val title: String, val start: String, val stops: List<Place>, val done: Int, val active: Boolean, val days: Int = 1, val origin: Place? = null) {
    /** Days shown: the ones the user added, even if still empty. */
    val dayCount get() = maxOf(days, stops.maxOfOrNull { it.day } ?: 0, 1)
}

/** Screen state shared between the service and the activity. */
object Store {
    data class Ui(
        val phase: Phase = Phase.PARKED,
        val seats: Set<Seat> = emptySet(),
        val who: Map<Seat, Who> = emptyMap(),
        val ask: Seat? = null,              // waiting for "누가 탔나요?"
        val suggestion: Place? = null,      // learned destination, no route yet
        val dest: String? = null,
        val etaMin: Int = 0,
        val toll: Int = 0,
        val kit: List<String> = emptyList(),
        val kind: String? = null,           // destination type label, e.g. "병원"
        val note: String? = null,           // arrival time vs usual hours
        val soon: String? = null,           // pre-arrival line, set ~3 min before
        val energy: String? = null,
        val alerts: List<Alert> = emptyList(),
        val place: String? = null,
        val voice: String = "",
        val trips: Int = 0,
        val plan: List<Place> = emptyList(),  // trip plan, day by day, in order
        val planDone: Int = 0,                // stops already reached
        val editDay: Int = 1,                 // day that "담기" adds to
        val check: String? = null,            // departure check line for the day
        val late: String? = null,             // "내일로 미룰까요?" offer
        val wish: List<Place> = emptyList(),  // saved "maybe" places
        val wishNear: Place? = null,          // a saved place close to the route now
        val cost: String? = null,             // trip car costs so far
        val estimate: Est? = null,            // expected trip car cost before driving
        val from: String? = null,             // where the estimate starts and ends: "지금 위치에서 왕복" / "출발 위치에서 왕복"; null = from the first stop
        val dayCost: Map<Int, Est> = emptyMap(), // expected cost per day
        val spent: Int = 0,                   // what the trip has cost so far
        val askArrived: Place? = null,        // parked away from the next stop: "도착했나요?"
        val tips: List<String> = emptyList(), // weather advice for the plan
        val dayWx: Map<Int, String> = emptyMap(), // plan day -> "오후 비 70% · 3~12°C"
        val card: PlaceInfo? = null,          // hours and phone of the destination
        val lots: List<String> = emptyList(), // 3 parking lots near the destination
        val share: String? = null,            // shared-trip code
        val companion: String? = null,        // "동행 차는 3.2km 뒤"
        val guest: Boolean = false,           // joined someone's code: plan is read-only
        val cars: List<CarBar> = emptyList(), // trip progress of every car in the shared trip, this car first
        val popular: List<String> = emptyList(), // places other cars drove to near the next stop
        val fuel: Station? = null,            // cheapest station / free fast charger on the way
        val drafts: Set<Int> = emptySet(),    // trip ids edited but not saved
        val undos: Map<Int, Int> = emptyMap(), // trip id -> steps "되돌리기" can still undo
        val bases: Set<Int> = emptySet(),     // trip ids "수정 취소" can return to the saved state
        val joinLock: Long = 0,
        val routines: List<Routine> = emptyList(),      // registered daily drives (출근, 등교 …)
        val foundRoutines: List<Routine> = emptyList(), // repeated drives not registered yet
        val routineDraft: Routine? = null,              // 루틴 tab: the one being added or edited (id 0 = new)
        val routineId: Int? = null,                     // 루틴 tab: the one shown on the right               // join keypad locked until this time (ms) after 5 wrong codes
        val plans: List<PlanCard> = emptyList(), // all saved trip plans; the active one mirrors plan/planDone
        val editId: Int? = null,              // trip being edited in the plan tab (null = the active one)
        val addDay: Int? = null,              // "+" pressed: day we are adding a place to
        val newDay: Int? = null,              // day just added: its "+ 장소 추가" is highlighted
        val testDays: Long = 0,               // test button: days "today" is moved by
        val editing: Boolean = false,         // plan tab: edit mode (view mode shows no edit buttons)
        val guide: Int = 0,                   // new-trip guide: 2 = pick dates next, 3 = add a place next, 0 = off
        val naming: Boolean = false,          // trip name sheet open (for the trip shown in the plan tab)
        val hits: List<Hit> = emptyList(),    // place search results for "+"
        val here: Pair<Double, Double>? = null, // car position for the day map
        val nearby: List<Hit> = emptyList(),
        val parkOffer: List<Lot> = emptyList(), // lots offered just before arriving at a stop with no lot chosen  // quick picks around the trip's area, shown before typing
    )

    val ui = MutableStateFlow(Ui())
    fun set(f: Ui.() -> Ui) = ui.update(f)

    // actions from the screen, wired by the service
    var onAnswer: ((Seat, Who?) -> Unit)? = null
    var onGo: ((Place) -> Unit)? = null
    var onSeed: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onReset: (() -> Unit)? = null
    var onDemoPlan: (() -> Unit)? = null
    var onPostpone: (() -> Unit)? = null
    var onVisitWish: ((Place) -> Unit)? = null
    var onArrived: ((Boolean) -> Unit)? = null
    var onShare: (() -> Unit)? = null
    var onJoin: ((String) -> Unit)? = null
    var onEndShare: (() -> Unit)? = null
    var onFuel: ((Station) -> Unit)? = null
    var onNewTrip: (() -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null
    var onLots: ((Place, (List<Lot>) -> Unit) -> Unit)? = null
    var onSetPark: ((Int, Place?) -> Unit)? = null      // stop index in the trip being edited, lot or null for the place itself
    var onPickPark: ((Place?) -> Unit)? = null          // answer to the offer before arriving
    var onDemo: ((String) -> Unit)? = null // debug test menu: "soon"
    var onNearby: ((Int) -> Unit)? = null
    var onRename: ((String) -> Unit)? = null
    var onAddDay: (() -> Unit)? = null
    var onRemoveStop: ((Int) -> Unit)? = null
    var onSetDates: ((String, Int) -> Unit)? = null
    var onRemoveDay: ((Int) -> Unit)? = null
    var onOrderDay: ((Int) -> Unit)? = null
    var onTestDay: ((Long) -> Unit)? = null
    var onConsole: ((String) -> Unit)? = null // test menu: PUT a Virtual Console path (emulator only) // start (yyyy-mm-dd), number of days
    var onDeleteTrip: ((Int) -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onEdit: ((Int) -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onCancelEdit: (() -> Unit)? = null
    var onSetOrigin: ((Place?) -> Unit)? = null
    var onSaveRoutine: ((Routine) -> Unit)? = null
    var onDeleteRoutine: ((Int) -> Unit)? = null
    var onMoveStop: ((Int, Int) -> Unit)? = null
    var onDropStop: ((Int, Int, Int?) -> Unit)? = null
    var onAddPlace: ((Hit, Boolean) -> Unit)? = null
    var onActivate: ((Int) -> Unit)? = null
}

/** Trips, comfort and the trip plan in SharedPreferences as JSON. ponytail: fine for a few hundred trips. */
class Memory(ctx: Context) {
    private val p = ctx.getSharedPreferences("powerj", Context.MODE_PRIVATE)

    var trips: List<Trip>
        get() = JSONArray(p.getString("trips", "[]")).let { a -> List(a.length()) { tripOf(a.getJSONObject(it)) } }
        set(v) = p.edit().putString("trips", JSONArray(v.takeLast(300).map(::json)).toString()).apply()

    /** Per person: passenger temperature (°C) or rear seat heat level, remembered from the last ride. */
    fun comfort(who: Who): Float? = p.getFloat("comfort_$who", -1f).takeIf { it >= 0 }
    fun saveComfort(who: Who, value: Float) = p.edit().putFloat("comfort_$who", value).apply()

    /** Trip plan survives engine off/on, so the route can pick up where it left off. */
    var plan: List<Place>
        get() = JSONArray(p.getString("plan", "[]")).let { a -> List(a.length()) { placeOf(a.getJSONObject(it)) } }
        set(v) = p.edit().putString("plan", JSONArray(v.map(::placeJson)).toString()).apply()

    var planDone: Int
        get() = p.getInt("planDone", 0)
        set(v) = p.edit().putInt("planDone", v).apply()

    var editDay: Int
        get() = p.getInt("editDay", 1)
        set(v) = p.edit().putInt("editDay", v).apply()

    /** Trip day whose departure check was already spoken. */
    var checkedDay: Int
        get() = p.getInt("checkedDay", 0)
        set(v) = p.edit().putInt("checkedDay", v).apply()

    var routines: List<Routine>
        get() = JSONArray(p.getString("routines", "[]")).let { a -> List(a.length()) { a.getJSONObject(it) }.map { o ->
            val d = o.getJSONArray("days"); val w = o.getJSONArray("who")
            Routine(o.getInt("id"), o.getString("name"), placeOf(o.getJSONObject("dest")), List(d.length()) { d.getInt(it) }.toSet(), o.getInt("hour"),
                List(w.length()) { w.getString(it) }.mapNotNull { n -> Who.entries.firstOrNull { it.name == n } })
        } }
        set(v) = p.edit().putString("routines", JSONArray(v.map { r ->
            JSONObject().put("id", r.id).put("name", r.name).put("dest", placeJson(r.dest)).put("days", JSONArray(r.days.sorted()))
                .put("hour", r.hour).put("who", JSONArray(r.who.map { it.name }))
        }).toString()).apply()

    var wish: List<Place>
        get() = JSONArray(p.getString("wish", "[]")).let { a -> List(a.length()) { placeOf(a.getJSONObject(it)) } }
        set(v) = p.edit().putString("wish", JSONArray(v.map(::placeJson)).toString()).apply()

    /** Trip costs: tolls (원) and energy used (kWh) since the plan started. */
    var tolls: Int
        get() = p.getInt("tolls", 0)
        set(v) = p.edit().putInt("tolls", v).apply()
    /** "index:name" of the plan stop whose toll is already counted, kept across restarts so re-guiding doesn't count it twice. */
    var tollFor: String?
        get() = p.getString("tollFor", null)
        set(v) = p.edit().putString("tollFor", v).apply()
    var kWh: Float
        get() = p.getFloat("kWh", 0f)
        set(v) = p.edit().putFloat("kWh", v).apply()

    var planStart: String
        get() = p.getString("planStart", "") ?: ""
        set(v) = p.edit().putString("planStart", v).apply()

    var share: String?
        get() = p.getString("share", null)
        set(v) = p.edit().putString("share", v).apply()
    /** This car made the share code: only it may change the shared plan. */
    var shareHost: Boolean
        get() = p.getBoolean("shareHost", false)
        set(v) = p.edit().putBoolean("shareHost", v).apply()

    // ---------- several saved trips: the active one lives in plan/planDone/planStart, the rest here ----------

    var activeId: Int
        get() = p.getInt("activeId", 1)
        set(v) = p.edit().putInt("activeId", v).apply()

    /** Trips opened with "수정하기" (or new) and not yet saved: listed as "수정 중", can't be shared. */
    var drafts: Set<Int>
        get() = p.getStringSet("drafts", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("drafts", v.map { it.toString() }.toSet()).apply()

    var title: String
        get() = p.getString("title", "") ?: ""
        set(v) = p.edit().putString("title", v).apply()

    /** Days in the active trip, including empty ones the user added. */
    var dayCount: Int
        get() = maxOf(p.getInt("days", 1), plan.maxOfOrNull { it.day } ?: 0)
        set(v) = p.edit().putInt("days", v).apply()

    private var saved: List<JSONObject>
        get() = JSONArray(p.getString("plans", "[]")).let { a -> List(a.length()) { a.getJSONObject(it) } }
        set(v) = p.edit().putString("plans", JSONArray(v).toString()).apply()

    private fun activeJson() = JSONObject().put("id", activeId).put("title", title).put("start", planStart).put("done", planDone)
        .put("editDay", editDay).put("days", dayCount).put("stops", JSONArray(plan.map(::placeJson)))

    /** All trips for the list, active first, then by start date. */
    fun cards(): List<PlanCard> {
        val others = saved.filter { it.getInt("id") != activeId }.map { o ->
            val a = o.getJSONArray("stops")
            PlanCard(o.getInt("id"), o.optString("title"), o.optString("start"), List(a.length()) { placeOf(a.getJSONObject(it)) }, o.optInt("done"), false, o.optInt("days", 1), origin(o.getInt("id")))
        }
        val me = listOf(PlanCard(activeId, title, planStart, plan, planDone, true, dayCount, origin(activeId))) // shown even when empty, so a fresh "+ 새 여행" appears at once
        return me + others.sortedBy { it.start }
    }

    /** Park the active trip in the list and make [id] active (or a new empty trip when [id] is null). */
    fun switchTo(id: Int?) {
        val keep = saved.filter { it.getInt("id") != activeId }.toMutableList()
        if (plan.isNotEmpty() || title.isNotEmpty()) keep += activeJson()
        val next = id?.let { want -> keep.firstOrNull { it.getInt("id") == want } }
        keep.removeAll { it === next }
        saved = keep
        clearPlan()
        if (next == null) {
            activeId = (keep.map { it.getInt("id") } + activeId).max() + 1
            title = ""; setOrigin(activeId, null) // a reused id must not inherit an old trip's start
        } else {
            activeId = next.getInt("id"); title = next.optString("title")
            val a = next.getJSONArray("stops")
            plan = List(a.length()) { placeOf(a.getJSONObject(it)) }
            planDone = next.optInt("done"); planStart = next.optString("start"); editDay = next.optInt("editDay", 1); dayCount = next.optInt("days", 1)
        }
    }

    /** Editable view of any trip, active or saved. */
    data class TripData(val title: String, val start: String, val stops: List<Place>, val done: Int, val days: Int, val origin: Place? = null)

    /** "출발 위치" the user picked for a trip; null = the car's spot. Kept per trip id, outside the trip JSON. */
    fun origin(id: Int): Place? = p.getString("origin_$id", null)?.let { placeOf(JSONObject(it)) }
    fun setOrigin(id: Int, o: Place?) = p.edit().apply { if (o == null) remove("origin_$id") else putString("origin_$id", placeJson(o).toString()) }.apply()

    fun trip(id: Int): TripData? =
        if (id == activeId) TripData(title, planStart, plan, planDone, dayCount, origin(id))
        else saved.firstOrNull { it.getInt("id") == id }?.let { o ->
            val a = o.getJSONArray("stops")
            TripData(o.optString("title"), o.optString("start"), List(a.length()) { placeOf(a.getJSONObject(it)) }, o.optInt("done"), o.optInt("days", 1), origin(id))
        }

    fun saveTrip(id: Int, d: TripData) {
        setOrigin(id, d.origin)
        if (id == activeId) { title = d.title; planStart = d.start; plan = d.stops; planDone = d.done; dayCount = maxOf(d.days, d.stops.maxOfOrNull { it.day } ?: 1); return }
        saved = saved.map { o ->
            if (o.getInt("id") != id) o
            else JSONObject(o.toString()).put("title", d.title).put("start", d.start).put("done", d.done)
                .put("days", maxOf(d.days, d.stops.maxOfOrNull { it.day } ?: 1)).put("stops", JSONArray(d.stops.map(::placeJson)))
        }
    }

    /** Delete a trip. Deleting the active one opens the next saved trip, or a fresh empty one. */
    fun delete(id: Int) {
        setOrigin(id, null)
        if (id != activeId) { saved = saved.filter { it.getInt("id") != id }; return }
        val next = saved.minByOrNull { it.optString("start") }?.getInt("id")
        clearPlan(); title = ""
        switchTo(next) // the emptied active trip is not kept
    }

    /** Save a whole trip into the list without touching the active one (demo data). */
    fun keep(id: Int, title: String, start: String, stops: List<Place>) {
        saved = saved.filter { it.getInt("id") != id } + JSONObject().put("id", id).put("title", title).put("start", start).put("done", 0)
            .put("editDay", 1).put("days", stops.maxOfOrNull { it.day } ?: 1).put("stops", JSONArray(stops.map(::placeJson)))
    }

    fun clearPlan() = p.edit().remove("plan").remove("planDone").remove("editDay").remove("tolls").remove("tollFor").remove("kWh").remove("checkedDay").remove("planStart").remove("share").remove("shareHost").remove("title").remove("days").apply()

    fun clear() = p.edit().clear().apply()


    private fun json(t: Trip) = JSONObject().apply {
        put("seats", JSONArray(t.seats.map { it.name }))
        put("who", JSONObject(t.who.entries.associate { it.key.name to it.value.name }))
        put("weekend", t.weekend); put("hour", t.hour); put("cell", t.startCell)
        t.dest?.let { put("dest", placeJson(it)) }
    }

    private fun tripOf(o: JSONObject): Trip {
        val s = o.getJSONArray("seats"); val w = o.getJSONObject("who")
        return Trip(
            seats = List(s.length()) { Seat.valueOf(s.getString(it)) }.toSet(),
            who = w.keys().asSequence().mapNotNull { k -> Who.entries.firstOrNull { it.name == w.getString(k) }?.let { Seat.valueOf(k) to it } }.toMap(),
            weekend = o.getBoolean("weekend"), hour = o.getInt("hour"), startCell = o.getString("cell"),
            dest = o.optJSONObject("dest")?.let(::placeOf),
        )
    }
}
