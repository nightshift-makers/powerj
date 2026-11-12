package com.beat.powerj

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Same look as Pleos Connect native apps (Phone, Vehicle control): monochrome, tabs, 1px-divided tiles, sharp buttons. */
private class Pal(val bg: Color, val panel: Color, val line: Color, val ink: Color, val sub: Color, val dim: Color, val onInk: Color, val button: Color)
private val LightPal = Pal(Color.White, Color(0xFFF2F2F2), Color(0xFFE0E0E0), Color.Black, Color(0xFF6B6B6B), Color(0xFF9E9E9E), Color.White, Color(0xFFE6E6E6))
private val DarkPal = Pal(Color(0xFF111111), Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color.White, Color(0xFFA0A0A0), Color(0xFF6E6E6E), Color.Black, Color(0xFF3A3A3C))
private var C = LightPal
private val Red = Color(0xFFE5342B)
private val Amber = Color(0xFFE0A800)
private val Ok = Color(0xFF00B33C)

// Asta Sans is registered as "pretendard" on Pleos Connect
private val Pleos = FontFamily(android.graphics.Typeface.create("pretendard", android.graphics.Typeface.NORMAL))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, JService::class.java))
        setContent {
            val ui by Store.ui.collectAsStateWithLifecycle()
            Screen(ui)
        }
    }

}

@Composable
private fun Screen(ui: Store.Ui) {
    C = if (isSystemInDarkTheme()) DarkPal else LightPal
    var tab by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Pleos, color = C.ink)) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            Column(Modifier.fillMaxSize()) {
                Header(ui)
                Tabs(listOf("오늘", "루틴", "여행 계획"), tab) { tab = it }
                when (tab) {
                    0 -> Today(ui)
                    1 -> RoutineTab(ui)
                    else -> PlanTab(ui)
                }
            }
            val parked = ui.phase != Phase.DRIVING // keyboards and editing only while stopped (the app is distraction-optimized)
            if (tab == 2 && parked) ui.addDay?.takeIf { it >= 0 }?.let { PlaceSearch(it, ui.hits, ui.nearby) }
            if (tab == 1 && parked && ui.addDay == -1) PlaceSearch(-1, ui.hits, emptyList())
            val shown = ui.plans.firstOrNull { it.id == ui.editId } ?: ui.plans.firstOrNull { it.active }
            if (tab == 2 && parked && ui.naming && shown != null) TripName(shown)
            if (tab == 1 && parked && ui.naming) ui.routineDraft?.let { RoutineName(it) }
            dayMap?.let { (title, stops) -> DayMap(title, stops, ui.here) { dayMap = null } }
            if (parked) parkPick?.let { (_, i, stop) -> ParkPicker(stop, ui.here) { lot, picked -> if (picked) Store.onSetPark?.invoke(i, lot); parkPick = null } }
        }
    }
}

/** Test menu's ignition button state (the console has no read-back the app uses). */
private var powerOn by mutableStateOf(true)

@Composable private fun MenuLine(label: String, content: @Composable RowScope.() -> Unit) =
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 20.sp, color = C.sub, modifier = Modifier.width(110.dp)); content()
    }

@Composable private fun MenuSection(title: String) =
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.dim, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))

/** Debug builds only: move "today", change gear, seat people. The car parts go through the emulator's Virtual Console. */
@Composable
private fun TestLines(shift: Long, phase: Phase) {
    val console = { path: String -> Store.onConsole?.invoke(path) }
    MenuLine("오늘 날짜") {
        Button("◀ 하루") { Store.onTestDay?.invoke(shift - 1) }
        Button("실제 오늘", quiet = shift == 0L) { Store.onTestDay?.invoke(0) }
        Button("하루 ▶") { Store.onTestDay?.invoke(shift + 1) }
    }
    MenuLine("시동") { // console power state only, for the demo; the app itself treats P→D as the start
        Button("켜기", primary = powerOn) { powerOn = true; console("/vehicle/power-system/simulator?state=Ready&area_id=0") }
        Button("끄기", primary = !powerOn) {
            powerOn = false; console("/vehicle/gear?gear=Park")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ console("/vehicle/power-system/simulator?state=Off&area_id=0") }, 500)
        }
    }
    MenuLine("기어") {
        listOf("P" to "Park", "D" to "Drive").forEach { (k, v) -> // no R: the rear camera covers the whole screen and this menu with it
            val on = (k == "P" && phase == Phase.PARKED) || (k == "D" && phase == Phase.DRIVING)
            Button(k, primary = on) { console("/vehicle/gear?gear=$v") }
        }
        Text("D로 바꾸면 출발", fontSize = 17.sp, color = C.dim)
    }
    MenuLine("속도") { // the emulator's map position never moves; this only drives the cluster
        Button("정차 0") { console("/vehicle/controls?speed_kph=0") }
        Button("40 km/h") { console("/vehicle/controls?speed_kph=40") }
    }
    MenuLine("도착") {
        Button("곧 도착") { Store.onDemo?.invoke("soon") }
        Button("도착 (정차 후 P)") {
            console("/vehicle/controls?speed_kph=0")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ console("/vehicle/gear?gear=Park") }, 400)
        }
        Text("P면 '도착하셨나요?'", fontSize = 17.sp, color = C.dim)
    }
    MenuLine("조수석") {
        Button("앉기") { console("/vehicle/seat?kind=occupied&area=passenger&value=true") }
        Button("비우기") { console("/vehicle/seat?kind=occupied&area=passenger&value=false") }
    }
    MenuLine("뒷좌석") {
        // a rear door opening while parked means someone got in behind (belts can't be set from the console)
        Button("왼쪽 문 열고 닫기") { console("/vehicle/body?door_rl=true"); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ console("/vehicle/body?door_rl=false") }, 1500) }
        Button("오른쪽 문 열고 닫기") { console("/vehicle/body?door_rr=true"); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ console("/vehicle/body?door_rr=false") }, 1500) }
    }
}

@Composable
private fun Header(ui: Store.Ui) {
    var menu by remember { mutableStateOf(false) }
    var wiping by remember { mutableStateOf(false) }
    if (wiping) Confirm("기록을 모두 지울까요?", "학습한 주행, 저장한 여행 계획 ${ui.plans.count { it.stops.isNotEmpty() || it.title.isNotBlank() }}개, 가 볼 곳이 모두 지워지고 되돌릴 수 없어요.", "모두 지우기",
        { wiping = false; Store.onClear?.invoke() }, { wiping = false })
    val shift = ui.testDays
    Row(Modifier.fillMaxWidth().height(80.dp).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF16181B)), contentAlignment = Alignment.Center) { // same mark as the app icon
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(92.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text("POWER J", fontSize = 30.sp, fontWeight = FontWeight.Medium)
        // a moved "today" changes what the app shows, so it stays visible (the controls live in "···")
        if (shift != 0L) Text("테스트 날짜 " + dayLabel(today().toString(), 1, "").substringAfter(" · ") + " (${if (shift > 0) "+" else ""}${shift}일)",
            fontSize = 20.sp, color = Color.Black, modifier = Modifier.padding(start = 20.dp).background(Amber).clickable { menu = true }.padding(horizontal = 14.dp, vertical = 8.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        Box(Modifier.size(80.dp).clickable { menu = true }, contentAlignment = Alignment.Center) {
            Text("···", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(C.panel)) {
                MenuSection("시연 데이터")
                MenuLine("넣기") {
                    Button("주행 기록") { Store.onSeed?.invoke(); menu = false }
                    Button("여행 계획") { Store.onDemoPlan?.invoke(); menu = false }
                }
                MenuLine("지우기") { Button("기록 모두 지우기") { wiping = true; menu = false } }
                if (BuildConfig.DEBUG) { MenuSection("테스트 (에뮬레이터)"); TestLines(shift, ui.phase) }
            }
        }
    }
    Divider()
}

@Composable private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))

@Composable
private fun Tabs(names: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(78.dp).padding(start = 16.dp)) {
        names.forEachIndexed { i, n ->
            Column(Modifier.fillMaxHeight().width(IntrinsicSize.Max).clickable { onSel(i) }.padding(horizontal = 20.dp), verticalArrangement = Arrangement.Bottom) {
                Text(n, fontSize = 24.sp, fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Normal, color = if (i == sel) C.ink else C.sub,
                    modifier = Modifier.padding(bottom = 18.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (i == sel) C.ink else Color.Transparent))
            }
        }
    }
    Divider()
}

// ---------- 오늘 ----------

@Composable
private fun Today(ui: Store.Ui) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.42f).fillMaxHeight().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            val (mode, head, sub, tint) = headline(ui)
            Text(mode, color = C.sub, fontSize = 22.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(head, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = tint ?: C.ink, lineHeight = 54.sp)
                if (sub.isNotEmpty()) Text(sub, color = C.sub, fontSize = 24.sp, lineHeight = 34.sp)
            }
            ui.ask?.let { AskWho(it) }
            if (ui.ask == null) when {
                ui.fuel != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button("경유지로 넣기", primary = true) { Store.onFuel?.invoke(ui.fuel) }
                    Button("괜찮아요", quiet = true) { Store.set { copy(fuel = null) } }
                }
                ui.parkOffer.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("주차장으로 안내할까요?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    ui.parkOffer.forEachIndexed { k, lot ->
                        Button("${lot.place.name}  ·  ${lot.line.split(" · ").take(3).joinToString(" · ")}", primary = k == 0) { Store.onPickPark?.invoke(lot.place) }
                    }
                    Button("목적지로 그대로", quiet = true) { Store.onPickPark?.invoke(null) }
                }
                ui.askArrived != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button("도착했어요", primary = true) { Store.onArrived?.invoke(true) }
                    Button("아니요, 잠깐 들른 곳", quiet = true) { Store.onArrived?.invoke(false) }
                }
                ui.late != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button("내일로 미루기", primary = true) { Store.onPostpone?.invoke() }
                    Button("그대로 가기", quiet = true) { Store.set { copy(late = null) } }
                }
                ui.wishNear != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button("들르기", primary = true) { Store.onVisitWish?.invoke(ui.wishNear) }
                    Button("괜찮아요", quiet = true) { Store.set { copy(wishNear = null) } }
                }
                ui.suggestion != null -> Button("${ui.suggestion.name} 안내 시작", primary = true) { Store.onGo?.invoke(ui.suggestion) }
            }
            Spacer(Modifier.weight(1f))
            if (ui.share != null) { // leaving or ending a shared trip from 오늘 too, but only while one is shared
                var leaving by remember { mutableStateOf(false) }
                Button(if (ui.guest) "함께 가기 나가기" else "함께 가기 끝내기", quiet = true) { leaving = true }
                if (leaving) Confirm(if (ui.guest) "함께 가기에서 나갈까요?" else "함께 가기를 끝낼까요?",
                    if (ui.guest) "받은 계획은 내 여행으로 남아요." else "동행 차도 함께 가기에서 빠져요.", if (ui.guest) "나가기" else "끝내기",
                    { leaving = false; Store.onEndShare?.invoke() }, { leaving = false })
            } else if (ui.phase != Phase.DRIVING) JoinCode(ui.joinLock) // no typing codes while driving
            Voice(ui.voice)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        val ctx = LocalContext.current
        Grid(Modifier.weight(0.58f), todayTiles(ui) { phone -> runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
    }
}

