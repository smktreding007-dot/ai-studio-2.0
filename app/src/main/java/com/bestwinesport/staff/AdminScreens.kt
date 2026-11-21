package com.bestwinesport.staff

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListScope
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

// ---------------- Home ----------------
private data class AdminHomeTile(val label: String, val icon: ImageVector, val route: String, val permission: String? = null, val adminOnly: Boolean = false, val alternatePermission: String? = null)

@Composable
fun AdminHome(nav: NavController) {
    val role = Session.role
    val wds by rflow(init = emptyMap<String, Any?>()) { AR.watch("withdrawals") }
    val pw = wds.values.count { mp(it)["status"] == "pending" }
    var confirmOut by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tiles = listOf(
        AdminHomeTile("Matches", Icons.Default.SportsEsports, "crud/matches", "matches_manage", alternatePermission = "matches_delete"),
        AdminHomeTile("Match results", Icons.Default.EmojiEvents, "resultsqueue", "results_manage"),
        AdminHomeTile("Player matches", Icons.Default.Bolt, "pvpresults", "results_manage"),
        AdminHomeTile("Player match games", Icons.Default.Gamepad, "crud/pvpgames", "games_manage", alternatePermission = "games_delete"),
        AdminHomeTile("Sections", Icons.Default.Gamepad, "crud/sections", "games_manage", alternatePermission = "games_delete"),
        AdminHomeTile("Games", Icons.Default.Gamepad, "crud/games", "games_manage", alternatePermission = "games_delete"),
        AdminHomeTile("Banners", Icons.Default.Image, "crud/banners", "banners_manage", alternatePermission = "banners_delete"),
        AdminHomeTile("Withdrawals", Icons.Default.Payments, "withdrawals", "withdrawals_review", alternatePermission = "withdrawals_export"),
        AdminHomeTile("Deposit requests", Icons.Default.AccountBalanceWallet, "deposits", "balance_adjust"),
        AdminHomeTile("Players", Icons.Default.Group, "users", "players_view", alternatePermission = "players_export"),
        AdminHomeTile("Announcements", Icons.Default.Campaign, "crud/announcements", "announcements_manage", alternatePermission = "announcements_delete"),
        AdminHomeTile("Support tickets", Icons.Default.Headset, "supporttickets", "support_manage"),
        AdminHomeTile("Dashboard", Icons.Default.Dashboard, "dashboard", "dashboard_view"),
        AdminHomeTile("Send Notification", Icons.Default.Notifications, "notify", "notifications_send"),
        AdminHomeTile("World chat", Icons.Default.Forum, "worldchatroom", "notifications_send"),
        AdminHomeTile("Chat settings", Icons.Default.Settings, "worldchatadmin", "notifications_send"),
        AdminHomeTile("Lucky Wheel (Spin)", Icons.Default.Casino, "spin", "spin_manage"),
        AdminHomeTile("App control", Icons.Default.SystemUpdate, "appcontrol", "app_control"),
        AdminHomeTile("Activity log", Icons.Default.History, "logs", "activity_view"),
        AdminHomeTile("Settings", Icons.Default.Settings, "settings", "settings_manage"),
        AdminHomeTile("Leaderboard", Icons.Default.Leaderboard, "leaderboard", "leaderboard_manage", alternatePermission = "ranking_reset"),
        AdminHomeTile("Notification Card", Icons.Default.NotificationsActive, "notifycard", "settings_manage"),
        AdminHomeTile("Theme & colors", Icons.Default.Palette, "themeeditor", "settings_manage")
    ).filter { if (it.adminOnly) role == "admin" else canAccessAny(*listOfNotNull(it.permission, it.alternatePermission).toTypedArray()) }
    Column(Modifier.fillMaxSize()) {
        ABar("DOUx24 Staff  •  $role", null) {
            if (canAccess("admin_theme")) { Icon(Icons.Default.Palette, null, Modifier.clickable { showTheme = true }); Spacer(Modifier.width(18.dp)) }
            Icon(Icons.Default.PowerSettingsNew, null, Modifier.clickable { confirmOut = true })
        }
        if (role == "admin") Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("$pw", "Withdrawals waiting", Modifier.weight(1f))
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tiles) { tile ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(CardC).clickable { nav.go(tile.route) }.padding(vertical = 22.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(tile.icon, null, tint = Red, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(8.dp)); Text(tile.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (showTheme) ThemeDialog { showTheme = false }
    if (confirmOut) ConfirmDialog("Sign out?", { confirmOut = false }) { scope.launch { AR.logout(); nav.navigate("login") { popUpTo(0) } } }
}

@Composable
private fun Stat(v: String, l: String, m: Modifier) =
    Column(m.clip(RoundedCornerShape(10.dp)).background(CardC).padding(14.dp)) { Text(v, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(l, color = Color.Gray, fontSize = 12.sp) }

// ---------------- Generic CRUD ----------------
class F(val k: String, val l: String, val t: String = "text", val o: List<Pair<String, String>> = emptyList(), val def: String = "")
class CrudCfg(val key: String, val title: String, val single: String, val path: String, val fields: List<F>,
              val titleOf: (Map<String, Any?>) -> String, val subOf: (Map<String, Any?>) -> String, val sortKey: (Map<String, Any?>) -> Double,
              val split: List<String> = emptyList(), val onNew: Map<String, Any?> = emptyMap(), val results: Boolean = false, val stamp: Boolean = false,
              val preFill: (() -> Map<String, Any?>)? = null)

private fun opts(vararg v: String) = v.map { it to it }
private val dtFmt get() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

val CRUDS: Map<String, CrudCfg> = listOf(
    CrudCfg("matches", "Matches", "match", "matches", listOf(
        F("matchId", "Match ID (auto, you can edit)"), F("gameId", "Game", "game"), F("title", "Title", def = "FF MAX MATCH | ATTRIBUTES ON |"), F("matchNo", "Match number (leave empty = next running number is added automatically, example #656 -> #657)"),
        F("mode", "Mode", "select", opts("Solo", "Duo", "Squad", "3vs3", "5vs5", "6vs6")), F("map", "Map (type any map name)", def = "Bermuda"),
        F("type", "Type", "select", opts("PAID", "FREE")), F("entryFee", "Entry fee (coins)", "number"), F("prizePool", "Prize pool", "number"),
        F("perKill", "Per kill", "number"), F("startTime", "Start (dd/MM/yyyy HH:mm or h:mm AM/PM). Leave empty = match starts NOW", "datetime"), F("maxPlayers", "Max players", "number", def = "48"),
        F("minPlayers", "Auto-cancel: minimum players needed. If fewer players joined, the match is cancelled and everyone is refunded. 0 = never auto-cancel", "number", def = "0"),
        F("cancelAfterMin", "Auto-cancel check: minutes after the start time (0 = exactly at start time)", "number", def = "0"),
        F("status", "Status (choose cancelled + SAVE = every joined player is refunded automatically)", "select", opts("upcoming", "ongoing", "completed", "cancelled")), F("image", "Banner image", "image"),
        F("roomId", "Room ID"), F("roomPass", "Room password"), F("rules", "Rules", "textarea"),
        F("prizeDetails", "Prize pool details - one line each, like  BOOYAH :- 26  or  1st :- 100  (the coin icon is added automatically; shown when a player taps PRIZE POOL)", "textarea"),
        F("liveUrl", "Live / Watch link (optional). Used by SPECTATE (ongoing) and WATCH (completed). Empty = the DeveloperCodex YouTube channel"),
        F("comment", "Result comment (shown in green above the Winner card on the finished match page)", "textarea")),
        { "${str(it["title"])} #${str(it["matchNo"])}" },
        { "ID ${str(it["matchId"])} • ${str(it["mode"])} • ${fdate(it["startTime"])} • ${str(it["joined"])}/${str(it["maxPlayers"])} • ${str(it["status"])}" },
        { -num(it["startTime"]) }, split = listOf("roomId", "roomPass"), onNew = mapOf("joined" to 0L), results = true, preFill = { mapOf("matchId" to AR.genMatchId()) }),
    CrudCfg("sections", "Sections", "section", "sections", listOf(
        F("name", "Section name (example: FREE FIRE, PRIVATE, BGMI)"), F("image", "Home card image", "image"),
        F("type", "What opens when a player taps it", "select", listOf("games" to "Games list (you add the games and matches)", "player" to "Player Match (players create the matches)"), def = "games"),
        F("order", "Order", "number"), F("active", "Visible in app", "bool")),
        { str(it["name"]) }, { "${str(it["type"]).ifBlank { "games" }} • order ${str(it["order"])}" }, { num(it["order"]) }),
    CrudCfg("games", "Games", "game", "games", listOf(
        F("name", "Name"), F("image", "Image", "image"), F("section", "Section (create more in Sections)", "section", def = "freefire"), F("order", "Order", "number"), F("active", "Visible in app", "bool")),
        { str(it["name"]) }, { "${str(it["section"]).ifBlank { "freefire" }} • order ${str(it["order"])}" }, { num(it["order"]) }),
    CrudCfg("pvpgames", "Player match games", "player match game", "pvpGames", listOf(
        F("name", "Game name (example: CS 1vs1, LW 1vs1)"), F("image", "Small image (game icon)", "image"),
        F("banner", "Match banner - upload ONCE. It is shown on top of every match card of this game (wide image, about 1280 x 614 px). Empty = small image", "image"),
        F("title", "Match title shown on every match of this game, example: CS 1VS1 MATCH |  (the app adds the running number after it: #767, #768 ...). Empty = game name"),
        F("rules", "Rules (shown to players inside every match of this game)", "textarea"),
        F("slots", "Slots - how many players a match of this game can have. One number (2 = 1 vs 1) or several separated by commas (2,4). Max 20"),
        F("commission", "Admin commission % (optional). 0 = use the fixed winning table: 5 -> 8, 10 -> 16, 20 -> 35, 35 -> 60, 50 -> 85, 100 -> 170, 150 -> 250. Above 0 = winning is entry fee x players minus this %", "number"),
        F("order", "Order", "number"), F("active", "Visible in app", "bool")),
        { str(it["name"]) }, { "slots ${str(it["slots"]).ifBlank { "2" }} • commission ${str(it["commission"]).ifBlank { "0" }}% • order ${str(it["order"])}" }, { num(it["order"]) }),
    CrudCfg("banners", "Banners", "banner", "banners", listOf(
        F("section", "Where it shows", "select", listOf("important" to "Home - Important", "welcome" to "Home - Welcome strip", "earn" to "Earn tab", "onboarding" to "Onboarding slides")),
        F("image", "Image (wide banner, about 1280 x 614 px - use the same size for every banner)", "image"), F("link", "Link (any https link, Instagram/Telegram link, WhatsApp link, or app:refer / app:support / app:addmoney / app:wallet)"), F("order", "Order", "number"), F("active", "Visible in app", "bool")),
        { str(it["section"]).uppercase() + " banner" }, { str(it["image"]).takeLast(40) }, { num(it["order"]) }),
    CrudCfg("announcements", "Announcements", "announcement", "announcements", listOf(F("title", "Title (shown on the Home bar and as the first line of the announcement card)"), F("text", "Message (shown inside the white announcement card, under the title)", "textarea"), F("image", "Image (optional)", "image")),
        { str(it["title"]).ifBlank { str(it["text"]) }.take(60) }, { fdate(it["ts"]) }, { -num(it["ts"]) }, stamp = true)
).associateBy { it.key }

private fun toStr(f: F, v: Any?): String = when {
    f.t == "bool" -> (v as? Boolean ?: true).toString()
    f.t == "datetime" -> if (num(v) == 0.0) "" else dtFmt.format(java.util.Date(num(v).toLong()))
    v == null -> f.def
    else -> str(v)
}

private fun conv(f: F, s: String): Any = when (f.t) {
    "number" -> { val d = s.toDoubleOrNull() ?: 0.0; if (d % 1.0 == 0.0) d.toLong() else d }
    "bool" -> s.toBoolean()
    "datetime" -> parseMatchDate(s) ?: 0L
    else -> s.trim()
}

/** Player-match games reuse the staff permissions of normal games (games_manage / games_delete). */
private fun permBase(cfg: CrudCfg): String = if (cfg.key == "pvpgames" || cfg.key == "sections") "games" else cfg.key

@Composable
fun CrudScreen(nav: NavController, cfg: CrudCfg) {
    val dataN by rflow(cfg.path, init = null as Map<String, Any?>?) { AR.watch(cfg.path) }
    val gamesAll by rflow(init = emptyMap<String, Any?>()) { AR.watch("games") }
    var gameF by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var delId by remember { mutableStateOf<String?>(null) }
    var selMode by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<String>() }
    var bulkAsk by remember { mutableStateOf(false) }
    var bulkBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    BackHandler(editing != null) { editing = null }
    BackHandler(cfg.key == "matches" && editing == null && gameF.isNotBlank()) { gameF = "" }
    BackHandler(selMode && editing == null) { selMode = false; sel.clear() }
    val slideMod = Modifier.stepSlide(if (editing != null) 2 else if (cfg.key == "matches" && gameF.isNotBlank()) 1 else 0)
    var askNo by remember { mutableStateOf(false) }
    if (askNo) {
        val nextNo by rflow(init = 0L) { kotlinx.coroutines.flow.flow { emit(AR.peekMatchNo()) } }
        AskDialog("Next match number (now: #$nextNo)", "Next number, example 656", true, { askNo = false }) { v ->
            askNo = false; scope.launch { ctx.toast(AR.setNextMatchNo(v.toLongOrNull() ?: 0L)) }
        }
    }
    // First time: the two default sections (FREE FIRE and PRIVATE) are created automatically.
    if (cfg.key == "sections") LaunchedEffect(dataN?.isEmpty()) {
        if (dataN != null && dataN!!.isEmpty()) try {
            val ff = AR.get("settings/ffImage")?.toString().orEmpty(); val pv = AR.get("settings/privateImage")?.toString().orEmpty()
            AR.db.updateChildren(hashMapOf<String, Any?>(
                "sections/freefire" to mapOf("name" to "FREE FIRE", "image" to ff, "type" to "games", "order" to 1L, "active" to true),
                "sections/private" to mapOf("name" to "PRIVATE", "image" to pv, "type" to "player", "order" to 2L, "active" to true))).await()
        } catch (_: Exception) { }
    }
    val data = dataN ?: return Column(Modifier.fillMaxSize()) { ABar(cfg.title, nav); Loader() }
    editing?.let { id -> Box(slideMod.fillMaxSize()) { FormScreen(cfg, id, if (id.isBlank() && cfg.key == "matches" && gameF.isNotBlank()) mapOf("gameId" to gameF) else mp(data[id])) { editing = null } }; return }
    Column(slideMod.fillMaxSize()) {
        ABar(cfg.title, nav) {
            if (cfg.key == "matches" && canAccess("matches_manage")) {
                ABtn("NEXT NO", color = Field, small = true) { askNo = true }; Spacer(Modifier.width(6.dp))
                ABtn("+ AUTO", color = Green, small = true) { nav.go("automatch") }; Spacer(Modifier.width(6.dp))
            }
            if (canAccess("${permBase(cfg)}_delete")) { ABtn(if (selMode) "CANCEL" else "SELECT", color = Field, small = true) { selMode = !selMode; sel.clear() }; Spacer(Modifier.width(6.dp)) }
            if (canAccess("${permBase(cfg)}_manage")) ABtn(if (cfg.key == "matches") "+ MANUAL" else "+ NEW", color = Red, small = true) { editing = "" }
        }
        if (cfg.key == "matches" && gameF.isBlank()) { GameTiles(gamesAll.onlyAllowedGames(), data) { gameF = it }; return@Column }
        if (cfg.key == "matches") GameHeader(str(mp(gamesAll[gameF])["name"]).ifBlank { "Matches" }) { gameF = "" }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 0.dp), label = { Text("Search (Match ID, name...)") }, singleLine = true)
        val rows = data.entries.sortedBy { cfg.sortKey(mp(it.value)) }.filter { cfg.key != "matches" || gameF.isBlank() || str(mp(it.value)["gameId"]) == gameF }.filter { cfg.key != "matches" || gameAllowed(str(mp(it.value)["gameId"])) }.filter { q.isBlank() || (cfg.titleOf(mp(it.value)) + " " + cfg.subOf(mp(it.value))).contains(q.trim(), true) }
        if (rows.isEmpty()) Text("Nothing here yet. Tap + NEW.", Modifier.padding(30.dp), color = Color.Gray)
        if (selMode) Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp, 12.dp, 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val allSel = rows.isNotEmpty() && rows.all { it.key in sel }
            ABtn(if (allSel) "UNSELECT ALL" else "SELECT ALL (${rows.size})", color = Field, small = true) {
                if (allSel) sel.removeAll(rows.map { it.key }.toSet()) else rows.forEach { if (it.key !in sel) sel.add(it.key) }
            }
            Text("${sel.size} selected", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
            ABtn("DELETE (${sel.size})", color = Color(0xFF7A1F1F), small = true, enabled = sel.isNotEmpty() && !bulkBusy) { bulkAsk = true }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.key }) { e ->
                val r = mp(e.value)
                val picked = e.key in sel
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC)
                    .then(if (selMode) Modifier.clickable { if (picked) sel.remove(e.key) else sel.add(e.key) } else Modifier).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selMode) Checkbox(picked, { if (it) sel.add(e.key) else sel.remove(e.key) })
                        Column(Modifier.weight(1f)) {
                            if (cfg.key == "matches") Text(gameNameOf(gamesAll, r), color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(cfg.titleOf(r), fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(cfg.subOf(r), color = Color.Gray, fontSize = 12.sp)
                        }
                        if (cfg.key == "matches") Pill(str(r["status"]))
                    }
                    if (!selMode) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canAccess("${permBase(cfg)}_manage")) ABtn("EDIT", color = Field, small = true) { editing = e.key }
                        if (cfg.results && canAccess("results_manage")) ABtn("PLAYERS / RESULT", small = true) { nav.go("results/${e.key}") }
                        if (cfg.key == "matches") ABtn("CLONE", color = Field, small = true) { scope.launch { ctx.toast(AR.cloneMatch(e.key)) } }
                        if (canAccess("${permBase(cfg)}_delete")) ABtn("DELETE", color = Color(0xFF7A1F1F), small = true) { delId = e.key }
                    }
                }
            }
        }
    }
    if (bulkAsk) ConfirmDialog("Delete ${sel.size} selected ${cfg.title.lowercase()} permanently? This cannot be undone.", { bulkAsk = false }) {
        val ids = sel.toList(); bulkAsk = false; bulkBusy = true
        scope.launch {
            try {
                ids.chunked(100).forEach { part ->
                    val ups = hashMapOf<String, Any?>()
                    part.forEach { id -> ups["${cfg.path}/$id"] = null; if (cfg.split.isNotEmpty()) ups["rooms/$id"] = null }
                    AR.db.updateChildren(ups).await()
                }
                ctx.toast("${ids.size} deleted"); sel.clear(); selMode = false
            } catch (e: Exception) { ctx.toast(e.message ?: "Delete failed") }
            bulkBusy = false
        }
    }
    delId?.let { id -> ConfirmDialog("Delete this ${cfg.single}?", { delId = null }) {
        scope.launch {
            val ups = hashMapOf<String, Any?>("${cfg.path}/$id" to null); if (cfg.split.isNotEmpty()) ups["rooms/$id"] = null
            AR.db.updateChildren(ups).await(); delId = null
        }
    } }
}

