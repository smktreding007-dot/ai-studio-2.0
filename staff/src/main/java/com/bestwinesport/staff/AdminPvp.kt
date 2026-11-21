package com.bestwinesport.staff

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ---------------------------------------------------------------------------------------------------------------
// Player matches (created by players): the admin sees the screenshots, picks the winner and the coins go to his wallet.
// ---------------------------------------------------------------------------------------------------------------

/** "CS 1VS1 MATCH | #767" (admin title + running number). */
fun pvpLabel(m: Map<String, Any?>): String =
    str(m["title"]).ifBlank { str(m["gameName"]) } + " #" + (if (num(m["matchNo"]) > 0) str(m["matchNo"]) else str(m["matchCode"]))

private fun slotText(n: Double): String = if (n.toInt() == 2) "1 vs 1" else "${n.toInt()} players"

private fun pvpState(m: Map<String, Any?>): String = when {
    str(m["status"]) == "cancelled" -> "cancelled"
    m["resultDeclared"] == true || str(m["status"]) == "completed" -> "completed"
    mp(m["reports"]).isNotEmpty() -> "pending"
    str(m["status"]) == "ongoing" -> "ongoing"
    else -> "upcoming"
}

@Composable
fun PvpResultsScreen(nav: NavController) {
    val dataN by rflow(init = null as Map<String, Any?>?) { AR.watch("pvpMatches") }
    var filter by remember { mutableStateOf("pending") }
    var query by remember { mutableStateOf("") }
    var gameF by remember { mutableStateOf("") }
    val pvpGamesAll by rflow(init = emptyMap<String, Any?>()) { AR.watch("pvpGames") }
    androidx.activity.compose.BackHandler(gameF.isNotBlank()) { gameF = "" }
    val slideMod = Modifier.stepSlide(if (gameF.isNotBlank()) 1 else 0)
    val data = dataN ?: return Column(Modifier.fillMaxSize()) { ABar("Player matches", nav); Loader() }
    val all = data.entries.map { it.key to mp(it.value) }.sortedByDescending { num(it.second["createdAt"]) }
    val rows = all.filter { (_, m) ->
        val st = pvpState(m)
        val okF = when (filter) { "pending" -> st == "pending"; "open" -> st == "upcoming" || st == "ongoing"; "done" -> st == "completed" || st == "cancelled"; else -> true }
        okF && pvpGameAllowed(str(m["gameId"])) && (gameF.isBlank() || str(m["gameId"]) == gameF) && (query.isBlank() || (pvpLabel(m) + " " + str(m["creatorName"])).contains(query.trim(), true))
    }
    val pendingCount = all.count { pvpGameAllowed(str(it.second["gameId"])) && pvpState(it.second) == "pending" }
    val counter by rflow(init = 0.0) { kotlinx.coroutines.flow.flow { emit(num(AR.get("pvpCounter"))) } }
    var askNo by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (askNo) AskDialog("Next match number (current next: #${counter.toLong() + 1})", "Next number, example 767", true, { askNo = false }) { v ->
        askNo = false
        val n = v.toLongOrNull()
        if (n == null || n < 1) ctx.toast("Enter a number from 1")
        else scope.launch { try { AR.db.child("pvpCounter").setValue(n - 1).await(); ctx.toast("The next match will be #$n") } catch (e: Exception) { ctx.toast(e.message ?: "Failed") } }
    }
    Column(slideMod.fillMaxSize()) {
        ABar("Player matches" + if (pendingCount > 0) "  ($pendingCount pending)" else "", nav) {
            ABtn("NEXT NO", color = Field, small = true) { askNo = true }
        }
        if (gameF.isBlank()) { GameTiles(pvpGamesAll.onlyAllowedPvpGames(), data) { gameF = it }; return@Column }
        GameHeader(str(mp(pvpGamesAll[gameF])["name"]).ifBlank { "Player matches" }) { gameF = "" }
        FilterRow(filter, listOf("pending", "open", "done", "all")) { filter = it }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp, 8.dp, 12.dp, 0.dp), label = { Text("Search (game, match code, creator)") }, singleLine = true)
        if (rows.isEmpty()) Text("Nothing here.", Modifier.padding(30.dp), color = Color.Gray)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.first }) { (id, m) ->
                val st = pvpState(m)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(str(m["gameName"]), color = Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${pvpLabel(m)} • ${slotText(num(m["slots"]))}", fontWeight = FontWeight.SemiBold)
                            Text("By ${str(m["creatorName"])} • entry ₹${money(num(m["entryFee"]))} • winning ₹${money(num(m["prize"]))}", color = Color.Gray, fontSize = 12.sp)
                            Text("${mp(m["players"]).size}/${str(m["slots"])} players • ${mp(m["reports"]).size} screenshot(s) • ${fdate(m["startTime"])}", color = Color.Gray, fontSize = 12.sp)
                        }
                        Pill(if (st == "pending") "pending" else st)
                    }
                    ABtn(if (st == "completed") "VIEW RESULT" else "OPEN / DECLARE WINNER", Modifier.fillMaxWidth().padding(top = 8.dp), Green, true) { nav.go("pvpresult/$id") }
                }
            }
        }
    }
}