private data class Head(val mode: String, val head: String, val sub: String, val tint: Color? = null)

private fun headline(ui: Store.Ui): Head {
    ui.fuel?.let { return Head(if (it.price > 0) "가는 길 주유" else "가는 길 충전", it.name,
        if (it.price > 0) "%.0fkm 앞 · 리터당 %,d원 · 가장 싸요".format(it.km, it.price) else "%.0fkm 앞 · 급속 충전기 비어 있음".format(it.km)) }
    ui.askArrived?.let { return Head("도착 확인", "${it.name}에 도착하셨나요?", "주차한 곳이 목적지에서 조금 떨어져 있어요") }
    ui.late?.let { return Head("계획 조정", "내일로 미룰까요?", it, Amber) }
    ui.wishNear?.let { return Head("가 볼 곳", "${it.name} 근처예요", "담아 두신 곳이에요. 들르면 경유지로 넣을게요") }
    if (ui.phase == Phase.PARKED) ui.plans.firstOrNull { it.active }?.takeIf { it.stops.isNotEmpty() && !started(it.start, today()) }?.let {
        return Head("다가오는 여행 · ${tripDates(it)}", tripTitle(it), "그날 시동을 걸면 ${it.stops.first().name.ro()} 바로 안내해요. 오늘은 평소처럼 챙길게요")
    }
    if (ui.phase == Phase.PARKED) ui.plans.firstOrNull { it.active }?.let { t ->
        val next = t.stops.getOrNull(t.done) ?: return@let
        val n = dayNo(t.start, today())
        if (next.day > n) return Head("여행 ${n}일차 · ${tripTitle(t)}", "오늘은 담은 곳이 없어요",
            "다음 일정은 ${dayLabel(t.start, next.day, "")} ${next.name.j("이에요", "예요")}. 그날 시동을 걸면 안내해요")
    }
    if (ui.phase == Phase.PARKED) ui.plan.getOrNull(ui.planDone)?.let {
        return Head("여행 ${it.day}일차 · ${ui.planDone + 1}/${ui.plan.size}", "다음: ${it.name}", ui.check ?: "기어를 D로 바꾸면 날씨와 차 상태를 점검하고 바로 이어서 안내해요")
    }
    if (ui.phase == Phase.PARKED) return Head("출발 전", "오늘도 미리 챙길게요", "기어를 D로 바꾸면 누가 탔는지, 어디 가는지 준비해요")
    ui.ask?.let { return Head("탑승 확인", "${it.label}에 누가 탔나요?", "한 번만 알려 주시면 다음부터는 묻지 않아요") }
    ui.suggestion?.let { s ->
        ui.routines.firstOrNull { it.dest.name == s.name }?.let { r -> return Head("루틴 · ${r.name}", "${r.name} 시간이에요. ${s.name}?", "${daysLabel(r.days)} ${r.hour}시 루틴이에요. 누르면 바로 안내해요") }
        return Head("목적지 예측", "평소처럼 ${s.name}?", "같은 시간, 같은 사람과 자주 가신 곳이에요")
    }
    ui.soon?.let { return Head("곧 도착", "${ui.dest}에 곧 도착해요", it) }
    ui.dest?.let { return Head(ui.kind?.let { k -> "안내 중 · $k" } ?: "안내 중", "${it}까지 ${ui.etaMin}분",
        ui.note ?: ui.kit.takeIf { k -> k.isNotEmpty() }?.joinToString(" · ")?.let { k -> "챙길 것: $k" } ?: "") }
    return Head("주행 중", "준비 완료", "내비에 목적지를 넣으면 브리핑해 드려요")
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AskWho(seat: Seat) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Who.entries.forEach { w -> Button(w.label) { Store.onAnswer?.invoke(seat, w) } }
        Button("건너뛰기", quiet = true) { Store.onAnswer?.invoke(seat, null) }
    }
}

private data class Tile(val icon: ImageVector, val title: String, val value: String, val tint: Color? = null, val onClick: (() -> Unit)? = null, val big: String? = null, val bars: List<CarBar> = emptyList())

private fun todayTiles(ui: Store.Ui, dial: (String) -> Unit) = listOf(
    Tile(Icons.Outlined.Person, "탑승",
        if (ui.seats.isEmpty()) "운전자 혼자" else ui.seats.joinToString(" · ") { s -> ui.who[s]?.let { "${it.label}(${s.label})" } ?: s.label }),
    Tile(Icons.Outlined.Place, "목적지", ui.dest?.let { "$it · ${ui.etaMin}분" + if (ui.toll > 0) " · 통행료 %,d원".format(ui.toll) else "" }
        ?: ui.suggestion?.let { "예측: ${it.name}" } ?: "안내 전"),
    Tile(Icons.Outlined.CheckCircle, "챙길 것", ui.kit.joinToString(" · ").ifEmpty { "목적지를 정하면 알려 드려요" }),
    Tile(Icons.Outlined.Build, "출발 전 점검", ui.check ?: "첫 출발 때 오늘 일정과 차 상태를 비교해요",
        if (ui.check?.contains("필요해요") == true || ui.check?.contains("경고등") == true) Amber else null),
    ui.card?.let { c ->
        val phone = dialable(c.phone)
        Tile(Icons.Outlined.Phone, "장소 정보" + if (phone != null) " · 눌러서 전화" else "",
            listOfNotNull("영업시간 ${c.hours ?: "정보 없음"}", c.closed?.let { "쉬는 날 $it" }, "전화 ${c.phone ?: "정보 없음"}").joinToString("\n"),
            onClick = phone?.let { { dial(it) } })
    } ?: Tile(Icons.Outlined.Refresh, "왕복 배터리", ui.energy ?: "목적지를 정하면 계산해요"),
    if (ui.lots.isNotEmpty()) Tile(Icons.Outlined.Place, "주변 주차장", ui.lots.joinToString("\n"))
    else if (ui.plan.isEmpty()) todayRoutines(ui).let { rs ->
        Tile(Icons.Outlined.DateRange, "오늘 루틴", if (rs.isEmpty()) "루틴 탭에서 출근·등교를 등록하면 그 시간에 먼저 챙겨요\n학습한 주행 ${ui.trips}회"
            else rs.joinToString("\n") { "%02d시 %s · %s".format(it.hour, it.name, it.dest.name) })
    }
    else ui.plans.firstOrNull { it.active }?.takeIf { !started(it.start, today()) }
        ?.let { Tile(Icons.Outlined.DateRange, "다가오는 여행", "${tripDates(it)} · ${tripTitle(it)}\n그날 시동을 걸면 ${it.stops.firstOrNull()?.name ?: "첫 장소"}부터 안내해요") }
        ?: Tile(Icons.Outlined.DateRange, "여행 계획 ${ui.planDone}/${ui.plan.size}", ui.cost?.let { "차량비 $it" } ?: planLine(ui)),
)

private fun planLine(ui: Store.Ui) = ui.plan.mapIndexed { i, p -> (if (i < ui.planDone) "✓" else "") + p.name }.joinToString(" → ")

private fun todayRoutines(ui: Store.Ui): List<Routine> {
    val dow = today().dayOfWeek.value // 1 = Mon .. 7 = Sun
    return ui.routines.filter { dow in it.days }.sortedBy { it.hour }
}

// ---------- 루틴 ----------

private val routineNames = listOf("출근", "퇴근", "등교", "하원", "학원", "운동", "장보기")