@Composable
fun FormScreen(cfg: CrudCfg, id: String, row: Map<String, Any?>, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val games by rflow(init = emptyMap<String, Any?>()) { AR.watch("games") }
    val sectionsAll by rflow(init = emptyMap<String, Any?>()) { AR.watch("sections") }
    val vals = remember(id) {
        val pre = if (id.isBlank()) cfg.preFill?.invoke() ?: emptyMap() else emptyMap()
        mutableStateMapOf<String, String>().also { m -> cfg.fields.forEach { f -> m[f.k] = toStr(f, row[f.k] ?: pre[f.k]) } }
    }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (cfg.split.isNotEmpty() && id.isNotBlank()) { val r = mp(AR.get("rooms/$id")); cfg.split.forEach { vals[it] = str(r[it]) } }
    }
    Column(Modifier.fillMaxSize()) {
        ABar(if (id.isBlank()) "New ${cfg.single}" else "Edit ${cfg.single}", null) { TextButton(close) { Text("CLOSE", color = Color.White) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            cfg.fields.forEach { f ->
                val v = vals[f.k] ?: ""
                when (f.t) {
                    "bool" -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.l, Modifier.weight(1f)); Switch(v.toBoolean(), { vals[f.k] = it.toString() })
                    }
                    "image" -> ImageField(f.l, v, 1280, cfg.key, id, if (id.isNotBlank() && f.k !in cfg.split) "${cfg.path}/$id/${f.k}" else null) { vals[f.k] = it }
                    "select" -> SelectField(f.l, v, f.o) { vals[f.k] = it }
                    "section" -> SelectField(f.l, v, sectionsAll.entries.filter { str(mp(it.value)["type"]).ifBlank { "games" } == "games" }.sortedBy { num(mp(it.value)["order"]) }.map { it.key to str(mp(it.value)["name"]) }.ifEmpty { listOf("freefire" to "FREE FIRE") }) { vals[f.k] = it }
                    "game" -> SelectField(f.l, v, games.onlyAllowedGames().entries.map { it.key to str(mp(it.value)["name"]) }) { vals[f.k] = it }
                    else -> OutlinedTextField(v, { vals[f.k] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(f.l) },
                        singleLine = f.t != "textarea", minLines = if (f.t == "textarea") 4 else 1,
                        keyboardOptions = KeyboardOptions(keyboardType = if (f.t == "number") KeyboardType.Decimal else KeyboardType.Text))
                }
            }
        }
        ABtn(if (busy) "SAVING..." else "SAVE", Modifier.fillMaxWidth().padding(14.dp), Green, enabled = !busy) {
            scope.launch {
                busy = true
                try {
                    if (cfg.key == "matches" && !gameAllowed(vals["gameId"] ?: "")) { ctx.toast("You can only add / edit matches of the games given to you"); busy = false; return@launch }
                    val nid = id.ifBlank { AR.db.child(cfg.path).push().key!! }
                    if (cfg.key == "matches" && id.isNotBlank()) {
                        val wasCancelled = str(row["status"]) == "cancelled"
                        val nowStatus = vals["status"] ?: ""
                        if (wasCancelled && nowStatus != "cancelled") { ctx.toast("A cancelled match was already refunded and cannot be reopened. Use CLONE to make a new match"); busy = false; return@launch }
                        if (!wasCancelled && nowStatus == "cancelled") {
                            val err = AR.cancelMatchRefund(id)
                            if (err != null) { ctx.toast(err); busy = false; return@launch }
                            ctx.toast("Match cancelled and all joined players refunded")
                        }
                    }
                    val ups = HashMap<String, Any?>()
                    cfg.fields.forEach { f ->
                        val cv = conv(f, vals[f.k] ?: "")
                        if (f.k in cfg.split) ups["rooms/$nid/${f.k}"] = cv else ups["${cfg.path}/$nid/${f.k}"] = cv
                    }
                    if (cfg.key == "matches") {
                        // Start time empty = NOW. Match number empty = next running number. Auto-cancel time is worked out from the start time.
                        var st = (conv(cfg.fields.first { it.k == "startTime" }, vals["startTime"] ?: "") as Number).toLong()
                        if (st == 0L) st = System.currentTimeMillis()
                        ups["matches/$nid/startTime"] = st
                        if ((vals["matchNo"] ?: "").isBlank()) {
                            val no = AR.reserveMatchNos(1)
                            if (no < 1) { ctx.toast("Could not get the match number. Try again"); busy = false; return@launch }
                            ups["matches/$nid/matchNo"] = no.toString()
                        }
                        val mp0 = (vals["minPlayers"]?.toDoubleOrNull() ?: 0.0).toLong().coerceAtLeast(0)
                        val af = (vals["cancelAfterMin"]?.toDoubleOrNull() ?: 0.0).toLong().coerceAtLeast(0)
                        ups["matches/$nid/cancelAt"] = AR.cancelAtOf(st, mp0, af)
                    }
                    if (id.isBlank()) { cfg.onNew.forEach { (k, v) -> ups["${cfg.path}/$nid/$k"] = v }; if (cfg.stamp) ups["${cfg.path}/$nid/ts"] = System.currentTimeMillis() }
                    AR.db.updateChildren(ups).await()
                    ctx.toast("Saved"); close()
                } catch (e: Exception) { ctx.toast(e.message ?: "Save failed") }
                busy = false
            }
        }
    }
}