@Composable
private fun AdminPvpShot(mid: String, uid: String) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, mid, uid) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val v = AR.get("pvpShots/$mid/$uid")?.toString().orEmpty()
                val bytes = android.util.Base64.decode(v.substringAfter("base64,", v), android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }
    var big by remember { mutableStateOf(false) }
    val b = bmp
    if (b == null) Text("Loading screenshot...", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    else {
        Image(b, null, Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { big = true }, contentScale = ContentScale.Fit)
        if (big) androidx.compose.ui.window.Dialog(onDismissRequest = { big = false }) {
            Image(b, null, Modifier.fillMaxWidth().clickable { big = false }, contentScale = ContentScale.Fit)
        }
    }
}

@Composable
fun PvpResultScreen(nav: NavController, mid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mN by rflow(mid, init = null as Map<String, Any?>?) { AR.watch("pvpMatches/$mid") }
    val roomN by rflow(mid, init = emptyMap<String, Any?>()) { AR.watch("pvpRooms/$mid") }
    var winnerAsk by remember { mutableStateOf<Pair<String, String>?>(null) }
    var cancelAsk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val m = mN ?: return Column(Modifier.fillMaxSize()) { ABar("Player match", nav); Loader() }
    if (!pvpGameAllowed(str(m["gameId"]))) return Column(Modifier.fillMaxSize()) { ABar("Player match", nav); Text("This match belongs to a game that is not given to you.", Modifier.padding(24.dp), color = androidx.compose.ui.graphics.Color.Gray) }
    val players = mp(m["players"]).entries.map { mp(it.value) }.sortedBy { num(it["ts"]) }
    val reports = mp(m["reports"])
    val done = m["resultDeclared"] == true
    val cancelled = str(m["status"]) == "cancelled"
    val prize = AR.pvpPrize(num(m["entryFee"]), players.size, num(m["commission"]))
    winnerAsk?.let { (uid, name) ->
        ConfirmDialog("Make $name the winner?\n₹${money(prize)} will be added to ${name}'s winning wallet. This cannot be undone.", { winnerAsk = null }) {
            winnerAsk = null; busy = true
            scope.launch { ctx.toast(AR.declarePvp(mid, uid)); busy = false }
        }
    }
    if (cancelAsk) ConfirmDialog("Cancel this match and refund every player's entry fee?", { cancelAsk = false }) {
        cancelAsk = false; busy = true
        scope.launch { ctx.toast(AR.cancelPvp(mid)); busy = false }
    }
    Column(Modifier.fillMaxSize()) {
        ABar(pvpLabel(m), nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${slotText(num(m["slots"]))} • by ${str(m["creatorName"])}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Pill(pvpState(m))
                }
                Text("Entry ₹${money(num(m["entryFee"]))} • Winning ₹${money(prize)} (${players.size} players)", fontSize = 13.sp)
                if (num(m["commission"]) > 0) Text("Commission ${money(num(m["commission"]))}% set for this game", color = Color.Gray, fontSize = 12.sp)
                Text("Time: ${fdate(m["startTime"])}", color = Color.Gray, fontSize = 12.sp)
                if (str(m["note"]).isNotBlank()) Text("Note: ${str(m["note"])}", fontSize = 12.sp)
                if (str(roomN["roomId"]).isNotBlank()) Text("Room ID: ${str(roomN["roomId"])}   Password: ${str(roomN["roomPass"])}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                if (players.size < num(m["slots"])) Text("Room is not full (${players.size}/${str(m["slots"])}). The winning is paid for the ${players.size} players who joined.", color = Color(0xFFFFB74D), fontSize = 12.sp)
            }
            if (done) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B3D2A)).padding(12.dp)) {
                Text("WINNER: ${str(m["winnerName"])}", fontWeight = FontWeight.Bold)
                Text("₹${money(num(m["prize"]))} paid to the winner's wallet", fontSize = 12.sp)
            }
            if (cancelled) Text("This match was cancelled and refunded.", color = Color(0xFFFF8A80))
            Text("PLAYERS & SCREENSHOTS", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            players.forEach { p ->
                val uid = str(p["uid"]); val r = mp(reports[uid])
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(str(p["username"]) + if (uid == str(m["creatorUid"])) "  (creator)" else "", fontWeight = FontWeight.SemiBold)
                            if (str(p["ign"]).isNotBlank()) Text("Game name: ${str(p["ign"])}", fontSize = 12.sp, color = Color.LightGray)
                        }
                        Pill(if (r.isEmpty()) "no screenshot" else "pending")
                    }
                    if (r.isNotEmpty()) {
                        if (str(r["winnerName"]).isNotBlank()) Text("Says the winner is: ${str(r["winnerName"])}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        if (str(r["note"]).isNotBlank()) Text("Note: ${str(r["note"])}", fontSize = 13.sp, color = Color.LightGray)
                        AdminPvpShot(mid, uid)
                    }
                    if (!done && !cancelled && canAccess("results_manage")) ABtn("MAKE ${str(p["username"]).uppercase()} WINNER", Modifier.fillMaxWidth().padding(top = 8.dp), Green, true, enabled = !busy) { winnerAsk = uid to str(p["username"]) }
                }
            }
            if (!done && !cancelled) ABtn("CANCEL MATCH + REFUND ALL", Modifier.fillMaxWidth(), Color(0xFF7A1F1F), true, enabled = !busy) { cancelAsk = true }
            Spacer(Modifier.height(20.dp))
        }
    }
}