@Composable
private fun RoutineTab(ui: Store.Ui) {
    val parked = ui.phase != Phase.DRIVING
    val draft = ui.routineDraft
    val shown = ui.routines.firstOrNull { it.id == ui.routineId } ?: ui.routines.firstOrNull()
    fun newDraft(from: Routine? = null) = Store.set { copy(routineDraft = from ?: Routine(0, "", Place("", 0.0, 0.0), WEEKDAYS, java.time.LocalTime.now().hour)) }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.34f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("루틴", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (parked) Button("＋ 루틴 추가", primary = true, next = ui.routines.isEmpty() && draft == null) { newDraft() }
            }
            if (ui.routines.isEmpty()) Text("출근, 등교처럼 매일 타는 길을 등록하면\n그 시간에 시동을 걸 때 먼저 챙겨요.", fontSize = 22.sp, color = C.sub, lineHeight = 32.sp,
                modifier = Modifier.fillMaxWidth().background(C.panel).padding(20.dp))
            ui.routines.sortedBy { it.hour }.forEach { r ->
                val sel = draft == null && r.id == shown?.id || draft?.id == r.id
                Column(Modifier.fillMaxWidth().background(if (sel) C.panel else Color.Transparent).then(if (sel) Modifier.border(3.dp, Amber) else Modifier)
                    .clickable { Store.set { copy(routineId = r.id, routineDraft = null) } }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sel) { Box(Modifier.size(16.dp).clip(CircleShape).background(Amber)); Spacer(Modifier.width(12.dp)) }
                        Text(r.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (draft?.id == r.id) Text("수정 중", fontSize = 18.sp, color = Color.Black, modifier = Modifier.background(Amber).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Text("${daysLabel(r.days)} ${r.hour}시 · ${r.dest.name}", color = C.sub, fontSize = 21.sp)
                }
            }
            if (ui.foundRoutines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("자동으로 찾은 루틴", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("같은 시간에 같은 곳을 여러 번 다녀왔어요", fontSize = 19.sp, color = C.sub)
                ui.foundRoutines.forEach { f ->
                    Column(Modifier.fillMaxWidth().border(1.dp, C.line).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${daysLabel(f.days)} ${f.hour}시 · ${f.dest.name}", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("${f.seen}번 다녀옴" + (if (f.who.isNotEmpty()) " · ${f.who.joinToString(", ") { it.label }}와 함께" else ""), fontSize = 19.sp, color = C.sub)
                        if (parked) Button("루틴으로 저장") { newDraft(f.copy(id = 0)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Voice(ui.voice)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        Column(Modifier.weight(0.66f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when {
                draft != null && parked -> RoutineForm(draft)
                shown != null -> RoutineView(shown, parked)
                else -> {
                    Text("루틴을 만들어 볼까요?", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("왼쪽의 '＋ 루틴 추가'를 눌러 이름, 목적지, 요일과 시간을 정해요.\n등록하지 않아도 같은 길을 두 번 다니면 '자동으로 찾은 루틴'에 떠요.", fontSize = 23.sp, color = C.sub, lineHeight = 34.sp)
                }
            }
        }
    }
}

/** What the app does when this routine starts: shown on the routine and while editing it. */
@Composable
private fun RoutinePreview(r: Routine) {
    val kit = if (r.dest.name.isBlank()) emptyList() else checklist(r.dest.name, r.who)
    val lines = listOfNotNull(
        "시동 후 D로 바꾸면  \"${r.name.ifBlank { "루틴" }} 시간이에요. ${r.dest.name.ifBlank { "목적지" }} 가시죠?\"  — 누르면 바로 안내",
        "함께 타는 사람이 지난번에 쓰던 온도·열선을 맞춰요".takeIf { r.who.isNotEmpty() },
        "챙길 것: ${kit.joinToString(", ")}".takeIf { kit.isNotEmpty() },
        "안내를 시작하면 도착 시간, 통행료, 왕복 배터리를 한 번에 알려 줘요",
        kindOf(r.dest.name)?.let { "도착 3분 전: ${it.soon}" },
    )
    Column(Modifier.fillMaxWidth().background(C.panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("출발하면 이렇게 챙겨요", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        lines.forEach { Text("· $it", fontSize = 21.sp, color = C.sub, lineHeight = 30.sp) }
    }
}

@Composable
private fun RoutineView(r: Routine, parked: Boolean) {
    var asking by remember(r.id) { mutableStateOf(false) }
    if (asking) Confirm("'${r.name}' 루틴을 지울까요?", "학습한 주행 기록은 남아요.", "지우기", { asking = false; Store.onDeleteRoutine?.invoke(r.id) }, { asking = false })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(r.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (parked) { Button("삭제", quiet = true) { asking = true }; Button("수정하기") { Store.set { copy(routineDraft = r) } } }
        else Text("주행 중에는 볼 수만 있어요", fontSize = 21.sp, color = C.sub)
    }
    Text("${daysLabel(r.days)} ${r.hour}시쯤 · ${r.dest.name}", fontSize = 25.sp)
    Text("함께 타는 사람: " + (r.who.joinToString(", ") { it.label }.ifEmpty { "혼자" }), fontSize = 23.sp, color = C.sub)
    RoutinePreview(r)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RoutineForm(d: Routine) {
    fun edit(f: Routine.() -> Routine) = Store.set { copy(routineDraft = routineDraft?.f()) }
    @Composable fun Label(t: String) = Text(t, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val gap = Arrangement.spacedBy(10.dp)
    Text(if (d.id == 0) "새 루틴" else "${d.name} 수정", fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Label("이름")
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        routineNames.forEach { n -> Button(n, primary = d.name == n) { edit { copy(name = n) } } }
        Button(if (d.name.isNotBlank() && d.name !in routineNames) "${d.name} ✎" else "직접 입력", primary = d.name.isNotBlank() && d.name !in routineNames) { Store.set { copy(naming = true) } }
    }
    Label("목적지")
    Row(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(d.dest.name.ifBlank { "아직 정하지 않았어요" }, fontSize = 24.sp, color = if (d.dest.name.isBlank()) C.sub else C.ink, modifier = Modifier.weight(1f))
        Button(if (d.dest.name.isBlank()) "정하기" else "바꾸기", next = d.name.isNotBlank() && d.dest.name.isBlank()) { Store.set { copy(addDay = -1, hits = emptyList()) } }
    }
    Label("요일")
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        listOf("평일" to WEEKDAYS, "주말" to WEEKEND, "매일" to WEEKDAYS + WEEKEND).forEach { (n, set) -> Button(n, primary = d.days == set) { edit { copy(days = set) } } }
        Spacer(Modifier.width(12.dp))
        (1..7).forEach { k -> Button("월화수목금토일"[k - 1].toString(), primary = k in d.days) { edit { copy(days = if (k in days) days - k else days + k) } } }
    }
    Label("출발 시간")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button("－") { edit { copy(hour = (hour + 23) % 24) } }
        Text("%02d시쯤".format(d.hour), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Button("＋") { edit { copy(hour = (hour + 1) % 24) } }
        Text("앞뒤 1시간 안에 출발하면 이 루틴으로 봐요", fontSize = 19.sp, color = C.sub)
    }
    Label("함께 타는 사람")
    FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
        Button("혼자", primary = d.who.isEmpty()) { edit { copy(who = emptyList()) } }
        Who.entries.forEach { w -> Button(w.label, primary = w in d.who) { edit { copy(who = if (w in who) who - w else who + w) } } }
    }
    RoutinePreview(d)
    val ready = d.name.isNotBlank() && d.dest.name.isNotBlank() && d.days.isNotEmpty()
    if (ready) Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).background(Amber).clickable { Store.onSaveRoutine?.invoke(d) }, contentAlignment = Alignment.Center) {
        Text("저장", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    } else Text(when { d.name.isBlank() -> "이름을 고르면"; d.dest.name.isBlank() -> "목적지를 정하면"; else -> "요일을 하나 이상 고르면" } + " 저장할 수 있어요",
        fontSize = 21.sp, color = C.sub, modifier = Modifier.fillMaxWidth().background(C.panel).padding(20.dp))
    Button("취소", quiet = true) { Store.set { copy(routineDraft = null, addDay = null) } }
}

/** Typed routine name, for names beyond the ready-made ones. */
@Composable
private fun RoutineName(d: Routine) {
    val close = { Store.set { copy(naming = false) } }
    KeyboardSheet("루틴 이름", "예: 수영, 피아노 학원, 부모님 댁", d.name.takeIf { it !in routineNames }.orEmpty(), "루틴 이름", ImeAction.Done, "저장", {},
        { n -> if (n.isNotBlank()) Store.set { copy(routineDraft = routineDraft?.copy(name = n.trim())) }; close() }, close) { }
}

// ---------- 여행 계획 ----------

@Composable
private fun PlanTab(ui: Store.Ui) {
    val shown = ui.plans.firstOrNull { it.id == ui.editId } ?: ui.plans.firstOrNull { it.active } ?: ui.plans.firstOrNull()
    val pick: (Int?) -> Unit = { id ->
        if (ui.editing) Store.set { copy(editing = false) } // unsaved edits stay; the list marks the trip "수정 중"
        Store.set { copy(editId = id, addDay = null, hits = emptyList(), guide = 0, newDay = null) } }
    // the always-present blank active trip only shows once the user pressed "＋ 새 여행" (guide > 0)
    fun blank(t: PlanCard) = t.active && t.stops.isEmpty() && t.title.isBlank() && ui.guide == 0 && !ui.editing && !ui.guest
    val list = ui.plans.filterNot(::blank)
    val none = list.isEmpty()
    val canNew = !ui.guest && ui.phase != Phase.DRIVING // no creating trips while driving or as a joined car
    val newHint = when { canNew -> "위의 '＋ 새 여행'을 눌러 첫 여행을 만들어 보세요."; ui.guest -> "함께 가기 중에는 코드를 만든 차만 여행을 만들 수 있어요."; else -> "주행 중에는 만들 수 없어요. 기어를 P로 바꾸면 '＋ 새 여행' 버튼이 보여요." }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.34f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("여행 계획", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (canNew) Button("＋ 새 여행", primary = true, next = none) { Store.onNewTrip?.invoke() }
            }
            if (none) Text("아직 여행이 없어요.\n" + newHint, fontSize = 22.sp, color = C.sub, lineHeight = 32.sp,
                modifier = Modifier.fillMaxWidth().background(C.panel).padding(20.dp))
            // joined car: anything that swaps or deletes the active (shared) trip is hidden; leave via "함께 가기 나가기"
            list.forEach { t -> TripRow(t, t.id == shown?.id, deletable = !ui.guest && ui.phase != Phase.DRIVING, draft = t.id in ui.drafts && !(ui.guest && t.active)) { pick(t.id) } }
            if (shown != null && !shown.active && !ui.guest) when (tripWhen(shown, today())) { // the app switches to a trip by itself on its day
                0 -> Button("이 여행으로 안내", primary = true) { Store.onActivate?.invoke(shown.id) }
                1 -> Text("${dayLabel(shown.start, 1, "").substringAfter(" · ")}에 시동을 걸면 자동으로 안내해요", fontSize = 21.sp, color = C.sub, lineHeight = 30.sp)
                else -> Text("지난 여행이에요", fontSize = 21.sp, color = C.sub)
            }
            if (ui.share == null && ui.phase != Phase.DRIVING) { Spacer(Modifier.height(8.dp)); JoinCode(ui.joinLock) } // joining someone's trip is list-level, not part of one trip
            Spacer(Modifier.height(8.dp))
            Voice(ui.voice)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
        Column(Modifier.weight(0.66f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (shown == null || blank(shown)) Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (none) "여행을 만들어 볼까요?" else "여행을 골라 주세요", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(if (!canNew) newHint else if (none) "왼쪽의 '＋ 새 여행' 버튼을 누르면 이름 → 날짜 → 장소 순서로 안내해 드려요."
                     else "왼쪽 목록에서 여행을 고르거나, '＋ 새 여행' 버튼을 눌러 새로 만들어요.", fontSize = 23.sp, color = C.sub, lineHeight = 34.sp)
            }
            else if (shown != null) TripEditor(shown, ui, editable = ui.phase != Phase.DRIVING && (ui.editing || shown.id in ui.drafts) && !(ui.guest && shown.active)) // unsaved ("수정 중") trips reopen in edit mode; joined car: shared trip is view-only
            if (shown?.active == true && shown.stops.isNotEmpty()) Grid(Modifier.fillMaxWidth(), planTiles(ui), scroll = false) // nothing to total yet
            if (shown != null && !blank(shown)) TripExtras(ui, shown)
        }
    }
}

/** Yes/no popup used before anything that deletes places. */
@Composable
private fun Confirm(title: String, body: String, yes: String, onYes: () -> Unit, onNo: () -> Unit, ask: Boolean = true,
                    content: (@Composable () -> Unit)? = null) = AlertDialog(
    onDismissRequest = onNo,
    modifier = if (content != null) Modifier.fillMaxWidth(0.8f) else Modifier,
    properties = DialogProperties(usePlatformDefaultWidth = content == null),
    title = { Text(title, fontSize = 26.sp, color = C.ink) },
    text = { content?.invoke() ?: Text(body, fontSize = 20.sp, color = C.sub) },
    confirmButton = { Button(yes, primary = true, onClick = onYes) },
    dismissButton = { if (ask) Button("취소", quiet = true, onClick = onNo) },
    containerColor = C.panel,
)

/** The parking picker overlay: trip id, stop index, stop; or null when closed. */
private var parkPick by mutableStateOf<Triple<Int, Int, Place>?>(null)

/** The day map overlay: title and stops, or null when closed. */
private var dayMap by mutableStateOf<Pair<String, List<Place>>?>(null)

/** Pick a parking lot for [stop] on a map: the place itself first, then lots nearest first. done(lot, picked): lot null = the place itself. */
@Composable
private fun ParkPicker(stop: Place, here: Pair<Double, Double>?, done: (Place?, Boolean) -> Unit) {
    BackHandler { done(null, false) }
    val dark = C == DarkPal
    var lots by remember(stop) { mutableStateOf<List<Lot>?>(null) }
    var chosen by remember(stop) { mutableStateOf(stop.park) }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    LaunchedEffect(stop) { Store.onLots?.invoke(stop) { lots = it } }
    fun js(code: String) { web?.evaluateJavascript(code, null) }
    fun show(i: Int) = js("pick($i)") // -1 = the place
    val ink = if (dark) "#fff" else "#000"; val paper = if (dark) "#000" else "#fff"
    val pins = org.json.JSONArray(lots.orEmpty().map { org.json.JSONObject().put("name", it.place.name).put("lat", it.place.lat).put("lon", it.place.lon) })
    val ready = lots != null
    Row(Modifier.fillMaxSize().background(C.bg).clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(Modifier.width(460.dp).fillMaxHeight().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(horizontal = 28.dp)) {
                Text("${stop.name} 주차", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("고른 곳으로 안내해요. 지도에서 P를 눌러도 돼요.", fontSize = 19.sp, color = C.sub)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                @Composable fun Row1(title: String, line: String, on: Boolean, onClick: () -> Unit) =
                    Column(Modifier.fillMaxWidth().background(if (on) C.panel else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 12.dp)) {
                        Text((if (on) "● " else "○ ") + title, fontSize = 24.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) Amber else C.ink)
                        Text(line, fontSize = 19.sp, color = C.sub)
                    }
                Row1("목적지 그대로", "${stop.name}에 바로 세워요", chosen == null) { chosen = null; show(-1) }
                when {
                    !ready -> Text("주변 주차장을 찾는 중…", fontSize = 20.sp, color = C.sub, modifier = Modifier.padding(28.dp))
                    lots!!.isEmpty() -> Text("800m 안에 찾은 주차장이 없어요", fontSize = 20.sp, color = C.sub, modifier = Modifier.padding(28.dp))
                    else -> lots!!.forEachIndexed { k, l -> Row1(l.place.name, l.line, chosen == l.place) { chosen = l.place; show(k) } }
                }
            }
            Row(Modifier.padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button("이곳으로 정하기", primary = true) { done(chosen, true) }
                Button("닫기", quiet = true) { done(null, false) }
            }
        }
        if (ready) Box(Modifier.weight(1f).fillMaxHeight()) {
            val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
${leafletHead()}
<style>html,body{margin:0;height:100%}${if (dark) ".leaflet-tile-pane{filter:invert(1) hue-rotate(180deg) brightness(.85) contrast(.9)}" else ""}
#m{height:100%;background:${if (dark) "#111" else "#eee"}}
.d{width:48px;height:48px;border-radius:50%;background:#E0A800;color:#000;font:bold 20px sans-serif;display:flex;align-items:center;justify-content:center;border:3px solid $paper;box-shadow:0 2px 6px #0008}
.p{width:40px;height:40px;border-radius:8px;background:#2F80ED;color:#fff;font:bold 22px sans-serif;display:flex;align-items:center;justify-content:center;border:3px solid $paper;box-shadow:0 2px 6px #0008;transition:transform .2s}
.on{transform:scale(1.35);background:#E0A800;color:#000}
.lb{font:bold 16px sans-serif;color:$ink;text-shadow:0 0 4px $paper,0 0 4px $paper,0 0 4px $paper;white-space:nowrap}
.car{width:22px;height:22px;border-radius:50%;background:#2F80ED;border:4px solid #fff;box-shadow:0 0 0 8px #2F80ED44}</style></head>
<body><div id="m"></div><script>
var L0=$pins, D=[${stop.lat},${stop.lon}], H=${here?.let { "[${it.first},${it.second}]" } ?: "null"}, m=L.map('m',{zoomControl:false}), mk=[];
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'© OpenStreetMap',maxZoom:19}).addTo(m);
L.control.scale({imperial:false,position:'bottomleft'}).addTo(m);
function lb(ll,t,d){L.marker(ll,{interactive:false,icon:L.divIcon({className:'',html:'<div class="lb">'+t.replace(/</g,'&lt;')+'</div>',iconSize:[0,0],iconAnchor:[-26,10-(d||0)]})}).addTo(m)}
L.marker(D,{zIndexOffset:500,icon:L.divIcon({className:'',html:'<div class="d">목</div>',iconSize:[48,48],iconAnchor:[24,24]})}).addTo(m).on('click',function(){pick(-1);A.pick(-1)});
lb(D,${org.json.JSONObject.quote(stop.name)});
// lots within 40 m of an earlier one stack 46 px lower so both stay readable (same as the day map)
var dy=L0.map(function(p,i){var k=0;for(var j=0;j<i;j++)if(m.distance([L0[j].lat,L0[j].lon],[p.lat,p.lon])<40)k++;return k*46});
L0.forEach(function(p,i){var k=L.marker([p.lat,p.lon],{icon:L.divIcon({className:'',html:'<div class="p">P</div>',iconSize:[40,40],iconAnchor:[20,20-dy[i]]})}).addTo(m);
 k.on('click',function(){pick(i);A.pick(i)}); mk.push(k); lb([p.lat,p.lon],p.name,dy[i])});
function pick(i){mk.forEach(function(k,j){var e=k.getElement();if(e)e.firstChild.classList.toggle('on',j==i)});if(i>=0)m.panTo(mk[i].getLatLng())}
function fit(){m.invalidateSize();var b=L.latLngBounds([D].concat(L0.map(function(p){return [p.lat,p.lon]})));if(L0.length)m.fitBounds(b,{padding:[70,70],maxZoom:18});else m.setView(D,17)}
if(H)L.marker(H,{interactive:false,zIndexOffset:1000,icon:L.divIcon({className:'',html:'<div class="car"></div>',iconSize:[30,30],iconAnchor:[15,15]})}).addTo(m);
function all(){var b=L.latLngBounds([D].concat(L0.map(function(p){return [p.lat,p.lon]})));if(L0.length)m.flyToBounds(b,{padding:[70,70],maxZoom:18,duration:.6});else m.flyTo(D,17,{duration:.6})}
function car(){if(H)m.flyTo(H,16,{duration:.6})}
fit();addEventListener('resize',fit);${lots!!.indexOfFirst { it.place == chosen }.let { if (it >= 0) "pick($it);" else "" }}
</script></body></html>"""
            androidx.compose.ui.viewinterop.AndroidView({ ctx ->
                android.webkit.WebView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(object { @android.webkit.JavascriptInterface fun pick(i: Int) { post { chosen = if (i < 0) null else lots?.getOrNull(i)?.place } } }, "A")
                    loadDataWithBaseURL("https://powerj.app/", html, "text/html", "utf-8", null)
                    web = this
                }
            }, Modifier.fillMaxSize())
            MapControls(::js, here)
        } else Box(Modifier.weight(1f).fillMaxHeight().background(C.panel))
    }
}

/** One day on a map: numbered pins in visiting order (the stay last, as "숙"), a dashed line between them, and the list beside it.
 *  Drag and pinch work on the map; the big buttons zoom, show all, and center on the car. Tapping a row flies to that pin. */
@Composable
private fun DayMap(title: String, stops: List<Place>, here: Pair<Double, Double>?, close: () -> Unit) {
    BackHandler(onBack = close)
    val dark = C == DarkPal
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var picked by remember { mutableStateOf<Int?>(null) }
    fun js(code: String) { web?.evaluateJavascript(code, null) }
    val pins = org.json.JSONArray(stops.mapIndexed { i, p ->
        org.json.JSONObject().put("n", if (p.stay) "숙" else "${i + 1}").put("name", p.name).put("lat", p.lat).put("lon", p.lon) })
    val ink = if (dark) "#fff" else "#000"; val paper = if (dark) "#000" else "#fff"
    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
${leafletHead()}
<style>html,body{margin:0;height:100%}${if (dark) ".leaflet-tile-pane{filter:invert(1) hue-rotate(180deg) brightness(.85) contrast(.9)}" else ""}
#m{height:100%;background:${if (dark) "#111" else "#eee"}}
.pin{width:44px;height:44px;border-radius:50%;background:#E0A800;color:#000;font:bold 22px sans-serif;display:flex;align-items:center;justify-content:center;border:3px solid $paper;box-shadow:0 2px 6px #0008;transition:transform .2s}
.pin.s{background:$ink;color:$paper}
.pin.on{transform:scale(1.35);border-color:$ink}
.lb{font:bold 17px sans-serif;color:$ink;text-shadow:0 0 4px $paper,0 0 4px $paper,0 0 4px $paper;white-space:nowrap}
.car{width:22px;height:22px;border-radius:50%;background:#2F80ED;border:4px solid #fff;box-shadow:0 0 0 8px #2F80ED44}
.leaflet-control-scale-line{font-size:15px}</style></head>
<body><div id="m"></div><script>
var P=$pins, H=${here?.let { "[${it.first},${it.second}]" } ?: "null"}, m=L.map('m',{zoomControl:false,zoomSnap:0.5});
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'© OpenStreetMap',maxZoom:19}).addTo(m);
L.control.scale({imperial:false,position:'bottomleft'}).addTo(m);
var ll=P.map(function(p){return [p.lat,p.lon]}), mk=[], pad={paddingTopLeft:[90,90],paddingBottomRight:[200,90]};
// pins within 40 m of an earlier one stack 50 px lower so both stay readable
var dy=P.map(function(p,i){var k=0;for(var j=0;j<i;j++)if(m.distance(ll[j],ll[i])<40)k++;return k*50});
L.polyline(ll,{color:'#E0A800',weight:5,dashArray:'10 10'}).addTo(m);
P.forEach(function(p,i){
 var k=L.marker([p.lat,p.lon],{zIndexOffset:100-i,icon:L.divIcon({className:'',html:'<div class="pin'+(p.n=='숙'?' s':'')+'">'+p.n+'</div>',iconSize:[44,44],iconAnchor:[22,22-dy[i]]})}).addTo(m);
 k.on('click',function(){go(i)}); mk.push(k);
 L.marker([p.lat,p.lon],{interactive:false,icon:L.divIcon({className:'',html:'<div class="lb">'+p.name.replace(/</g,'&lt;')+'</div>',iconSize:[0,0],iconAnchor:[-28,10-dy[i]]})}).addTo(m);
});
if(H)L.marker(H,{interactive:false,zIndexOffset:1000,icon:L.divIcon({className:'',html:'<div class="car"></div>',iconSize:[30,30],iconAnchor:[15,15]})}).addTo(m);
function pick(i){mk.forEach(function(k,j){var e=k.getElement();if(e){e.firstChild.classList.toggle('on',j==i);k.setZIndexOffset(j==i?900:100-j)}})}
function go(i){pick(i);m.flyTo(ll[i],Math.max(m.getZoom(),17),{duration:.6});if(window.A)A.picked(i)}
function all(){pick(-1);m.invalidateSize();if(ll.length>1)m.flyToBounds(ll,Object.assign({duration:.6},pad));else m.flyTo(ll[0],16,{duration:.6})}
function car(){if(H)m.flyTo(H,16,{duration:.6})}
function fit(){m.invalidateSize();if(ll.length>1)m.fitBounds(ll,pad);else m.setView(ll[0],16)}
fit();addEventListener('resize',fit);
</script></body></html>"""
    Row(Modifier.fillMaxSize().background(C.bg).clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(Modifier.width(400.dp).fillMaxHeight().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.ink, modifier = Modifier.padding(horizontal = 28.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                stops.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().background(if (picked == i) C.panel else Color.Transparent)
                        .clickable { picked = i; js("go($i)") }.padding(horizontal = 28.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (p.stay) C.ink else Amber), contentAlignment = Alignment.Center) {
                            Text(if (p.stay) "숙" else "${i + 1}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (p.stay) C.onInk else Color.Black)
                        }
                        Text(p.name, fontSize = 23.sp, color = C.ink, maxLines = 2, fontWeight = if (picked == i) FontWeight.Bold else FontWeight.Normal)
                    }
                    stops.getOrNull(i + 1)?.let { n ->
                        Text("↓  약 %.1fkm".format(meters(p, n.lat, n.lon) / 1000), fontSize = 18.sp, color = C.sub, modifier = Modifier.padding(start = 40.dp, top = 2.dp, bottom = 2.dp))
                    }
                }
            }
            if (stops.size > 1) Text("총 약 %.1fkm (직선 거리)".format(stops.zipWithNext { a, b -> meters(a, b.lat, b.lon) }.sum() / 1000), fontSize = 20.sp, color = C.sub, modifier = Modifier.padding(horizontal = 28.dp))
            Box(Modifier.padding(horizontal = 28.dp)) { Button("닫기", primary = true, onClick = close) }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            androidx.compose.ui.viewinterop.AndroidView({ ctx ->
                android.webkit.WebView(ctx).apply {
                    // AndroidView gives wrap_content by default, and WebView then sizes the page to 0 height.
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(object { @android.webkit.JavascriptInterface fun picked(i: Int) { post { picked = i } } }, "A")
                    loadDataWithBaseURL("https://powerj.app/", html, "text/html", "utf-8", null)
                    web = this
                }
            }, Modifier.fillMaxSize())
            MapControls(::js, here) { picked = null }
        }
    }
}

/** The same buttons on every map: zoom, show everything, center on the car. The page must define m, all() and car(). */
@Composable
private fun BoxScope.MapControls(js: (String) -> Unit, here: Pair<Double, Double>?, onMove: () -> Unit = {}) =
    Column(Modifier.align(Alignment.CenterEnd).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MapButton("+") { js("m.zoomIn()") }
        MapButton("−") { js("m.zoomOut()") }
        Spacer(Modifier.height(8.dp))
        MapButton("전체") { onMove(); js("all()") }
        if (here != null) MapButton("내 차") { onMove(); js("car()") }
    }

/** Big square map button, easy to hit while parked. */
@Composable
private fun MapButton(label: String, onClick: () -> Unit) =
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = if (label.length == 1) 44.sp else 23.sp, fontWeight = FontWeight.Bold, color = C.ink)
    }

/** One day's stops as a numbered list. With [was], stops that moved get an amber number. */
@Composable
private fun RouteCard(label: String, stops: List<Place>, was: List<Place>?, strong: Boolean, modifier: Modifier = Modifier) =
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (strong) C.bg else Color.Transparent)
        .border(2.dp, if (strong) Amber else C.line, RoundedCornerShape(14.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (strong) Amber else C.dim, modifier = Modifier.height(28.dp))
        stops.forEachIndexed { i, p ->
            val moved = was != null && was.getOrNull(i) != p
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(if (moved) Amber else if (strong) C.ink else C.line), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (moved) Color.Black else if (strong) C.onInk else C.sub)
                }
                Text(p.name, fontSize = 24.sp, color = if (strong) C.ink else C.sub, fontWeight = if (moved) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }

/** Curves from each stop's row in the old order to its row in the new one. Rows sit at 76 + 50*i dp (see [RouteCard]). */
@Composable
private fun RouteLinks(before: List<Place>, after: List<Place>, modifier: Modifier) {
    val line = C.line
    androidx.compose.foundation.Canvas(modifier) {
        fun y(i: Int) = (76 + 50 * i).dp.toPx()
        val w = size.width; val r = 6.dp.toPx(); val head = 10.dp.toPx()
        before.forEachIndexed { i, p ->
            val j = after.indexOf(p).takeIf { it >= 0 } ?: return@forEachIndexed
            val c = if (i == j) line else Amber
            val (y1, y2) = y(i) to y(j)
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(r, y1); cubicTo(w / 2, y1, w / 2, y2, w - head, y2) }
            drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = (if (i == j) 2 else 4).dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawCircle(c, r, androidx.compose.ui.geometry.Offset(r, y1))
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w, y2); lineTo(w - head, y2 - head * 0.6f); lineTo(w - head, y2 + head * 0.6f); close() }, c)
        }
    }
}

@Composable
private fun TripRow(t: PlanCard, selected: Boolean, deletable: Boolean, draft: Boolean, onClick: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("'${tripTitle(t)}'" + (if (tripTitle(t).j("을", "를").endsWith("을")) "을" else "를") + " 지울까요?", fontSize = 26.sp, color = C.ink) },
        text = { Text(if (t.active) "안내 중인 여행이에요. 지우면 안내를 멈추고 다른 여행을 띄워요." else "지운 여행은 되돌릴 수 없어요.", fontSize = 20.sp, color = C.sub) },
        confirmButton = { Button("지우기", primary = true) { asking = false; Store.onDeleteTrip?.invoke(t.id) } },
        dismissButton = { Button("취소", quiet = true) { asking = false } },
        containerColor = C.panel,
    )
    Column(Modifier.fillMaxWidth().background(if (selected) C.panel else Color.Transparent)
        .then(if (selected) Modifier.border(3.dp, Amber) else Modifier).clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) { Box(Modifier.size(16.dp).clip(CircleShape).background(Amber)); Spacer(Modifier.width(12.dp)) } // which trip is open on the right
            Text(tripTitle(t), fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (draft) { Text("수정 중", fontSize = 18.sp, color = Color.Black, modifier = Modifier.background(Amber).padding(horizontal = 8.dp, vertical = 3.dp)); Spacer(Modifier.width(6.dp)) }
            if (t.active) Text(if (tripWhen(t, today()) < 0) "지난 여행" else "안내 중", fontSize = 18.sp, color = C.onInk, modifier = Modifier.background(C.ink).padding(horizontal = 8.dp, vertical = 3.dp))
            if (deletable && (t.stops.isNotEmpty() || t.title.isNotBlank() || !t.active))
                Text("삭제", fontSize = 20.sp, color = C.sub, modifier = Modifier.clickable { asking = true }.padding(start = 14.dp, top = 4.dp, bottom = 4.dp))
        }
        Text("${tripDates(t)} · ${t.dayCount}일 · ${t.stops.size}곳", color = C.sub, fontSize = 21.sp)
    }
}