/** Name of the game (category) a match belongs to, for example CS 1VS1 or FULL MAP. */
fun gameNameOf(games: Map<String, Any?>, m: Map<String, Any?>): String =
    str(mp(games[str(m["gameId"])])["name"]).ifBlank { "No game selected" }

/** Game categories as tiles (image + name, 3 in a row) - tap one to open only that game's matches. */
@Composable
fun GameTiles(games: Map<String, Any?>, matches: Map<String, Any?>, onPick: (String) -> Unit) {
    val list = games.entries.sortedBy { num(mp(it.value)["order"]) }
    if (list.isEmpty()) Text("No games yet. Add a game first (Games).", Modifier.padding(30.dp), color = Color.Gray)
    LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(horizontal = 9.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(17.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(list, key = { it.key }) { g ->
            val gm = mp(g.value)
            Column(Modifier.clip(RoundedCornerShape(10.dp)).background(CardC).clickable { onPick(g.key) }) {
                coil.compose.AsyncImage(BackendConfig.resolve(str(gm["image"])), null, Modifier.fillMaxWidth().aspectRatio(1.23f), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Text(str(gm["name"]), Modifier.fillMaxWidth().background(Color(0xFFE6E7EB)).padding(vertical = 4.dp, horizontal = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = Color.Black)
                Text("${matches.values.count { str(mp(it)["gameId"]) == g.key }} matches", Modifier.fillMaxWidth().padding(vertical = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

/** Back arrow + game name shown on top of one game's match list. */
@Composable
fun GameHeader(name: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp, 10.dp, 14.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(22.dp).clickable(onClick = onBack))
        Spacer(Modifier.width(12.dp)); Text(name, color = Purple, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------- Results ----------------
@Composable
fun ResultsQueueScreen(nav: NavController) {
    val data by rflow(init = null as Map<String, Any?>?) { AR.watch("matches") }
    var query by remember { mutableStateOf("") }
    val gamesAll by rflow(init = emptyMap<String, Any?>()) { AR.watch("games") }
    var gameF by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler(gameF.isNotBlank()) { gameF = "" }
    val slideMod = Modifier.stepSlide(if (gameF.isNotBlank()) 1 else 0)
    val matches = data ?: return Column(Modifier.fillMaxSize()) { ABar("Match results", nav); Loader() }
    val rows = matches.entries.sortedByDescending { num(mp(it.value)["startTime"]) }
        .filter { gameAllowed(str(mp(it.value)["gameId"])) }
        .filter { gameF.isBlank() || str(mp(it.value)["gameId"]) == gameF }
        .filter { (str(mp(it.value)["title"]) + " " + str(mp(it.value)["matchNo"]) + " " + str(mp(it.value)["matchId"])).contains(query, true) }
    Column(slideMod.fillMaxSize()) {
        ABar("Match results", nav)
        if (gameF.isBlank()) { GameTiles(gamesAll.onlyAllowedGames(), matches) { gameF = it }; return@Column }
        GameHeader(str(mp(gamesAll[gameF])["name"]).ifBlank { "Matches" }) { gameF = "" }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Search matches") }, singleLine = true)
        if (rows.isEmpty()) Text("No matching tournaments.", Modifier.padding(24.dp), color = Color.Gray)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.key }) { entry ->
                val m = mp(entry.value)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(gameNameOf(gamesAll, m), color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${str(m["title"])} #${str(m["matchNo"])}", fontWeight = FontWeight.SemiBold)
                            Text("${mp(m["slots"]).size} participants • ${str(m["status"])}", color = Color.Gray, fontSize = 12.sp)
                        }
                        Pill(if (m["resultDeclared"] == true) "completed" else str(m["status"]))
                    }
                    ABtn(if (m["resultDeclared"] == true) "VIEW RESULT" else "ENTER / DECLARE RESULT", Modifier.fillMaxWidth().padding(top = 8.dp), Green, true) {
                        nav.go("results/${entry.key}")
                    }
                }
            }
        }
    }
}

@Composable
fun ResultsScreen(nav: NavController, mid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mN by rflow(mid, init = null as Map<String, Any?>?) { AR.watch("matches/$mid") }
    val kills = remember { mutableStateMapOf<String, String>() }
    val prizes = remember { mutableStateMapOf<String, String>() }
    var ask by remember { mutableStateOf(false) }
    var askMore by remember { mutableStateOf(false) }
    var editing by remember(mid) { mutableStateOf(false) }   // result already declared -> admin re-opened it to add missed winnings
    val m = mN ?: return Column(Modifier.fillMaxSize()) { ABar("Players", nav); Loader() }
    if (!gameAllowed(str(m["gameId"]))) return Column(Modifier.fillMaxSize()) { ABar("Players", nav); Text("This match belongs to a game that is not given to you.", Modifier.padding(24.dp), color = Color.Gray) }
    var comment by remember(mid) { mutableStateOf(str(m["comment"])) }
    var savingC by remember { mutableStateOf(false) }
    // Search inside the players of this match (username / in-game name / team): the kills + prize already typed for other players are kept while searching.
    var search by remember(mid) { mutableStateOf("") }
    val slots = mp(m["slots"]); val res = mp(m["results"]); val done = m["resultDeclared"] == true
    val q = search.trim()
    // Searching does not hide anyone: players matching the username / in-game name come to the TOP, everybody else stays below them.
    val matchedKeys = remember(slots, q) {
        if (q.isEmpty()) emptySet() else slots.entries.filter { e ->
            val sl = mp(e.value)
            listOf(str(sl["username"]), str(sl["ign"]), e.key.replace("t", "Team ").replace("_", " - ")).any { it.contains(q, ignoreCase = true) }
        }.map { it.key }.toSet()
    }
    val shownSlots = remember(slots, matchedKeys) {
        slots.entries.sortedWith(compareBy({ if (it.key in matchedKeys) 0 else 1 }, { it.key.length }, { it.key }))
    }
    Column(Modifier.fillMaxSize()) {
        ABar("${str(m["title"])} #${str(m["matchNo"])}", nav)
        if (canAccess("notifications_send")) ABtn("NOTIFY THESE PLAYERS", Modifier.fillMaxWidth().padding(14.dp, 10.dp, 14.dp, 0.dp), Purple, true) { nav.go("notify?mid=$mid") }
        Text("Match ID: ${str(m["matchId"]).ifBlank { "-" }}", Modifier.padding(14.dp, 10.dp, 14.dp, 0.dp), color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(if (done && editing) "Result re-opened: enter kills / prize for the missed players (already paid amounts cannot be reduced), then save." else if (done) "Result declared and paid." else "${slots.size} positions filled. Enter kills and prize, then declare.", Modifier.padding(14.dp), color = Color.Gray, fontSize = 13.sp)
        if (slots.size > 1) {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                label = { Text("Search player: username or in-game name") }, singleLine = true,
                trailingIcon = { if (search.isNotEmpty()) TextButton({ search = "" }) { Text("CLEAR") } })
            if (q.isNotEmpty()) Text(if (matchedKeys.isEmpty()) "No player found - all ${slots.size} players shown below" else "${matchedKeys.size} matched (on top) - other players below", Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp), color = if (matchedKeys.isEmpty()) Color(0xFFFFB74D) else Green, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Result comment: written here (above Team 1) or in the match EDIT form - both save the same field, so the match need not be opened.
            if (q.isEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth(), label = { Text("Result comment (shown in green above the Winner card)") }, minLines = 2)
                    ABtn(if (savingC) "SAVING..." else "SAVE COMMENT", Modifier.fillMaxWidth().padding(top = 8.dp), Purple, true, enabled = !savingC) {
                        savingC = true
                        scope.launch {
                            try { AR.db.child("matches/$mid/comment").setValue(comment.trim()).await(); ctx.toast("Comment saved") } catch (e: Exception) { ctx.toast(e.message ?: "Could not save") }
                            savingC = false
                        }
                    }
                }
            }
            items(shownSlots, key = { it.key }) { e ->
                val s = mp(e.value); val r = mp(res[e.key])
                val isMatch = e.key in matchedKeys
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).then(if (isMatch) Modifier.border(2.dp, Green, RoundedCornerShape(10.dp)) else Modifier).padding(12.dp)) {
                    Text("${e.key.replace("t", "Team ").replace("_", " - ")}  •  ${str(s["username"])}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("In-game: ${str(s["ign"])}", color = Color.Gray, fontSize = 12.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(kills[e.key] ?: str(r["kills"]).ifBlank { "0" }, { kills[e.key] = it }, Modifier.weight(1f), label = { Text("Kills") }, singleLine = true, enabled = !done || editing,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(prizes[e.key] ?: str(r["prize"]).ifBlank { "0" }, { prizes[e.key] = it }, Modifier.weight(1f), label = { Text("Prize ₹") }, singleLine = true, enabled = !done || editing,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }
            }
        }
        if (!done && slots.isNotEmpty()) ABtn("DECLARE RESULT AND PAY", Modifier.fillMaxWidth().padding(14.dp), Green) { ask = true }
        if (done && slots.isNotEmpty() && canAccess("results_manage")) {
            if (!editing) ABtn("OPEN RESULT - ADD MISSED WINNINGS", Modifier.fillMaxWidth().padding(14.dp), Orange) { editing = true }
            else Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ABtn("CANCEL", Modifier.weight(1f), Field) { editing = false; kills.clear(); prizes.clear() }
                ABtn("SAVE AND PUBLISH", Modifier.weight(2f), Green) { askMore = true }
            }
        }
    }
    if (askMore) {
        // Only the EXTRA amount over what each player was already paid is credited.
        val extra = slots.keys.mapNotNull { k ->
            val op = num(mp(res[k])["prize"]); val np = prizes[k]?.toDoubleOrNull() ?: op
            if (np > op + 1e-9) np - op else null
        }
        ConfirmDialog(if (extra.isEmpty()) "Only kills / result changes will be saved, no extra winnings. Save and publish?"
            else "Add ₹${money(extra.sum())} extra winnings to ${extra.size} player(s) and publish the updated result? Already paid players are not paid again. This cannot be undone.", { askMore = false }) {
            askMore = false
            scope.launch {
                val k = slots.keys.associateWith { kills[it] ?: str(mp(res[it])["kills"]) }
                val p = slots.keys.associateWith { prizes[it] ?: str(mp(res[it])["prize"]) }
                if (comment.trim() != str(m["comment"]).trim()) try { AR.db.child("matches/$mid/comment").setValue(comment.trim()).await() } catch (_: Exception) { }
                val msg = AR.addMissedWinnings(mid, k, p)
                ctx.toast(msg)
                if (msg.startsWith("Result updated")) { editing = false; kills.clear(); prizes.clear() }
            }
        }
    }
    if (ask) ConfirmDialog("Declare result and credit winnings? This cannot be undone.", { ask = false }) {
        ask = false
        scope.launch {
            val k = slots.keys.associateWith { kills[it] ?: str(mp(res[it])["kills"]) }
            val p = slots.keys.associateWith { prizes[it] ?: str(mp(res[it])["prize"]) }
            // The comment written above is saved together with the result.
            if (comment.trim() != str(m["comment"]).trim()) try { AR.db.child("matches/$mid/comment").setValue(comment.trim()).await() } catch (_: Exception) { }
            ctx.toast(AR.declare(mid, k, p))
        }
    }
}

// ---------------- Withdrawals ----------------
@Composable
fun WithdrawalsScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val wN by rflow(init = null as Map<String, Any?>?) { AR.watch("withdrawals") }
    var filter by remember { mutableStateOf("pending") }
    val w = wN ?: return Column(Modifier.fillMaxSize()) { ABar("Withdraw requests", nav); Loader() }
    val rows = w.entries.sortedByDescending { num(mp(it.value)["ts"]) }.filter { filter == "all" || mp(it.value)["status"] == filter }
    Column(Modifier.fillMaxSize()) {
        ABar("Withdraw requests", nav) {
            if (canAccess("withdrawals_export")) ABtn("EXPORT", color = Field, small = true) { shareCsv(ctx, "withdrawals", "date,username,amount,method,fee_percent,pay_amount,destination,status", rows.map { val r = mp(it.value); listOf(fdate(r["ts"]), str(r["username"]), str(r["amount"]), str(r["methodTitle"]).ifBlank { "UPI ID" }, str(r["feePct"]).ifBlank { "0" }, str(r["net"]).ifBlank { str(r["amount"]) }, str(r["upi"]), str(r["status"])) }) }
        }
        FilterRow(filter, listOf("pending", "paid", "rejected", "all")) { filter = it }
        if (rows.isEmpty()) Text("No withdraw requests yet.", Modifier.padding(30.dp), color = Color.Gray)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.key }) { e ->
                val r = mp(e.value)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row { Text("${str(r["username"])}  •  ₹${str(r["amount"])}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Pill(str(r["status"])) }
                    Text("${str(r["methodTitle"]).ifBlank { "UPI ID" }}: ${str(r["upi"])}", fontSize = 13.sp)
                    if (num(r["feePct"]) > 0) Text("Pay ₹${money(num(r["net"]))} after ${money(num(r["feePct"]))}% cut", fontSize = 13.sp, color = Color(0xFFFFB74D))
                    Text(fdate(r["ts"]), color = Color.Gray, fontSize = 12.sp)
                    if (r["status"] == "pending") Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canAccess("withdrawals_review")) ABtn("MARK PAID", color = Green, small = true) { scope.launch { ctx.toast(AR.withdraw(e.key, true)) } }
                        if (canAccess("withdrawals_review")) ABtn("REJECT + REFUND", color = Color(0xFF7A1F1F), small = true) { scope.launch { ctx.toast(AR.withdraw(e.key, false)) } }
                    }
                }
            }
        }
    }
}

