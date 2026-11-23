package com.bestwinesport.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin -> Matches -> + AUTO.
 * The admin fills the match details ONCE, chooses how many matches and the gap between them. No time and no match number has to be typed:
 * times are worked out from the gap, numbers continue the running count (#656, #657 ...). All matches are written in one go.
 * Cancel rule (optional): minimum players needed; the backend server cancels + refunds a match that has fewer players.
 */
@Composable
fun AutoMatchScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val games by rflow(init = emptyMap<String, Any?>()) { AR.watch("games") }
    val nextNo by rflow(init = 0L) { kotlinx.coroutines.flow.flow { emit(AR.peekMatchNo()) } }
    val v = remember {
        mutableStateMapOf(
            "gameId" to "", "title" to "FF MAX MATCH | ATTRIBUTES ON |", "mode" to "Solo", "map" to "Bermuda", "type" to "PAID",
            "entryFee" to "", "prizePool" to "", "perKill" to "", "maxPlayers" to "48", "image" to "", "rules" to "", "prizeDetails" to "",
            "count" to "5", "gapH" to "0", "gapM" to "30", "startAt" to "", "minPlayers" to "0", "cancelAfter" to "0"
        )
    }
    var busy by remember { mutableStateOf(false) }

    fun n(k: String): Long = (v[k]?.trim()?.toDoubleOrNull() ?: 0.0).toLong().coerceAtLeast(0)
    val count = n("count").toInt()
    val gapMs = (n("gapH") * 60 + n("gapM")) * 60_000L
    // Accept both 24-hour input and 12-hour input such as 30/10/2026 6:00 PM.
    fun parseStart(): Long? {
        val t = (v["startAt"] ?: "").trim()
        if (t.isEmpty()) return System.currentTimeMillis()
        return parseMatchDate(t)
    }
    val first = parseStart() ?: System.currentTimeMillis()
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    @Composable
    fun Num(label: String, key: String, decimal: Boolean = false) =
        OutlinedTextField(v[key] ?: "", { v[key] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))

    @Composable
    fun Txt(label: String, key: String, area: Boolean = false) =
        OutlinedTextField(v[key] ?: "", { v[key] = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) },
            singleLine = !area, minLines = if (area) 4 else 1)

    @Composable
    fun Head(t: String) = Text(t, Modifier.padding(top = 14.dp, bottom = 2.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Orange)

    Column(Modifier.fillMaxSize()) {
        ABar("Auto matches", nav)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Text("Fill the details once. The app creates all matches, adds the time and the running number by itself.", color = Color.Gray, fontSize = 12.sp)

            Head("Match details")
            SelectField("Game", v["gameId"] ?: "", games.onlyAllowedGames().entries.map { it.key to str(mp(it.value)["name"]) }) { v["gameId"] = it }
            Txt("Title (the number is added after it, example: FF MAX MATCH | #656)", "title")
            SelectField("Mode", v["mode"] ?: "", listOf("Solo", "Duo", "Squad", "3vs3", "5vs5", "6vs6").map { it to it }) { v["mode"] = it }
            Txt("Map (type any map name)", "map")
            SelectField("Type", v["type"] ?: "", listOf("PAID", "FREE").map { it to it }) { v["type"] = it }
            Num("Entry fee (coins)", "entryFee", true); Num("Prize pool", "prizePool", true); Num("Per kill", "perKill", true); Num("Max players", "maxPlayers")
            ImageField("Banner image", v["image"] ?: "", 1280, "matches", "", null) { v["image"] = it }
            Txt("Rules", "rules", true)
            Txt("Prize pool details - one line each, like  BOOYAH :- 26", "prizeDetails", true)

            Head("How many matches and the gap")
            Num("How many matches to create (1 to 100)", "count")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Num("Gap hours", "gapH") }; Box(Modifier.weight(1f)) { Num("Gap minutes", "gapM") }
            }
            Txt("First match start (dd/MM/yyyy HH:mm or h:mm AM/PM). Leave empty = starts NOW. Next matches follow after the gap", "startAt")
            if (parseStart() == null) Text("Wrong format. Examples: 30/10/2026 18:30 or 30/10/2026 6:00 PM", color = Red, fontSize = 12.sp)

            Head("Auto-cancel + refund (optional)")
            Num("Minimum players needed. Example: 2 slots -> 2, 10 slots -> 10. 0 = never auto-cancel", "minPlayers")
            Num("Check after the start time (minutes). 0 = exactly at the start time", "cancelAfter")
            Text("If fewer players joined at that time, the match is cancelled and every player gets the entry fee back (same wallet part). This runs on the backend server.",
                color = Color.Gray, fontSize = 11.sp)

            Head("Preview")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardC).padding(12.dp)) {
                if (count < 1) Text("Enter how many matches to create", color = Color.Gray)
                else {
                    val shown = minOf(count, 6)
                    for (i in 0 until shown) Text("#${nextNo + i}  -  ${fmt.format(Date(first + i * gapMs))}", fontSize = 13.sp)
                    if (count > shown) Text("... and ${count - shown} more (last: #${nextNo + count - 1}  -  ${fmt.format(Date(first + (count - 1) * gapMs))})", color = Color.Gray, fontSize = 12.sp)
                    if (count > 1 && gapMs <= 0) Text("Set the gap between matches", color = Red, fontSize = 12.sp)
                }
            }
        }
        ABtn(if (busy) "CREATING..." else "CREATE $count MATCHES", Modifier.fillMaxWidth().padding(14.dp), Green, enabled = !busy && count in 1..100) {
            val gid = v["gameId"] ?: ""
            if (gid.isBlank()) { ctx.toast("Choose a game"); return@ABtn }
            if ((v["title"] ?: "").isBlank()) { ctx.toast("Enter the match title"); return@ABtn }
            if (parseStart() == null) { ctx.toast("First match start time is wrong. Use 30/10/2026 6:00 PM or 30/10/2026 18:30"); return@ABtn }
            val maxP = n("maxPlayers"); val minP = n("minPlayers")
            if (maxP < 2) { ctx.toast("Max players must be at least 2"); return@ABtn }
            if (minP > maxP) { ctx.toast("Minimum players cannot be more than max players"); return@ABtn }
            scope.launch {
                busy = true
                fun d(k: String): Any = (v[k]?.trim()?.toDoubleOrNull() ?: 0.0).let { if (it % 1.0 == 0.0) it.toLong() else it }
                val base = mapOf<String, Any?>(
                    "gameId" to gid, "title" to (v["title"] ?: "").trim(), "mode" to v["mode"], "map" to v["map"], "type" to v["type"],
                    "entryFee" to d("entryFee"), "prizePool" to d("prizePool"), "perKill" to d("perKill"), "maxPlayers" to maxP,
                    "image" to (v["image"] ?: ""), "rules" to (v["rules"] ?: "").trim(), "prizeDetails" to (v["prizeDetails"] ?: "").trim()
                )
                // empty start = NOW, measured when the button is pressed, not when the screen was opened
                val firstNow = if ((v["startAt"] ?: "").isBlank()) System.currentTimeMillis() else parseStart()!!
                val msg = AR.createAutoMatches(base, count, firstNow, gapMs, minP, n("cancelAfter"))
                ctx.toast(msg); busy = false
                if (msg.contains("created")) nav.popBackStack()
            }
        }
    }
}