/** Trip editor: name, start date, one card per day with its places, "+ 장소 추가" inside each day, "+ 일차 추가" at the end. */
@Composable
private fun TripEditor(t: PlanCard, ui: Store.Ui, editable: Boolean) {
    val today = today().toString()
    val guide = if (editable && t.active) ui.guide else 0
    Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (guide > 0) Row(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("이름", "날짜 정하기", "장소 추가").forEachIndexed { i, step ->
                val n = i + 1
                if (i > 0) Text("  →  ", fontSize = 20.sp, color = C.dim)
                Text((if (n < guide) "✓ " else "$n ") + step, fontSize = 21.sp,
                    fontWeight = if (n == guide) FontWeight.Bold else FontWeight.Normal, color = when { n == guide -> Amber; n < guide -> C.sub; else -> C.dim })
            }
            Spacer(Modifier.weight(1f))
            Text(if (guide == 2) "가는 날부터 골라요" else "1일차에 갈 곳을 담아요", fontSize = 19.sp, color = C.sub)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tripTitle(t), fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (editable) Button(if (t.title.isBlank()) "이름 정하기" else "이름 바꾸기", quiet = t.title.isNotBlank()) { Store.set { copy(naming = true, addDay = null) } }
            val readOnly = ui.guest && t.active
            if (!editable && !readOnly && ui.phase == Phase.DRIVING) Text("주행 중에는 볼 수만 있어요", fontSize = 21.sp, color = C.sub)
            else if (!editable && !readOnly) Button("수정하기") { Store.onEdit?.invoke(t.id) }
        }
        if (ui.guest && t.active) Text("함께 가기 ${ui.share} · 읽기 전용 · 계획은 코드를 만든 차만 바꿀 수 있어요", fontSize = 21.sp, color = C.sub,
            modifier = Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 20.dp, vertical = 14.dp))
        var ordering by remember(t.id) { mutableStateOf<Int?>(null) }
        ordering?.let { d ->
            val after = orderOneDay(t.stops, t.done, d)
            if (after == t.stops) Confirm("이미 가까운 순서예요", t.stops.filter { it.day == d }.joinToString(" → ") { it.name }, "확인", { ordering = null }, { ordering = null }, ask = false)
            else Confirm("${d}일차 동선을 바꿀까요?", "", "바꾸기", { ordering = null; Store.onOrderDay?.invoke(d) }, { ordering = null }) {
                val before = t.stops.filter { it.day == d }; val now = after.filter { it.day == d }
                val (a, b) = dayKm(t.stops, d) to dayKm(after, d)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        RouteCard("변경 전", before, null, false, Modifier.weight(1f))
                        RouteLinks(before, now, Modifier.width(120.dp).height((64 + 50 * now.size).dp))
                        RouteCard("변경 후", now, before, true, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.bg).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("이동 거리(직선)", fontSize = 19.sp, color = C.sub, modifier = Modifier.weight(1f))
                        Text("%.1fkm".format(a), fontSize = 20.sp, color = C.dim)
                        Text("  →  ", fontSize = 20.sp, color = C.dim)
                        Text("%.1fkm".format(b), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.ink)
                        if (a - b >= 0.1) Text("  %.1fkm 줄어요".format(a - b), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Amber)
                    }
                }
            }
        }
        var dropping by remember(t.id) { mutableStateOf<Int?>(null) }
        dropping?.let { d ->
            val names = t.stops.filter { it.day == d }.map { it.name }
            Confirm("${d}일차를 뺄까요?", "${d}일차에 담은 ${names.size}곳(${names.take(3).joinToString(", ")}${if (names.size > 3) " 등" else ""})도 함께 지워져요." +
                (if (d < t.dayCount) " 뒤의 날들은 하루씩 앞당겨져요." else ""), "빼기",
                { dropping = null; Store.onRemoveDay?.invoke(d) }, { dropping = null })
        }
        var calendar by remember(t.id) { mutableStateOf(false) }
        if (calendar) TripDates(t, takenDays(ui.plans, t.id)) { calendar = false }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tripDates(t) + " · ${t.dayCount}일 · ${t.stops.size}곳" + if (t.stops.isNotEmpty()) " · ${t.done}곳 다녀옴" else "", color = C.sub, fontSize = 23.sp, modifier = Modifier.weight(1f))
            if (editable) Button("날짜 정하기", next = guide == 2) { calendar = true }
        }
        if (editable || t.stops.isNotEmpty()) Row(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = C.sub)) { append("출발 위치  ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.origin?.name ?: "지금 차 위치") }
                }, fontSize = 23.sp)
                Text(when {
                    t.origin != null -> "돌아오는 길도 이곳으로 계산해요. 여행 날에는 실제 차 위치에서 다시 계산해요."
                    editable -> "선택 사항이에요. 정하지 않으면 지금 차 위치에서 출발하는 걸로 계산해요."
                    else -> "지금 차 위치 기준이라 실제 출발 위치에 따라 달라질 수 있어요."
                }, fontSize = 19.sp, color = C.sub)
            }
            if (editable && t.origin != null) Button("차 위치로", quiet = true) { Store.onSetOrigin?.invoke(null) }
            if (editable) Button(if (t.origin == null) "정하기" else "바꾸기", quiet = t.origin != null) { Store.set { copy(addDay = 0, hits = emptyList()) } }
        }
        if (t.stops.isNotEmpty()) Box(Modifier.fillMaxWidth().height(10.dp).background(C.button)) {
            Box(Modifier.fillMaxWidth(t.done.toFloat() / t.stops.size).fillMaxHeight().background(C.ink))
        }
        val byDay = t.stops.withIndex().groupBy { it.value.day }
        // drag and drop: positions of each stop row and each day's "end" spot, in root pixels
        val rows = remember(t.id) { mutableStateMapOf<Int, Pair<Float, Float>>() }
        val ends = remember(t.id) { mutableStateMapOf<Int, Float>() }
        var boxTop by remember { mutableFloatStateOf(0f) }
        var drag by remember { mutableStateOf<Int?>(null) }
        var dy by remember { mutableFloatStateOf(0f) }
        fun target(): Triple<Int, Int?, Float>? {
            val i = drag ?: return null
            val r = rows[i] ?: return null
            val centre = r.first + dy + (r.second - r.first) / 2
            val slots = (1..t.dayCount).flatMap { d ->
                byDay[d].orEmpty().filter { !it.value.stay && it.index >= t.done }.mapNotNull { (j, _) -> rows[j]?.let { Triple(d, j as Int?, it.first) } } +
                    listOfNotNull(ends[d]?.let { Triple(d, null as Int?, it) })
            }
            return slots.minByOrNull { kotlin.math.abs(it.third - centre) }
        }
        Box(Modifier.fillMaxWidth().onGloballyPositioned { boxTop = it.positionInRoot().y }) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                (1..t.dayCount).forEach { day ->
                    val wx = if (t.active) ui.dayWx[day] else null
                    Column(Modifier.fillMaxWidth().background(C.panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val stops = byDay[day].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dayLabel(t.start, day, today) + (wx?.let { " · $it" } ?: ""), fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                color = if (wx?.let { it.contains("비 ") && !it.startsWith("맑음") } == true) Amber else C.ink)
                            if (editable && t.dayCount > 1 && stops.none { it.index < t.done })
                                Text("이 날 빼기", fontSize = 21.sp, color = C.sub, modifier = Modifier.clickable {
                                    if (stops.isEmpty()) Store.onRemoveDay?.invoke(day) else dropping = day
                                }.padding(start = 14.dp, top = 4.dp, bottom = 4.dp))
                        }
                        @Composable fun CostLine(label: String, e: Est) =
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = C.sub)) { append("$label  ") }
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = C.ink)) { append("${durationText(e.min)} · %,dkm".format(e.km.toInt())) }
                                withStyle(SpanStyle(color = C.sub)) { append("     차량비  ") }
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = C.ink)) { append("약 %,d원".format(e.total)) }
                                if (e.toll > 0) withStyle(SpanStyle(color = C.sub)) { append(" (통행료 %,d원)".format(e.toll)) }
                            }, fontSize = 23.sp)
                        if (t.active) ui.dayCost[day]?.let { CostLine("이동", it) }
                        if (t.active && day == t.dayCount) ui.dayCost[0]?.let { CostLine("돌아오는 길", it) } // back to where the car started
                        if (stops.isEmpty()) Text("아직 담은 곳이 없어요", color = C.sub, fontSize = 22.sp)
                        val (visits, stays) = stops.partition { !it.value.stay }
                        @Composable fun StopRow(i: Int, p: Place, label: String) {
                            Row(Modifier.fillMaxWidth().onGloballyPositioned { c -> val y = c.positionInRoot().y; rows[i] = y to y + c.size.height }
                                .graphicsLayer { alpha = if (drag == i) 0.25f else 1f },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (editable && i >= t.done && !p.stay) Text("⠿", fontSize = 30.sp, color = C.sub,
                                    modifier = Modifier.pointerInput(i, t.stops) {
                                        detectDragGestures(
                                            onDragStart = { drag = i; dy = 0f },
                                            onDragEnd = { target()?.let { (d, before, _) -> Store.onDropStop?.invoke(i, d, before) }; drag = null; dy = 0f },
                                            onDragCancel = { drag = null; dy = 0f },
                                        ) { change, amount -> change.consume(); dy += amount.y }
                                    }.padding(horizontal = 8.dp))
                                else if (editable) Spacer(Modifier.width(40.dp))
                                Text(label, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = C.onInk,
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(if (i < t.done) C.dim else C.ink).padding(top = 5.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontSize = 26.sp, fontWeight = if (i == t.done) FontWeight.Bold else FontWeight.Normal, color = if (i < t.done) C.sub else C.ink)
                                    val tag = listOfNotNull(if (p.stay) "숙소" else null, when { i < t.done -> "다녀옴"; i == t.done && t.active && tripWhen(t, today()) >= 0 -> "다음 목적지"; else -> null })
                                    if (tag.isNotEmpty()) Text(tag.joinToString(" · "), color = if ("다음 목적지" in tag) Amber else C.sub, fontSize = 19.sp, fontWeight = if ("다음 목적지" in tag) FontWeight.Bold else FontWeight.Normal)
                                    p.park?.let { Text("주차  ${it.name} · 도보 ${walkMin(p, it)}분", color = C.sub, fontSize = 19.sp) }
                                }
                                if (editable && !p.stay && i >= t.done) Text(if (p.park == null) "주차" else "주차 변경", fontSize = 20.sp, color = C.ink,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.button).clickable { parkPick = Triple(t.id, i, p) }.padding(horizontal = 14.dp, vertical = 8.dp))
                                if (editable) Text("×", fontSize = 30.sp, color = C.sub, modifier = Modifier.clickable { Store.onRemoveStop?.invoke(i) }.padding(horizontal = 12.dp))
                            }
                        }
                        visits.forEachIndexed { k, (i, p) -> StopRow(i, p, "${k + 1}") }
                        Box(Modifier.fillMaxWidth().height(1.dp).onGloballyPositioned { ends[day] = it.positionInRoot().y }) // "end of this day" drop spot, above the stay
                        stays.forEach { (i, p) -> StopRow(i, p, "숙") }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (editable) Button("＋ 장소 추가", next = (guide == 3 && day == 1) || (editable && ui.newDay == day) || (t.stops.isEmpty() && day == 1 && guide != 2) /* empty trip (after the date step): point at the first step even after a restart */) { Store.set { copy(addDay = day, hits = emptyList()) } }
                            if (editable && visits.count { it.index >= t.done } >= 2) Button("동선 정리") { ordering = day }
                            if (visits.size + stays.size > 0) Button("지도로 보기") {
                                dayMap = dayLabel(t.start.ifEmpty { today().toString() }, day, "") to (visits + stays).map { it.value }
                            }
                        }
                    }
                }
            }
            // while dragging: a line where it will land, and the place following the finger
            drag?.let { i ->
                rows[i]?.let { r ->
                    Text("⠿  " + t.stops[i].name, fontSize = 26.sp, color = C.onInk, fontWeight = FontWeight.Medium,
                        modifier = Modifier.offset { IntOffset(48, (r.first - boxTop + dy).roundToInt()) }.fillMaxWidth(0.8f).background(C.ink.copy(alpha = 0.85f)).padding(18.dp))
                    // landing line on top so the floating card never hides it
                    target()?.let { tg -> Box(Modifier.offset { IntOffset(0, (tg.third - boxTop).roundToInt() - 3) }.fillMaxWidth().height(6.dp).background(Amber)) }
                }
            }
        }
        if (editable) Button("＋ 일차 추가") { Store.onAddDay?.invoke() }
        if (editable) {
            val undos = ui.undos[t.id] ?: 0
            if (undos > 0 || t.id in ui.bases) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (undos > 0) Button("↶ 되돌리기 ($undos)") { Store.onUndo?.invoke() }
                if (t.id in ui.bases) Button("수정 취소", quiet = true) { Store.onCancelEdit?.invoke() }
            }
            val ready = t.stops.isNotEmpty() && t.start.isNotBlank() && guide != 2 // guide 2 = dates not picked yet
            if (ready) Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).background(Amber).clickable { Store.onSave?.invoke() }, contentAlignment = Alignment.Center) {
                Text("저장", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
            else Text(if (guide == 2) "날짜를 정하고 장소를 하나 이상 담으면 저장할 수 있어요" else "장소를 하나 이상 담으면 저장할 수 있어요", fontSize = 21.sp, color = C.sub,
                modifier = Modifier.fillMaxWidth().background(C.panel).padding(20.dp))
        }
    }
    Divider()
}