// ---------------- Manual deposit requests ----------------
@Composable
fun DepositRequestsScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val dN by rflow(init = null as Map<String, Any?>?) { AR.watch("depositRequests") }
    var filter by remember { mutableStateOf("pending") }
    var proof by remember { mutableStateOf<Pair<String, String>?>(null) }
    val d = dN ?: return Column(Modifier.fillMaxSize()) { ABar("Deposit requests", nav); Loader() }
    proof?.let { (puid, pid) ->
        val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, puid, pid) {
            value = try {
                val v = AR.get("depositProofs/$puid/$pid")?.toString().orEmpty()
                val bytes = android.util.Base64.decode(v.substringAfter("base64,", v), android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
        AlertDialog(onDismissRequest = { proof = null }, containerColor = DialogC,
            title = { Text("Payment screenshot") },
            text = { if (bmp == null) Text("Loading... (if nothing appears the player did not attach one)", color = Color.Gray) else androidx.compose.foundation.Image(bmp!!, null, Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit) },
            confirmButton = { TextButton({ proof = null }) { Text("CLOSE") } })
    }
    val all = d.entries.flatMap { u -> mp(u.value).entries.map { Triple(u.key, it.key, mp(it.value)) } }
    val rows = all.sortedByDescending { num(it.third["ts"]) }.filter { filter == "all" || it.third["status"] == filter }
    Column(Modifier.fillMaxSize()) {
        ABar("Deposit requests", nav)
        FilterRow(filter, listOf("pending", "approved", "rejected", "all")) { filter = it }
        if (rows.isEmpty()) Text("No deposit requests yet.", Modifier.padding(30.dp), color = Color.Gray)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.first + "/" + it.second }) { (uid, id, r) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row { Text("${str(r["username"])}  •  ₹${str(r["amount"])}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Pill(str(r["status"])) }
                    Text("UTR: ${str(r["utr"]).ifBlank { "-" }}", fontSize = 13.sp)
                    if (r["hasProof"] == true) ABtn("VIEW SCREENSHOT", color = Color(0xFF2B3A67), small = true) { proof = uid to id }
                    Text(fdate(r["ts"]), color = Color.Gray, fontSize = 12.sp)
                    if (r["status"] == "pending" && canAccess("balance_adjust")) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ABtn("APPROVE + ADD COINS", color = Green, small = true) { scope.launch { ctx.toast(AR.reviewDeposit(uid, id, true)) } }
                        ABtn("REJECT", color = Color(0xFF7A1F1F), small = true) { scope.launch { ctx.toast(AR.reviewDeposit(uid, id, false)) } }
                    }
                }
            }
        }
    }
}