/** "11/14(토) ~ 11/16(월)", or one date for a day trip. */
private fun tripDates(t: PlanCard): String {
    val start = t.start.ifEmpty { today().toString() }
    val first = dayLabel(start, 1, "").substringAfter(" · ")
    return if (t.dayCount == 1) first else "$first ~ " + dayLabel(start, t.dayCount, "").substringAfter(" · ")
}

/** Calendar: tap the first day, then the last day (either order). Sundays and holidays red, Saturdays blue. */
@Composable
private fun TripDates(t: PlanCard, taken: Map<java.time.LocalDate, String>, close: () -> Unit) {
    val today = today()
    val start0 = runCatching { java.time.LocalDate.parse(t.start) }.getOrElse { today }
    var from by remember { mutableStateOf<java.time.LocalDate?>(start0) }
    var to by remember { mutableStateOf<java.time.LocalDate?>(start0.plusDays(t.dayCount - 1L)) }
    var warn by remember { mutableStateOf<String?>(null) }
    fun tap(d: java.time.LocalDate) {
        val f = from
        warn = null
        if (f == null || to != null) { from = d; to = null; return }
        val (a, b) = if (d < f) d to f else f to d
        val clash = generateSequence(a) { it.plusDays(1) }.takeWhile { it <= b }.firstNotNullOfOrNull { day -> taken[day]?.let { day to it } }
        if (clash != null) { warn = "${dayLabel(clash.first.toString(), 1, "").substringAfter(" · ")}에 '${clash.second}' 여행이 있어요. 겹치지 않게 골라 주세요."; return }
        from = a; to = b
    }
    val now0 = start0..start0.plusDays(t.dayCount - 1L) // the trip's dates before this edit
    var asking by remember { mutableStateOf<Pair<String, Int>?>(null) }
    fun apply(start: String, days: Int) {
        val lost = lostByDays(t.stops, t.done, days)
        if (lost.isEmpty()) { Store.onSetDates?.invoke(start, days); close() } else asking = start to days
    }
    asking?.let { (start, days) ->
        val lost = lostByDays(t.stops, t.done, days)
        Confirm("${days}일로 줄일까요?", "${days + 1}일차부터 담은 ${lost.size}곳(${lost.take(3).joinToString(", ") { it.name }}${if (lost.size > 3) " 등" else ""})이 함께 지워져요.", "줄이기",
            { asking = null; Store.onSetDates?.invoke(start, days); close() }, { asking = null })
    }
    fun label(d: java.time.LocalDate?) = d?.let { dayLabel(it.toString(), 1, "").substringAfter(" · ") + (holidays[it]?.let { h -> " $h" } ?: "") } ?: "오는 날"
    val firstMonth = java.time.YearMonth.from(today)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxHeight(0.92f).width(620.dp).background(C.panel).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("가는 날과 오는 날을 누르세요", fontSize = 22.sp, color = C.sub)
            Text(label(from) + " ~ " + label(to) + (to?.let { e -> from?.let { " · ${e.toEpochDay() - it.toEpochDay() + 1}일" } } ?: ""), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            warn?.let { Text(it, fontSize = 20.sp, color = Amber) } ?: Text("회색 줄: 지금 일정" + if (taken.isNotEmpty()) " · 주황 줄: 다른 여행 (고를 수 없어요)" else "", fontSize = 18.sp, color = C.sub)
            Row { listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, w ->
                Text(w, Modifier.weight(1f), fontSize = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = when (i) { 0 -> Sun; 6 -> Sat; else -> C.sub })
            } }
            LazyColumn(Modifier.weight(1f), state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, java.time.YearMonth.from(start0).let { (it.year - firstMonth.year) * 12 + it.monthValue - firstMonth.monthValue }))) {
                items(13) { m ->
                    val ym = firstMonth.plusMonths(m.toLong())
                    Text("${ym.year}년 ${ym.monthValue}월", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    val lead = ym.atDay(1).dayOfWeek.value % 7 // Sunday first
                    (0 until (lead + ym.lengthOfMonth() + 6) / 7).forEach { w ->
                        Row {
                            (0..6).forEach { c ->
                                val n = w * 7 + c - lead + 1
                                if (n < 1 || n > ym.lengthOfMonth()) Spacer(Modifier.weight(1f).height(84.dp))
                                else {
                                    val d = ym.atDay(n)
                                    val past = d < today
                                    val end = d == from || d == to
                                    val inRange = from != null && to != null && d > from!! && d < to!!
                                    val hol = holidays[d]
                                    val other = taken[d]
                                    Column(Modifier.weight(1f).height(84.dp).background(if (inRange || (end && to != null && from != to)) C.button else Color.Transparent)
                                        .clickable(enabled = !past && other == null) { tap(d) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("$n", fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            fontWeight = if (end || d == today) FontWeight.Bold else FontWeight.Normal,
                                            color = when { end -> C.onInk; past || other != null -> C.dim; hol != null || c == 0 -> Sun; c == 6 -> Sat; else -> C.ink },
                                            modifier = Modifier.size(42.dp).clip(CircleShape).background(if (end) C.ink else Color.Transparent).padding(top = 6.dp))
                                        if (other != null) {
                                            Box(Modifier.fillMaxWidth().height(4.dp).background(Amber.copy(alpha = if (past) 0.4f else 1f)))
                                            Text(other, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Amber)
                                        } else {
                                            val mine = d in now0
                                            if (mine) Box(Modifier.fillMaxWidth().height(4.dp).background(C.sub))
                                            Text(hol ?: if (mine) "지금" else if (d == today) "오늘" else "", fontSize = 15.sp, maxLines = 1, color = if (past) C.dim else if (hol != null) Sun else C.sub)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button("취소", quiet = true, onClick = close)
                Button("정하기", primary = true) {
                    from?.let { f -> apply(f.toString(), ((to ?: f).toEpochDay() - f.toEpochDay()).toInt() + 1) } ?: close()
                }
            }
        }
    }
}

private val Sun = Color(0xFFE5484D)
private val Sat = Color(0xFF4C8DF6)

/** Public holidays (KASI calendar data). The calendar starts today, so 2026 needs only December. ponytail: add each year's list, or the 특일정보 API once the key is approved for it. */
private val holidays: Map<java.time.LocalDate, String> = listOf(
    "2026-12-25" to "성탄절",
    "2027-01-01" to "신정", "2027-02-06" to "설날", "2027-02-07" to "설날", "2027-02-08" to "설날", "2027-02-09" to "대체휴일",
    "2027-03-01" to "삼일절", "2027-05-01" to "노동절", "2027-05-03" to "대체휴일", "2027-05-05" to "어린이날", "2027-05-13" to "부처님오신날",
    "2027-06-06" to "현충일", "2027-07-17" to "제헌절", "2027-07-19" to "대체휴일", "2027-08-15" to "광복절", "2027-08-16" to "대체휴일",
    "2027-09-14" to "추석", "2027-09-15" to "추석", "2027-09-16" to "추석", "2027-10-03" to "개천절", "2027-10-04" to "대체휴일",
    "2027-10-09" to "한글날", "2027-10-11" to "대체휴일", "2027-12-25" to "성탄절", "2027-12-27" to "대체휴일",
).associate { (d, n) -> java.time.LocalDate.parse(d) to n }

/**
 * Every text input sits in this sheet: the field right above the keyboard, its title on the left, and live
 * suggestions or results stacked above it (the first one nearest the field). Tap outside, 닫기 or back to close.
 */
@Composable
private fun KeyboardSheet(
    title: String, note: String?, start: String, hint: String, action: ImeAction, actionLabel: String?,
    onChange: (String) -> Unit, onAction: (String) -> Unit, close: () -> Unit, above: androidx.compose.foundation.lazy.LazyListScope.(String) -> Unit,
) {
    var v by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) } // cursor after any prefilled text
    val focus = remember { FocusRequester() }
    // physical keyboard (the emulator's): the car IME passes letters through as-is, so compose Hangul here
    var korean by remember { mutableStateOf(true) }
    val jamo = remember { mutableStateListOf<Char>() }
    var base by remember { mutableStateOf("") }
    fun set(t: String) { v = TextFieldValue(t, TextRange(t.length)); onChange(t) }
    val hardKeys = Modifier.onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        if (k.action != android.view.KeyEvent.ACTION_DOWN || k.isCtrlPressed || k.isMetaPressed || k.isAltPressed) return@onPreviewKeyEvent false
        val code = k.keyCode
        val toggle = code == android.view.KeyEvent.KEYCODE_LANGUAGE_SWITCH || code == android.view.KeyEvent.KEYCODE_CAPS_LOCK ||
            (code == android.view.KeyEvent.KEYCODE_SPACE && k.isShiftPressed)
        val j = if (korean && code in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) jamoOf('a' + (code - android.view.KeyEvent.KEYCODE_A), k.isShiftPressed) else null
        when {
            toggle -> { jamo.clear(); korean = !korean; true }
            j != null -> { if (jamo.isEmpty()) base = v.text; jamo += j; set(base + hangul(jamo)); true }
            code == android.view.KeyEvent.KEYCODE_DEL && jamo.isNotEmpty() -> { jamo.removeAt(jamo.lastIndex); set(base + hangul(jamo)); true }
            else -> { jamo.clear(); false } // space, enter, digits: the syllable is done
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.96f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = close)
        .padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        note?.let { Text(it, fontSize = 20.sp, color = C.sub) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) { above(v.text) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(v, { if (it.text != v.text) jamo.clear(); v = it; onChange(it.text) }, singleLine = true, placeholder = { Text(hint, color = C.dim, fontSize = 22.sp) },
                textStyle = TextStyle(fontSize = 26.sp, color = C.ink, fontFamily = Pleos),
                keyboardOptions = KeyboardOptions(imeAction = action), keyboardActions = KeyboardActions(onAny = { onAction(v.text) }),
                modifier = Modifier.weight(1f).focusRequester(focus).then(hardKeys).background(C.panel))
            Text(if (korean) "한" else "A", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.sub,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.button).clickable { jamo.clear(); korean = !korean }.padding(horizontal = 14.dp, vertical = 10.dp))
            actionLabel?.let { Button(it, primary = true) { onAction(v.text) } }
            Button("닫기", quiet = true, onClick = close)
        }
    }
}

/** Place search: results update as you type, best match right above the field. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlaceSearch(day: Int, hits: List<Hit>, nearby: List<Hit>) {
    var q by remember(day) { mutableStateOf("") }
    val origin = day <= 0 // 0: the trip's 출발 위치, -1: a routine's destination; not a stop
    val routine = day < 0
    fun pickPlace(p: Place) = if (routine) Store.set { copy(routineDraft = routineDraft?.copy(dest = p.copy(day = 0, stay = false)), addDay = null, hits = emptyList()) }
        else Store.onSetOrigin?.invoke(p)
    LaunchedEffect(day) { if (!origin) Store.onNearby?.invoke(day) }
    LaunchedEffect(q) {
        if (q.isBlank()) Store.set { copy(hits = emptyList()) }
        else { delay(250); Store.onSearch?.invoke(q) } // typing again within 250 ms cancels this one
    }
    KeyboardSheet(if (routine) "루틴 목적지를 찾아요" else if (origin) "출발 위치를 찾아요" else "${day}일차에 갈 곳을 찾아요",
        if (routine) "회사, 학교, 학원처럼 자주 가는 곳을 골라요." else if (origin) "집이나 회사처럼 여행을 떠날 곳을 골라요. 돌아오는 길도 이곳으로 계산해요." else "글자를 칠 때마다 근처 장소가 아래에 떠요. 숙박 시설은 숙소로 담겨요.", "",
        if (routine) "자주 가는 곳 (회사, 학교, 학원)" else if (origin) "출발할 곳 (아파트, 회사, 주소)" else "갈 곳 (장소, 식당, 숙소)", ImeAction.Search, null, { q = it }, { Store.onSearch?.invoke(it) },
        { Store.set { copy(addDay = null, hits = emptyList()) } }) { typed ->
        if (origin && typed.isBlank()) item { Text(if (routine) "예: 판교초등학교, 회사 이름, 수영장" else "예: 판교역, 래미안 아파트, 회사 이름", fontSize = 21.sp, color = C.sub) }
        if (!origin && typed.isBlank() && nearby.isEmpty()) item { // first place of a trip: nothing to suggest "nearby" yet
            Text("가고 싶은 곳의 이름을 입력해 보세요. 예: 경주 불국사, 강릉 안목해변", fontSize = 21.sp, color = C.sub)
        }
        if (typed.isNotBlank() && hits.isEmpty()) item { Text("'$typed' 검색 중이거나 찾은 곳이 없어요. 지역 이름을 같이 넣어 보세요.", fontSize = 21.sp, color = C.sub) }
        if (!origin && typed.isBlank() && nearby.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("이 근처에서 많이 가는 곳 · 누르면 바로 담겨요", fontSize = 19.sp, color = C.sub)
                nearby.groupBy { when { it.category.startsWith("차 ") -> "다른 차들이 많이 간 곳"; it.category == "음식점" -> "맛집"; it.category == "카페" -> "카페"; else -> "명소" } }
                    .forEach { (group, list) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(group, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (group.startsWith("다른")) Amber else C.ink,
                                modifier = Modifier.width(110.dp).padding(top = 12.dp))
                            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                list.forEach { h ->
                                    Text("＋ " + h.place.name, fontSize = 21.sp, color = C.ink, maxLines = 1,
                                        modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(C.panel)
                                            .border(1.dp, if (group.startsWith("다른")) Amber else C.line, RoundedCornerShape(24.dp))
                                            .clickable { Store.onAddPlace?.invoke(h, h.lodging) }.padding(horizontal = 18.dp, vertical = 10.dp))
                                }
                            }
                        }
                    }
            }
        }
        items(hits) { h ->
            Row(Modifier.fillMaxWidth().background(C.panel).clickable { if (origin) pickPlace(h.place) else Store.onAddPlace?.invoke(h, h.lodging) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(highlight(h.place.name, typed), fontSize = 23.sp)
                    Text(listOf(h.category, h.address).filter { it.isNotBlank() }.joinToString(" · "), color = C.sub, fontSize = 17.sp)
                }
                if (origin) { Button(if (routine) "목적지로" else "출발 위치로", primary = true) { pickPlace(h.place) }; return@Row }
                Button("담기", primary = !h.lodging) { Store.onAddPlace?.invoke(h, false) }
                Spacer(Modifier.width(8.dp))
                Button("숙소로", primary = h.lodging) { Store.onAddPlace?.invoke(h, true) }
            }
        }
    }
}

/** Trip name: current name prefilled, a few ready-made names above to tap. */
@Composable
private fun TripName(t: PlanCard) {
    val close = { Store.set { copy(naming = false) } }
    val save = { name: String -> Store.onRename?.invoke(name); close() }
    val month = runCatching { java.time.LocalDate.parse(t.start).monthValue }.getOrElse { today().monthValue }
    val ideas = listOf(if (t.dayCount > 1) "${t.dayCount - 1}박 ${t.dayCount}일 여행" else "당일치기", "${month}월 가족 여행", "주말 나들이", "친구들과 여행")
    val fresh = t.title.isBlank() && t.stops.isEmpty()
    KeyboardSheet(if (fresh) "새 여행의 이름을 정해 주세요" else "여행 이름 바꾸기",
        if (fresh) "아래 추천을 누르거나 직접 입력하세요. 나중에 바꿀 수 있고, 닫으면 이름 없이 시작해요." else null, t.title, "예: 경주 가족 여행", ImeAction.Done, "저장", {}, save, close) { typed ->
        items(ideas.filter { it != typed }) { idea ->
            Text(idea, fontSize = 23.sp, modifier = Modifier.fillMaxWidth().background(C.panel).clickable { save(idea) }.padding(18.dp))
        }
        item { Text("추천 이름 (누르면 바로 저장)", fontSize = 18.sp, color = C.sub) }
    }
}

/** The typed part of a result name in bold. */
private fun highlight(name: String, q: String) = buildAnnotatedString {
    val at = if (q.isBlank()) -1 else name.indexOf(q.trim(), ignoreCase = true)
    if (at < 0) { append(name); return@buildAnnotatedString }
    append(name.take(at))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name.substring(at, at + q.trim().length)) }
    append(name.drop(at + q.trim().length))
}

/** Less frequent tools for the active trip, kept below the editor. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TripExtras(ui: Store.Ui, t: PlanCard) {
    val draft = ui.editing || t.id in ui.drafts
    Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("함께 가기", color = C.sub, fontSize = 20.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when { // sharing always sends the active trip, so a saved list trip is made active first
                ui.share != null && !t.active -> Text("지금은 안내 중인 여행으로 함께 가기 ${ui.share} 중이에요", fontSize = 21.sp, color = C.sub, modifier = Modifier.padding(vertical = 20.dp))
                ui.guest -> Button("함께 가기 나가기") { Store.onShare?.invoke() }
                ui.share == null && tripWhen(t, today()) < 0 -> Text("지난 여행은 함께 가기를 시작할 수 없어요", fontSize = 21.sp, color = C.sub, modifier = Modifier.padding(vertical = 20.dp))
                draft -> Text(if (ui.share != null) "함께 가기 ${ui.share} 중이에요. 저장하면 바뀐 계획을 동행 차에 보내요" else "저장하면 함께 가기 코드를 만들 수 있어요", fontSize = 21.sp, color = C.sub, modifier = Modifier.padding(vertical = 20.dp))
                t.stops.isEmpty() -> Text("장소를 담고 저장하면 함께 가기 코드를 만들 수 있어요", fontSize = 21.sp, color = C.sub, modifier = Modifier.padding(vertical = 20.dp))
                ui.share != null -> { Button("함께 가기 ${ui.share}") { Store.onShare?.invoke() }; Button("함께 가기 끝내기", quiet = true) { Store.onEndShare?.invoke() } }
                else -> Button("함께 가기 시작") { if (!t.active) Store.onActivate?.invoke(t.id); Store.onShare?.invoke() }
            }
        }
    }
}

/** "코드로 참여" button + 6-digit keypad; on the 오늘 tab and in the trip's "더 하기". */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun JoinCode(lock: Long) {
    var keypad by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lock) { while (System.currentTimeMillis() < lock) { now = System.currentTimeMillis(); delay(1000) }; now = System.currentTimeMillis() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(if (keypad) "코드 입력 닫기" else "코드로 참여", quiet = keypad) { keypad = !keypad; code = "" }
        if (keypad && lock > now) Text("코드를 5번 틀렸어요 · ${(lock - now) / 1000 + 1}초 뒤에 다시 입력할 수 있어요", fontSize = 21.sp, color = Amber)
        if (keypad) {
            Text(if (code.isEmpty()) "동행 차의 6자리 코드" else code, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                "1234567890".forEach { d -> Button("$d") { if (code.length < 6) code += d } }
                Button("지움", quiet = true) { code = code.dropLast(1) }
                Button("참여", primary = true) { if (code.length == 6) Store.onJoin?.invoke(code) } // stays open on a wrong code; the whole block hides once joined
            }
        }
    }
}