// ---------------- Users ----------------
@Composable
fun UsersScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val uN by rflow(init = null as Map<String, Any?>?) { AR.watchQ(AR.db.child("users").orderByChild("createdAt").limitToLast(300)) }
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    var roleDlg by remember { mutableStateOf(false) }
    var themeDlg by remember { mutableStateOf<String?>(null) }
    var banDlg by remember { mutableStateOf<String?>(null) }
    var banReasonText by remember { mutableStateOf("") }
    var themeValue by remember { mutableStateOf(PlayerThemeChoices.defaultId) }
    val users = uN ?: return Column(Modifier.fillMaxSize()) { ABar("Players", nav); Loader() }
    val rows = users.entries.sortedByDescending { num(mp(it.value)["createdAt"]) }
        .filter { val u = mp(it.value); q.isBlank() || listOf("username", "phone", "email").any { k -> str(u[k]).contains(q, true) } }
    Column(Modifier.fillMaxSize()) {
        ABar("Players", nav) {
            if (canAccess("players_export")) ABtn("EXPORT", color = Field, small = true) { shareCsv(ctx, "players", "username,phone,email,deposited,winning,role,banned,theme", rows.map { val u = mp(it.value); listOf(str(u["username"]), str(u["phone"]), str(u["email"]), str(u["deposited"]), str(u["winning"]), str(u["role"]), (u["banned"] == true).toString(), str(u["theme"])) }) }
            Spacer(Modifier.width(8.dp))
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Search username / phone / email") }, singleLine = true)
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.key }) { e ->
                val u = mp(e.value)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).clickable { sel = e.key }.padding(12.dp)) {
                    Row { Text(str(u["username"]), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(str(u["role"]).ifBlank { "user" } + if (u["banned"] == true) " • BANNED" else "", color = if (u["banned"] == true) Red else Color.Gray, fontSize = 12.sp) }
                    Text("${str(u["phone"])}  •  Dep ₹${"%.2f".format(num(u["deposited"]))}  •  Win ₹${"%.2f".format(num(u["winning"]))}", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
    val uid = sel
    if (uid != null && ask == null && !roleDlg && themeDlg == null && banDlg == null) {
        val u = mp(users[uid])
        AlertDialog(onDismissRequest = { sel = null }, containerColor = DialogC, title = { Text(str(u["username"]), fontSize = 17.sp) }, confirmButton = {},
            dismissButton = { TextButton({ sel = null }) { Text("CLOSE") } },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canAccess("balance_adjust")) {
                    ABtn("± DEPOSITED BALANCE", Modifier.fillMaxWidth(), Field, true) { ask = "deposited" }
                    ABtn("± WINNING BALANCE", Modifier.fillMaxWidth(), Field, true) { ask = "winning" }
                }
                if (canAccess("players_reward")) ABtn("GIVE REWARD", Modifier.fillMaxWidth(), Field, true) { ask = "reward" }
                if (canAccess("notifications_send")) ABtn("SEND MESSAGE / NOTIFICATION", Modifier.fillMaxWidth(), Field, true) { nav.go("notify?user=${str(u["username"])}"); sel = null }
                if (canAccess("players_theme")) ABtn("CHANGE PLAYER THEME", Modifier.fillMaxWidth(), Purple, true) { themeValue = PlayerThemeChoices.canonical(str(u["theme"])); themeDlg = uid }
                if (canAccess("players_moderate")) {
                    ABtn(if (u["kyc"] == true) "REMOVE KYC VERIFIED" else "MARK KYC VERIFIED", Modifier.fillMaxWidth(), Field, true) { scope.launch { ctx.toast(AR.setUser(uid, "kyc", u["kyc"] != true)); sel = null } }
                    ABtn(if (u["banned"] == true) "UNBAN" else "BAN PLAYER", Modifier.fillMaxWidth(), if (u["banned"] == true) Green else Color(0xFF7A1F1F), true) {
                        if (u["banned"] == true) scope.launch { ctx.toast(AR.setBan(uid, false, "")); sel = null } else { banReasonText = ""; banDlg = uid }
                    }
                    if (u["banned"] == true && str(u["banReason"]).isNotBlank()) Text("Ban reason: ${str(u["banReason"])}", color = Color.Gray, fontSize = 12.sp)
                }
                if (canAccess("transactions_view")) ABtn("VIEW TRANSACTIONS", Modifier.fillMaxWidth(), Field, true) { nav.go("usertx/$uid"); sel = null }
                if (Session.role != "admin" && listOf("balance_adjust", "players_reward", "notifications_send", "players_theme", "players_moderate", "transactions_view").none(::canAccess))
                    Text("Profile is read-only. Ask an admin for additional permissions.", color = Color.Gray, fontSize = 12.sp)
            } })
    }
    if (uid != null && ask != null) {
        val a = ask!!
        if (a == "name") AskDialog("New first name", "Name", false, { ask = null }) { nm ->
            scope.launch { ctx.toast(AR.setUser(uid, "firstName", nm)); ask = null; sel = null }
        } else if (a == "reward") AskDialog("Reward amount (coins)", "Amount", true, { ask = null }) { amt ->
            scope.launch { ctx.toast(AR.reward(uid, "Admin reward", amt.toDoubleOrNull() ?: 0.0)); ask = null; sel = null }
        } else AskDialog("Add to $a (minus to deduct)", "Amount", false, { ask = null }) { amt ->
            scope.launch { val x = amt.toDoubleOrNull(); ctx.toast(if (x == null || x == 0.0) "Invalid amount" else AR.adjust(uid, a, x, "Admin adjustment")); ask = null; sel = null }
        }
    }
    banDlg?.let { targetUid ->
        val targetUser = mp(users[targetUid])
        AlertDialog(onDismissRequest = { banDlg = null }, containerColor = DialogC,
            title = { Text("Ban ${str(targetUser["username"])}") },
            text = { Column {
                Text("The player sees this reason in a popup with a Customer Support button.", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(banReasonText, { banReasonText = it.take(300) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Ban reason") }, minLines = 2)
            } },
            confirmButton = { TextButton({ if (banReasonText.isBlank()) ctx.toast("Write the ban reason") else scope.launch { ctx.toast(AR.setBan(targetUid, true, banReasonText)); banDlg = null; sel = null } }) { Text("BAN") } },
            dismissButton = { TextButton({ banDlg = null }) { Text("CANCEL") } })
    }
    themeDlg?.let { targetUid ->
        val targetUser = mp(users[targetUid])
        AlertDialog(onDismissRequest = { themeDlg = null }, containerColor = DialogC,
            title = { Text("Player theme: ${str(targetUser["username"])}") },
            text = { SelectField("Theme", themeValue, PlayerThemeChoices.perUserChoices) { themeValue = it } },
            confirmButton = { TextButton({ scope.launch { ctx.toast(AR.setPlayerTheme(targetUid, themeValue)); themeDlg = null; sel = null } }) { Text("SAVE") } },
            dismissButton = { TextButton({ themeDlg = null }) { Text("CANCEL") } })
    }
}

// ---------------- Settings ----------------
private val SET = listOf(
    F("playerTheme", "Default player theme", "select", PlayerThemeChoices.choices, PlayerThemeChoices.defaultId),
    F("imageStorage", "Image storage", "select", listOf("auto" to "Auto (imgbb, then Firebase, then Railway backend)", "imgbb" to "imgbb only", "firebase" to "Firebase only", "backend" to "Railway backend only"), "auto"),
    F("upiId", "Manual deposit: UPI ID"), F("upiName", "Manual deposit: payee name (shown in the UPI app)"), F("upiQr", "Manual deposit: QR code image", "image"),
    F("homeWhatsappUrl", "Home: WhatsApp button link (https://wa.me/... or a channel link)"), F("homeYoutubeUrl", "Home: YouTube button link"),
    F("minDeposit", "Minimum deposit ₹"), F("maxDeposit", "Maximum deposit ₹", def = "50000"),
    F("addMoneyStyle", "Add Coins page design (what players see)", "select", listOf("new" to "New design (default)", "old" to "Old design"), "new"),
    F("walletStyle", "My Wallet page design (what players see)", "select", listOf("new" to "New design (default)", "old" to "Old design"), "new"),
    F("depositChipsEnabled", "Add Coins page: show \"Select amount\" quick amount chips", "select", listOf("true" to "Enabled", "false" to "Disabled"), "false"), F("depositChips", "Deposit quick amounts (comma separated)", def = "10,20,50,100,200,500,1000"), F("depositMethodName", "Deposit payment method name (shown to player)", def = "ZapUPI"),
    F("minWithdraw", "Minimum withdraw ₹", def = "50"), F("maxWithdraw", "Maximum withdraw ₹", def = "10000"),
    F("wdUpiEnabled", "Withdraw: UPI ID", "select", listOf("true" to "Enabled", "false" to "Disabled"), "true"), F("wdUpiTitle", "Withdraw: UPI ID - title", def = "UPI ID"), F("wdUpiFee", "Withdraw: UPI ID - commission %", def = "0"), F("wdUpiHint", "Withdraw: UPI ID - input label", def = "UPI Id"),
    F("wdGpEnabled", "Withdraw: Google Play Redeem Code", "select", listOf("true" to "Enabled", "false" to "Disabled"), "true"), F("wdGpTitle", "Withdraw: Google Play - title", def = "Google Play Redeem Code"), F("wdGpFee", "Withdraw: Google Play - commission %", def = "20"), F("wdGpHint", "Withdraw: Google Play - input label", def = "Gmail ID"),
    F("wdFpEnabled", "Withdraw: Fampay", "select", listOf("true" to "Enabled", "false" to "Disabled"), "true"), F("wdFpTitle", "Withdraw: Fampay - title", def = "Fampay"), F("wdFpFee", "Withdraw: Fampay - commission %", def = "0"), F("wdFpHint", "Withdraw: Fampay - input label", def = "Fampay UPI Id"),
    F("walletNote", "Wallet page note (under balances)", def = "Fast Withdrawals, Processed Within 24 Hours!"), F("withdrawNote", "Withdraw page bottom note", def = "You can only withdraw win money."),
    F("zapupiDescription", "Default ZapUPI order remark (sent to provider)", "textarea"),
    F("referralBonus", "Referral bonus (coins, paid on first deposit)"),
    F("welcomeBg", "Welcome page BACKGROUND image (upload here; stays fixed behind the pictures. Leave empty = white)", "image"),
    F("homeQuickOn", "Quick buttons row on Home (Support / WhatsApp / YouTube / My Wallet)", "select", listOf("false" to "Hide (default)", "true" to "Show"), "false"),
    F("splashVideoEnabled", "Start screen: play a VIDEO instead of the image + logo (the video must be uploaded below; players see it from their next app start)", "select", listOf("false" to "Disabled", "true" to "Enabled"), "false"),
    F("splashVideo", "Start screen video (MP4, max 30 MB; shorter / lighter videos open faster for players; players get a SKIP button in the top corner)", "video"),
    F("splashImageEnabled", "Start screen: show the full-screen image", "select", listOf("true" to "Enabled", "false" to "Disabled"), "true"),
    F("splashLogoEnabled", "Start screen: show the round spinning logo", "select", listOf("true" to "Enabled", "false" to "Disabled"), "true"),
    F("logoUrl", "App logo / avatar image", "image"), F("splashImage", "Loading page: full-screen image (shown behind the round spinning logo)", "image"), F("loadingLogo", "Center loading spinner logo", "image"), F("whatsappUrl", "WhatsApp channel link (Earn tab)"), F("ffImage", "Free Fire card image", "image"), F("privateImage", "Private card image", "image"),
    F("shareText", "Share App text", "textarea"),
    F("supportUrl", "Support link (header headset icon)"), F("supportAddress", "Support: Address"), F("supportEmail", "Support: Email"),
    F("supportWhatsapp", "Support: WhatsApp number or link"), F("supportPhone", "Support: Phone number"),
    F("supportInstagramName", "Support page: Instagram name (the text players see)"), F("supportInstagram", "Support page: Instagram link or username (opens when tapped)"),
    F("supportTelegramName", "Support page: Telegram name (the text players see)"), F("supportTelegram", "Support page: Telegram link or username (opens when tapped)"),
    F("supportWhatsappName", "Support page: WhatsApp name (the text players see)"), F("supportStreet", "Support: Street"), F("supportTime", "Support: Hours"),
    F("globeLink", "Header globe icon link (opens when the player taps the globe icon)"),
    F("homeInstagramUrl", "Home share section: Instagram (link or username)"), F("homeTelegramUrl", "Home share section: Telegram (link or username)"),
    F("tutorialUrl", "App tutorial video link (YouTube) - opens from the player app Tutorial menu"),
    F("page_about", "About us", "textarea"), F("page_terms", "Terms and conditions", "textarea"), F("page_rewards", "Rewards info", "textarea"))

@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val sN by rflow(init = null as Map<String, Any?>?) { AR.watch("settings") }
    val vals = remember { mutableStateMapOf<String, String>() }
    var loaded by remember { mutableStateOf(false) }
    val s = sN ?: return Column(Modifier.fillMaxSize()) { ABar("Settings", nav); Loader() }
    if (!loaded) { SET.forEach { vals[it.k] = (if (it.k == "playerTheme") PlayerThemeChoices.canonical(str(s[it.k])) else str(s[it.k])).ifBlank { it.def } }; loaded = true }
    Column(Modifier.fillMaxSize()) {
        ABar("App settings", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            val cfgN by rflow(init = null as Map<String, Any?>?) { AR.watch("appConfig") }
            val updOn = cfgN?.get("updateOn") != false
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (updOn) "App Update page: ENABLED" else "App Update page: DISABLED", fontWeight = FontWeight.Bold, color = if (updOn) Green else Color.Gray)
                    Text("Off = players never see the update page or popup. On = the versions set in App control decide.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(updOn, { on -> scope.launch { ctx.toast(AR.saveAppConfig(mapOf("updateOn" to on)).let { if (it == "Saved") (if (on) "Update page ENABLED" else "Update page DISABLED") else it }) } })
            }
            SET.forEach { f ->
                if (f.k == "loadingLogo") Text("Used in the center of both apps while loading. A saved remote logo overrides the bundled logo. Remove the remote image to use the built-in spinner logo, or upload a new image here. Upload needs settings and image-upload access.", color = Color.Gray, fontSize = 12.sp)
                if (f.t == "video") VideoField(f.l, vals[f.k] ?: "", "settings/${f.k}") { vals[f.k] = it }
                else if (f.t == "image") ImageField(f.l, vals[f.k] ?: "", when (f.k) { "splashImage" -> 1920; "loadingLogo" -> 640; "developerLogo" -> 512; else -> 1280 }, "settings", "", "settings/${f.k}") { vals[f.k] = it }
                else if (f.t == "select") SelectField(f.l, vals[f.k] ?: f.def, f.o) { vals[f.k] = it }
                else OutlinedTextField(vals[f.k] ?: "", { vals[f.k] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(f.l) },
                    singleLine = f.t != "textarea", minLines = if (f.t == "textarea") 4 else 1)
            }
            if (Session.role == "admin") {
                var imgKey by remember { mutableStateOf("") }
                Text("imgbb API key (for image uploads)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                Text("Leave empty to use the built-in key. Get a free key from api.imgbb.com.", color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(imgKey, { imgKey = it.trim() }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("imgbb API key") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ABtn("SAVE IMGBB KEY", color = Green, small = true) { scope.launch { ctx.toast(AR.setImgbbKey(imgKey)); imgKey = "" } }
                    ABtn("USE BUILT-IN KEY", color = Field, small = true) { scope.launch { ctx.toast(AR.setImgbbKey("")) } }
                }
            }
            if (Session.role == "admin") {
                var keyIn by remember { mutableStateOf("") }
                var testMsg by remember { mutableStateOf("") }
                val keySet = str(s["zapupiKeySet"]) == "true"
                val enabled = str(s["zapupiEnabled"]).equals("true", ignoreCase = true)
                Text("PAYMENT GATEWAY (ZapUPI)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                Text("1) Paste your gateway key and SAVE KEY.  2) Turn ENABLE on.  That is all - no server address or Railway setting is needed here. While it is off, players pay by manual UPI.",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(keyIn, { keyIn = it.trim() }, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    label = { Text(if (keySet) "Key saved (hidden) - paste a new key to replace" else "ZapUPI API key") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ABtn("SAVE KEY", color = Green, small = true) { if (keyIn.isBlank()) ctx.toast("Paste the key first") else scope.launch { ctx.toast(AR.setZapupiKey(keyIn)); keyIn = "" } }
                    if (keySet) ABtn("REMOVE KEY", color = Color(0xFF7A1F1F), small = true) { scope.launch { ctx.toast(AR.setZapupiKey("")); ctx.toast(AR.setZapupiEnabled(false)) } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (enabled) "ENABLED - players can pay online" else "DISABLED - players use manual UPI", Modifier.weight(1f), fontWeight = FontWeight.Bold,
                        color = if (enabled) Green else Color.Gray)
                    Switch(checked = enabled, onCheckedChange = { on ->
                        if (on && !keySet) ctx.toast("Save the key first") else scope.launch { ctx.toast(AR.setZapupiEnabled(on)) }
                    })
                }
                ABtn("TEST PAYMENT SETUP", color = Field, small = true) { testMsg = "Testing..."; scope.launch { testMsg = AR.zapupiTest() } }
                if (testMsg.isNotBlank()) Text(testMsg, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (canAccess("ranking_reset")) ABtn("RESET LEADERBOARD", color = Field, small = true) { scope.launch { ctx.toast(AR.resetLB()) } }
        }
        ABtn("SAVE SETTINGS", Modifier.fillMaxWidth().padding(14.dp), Green) {
            scope.launch {
                try { AR.db.child("settings").updateChildren(vals.toMap<String, Any?>()).await(); AR.logAct("settings_updated", "settings", "default player theme=${vals["playerTheme"]}"); ctx.toast("Settings saved") }
                catch (e: Exception) { ctx.toast(e.message ?: "Failed") }
            }
        }
    }
}