private fun planTiles(ui: Store.Ui): List<Tile> = listOfNotNull(
    ui.tips.takeIf { it.isNotEmpty() }?.let { Tile(Icons.Outlined.Info, "날씨로 다듬기", it.joinToString("\n"), Amber) },
    ui.share?.let { Tile(Icons.Outlined.Person, "함께 가는 차 · 코드 $it" + if (ui.guest) " · 읽기 전용" else "",
        listOfNotNull(ui.companion ?: "동행 차가 참여하면 위치가 보여요", "계획은 코드를 만든 차만 바꿀 수 있어요".takeIf { ui.guest }).joinToString("\n"), bars = ui.cars) },
    ui.popular.takeIf { it.isNotEmpty() }?.let { Tile(Icons.Outlined.Star, "차들이 많이 간 곳", it.joinToString("\n")) },
    Tile(Icons.Outlined.Place, "총 이동", ui.estimate?.let { ((ui.from ?: "첫 장소부터") + " · ") + "${ui.plans.firstOrNull { it.active }?.dayCount ?: 1}일" } ?: "장소를 담으면 이동 시간을 계산해요",
        big = ui.estimate?.let { "${durationText(it.min)} · ${"%,d".format(it.km.toInt())}km" }),
    ui.estimate.let { e ->
        Tile(Icons.Outlined.Info, "총 여행 차량비", listOfNotNull(
            e?.let { "통행료 %,d원 · %s %,d원".format(it.toll, if (it.ev) "충전" else "기름값", it.energy) },
            ui.spent.takeIf { it > 0 }?.let { "지금까지 쓴 돈 %,d원".format(it) },
        ).joinToString("\n").ifEmpty { "장소를 담으면 예상 비용을 계산해요" }, big = e?.let { "약 %,d원".format(it.total) })
    },
)