// ---------------- Send notification ----------------
@Composable
fun NotifyScreen(nav: NavController, presetMatch: String, presetUser: String) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }; var body by remember { mutableStateOf("") }; var image by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(if (presetMatch.isNotBlank()) "match" else if (presetUser.isNotBlank()) "user" else "all") }
    var matchKey by remember { mutableStateOf(presetMatch) }; var uname by remember { mutableStateOf(presetUser) }
    var busy by remember { mutableStateOf(false) }
    var voiceFile by remember { mutableStateOf<java.io.File?>(null) }; var voiceMs by remember { mutableStateOf(0L) }
    val voicePlayer = rememberVoicePlayer()
    LaunchedEffect(Unit) { VoiceStore.purgeExpired() }
    var hasSa by remember { mutableStateOf(Fcm.sa(ctx).isNotBlank()) }
    val matches by rflow(init = emptyMap<String, Any?>()) { AR.watch("matches") }
    val pickSa = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            val txt = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            val j = org.json.JSONObject(txt); j.getString("private_key"); j.getString("client_email"); j.getString("project_id")
            Fcm.setSa(ctx, txt); hasSa = true; ctx.toast("Key saved on this phone")
        } catch (e: Exception) { ctx.toast("That is not a service account JSON file") }
    }
    Column(Modifier.fillMaxSize()) {
        ABar("Send notification", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            SelectField("Send to", target, listOf("all" to "All players", "match" to "Players of one match", "user" to "One player")) { target = it }
            if (target == "match") SelectField("Match", matchKey, matches.entries.sortedByDescending { num(mp(it.value)["startTime"]) }.map {
                val m = mp(it.value); it.key to "${str(m["title"])} #${str(m["matchNo"])}  (ID ${str(m["matchId"]).ifBlank { "-" }})" }) { matchKey = it }
            if (target == "user") OutlinedTextField(uname, { uname = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Player username") }, singleLine = true)
            VoiceRecorderCard(voiceFile, voiceMs, voicePlayer) { f, ms -> voiceFile = f; voiceMs = ms }
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(if (voiceFile == null) "Title (required)" else "Title (optional with a voice message)") }, singleLine = true)
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Message (optional)") }, minLines = 3)
            ImageField("Image (optional)", image, 1280, "notification") { image = it }
            if (image.isNotBlank()) ABtn("REMOVE PHOTO", color = Color(0xFF7A1F1F), small = true) { image = "" }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Text(if (hasSa) "Push key is saved on this phone." else "Push key not added. Add it to send notifications.", fontSize = 13.sp, color = if (hasSa) Color(0xFF2ECC71) else Orange)
                if (Session.role == "admin") ABtn(if (hasSa) "CHANGE SERVICE ACCOUNT FILE" else "SELECT SERVICE ACCOUNT FILE (.json)", Modifier.padding(top = 8.dp), Field, true) { pickSa.launch("*/*") }
                else Text("Only an admin can change the push service-account file.", Modifier.padding(top = 6.dp), fontSize = 11.sp, color = Color.Gray)
            }
        }
        ABtn(if (busy) "SENDING..." else "SEND", Modifier.fillMaxWidth().padding(14.dp), Green, enabled = !busy) {
            val vf = voiceFile
            if (title.isBlank() && vf == null) { ctx.toast("Title is required"); return@ABtn }
            if (target == "match" && matchKey.isBlank()) { ctx.toast("Choose a match"); return@ABtn }
            if (target == "user" && uname.isBlank()) { ctx.toast("Enter a username"); return@ABtn }
            scope.launch {
                busy = true
                try {
                    // voice message: saved in Firebase first (players listen to it again in Account -> Voice messages), then the push is sent
                    var voiceId = ""
                    var toUid = ""
                    if (vf != null) {
                        if (target == "user") {
                            toUid = str(AR.get("referralCodes/${uname.trim().lowercase()}"))
                            if (toUid.isBlank()) { ctx.toast("No player with that username"); busy = false; return@launch }
                        }
                        voicePlayer.stop()
                        voiceId = VoiceStore.upload(vf, voiceMs, title, body, target, if (target == "match") matchKey else "", toUid, if (target == "user") uname.trim().lowercase() else "")
                    }
                    var pushMsg = "Push not sent (no key)."
                    if (hasSa) {
                        val pTitle = if (vf != null) title.trim().ifBlank { "DOUx24" } else title
                        val pBody = if (vf != null) "Voice message" else body
                        val extra = if (vf != null) mapOf("type" to "voice", "vid" to voiceId) else emptyMap()
                        val err = when (target) {
                            "all" -> Fcm.send(ctx, "all", true, pTitle, pBody, BackendConfig.resolve(image), extra)
                            "match" -> Fcm.send(ctx, "m_$matchKey", true, pTitle, pBody, BackendConfig.resolve(image), extra)
                            else -> {
                                val uid = if (toUid.isNotBlank()) toUid else str(AR.get("referralCodes/${uname.trim().lowercase()}"))
                                val tk = if (uid.isBlank()) "" else str(AR.get("users/$uid/fcmToken"))
                                if (tk.isBlank()) "That player has not opened the app with notifications yet" else Fcm.send(ctx, tk, false, pTitle, pBody, BackendConfig.resolve(image), extra)
                            }
                        }
                        pushMsg = err ?: "Push sent."
                    }
                    if (vf != null) {
                        vf.delete(); voiceFile = null; voiceMs = 0L
                        VoiceStore.purgeExpired()
                        ctx.toast("Voice message saved. $pushMsg")
                    } else ctx.toast(pushMsg)
                } catch (e: Exception) { ctx.toast(e.message ?: "Failed") }
                busy = false
            }
        }
    }
}