// ---------- parts ----------

@Composable
private fun Grid(modifier: Modifier, tiles: List<Tile>, scroll: Boolean = true) {
    Column(if (scroll) modifier.verticalScroll(rememberScrollState()) else modifier) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { i, t ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
                    TileView(t, Modifier.weight(1f))
                }
                if (row.size == 1) { Box(Modifier.width(1.dp).fillMaxHeight().background(C.line)); Spacer(Modifier.weight(1f)) }
            }
            Divider()
        }
    }
}

@Composable
private fun TileView(t: Tile, modifier: Modifier) {
    Column(modifier.heightIn(min = 190.dp).then(t.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(t.icon, null, tint = t.tint ?: C.ink, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(t.title, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        t.big?.let { Text(it, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = C.ink) }
        Text(t.value, fontSize = 22.sp, color = t.tint ?: C.sub, lineHeight = 32.sp)
        t.bars.forEach { b ->
            Row(Modifier.fillMaxWidth()) {
                Text(b.label, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${(b.progress * 100).toInt()}%", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.fillMaxWidth().height(12.dp).background(C.button)) {
                Box(Modifier.fillMaxWidth(b.progress.coerceIn(0f, 1f)).fillMaxHeight().background(C.ink))
            }
            Text(b.note, fontSize = 18.sp, color = C.sub)
        }
    }
}

@Composable
private fun Button(label: String, primary: Boolean = false, quiet: Boolean = false, next: Boolean = false, onClick: () -> Unit) {
    val bg = when { next -> Amber; primary -> C.ink; quiet -> Color.Transparent; else -> C.button } // next: the step the guide points at
    Box(Modifier.heightIn(min = 72.dp).background(bg).clickable(onClick = onClick).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 24.sp, color = if (next) Color.Black else if (primary) C.onInk else if (quiet) C.sub else C.ink, fontWeight = if (next) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun Voice(text: String) {
    if (text.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(C.panel).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Ok))
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 22.sp, lineHeight = 32.sp)
    }
}

/** Leaflet 1.9.4 from assets, inlined so maps load on networks that block CDNs. Only the map tiles still need the internet. */
@Composable
private fun leafletHead(): String {
    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    return remember { "<style>" + assets.open("leaflet.css").reader().readText() + "</style><script>" + assets.open("leaflet.js").reader().readText() + "</script>" }
}