// ---------------- Dashboard ----------------
@Composable
fun DashboardScreen(nav: NavController) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { data = null; try { data = AR.dashboard() } catch (e: Exception) { ctx.toast(e.message ?: "Could not load"); data = emptyList() } }
    Column(Modifier.fillMaxSize()) {
        ABar("Dashboard", nav) { ABtn("REFRESH", color = Field, small = true) { tick++ } }
        val d = data ?: return@Column Loader()
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(d) { (label, value) ->
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(CardC).padding(14.dp).fillMaxWidth()) {
                    Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(label, color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------- Activity log ----------------
@Composable
fun LogsScreen(nav: NavController) {
    val lN by rflow(init = null as Map<String, Any?>?) { AR.watchQ(AR.db.child("adminLogs").limitToLast(200)) }
    val l = lN ?: return Column(Modifier.fillMaxSize()) { ABar("Activity log", nav); Loader() }
    val rows = l.values.map { mp(it) }.sortedByDescending { num(it["ts"]) }
    Column(Modifier.fillMaxSize()) {
        ABar("Activity log", nav)
        if (rows.isEmpty()) Text("No activity yet.", Modifier.padding(30.dp), color = Color.Gray)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows) { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(10.dp)) {
                    Text(str(r["action"]).replace("_", " "), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (str(r["detail"]).isNotBlank()) Text(str(r["detail"]), fontSize = 12.sp)
                    Text("${str(r["by"])}  •  ${fdate(r["ts"])}", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---------------- One player's transactions ----------------
@Composable
fun UserTxScreen(nav: NavController, uid: String) {
    val tN by rflow(uid, init = null as Map<String, Any?>?) { AR.watchQ(AR.db.child("transactions/$uid").orderByChild("ts").limitToLast(200)) }
    val t = tN ?: return Column(Modifier.fillMaxSize()) { ABar("Transactions", nav); Loader() }
    val rows = t.values.map { mp(it) }.sortedByDescending { num(it["ts"]) }
    Column(Modifier.fillMaxSize()) {
        ABar("Player transactions", nav)
        if (rows.isEmpty()) Text("No transactions.", Modifier.padding(30.dp), color = Color.Gray)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows) { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(str(r["note"]), fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text("${fdate(r["ts"])}  •  ${str(r["status"])}", color = Color.Gray, fontSize = 11.sp) }
                    Text((if (num(r["amount"]) >= 0) "+" else "") + money(num(r["amount"])), color = if (num(r["amount"]) >= 0) Color(0xFF2ECC71) else Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------- Maintenance + app version control + popup ----------------
@Suppress("DEPRECATION")
private fun packageVersionCode(p: android.content.pm.PackageInfo): Long = if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

@Composable
fun AppControlScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val cN by rflow(init = null as Map<String, Any?>?) { AR.watch("appConfig") }
    val vals = remember { mutableStateMapOf<String, String>() }
    var maint by remember { mutableStateOf(false) }
    var updateOn by remember { mutableStateOf(true) }
    var popupOn by remember { mutableStateOf(false) }
    var popupSupport by remember { mutableStateOf(true) }
    var popupAction by remember { mutableStateOf("none") }
    var popupFreq by remember { mutableStateOf("every_open") }
    var loaded by remember { mutableStateOf(false) }
    var confirmOn by remember { mutableStateOf(false) }
    val c = cN ?: return Column(Modifier.fillMaxSize()) { ABar("App control", nav); Loader() }
    if (!loaded) {
        // Everything that is already live is filled in, so you only edit what changed and press SAVE.
        listOf("maintenanceMsg", "latestCode", "latestName", "minCode", "updateUrl", "updateNotes", "popupTitle", "popupMsg", "popupImage", "popupButton", "popupLink").forEach { vals[it] = str(c[it]) }
        popupAction = str(c["popupAction"]).ifBlank { "none" }; popupFreq = str(c["popupFreq"]).ifBlank { "every_open" }
        // First time (nothing saved yet): suggest this build's own version so you only have to bump it.
        val own = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (_: Exception) { null }
        if (vals["latestCode"].isNullOrBlank() && own != null) vals["latestCode"] = packageVersionCode(own).toString()
        if (vals["latestName"].isNullOrBlank() && own != null) vals["latestName"] = own.versionName ?: ""
        updateOn = c["updateOn"] != false; maint = c["maintenance"] == true; popupOn = c["popupOn"] == true; popupSupport = c["popupSupport"] != false; loaded = true
    }
    fun updateMap() = hashMapOf<String, Any?>("updateOn" to updateOn, "maintenance" to maint, "maintenanceMsg" to (vals["maintenanceMsg"] ?: "").trim(),
        "latestCode" to ((vals["latestCode"] ?: "").toLongOrNull() ?: 0L), "minCode" to ((vals["minCode"] ?: "").toLongOrNull() ?: 0L),
        "latestName" to (vals["latestName"] ?: "").trim(), "updateUrl" to (vals["updateUrl"] ?: "").trim(), "updateNotes" to (vals["updateNotes"] ?: "").trim())
    fun setMaint(on: Boolean) { maint = on; scope.launch { ctx.toast(AR.saveAppConfig(updateMap()).let { if (it == "Saved") (if (on) "Maintenance is ON. Players are blocked." else "Maintenance is OFF.") else it }) } }
    fun popupMap(on: Boolean, fresh: Boolean) = hashMapOf<String, Any?>("popupOn" to on, "popupSupport" to popupSupport,
        "popupTitle" to (vals["popupTitle"] ?: "").trim(), "popupMsg" to (vals["popupMsg"] ?: "").trim(),
        "popupImage" to (vals["popupImage"] ?: "").trim(), "popupButton" to (vals["popupButton"] ?: "").trim(), "popupAction" to popupAction,
        "popupLink" to (vals["popupLink"] ?: "").trim(), "popupFreq" to popupFreq).also { if (fresh) it["popupId"] = System.currentTimeMillis().toString() }
    Column(Modifier.fillMaxSize()) {
        ABar("App control", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Text("CURRENTLY LIVE", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Latest: ${str(c["latestName"]).ifBlank { "-" }} (code ${str(c["latestCode"]).ifBlank { "0" }})   •   Minimum code: ${str(c["minCode"]).ifBlank { "0" }}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Update page: ${if (c["updateOn"] != false) "ON" else "OFF"}   •   Maintenance: ${if (c["maintenance"] == true) "ON" else "OFF"}   •   Popup: ${if (c["popupOn"] == true) "ON" else "OFF"}", fontSize = 13.sp, color = Color.Gray)
            }
            Text("MAINTENANCE MODE", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Block the player app", fontWeight = FontWeight.SemiBold); Text("Players see your message instead of the app. Takes effect at once.", fontSize = 12.sp, color = Color.Gray) }
                Switch(maint, { if (it) confirmOn = true else setMaint(false) })
            }
            OutlinedTextField(vals["maintenanceMsg"] ?: "", { vals["maintenanceMsg"] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Message shown to players") }, minLines = 2)
            Text("APP UPDATE", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (updateOn) "App Update page: ENABLED" else "App Update page: DISABLED", fontWeight = FontWeight.SemiBold, color = if (updateOn) Green else Color.Gray)
                    Text("Off = players never see the update page or popup (even for older versions). On = the rules below apply.", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(updateOn, { on -> updateOn = on; scope.launch { ctx.toast(AR.saveAppConfig(updateMap()).let { if (it == "Saved") (if (on) "Update page ENABLED" else "Update page DISABLED") else it }) } })
            }
            Text("The boxes below are already filled with what is live now. Change only what is new (version code = versionCode in app/build.gradle.kts), then SAVE UPDATE. Latest = optional popup (Later / Update). Minimum = blocks older versions until they update.", fontSize = 12.sp, color = Color.Gray)
            OutlinedTextField(vals["latestCode"] ?: "", { vals["latestCode"] = it.filter(Char::isDigit) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Latest version code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(vals["latestName"] ?: "", { vals["latestName"] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Latest version name (e.g. 1.2)") }, singleLine = true)
            OutlinedTextField(vals["minCode"] ?: "", { vals["minCode"] = it.filter(Char::isDigit) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Minimum version code (force update below this)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(vals["updateUrl"] ?: "", { vals["updateUrl"] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Update link - direct APK download link (the app downloads + installs it inside the app; Drive/Dropbox share links work too)") }, singleLine = true)
            OutlinedTextField(vals["updateNotes"] ?: "", { vals["updateNotes"] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("What is new (shown on the App Update page, one point per line)") }, minLines = 3)
            ABtn("SAVE UPDATE", Modifier.fillMaxWidth().padding(top = 6.dp), Green) {
                val m = updateMap()
                if ((m["minCode"] as Long) > (m["latestCode"] as Long) && (m["latestCode"] as Long) > 0) { ctx.toast("Minimum version cannot be higher than the latest version"); return@ABtn }
                scope.launch { ctx.toast(AR.saveAppConfig(m)) }
            }
            Text("POPUP FOR PLAYERS", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
            Text("Send a popup whenever you want. Players see it once, in the middle of the app, even if the app is already open.", fontSize = 12.sp, color = Color.Gray)
            ImageField(label = "Popup image (optional - upload from your phone)", value = vals["popupImage"] ?: "", maxSide = 1280, type = "settings", refId = "popup") { vals["popupImage"] = it }
            OutlinedTextField(vals["popupTitle"] ?: "", { vals["popupTitle"] = it.take(60) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Title (e.g. Today's Offer Only)") }, singleLine = true)
            OutlinedTextField(vals["popupMsg"] ?: "", { vals["popupMsg"] = it.take(500) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Message") }, minLines = 3)
            OutlinedTextField(vals["popupButton"] ?: "", { vals["popupButton"] = it.take(30) }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Button text (e.g. DEPOSIT NOW!)") }, singleLine = true)
            SelectField("When the player taps the button", popupAction, listOf("deposit" to "Open Add Money", "wallet" to "Open Wallet", "support" to "Open Customer Support", "link" to "Open a link", "none" to "Just close the popup")) { popupAction = it }
            if (popupAction == "link") OutlinedTextField(vals["popupLink"] ?: "", { vals["popupLink"] = it.trim() }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text("Link (https://...)") }, singleLine = true)
            SelectField("How often to show", popupFreq, listOf("once" to "Once (until you send a new popup)", "daily" to "Again every day until they tap the button", "every_open" to "Every time the app is opened")) { popupFreq = it }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ABtn("SEND POPUP NOW", Modifier.weight(1f), Purple) {
                    if ((vals["popupMsg"] ?: "").isBlank() && (vals["popupImage"] ?: "").isBlank() && (vals["popupTitle"] ?: "").isBlank()) { ctx.toast("Add an image, a title or a message"); return@ABtn }
                    popupOn = true; scope.launch { ctx.toast(AR.saveAppConfig(popupMap(true, true)).let { if (it == "Saved") "Popup sent to players" else it }) }
                }
                ABtn("TURN OFF", Modifier.weight(1f), Field) { popupOn = false; scope.launch { ctx.toast(AR.saveAppConfig(popupMap(false, false)).let { if (it == "Saved") "Popup turned off" else it }) } }
            }
            Text("Ban reason popup: when you ban a player (Players -> BAN PLAYER) you write the reason; that player sees it with a Customer Support button.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 14.dp, bottom = 20.dp))
        }
    }
    if (confirmOn) ConfirmDialog("Turn maintenance ON? Every player will be blocked right now.", { confirmOn = false }) { confirmOn = false; setMaint(true) }
}

// ---------------- Leaderboard control & player-app theme editor ----------------
@Composable
fun LeaderboardAdminScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by rflow(init = emptyMap<String, Any?>()) { AR.watch("leaderboardConfig") }
    val boardN by rflow(init = null as Map<String, Any?>?) { AR.watch("leaderboardCycle") }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editValue by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val autoState = config["auto"]
    var auto by remember(autoState) { mutableStateOf(autoState != false) }
    val board = boardN ?: return Column(Modifier.fillMaxSize()) { ABar("Leaderboard", nav); Loader() }
    val totals = linkedMapOf<String, MutableMap<String, Any?>>()
    board.values.forEach { cycle -> mp(cycle).forEach { (uid, raw) ->
        val row = mp(raw); val total = totals.getOrPut(uid) { mutableMapOf("username" to str(row["username"]), "score" to 0.0) }
        if (str(row["username"]).isNotBlank()) total["username"] = str(row["username"])
        total["score"] = num(total["score"]) + num(row["score"])
    } }
    val entries = totals.entries.filter { num(it.value["score"]) > 0 }.sortedByDescending { num(it.value["score"]) }

    Column(Modifier.fillMaxSize()) {
        ABar("Leaderboard", nav)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto update", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Match prizes add to the board when results are declared.", fontSize = 11.sp, color = Color.Gray)
            }
            Switch(checked = auto, enabled = canAccess("leaderboard_manage"), onCheckedChange = { enabled ->
                auto = enabled
                scope.launch { val r = AR.setLeaderboardAuto(enabled); ctx.toast(r); if (!r.startsWith("Automatic leaderboard updates")) auto = config["auto"] != false }
            })
        }
        // How many players each game's Top Players list shows (player app). Default 10.
        val savedTopN = (config["topN"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 10
        var topNText by remember(savedTopN) { mutableStateOf(savedTopN.toString()) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Players per game", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("How many top players each game shows (1-100, default 10).", fontSize = 11.sp, color = Color.Gray)
            }
            OutlinedTextField(topNText, { topNText = it.filter(Char::isDigit).take(3) }, Modifier.width(78.dp), singleLine = true, enabled = canAccess("leaderboard_manage"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.width(8.dp))
            ABtn("SAVE", color = Purple, small = true) {
                val n = topNText.toIntOrNull()
                if (n == null) ctx.toast("Enter a number from 1 to 100") else scope.launch { ctx.toast(AR.setLeaderboardTopN(n)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TOP 20 (does not reset automatically)", Modifier.weight(1f), fontSize = 11.sp, color = Color.Gray)
            if (canAccess("ranking_reset") || canAccess("leaderboard_manage")) ABtn("RESET NOW", color = Field, small = true) { confirmReset = true }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            itemsIndexed(entries, key = { _, e -> e.key }) { i, entry ->
                val item = mp(entry.value)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(CardC).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(26.dp), fontSize = 12.sp, color = Color.Gray)
                    Text(str(item["username"]).ifBlank { "Player ${entry.key.take(7)}" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                    Text(money(num(item["score"])), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (canAccess("leaderboard_manage")) {
                        Spacer(Modifier.width(10.dp))
                        ABtn("EDIT", color = Purple, small = true) { editing = entry.key to str(item["username"]); editValue = money(num(item["score"])) }
                    }
                }
            }
            if (entries.isEmpty()) item { Text("No winners yet.", Modifier.fillMaxWidth().padding(24.dp), color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }

    editing?.let { target ->
        AlertDialog(onDismissRequest = { editing = null }, containerColor = DialogC,
            title = { Text("Edit score") },
            text = { Column {
                Text(target.second.ifBlank { target.first }, fontSize = 13.sp, color = Color.Gray)
                OutlinedTextField(editValue, { editValue = it }, label = { Text("Score") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Changes only the leaderboard, not the player's wallet.", fontSize = 11.sp, color = Color.Gray)
            } },
            confirmButton = { TextButton({
                val score = editValue.trim().toDoubleOrNull()
                if (score == null || !score.isFinite() || score < 0) ctx.toast("Enter a valid score")
                else scope.launch { val r = AR.setLeaderboardScore(target.first, score); ctx.toast(r); if (r == "Leaderboard score saved") editing = null }
            }) { Text("SAVE") } },
            dismissButton = { TextButton({ editing = null }) { Text("CANCEL") } })
    }
    if (confirmReset) {
        AlertDialog(onDismissRequest = { confirmReset = false }, containerColor = DialogC,
            title = { Text("Reset leaderboard?") },
            text = { Text("The complete leaderboard will be cleared. It will not reset automatically again until you press RESET NOW.") },
            confirmButton = { TextButton({ scope.launch { ctx.toast(AR.resetLB()); confirmReset = false } }) { Text("RESET") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("CANCEL") } })
    }
}

private data class ThemeColorOption(val key: String, val label: String, val default: String)
private val THEME_COLOR_OPTIONS = listOf(
    ThemeColorOption("themeBg", "App background", "#10002B"),
    ThemeColorOption("themeCard", "Cards / surfaces", "#1A0536"),
    ThemeColorOption("themeField", "Input fields", "#2E1763"),
    ThemeColorOption("themeAccent", "Primary accent / icons", "#673AD1"),
    ThemeColorOption("themeButton", "Main buttons", "#673AD1"),
    ThemeColorOption("themeMatch", "Match cards", "#241044"),
    ThemeColorOption("themeSupport", "Customer support", "#168E9B"),
    ThemeColorOption("themeSuccess", "Success / joined status", "#2ECC71"),
    ThemeColorOption("themeWarning", "Warning / highlight", "#E8731A"),
    ThemeColorOption("themeDanger", "Error / danger", "#F02D07"),
    ThemeColorOption("themeMain", "Match card + Account / Wallet cards + top bars (main card color)", "#190236")
)

private fun adminThemePreview(value: String, fallback: Color): Color = try {
    val raw = value.trim().let { if (it.startsWith("#")) it else "#$it" }
    Color(android.graphics.Color.parseColor(raw))
} catch (_: Exception) { fallback }

@Composable
fun ThemeEditorScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsN by rflow(init = null as Map<String, Any?>?) { AR.watch("settings") }
    val settings = settingsN ?: return Column(Modifier.fillMaxSize()) { ABar("Theme & colors", nav); Loader() }
    val drafts = remember { mutableStateMapOf<String, String>() }
    var selectedTheme by remember { mutableStateOf(PlayerThemeChoices.defaultId) }
    var initialized by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    if (!initialized) {
        THEME_COLOR_OPTIONS.forEach { drafts[it.key] = str(settings[it.key]).ifBlank { it.default } }
        selectedTheme = if (PlayerThemeChoices.forced) PlayerThemeChoices.defaultId else PlayerThemeChoices.canonical(str(settings["playerTheme"])).ifBlank { PlayerThemeChoices.defaultId }
        // A preset theme is selected (not Custom): show ITS colours in the hex fields (the saved hex values belong to the Custom theme).
        if (selectedTheme != "custom") ThemePalettes.colors(selectedTheme)?.forEach { (k, v) -> drafts[k] = v }
        initialized = true
    }
    // Picking a theme (gallery tile or the select box) fills every hex field with that theme's colours. "Main card colour" is independent of the theme, so it is not touched.
    fun pickTheme(id: String) {
        selectedTheme = id
        if (id != "custom") ThemePalettes.colors(id)?.forEach { (k, v) -> drafts[k] = v }
    }

    Column(Modifier.fillMaxSize()) {
        ABar("Theme & colors", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            if (PlayerThemeChoices.forced) Text("The player app uses LIQUID GLASS for everyone (set in the app). Press SAVE THEME to store it. Hex colours below are not used while this is on.", color = Green, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Text("THEME GALLERY - tap a theme, then press SAVE THEME. It changes the whole player app (login, maintenance, everything).", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerThemeChoices.choices.filter { it.first != "custom" }.forEach { (id, name) ->
                    val sw = PlayerThemeChoices.swatches[id] ?: Triple(0xFF10002B, 0xFF1A0536, 0xFF673AD1)
                    Column(Modifier.width(100.dp).clip(RoundedCornerShape(12.dp)).background(Color(sw.first))
                        .border(2.dp, if (selectedTheme == id) Green else Color(0xFF333333), RoundedCornerShape(12.dp)).clickable { pickTheme(id) }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(sw.second)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(sw.third)))
                        }
                        Text(name, Modifier.padding(top = 6.dp), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
            SelectField("Global player-app theme", selectedTheme, PlayerThemeChoices.choices) { pickTheme(it) }
            Text("Type any hex code below and the theme switches to Custom automatically - press SAVE THEME and the player app uses exactly those colors. Tapping a preset theme above goes back to presets. A player's own theme override still takes priority.",
                Modifier.padding(vertical = 8.dp), color = Color.Gray, fontSize = 12.sp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(adminThemePreview(drafts["themeBg"].orEmpty(), CardC)).padding(12.dp)) {
                Text("LIVE PREVIEW", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(adminThemePreview(drafts["themeMain"].orEmpty(), CardC)).padding(12.dp)) {
                        Text("Match card", fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(adminThemePreview(drafts["themeSupport"].orEmpty(), Purple)).padding(12.dp)) {
                        Text("Support", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(adminThemePreview(drafts["themeButton"].orEmpty(), Purple)).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Text("MAIN BUTTON", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            THEME_COLOR_OPTIONS.forEach { option ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(CardC).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(adminThemePreview(drafts[option.key].orEmpty(), Field)))
                    }
                    OutlinedTextField(drafts[option.key] ?: option.default, { input ->
                        drafts[option.key] = input.filter { it == '#' || it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(7).uppercase()
                        if (option.key != "themeMain") selectedTheme = "custom"   // typing a hex code = use these colours in the player app (main card colour is independent of the theme)
                    }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Hex color (e.g. #673AD1)") }, singleLine = true)
                }
            }
            Text("For readable contrast, use dark background/card colors; app text stays light.", Modifier.padding(top = 10.dp), fontSize = 11.sp, color = Color.Gray)
            TextButton(onClick = { THEME_COLOR_OPTIONS.forEach { drafts[it.key] = it.default }; selectedTheme = "custom" }) { Text("RESTORE RECOMMENDED COLORS") }
        }
        ABtn(if (saving) "SAVING..." else "SAVE THEME", Modifier.fillMaxWidth().padding(14.dp), Green, enabled = !saving) {
            val updates = hashMapOf<String, Any?>("settings/playerTheme" to (if (PlayerThemeChoices.forced) PlayerThemeChoices.defaultId else selectedTheme))
            var invalid = false
            THEME_COLOR_OPTIONS.forEach { option ->
                val hex = drafts[option.key].orEmpty().trim().removePrefix("#")
                if (!Regex("(?i)^[0-9a-f]{6}$").matches(hex)) invalid = true
                else updates["settings/${option.key}"] = "#${hex.uppercase()}"
            }
            if (invalid) ctx.toast("Enter valid six-digit hex colors for every field")
            else {
                saving = true
                scope.launch {
                    try {
                        AR.db.updateChildren(updates).await()
                        AR.logAct("theme_colors_updated", "settings", "default=${selectedTheme}; ${THEME_COLOR_OPTIONS.size} color values")
                        ctx.toast("Theme saved. Player apps update automatically.")
                    } catch (e: Exception) { ctx.toast(e.message ?: "Could not save theme") }
                    saving = false
                }
            }
        }
    }
}
